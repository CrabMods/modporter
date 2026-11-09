package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Keeps custom 1.20.1 food and fluid codecs on their original serialized schemas.
 *
 * FoodProperties no longer stores the meat bit or the original saturation modifier. Legacy food
 * input therefore retains those fields by weak identity for exact round trips. Native target
 * values use an extension schema with the target scalar constraints and defaults but the source
 * project's custom effect codec, so it deliberately is not the vanilla DIRECT_CODEC wire schema.
 */
internal object LegacyFoodCodecFamilies {
    const val RULE_ID = "struct-legacy-food-codec-families"
    private const val FOOD_PROPERTIES = "net.minecraft.world.food.FoodProperties"
    private const val FLUID_STACK = "net.neoforged.neoforge.fluids.FluidStack"
    private const val CODEC = "com.mojang.serialization.Codec"
    private const val HELPER = "LegacyFoodPropertiesCodec"

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        compatPackageSupplier: () -> String,
        dryRun: Boolean
    ): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val files = JavaSourceText.walkJavaSourceRoots(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
                .toList()
        }
        val changes = mutableListOf<Change>()
        var compatPackage: String? = null

        for (file in files) {
            val source = runCatching { file.readText() }.getOrNull() ?: continue
            if (!source.contains("RecordCodecBuilder") ||
                (!source.contains("FoodProperties") && !source.contains("FluidStack"))
            ) continue
            val parsed = parser.parse(source)
            val unit = parsed.result.orElse(null) ?: continue
            val typing = SourceValueTypes(pass, source)
            LexicalPreservingPrinter.setup(unit)
            var foodChanged = false
            var fluidChanged = false

            for (variable in unit.findAll(VariableDeclarator::class.java).toList()) {
                when {
                    isCodecOf(variable, typing, FOOD_PROPERTIES) -> {
                        if (migrateFoodCodec(variable, parser) {
                            val pkg = compatPackage ?: compatPackageSupplier().also { compatPackage = it }
                            "$pkg.$HELPER"
                        }) foodChanged = true
                    }
                    isCodecOf(variable, typing, FLUID_STACK) ->
                        if (migrateFluidCodec(variable, parser)) fluidChanged = true
                }
            }
            if (!foodChanged && !fluidChanged) continue
            val migrated = LexicalPreservingPrinter.print(unit)
            check(parser.parse(migrated).isSuccessful) { "Legacy food/fluid codec migration emitted invalid Java in $file" }
            if (!dryRun) file.writeText(migrated)
            if (foodChanged) {
                changes += Change(
                    file, 1,
                    "Keep the legacy FoodProperties JSON fields and custom effect codec on the target record",
                    "1.20.1 FoodProperties accessors and Pair effects",
                    "target record accessors plus weak legacy-field metadata",
                    Confidence.HIGH, RULE_ID
                )
            }
            if (fluidChanged) {
                changes += Change(
                    file, 1,
                    "Keep the legacy FluidStack Tag field as the target custom-data component",
                    "FluidStack.getTag/setTag",
                    "DataComponents.CUSTOM_DATA",
                    Confidence.HIGH, RULE_ID
                )
            }
        }

        val pkg = compatPackage ?: return changes
        val generated = sourceRoot.resolve(pkg.replace('.', '/')).resolve("$HELPER.java")
        val content = javaClass.getResource("/compat/$HELPER.java.template")!!.readText()
            .replace("@PACKAGE@", pkg)
        if (!generated.toFile().exists() || generated.readText() != content) {
            if (!dryRun) {
                generated.parent.createDirectories()
                generated.writeText(content)
            }
            changes += Change(
                generated, 1,
                "Generate exact legacy FoodProperties codec field retention",
                "",
                HELPER,
                Confidence.HIGH, RULE_ID
            )
        }
        return changes
    }

    private fun isCodecOf(variable: VariableDeclarator, typing: SourceValueTypes, elementCanonical: String): Boolean {
        val type = variable.type as? ClassOrInterfaceType ?: return false
        val rawType = type.toString().substringBefore('<').trim()
        if (!typing.isTypeName(rawType, CODEC)) return false
        val element = type.typeArguments.orElse(null)?.singleOrNull()?.toString() ?: return false
        return typing.isTypeName(element, elementCanonical)
    }

    private fun migrateFluidCodec(variable: VariableDeclarator, parser: JavaParser): Boolean {
        val initializer = variable.initializer.orElse(null) ?: return false
        if (!isRecordCodecBuilderCreate(initializer)) return false
        val tagGetter = initializer.findAll(MethodCallExpr::class.java).singleOrNull { getter ->
            fieldName(getter) == "Tag" && getter.arguments.singleOrNull().let { argument ->
                val lambda = argument as? LambdaExpr ?: return@let false
                val parameter = lambda.parameters.singleOrNull()?.nameAsString ?: return@let false
                val body = lambda.expressionBody.orElse(null) as? MethodCallExpr ?: return@let false
                body.nameAsString == "ofNullable" &&
                    body.arguments.singleOrNull().let { tag ->
                        tag is MethodCallExpr && tag.nameAsString == "getTag" &&
                            tag.arguments.isEmpty() && tag.scope.map(Node::toString).orElse("") == parameter
                    }
            }
        } ?: return false
        val getterLambda = tagGetter.arguments[0] as LambdaExpr
        val stack = getterLambda.parameters.single().nameAsString
        val decoderStacks = initializer.findAll(VariableDeclarator::class.java).mapNotNull { decoder ->
            val construction = decoder.initializer.orElse(null) as? ObjectCreationExpr ?: return@mapNotNull null
            val declaredType = decoder.type.toString().substringAfterLast('.')
            val constructedType = construction.type.toString().substringAfterLast('.')
            decoder.nameAsString.takeIf { declaredType == "FluidStack" && constructedType == "FluidStack" }
        }.toSet()
        val setters = initializer.findAll(MethodReferenceExpr::class.java).mapNotNull { reference ->
            val receiver = reference.scope.toString()
            if (reference.identifier == "setTag" && receiver in decoderStacks &&
                reference.findAncestor(MethodCallExpr::class.java).filter { it.nameAsString == "ifPresent" }.isPresent
            ) reference to receiver else null
        }
        if (setters.isEmpty()) return false

        tagGetter.setArgument(0, expression(parser, """
            $stack -> $stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
                ? java.util.Optional.of($stack.getOrDefault(
                    net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY
                ).copyTag())
                : java.util.Optional.empty()
        """.trimIndent()))
        for ((setter, receiver) in setters) {
            setter.replace(expression(parser,
                "value -> $receiver.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(value))"))
        }
        return true
    }

    private fun migrateFoodCodec(
        variable: VariableDeclarator,
        parser: JavaParser,
        helperSupplier: () -> String
    ): Boolean {
        val initializer = variable.initializer.orElse(null) ?: return false
        if (!isRecordCodecBuilderCreate(initializer)) return false
        val apply = initializer.findAll(MethodCallExpr::class.java).singleOrNull { call ->
            call.nameAsString == "apply" &&
                call.arguments.size == 2 &&
                (call.scope.orElse(null) as? MethodCallExpr)?.nameAsString == "group"
        } ?: return false
        val group = apply.scope.get() as MethodCallExpr
        val getters = group.arguments.mapNotNull { argument ->
            (argument as? MethodCallExpr)?.takeIf { it.nameAsString == "forGetter" }
                ?.let { getter -> fieldName(getter)?.let { it to getter } }
        }.toMap()
        val expectedFields = setOf(
            "nutrition", "saturation_modifier", "is_meat",
            "can_always_eat", "is_fast_food", "effects"
        )
        if (getters.keys != expectedFields || group.arguments.size != expectedFields.size) return false
        if (!hasMethodReference(getters.getValue("nutrition"), "getNutrition") ||
            !hasMethodReference(getters.getValue("saturation_modifier"), "getSaturationModifier") ||
            !hasMethodReference(getters.getValue("is_meat"), "isMeat") ||
            !hasMethodReference(getters.getValue("is_fast_food"), "isFastFood")
        ) return false

        val factory = apply.arguments[1] as? LambdaExpr ?: return false
        if (factory.parameters.size != group.arguments.size) return false
        val parametersByField = group.arguments.mapIndexedNotNull { index, argument ->
            val getter = argument as? MethodCallExpr ?: return@mapIndexedNotNull null
            fieldName(getter)?.let { it to factory.parameters[index].nameAsString }
        }.toMap()
        if (parametersByField.keys != expectedFields) return false
        val factoryBlock = factory.body as? BlockStmt ?: return false
        val resultReturn = factoryBlock.findAll(ReturnStmt::class.java).singleOrNull { statement ->
            val call = statement.expression.orElse(null) as? MethodCallExpr
            call?.nameAsString == "build" && call.arguments.isEmpty()
        } ?: return false
        val buildCall = resultReturn.expression.get() as MethodCallExpr
        val builderName = (buildCall.scope.orElse(null) as? NameExpr)?.nameAsString ?: return false
        val decoderEffectLoop = decoderEffectLoop(
            factoryBlock,
            parametersByField.getValue("effects"),
            builderName
        ) ?: return false
        val effectLoop = effectLoop(getters.getValue("effects")) ?: return false
        val block = resultReturn.parentNode.orElse(null) as? BlockStmt ?: return false
        val returnIndex = block.statements.indexOf(resultReturn)
        if (returnIndex < 0) return false
        val helper = helperSupplier()

        getters.getValue("nutrition").setArgument(
            0, expression(parser, "($FOOD_PROPERTIES food) -> food.nutrition()")
        )
        getters.getValue("saturation_modifier").setArgument(
            0, expression(parser, "($FOOD_PROPERTIES food) -> $helper.saturationModifier(food)")
        )
        getters.getValue("is_meat").setArgument(
            0, expression(parser, "($FOOD_PROPERTIES food) -> $helper.isMeat(food)")
        )
        getters.getValue("is_fast_food").setArgument(
            0, expression(parser, "($FOOD_PROPERTIES food) -> $helper.isFastFood(food)")
        )
        migrateEffectLoop(effectLoop, parser)

        val names = factory.findAll(NameExpr::class.java).map { it.nameAsString }.toMutableSet()
        names += factory.parameters.map { it.nameAsString }
        val resultName = uniqueName("modporterFoodProperties", names)
        val buildExpression = resultReturn.expression.get().toString()
        block.addStatement(returnIndex, statement(parser, "$FOOD_PROPERTIES $resultName = $buildExpression;"))
        block.addStatement(returnIndex + 1, statement(parser, """
            $helper.remember(
                $resultName,
                ${parametersByField.getValue("saturation_modifier")},
                ${parametersByField.getValue("is_meat")},
                ${parametersByField.getValue("is_fast_food")}
            );
        """.trimIndent()))
        resultReturn.setExpression(NameExpr(resultName))

        val occupied = factory.findAll(NameExpr::class.java).map { it.nameAsString }.toMutableSet()
        occupied += factory.parameters.map { it.nameAsString }
        val nativeInstance = uniqueName("modporterNativeInstance", occupied)
        occupied += nativeInstance
        val nativeSaturation = uniqueName("modporterSaturation", occupied)
        occupied += nativeSaturation
        val nativeEatSeconds = uniqueName("modporterEatSeconds", occupied)
        occupied += nativeEatSeconds
        val nativeUsingConvertsTo = uniqueName("modporterUsingConvertsTo", occupied)
        occupied += nativeUsingConvertsTo
        val nativeEffects = uniqueName("modporterPossibleEffects", occupied)
        occupied += nativeEffects
        val nativeEffectLoop = nativeEffectLoop(
            decoderEffectLoop,
            builderName,
            nativeEffects,
            parser
        ) ?: return false

        val nativeNutritionGetter = getters.getValue("nutrition").clone().apply {
            setArgument(0, expression(parser, "$FOOD_PROPERTIES::nutrition"))
        }
        val nativeAlwaysEatGetter = getters.getValue("can_always_eat").clone().apply {
            setArgument(0, expression(parser, "$FOOD_PROPERTIES::canAlwaysEat"))
        }
        val nativeEffectsGetter = getters.getValue("effects").clone()
        val nativeCodec = """
            com.mojang.serialization.codecs.RecordCodecBuilder.<$FOOD_PROPERTIES>create(
                $nativeInstance -> $nativeInstance.group(
                $nativeNutritionGetter,
                com.mojang.serialization.Codec.FLOAT.fieldOf("saturation")
                    .forGetter($FOOD_PROPERTIES::saturation),
                $nativeAlwaysEatGetter,
                net.minecraft.util.ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("eat_seconds", 1.6F)
                    .forGetter($FOOD_PROPERTIES::eatSeconds),
                net.minecraft.world.item.ItemStack.SINGLE_ITEM_CODEC.optionalFieldOf("using_converts_to")
                    .forGetter($FOOD_PROPERTIES::usingConvertsTo),
                $nativeEffectsGetter
            ).apply(
                $nativeInstance,
                (
                    ${parametersByField.getValue("nutrition")},
                    $nativeSaturation,
                    ${parametersByField.getValue("can_always_eat")},
                    $nativeEatSeconds,
                    $nativeUsingConvertsTo,
                    ${parametersByField.getValue("effects")}
                ) -> {
                    java.util.List<$FOOD_PROPERTIES.PossibleEffect> $nativeEffects =
                        new java.util.ArrayList<>();
                    $nativeEffectLoop
                    return new $FOOD_PROPERTIES(
                        ${parametersByField.getValue("nutrition")},
                        $nativeSaturation,
                        ${parametersByField.getValue("can_always_eat")},
                        $nativeEatSeconds,
                        $nativeUsingConvertsTo,
                        $nativeEffects
                    );
                }
                )
            )
        """.trimIndent()
        val legacyCodec = recordCodecBuilderCreateWithType(initializer, FOOD_PROPERTIES)
        variable.setInitializer(expression(parser, """
            com.mojang.serialization.Codec.<$FOOD_PROPERTIES, $FOOD_PROPERTIES>either(
                $nativeCodec,
                $legacyCodec
            ).xmap(
                (com.mojang.datafixers.util.Either<$FOOD_PROPERTIES, $FOOD_PROPERTIES> either) ->
                    either.map(
                        ($FOOD_PROPERTIES food) -> food,
                        ($FOOD_PROPERTIES food) -> food
                    ),
                ($FOOD_PROPERTIES food) -> $helper.hasLegacyFields(food)
                    ? com.mojang.datafixers.util.Either.<$FOOD_PROPERTIES, $FOOD_PROPERTIES>right(food)
                    : com.mojang.datafixers.util.Either.<$FOOD_PROPERTIES, $FOOD_PROPERTIES>left(food)
            )
        """.trimIndent()))
        return true
    }

    private data class EffectLoop(val loop: ForEachStmt, val element: String)

    private fun decoderEffectLoop(
        block: BlockStmt,
        effectsParameter: String,
        builderName: String
    ): ForEachStmt? {
        val candidates = block.findAll(ForEachStmt::class.java).filter { loop ->
            loop.iterable.toString() == effectsParameter &&
                loop.findAll(MethodCallExpr::class.java).any { call ->
                    call.nameAsString == "effect" &&
                        call.arguments.size == 2 &&
                        call.scope.map(Node::toString).orElse("") == builderName
                }
        }
        val loop = candidates.singleOrNull() ?: return null
        val effectCalls = loop.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "effect" &&
                call.arguments.size == 2 &&
                call.scope.map(Node::toString).orElse("") == builderName
        }
        val builderReferences = loop.findAll(NameExpr::class.java).filter { it.nameAsString == builderName }
        if (builderReferences.any { reference ->
                effectCalls.none { call -> call.scope.orElse(null) === reference }
            }
        ) return null
        return loop
    }

    private fun nativeEffectLoop(
        source: ForEachStmt,
        builderName: String,
        effectsName: String,
        parser: JavaParser
    ): ForEachStmt? {
        val loop = source.clone()
        val calls = loop.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "effect" &&
                call.arguments.size == 2 &&
                call.scope.map(Node::toString).orElse("") == builderName
        }.toList()
        if (calls.isEmpty()) return null
        calls.forEach { call ->
            call.replace(expression(
                parser,
                "$effectsName.add(new $FOOD_PROPERTIES.PossibleEffect(${call.arguments[0]}, ${call.arguments[1]}))"
            ))
        }
        if (loop.findAll(NameExpr::class.java).any { it.nameAsString == builderName }) return null
        return loop
    }

    private fun effectLoop(getter: MethodCallExpr): EffectLoop? {
        val lambda = getter.arguments.singleOrNull() as? LambdaExpr ?: return null
        val receiver = lambda.parameters.singleOrNull()?.nameAsString ?: return null
        val loops = lambda.findAll(ForEachStmt::class.java).filter { loop ->
            val iterable = loop.iterable as? MethodCallExpr
            iterable != null && iterable.nameAsString in setOf("getEffects", "effects") &&
                iterable.arguments.isEmpty() &&
                iterable.scope.map(Node::toString).orElse("") == receiver
        }
        val loop = loops.singleOrNull() ?: return null
        val variable = loop.variable.variables.singleOrNull() ?: return null
        val type = variable.type.toString()
        if (!type.contains("Pair") || !type.contains("MobEffectInstance") || !type.contains("Float")) return null
        val element = variable.nameAsString
        val hasFirst = loop.body.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "getFirst" && it.scope.map(Node::toString).orElse("") == element
        }
        val hasSecond = loop.body.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "getSecond" && it.scope.map(Node::toString).orElse("") == element
        }
        return if (hasFirst && hasSecond) EffectLoop(loop, element) else null
    }

    private fun migrateEffectLoop(effect: EffectLoop, parser: JavaParser) {
        val getterLambda = effect.loop.findAncestor(LambdaExpr::class.java).orElseThrow()
        val getterParameter = getterLambda.parameters.single()
        getterLambda.setEnclosingParameters(true)
        getterParameter.setType(type(parser, FOOD_PROPERTIES))
        val iterable = effect.loop.iterable as MethodCallExpr
        iterable.setName("effects")
        effect.loop.variable.variables.single().setType(type(parser, "$FOOD_PROPERTIES.PossibleEffect"))
        effect.loop.body.findAll(MethodCallExpr::class.java).toList().forEach { call ->
            if (call.scope.map(Node::toString).orElse("") != effect.element) return@forEach
            when (call.nameAsString) {
                "getFirst" -> call.setName("effect")
                "getSecond" -> call.setName("probability")
            }
        }
    }

    private fun isRecordCodecBuilderCreate(expression: Expression): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        if (call.nameAsString != "create" || call.arguments.size != 1) return false
        val owner = call.scope.map(Node::toString).orElse("")
        return owner == "RecordCodecBuilder" || owner == "com.mojang.serialization.codecs.RecordCodecBuilder"
    }

    private fun recordCodecBuilderCreateWithType(
        expression: Expression,
        elementType: String
    ): String {
        val call = expression as MethodCallExpr
        val owner = call.scope.orElseThrow()
        return "$owner.<$elementType>create(${call.arguments.single()})"
    }

    private fun fieldName(getter: MethodCallExpr): String? {
        if (getter.nameAsString != "forGetter" || getter.arguments.size != 1) return null
        val field = getter.scope.orElse(null) as? MethodCallExpr ?: return null
        if (field.nameAsString !in setOf("fieldOf", "optionalFieldOf")) return null
        return (field.arguments.firstOrNull() as? StringLiteralExpr)?.value
    }

    private fun hasMethodReference(getter: MethodCallExpr, name: String): Boolean =
        (getter.arguments.singleOrNull() as? MethodReferenceExpr)?.identifier == name

    private fun uniqueName(base: String, occupied: Set<String>): String {
        if (base !in occupied) return base
        var suffix = 2
        while ("$base$suffix" in occupied) suffix++
        return "$base$suffix"
    }

    private fun expression(parser: JavaParser, source: String): Expression =
        parser.parseExpression<Expression>(source).result.orElseThrow()

    private fun statement(parser: JavaParser, source: String) =
        parser.parseStatement(source).result.orElseThrow()

    private fun type(parser: JavaParser, source: String) =
        parser.parseType(source).result.orElseThrow()
}
