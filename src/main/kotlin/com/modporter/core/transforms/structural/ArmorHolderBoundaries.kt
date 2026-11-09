package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText

/**
 * Repairs the two holder boundaries left by the 1.20.1 ArmorMaterial enum migration:
 *
 * 1. enum constants passed to a constructor slot declared as Supplier<SoundEvent> unwrap vanilla
 *    SoundEvents holders inside their zero-argument lambda; and
 * 2. the legacy ArmorTrim mixin shape moves its shadow, memoized texture function, and both
 *    injected method parameters to Holder<ArmorMaterial>, matching the 1.21.1 ArmorTrim ABI.
 *
 * Both admissions are declaration-proven. This deliberately does not append `.value()` to
 * arbitrary sound lambdas or repair isolated ArmorMaterial/deferred-holder comparisons.
 */
internal fun StructuralRefactorPass.migrateArmorHolderBoundaries(source: String): String {
    if (!source.contains("Supplier") &&
        !source.contains("ArmorTrim") &&
        !source.contains("SoundEvents.")
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

    val soundChanged = migrateArmorEnumSoundSuppliers(unit, parser)
    val trimChanged = migrateLegacyArmorTrimMixin(unit, parser)
    if (!soundChanged && !trimChanged) return source

    var result = LexicalPreservingPrinter.print(unit)
    if (trimChanged) {
        result = JavaSourceText.addImport(
            result,
            "net.minecraft.world.item.armortrim.TrimMaterial"
        )
    }
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private fun StructuralRefactorPass.migrateArmorEnumSoundSuppliers(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    var changed = false
    for (owner in unit.findAll(EnumDeclaration::class.java)) {
        if (owner.implementedTypes.none {
                armorBoundaryNormalizedType(it.asString()) == "ArmorMaterial" &&
                    armorBoundaryCanonicalType(
                        unit,
                        it.asString(),
                        "ArmorMaterial",
                        ARMOR_MATERIAL
                    )
            }
        ) {
            continue
        }
        val constructorShapes = owner.constructors.mapNotNull { constructor ->
            val supplierIndexes = constructor.parameters.mapIndexedNotNull { index, parameter ->
                index.takeIf {
                    isLegacySoundEventSupplierParameter(parameter.type.asString()) &&
                        armorBoundaryCanonicalType(
                            unit,
                            parameter.type.asString(),
                            "Supplier",
                            "java.util.function.Supplier"
                        ) &&
                        armorBoundaryCanonicalType(
                            unit,
                            parameter.type.asString(),
                            "SoundEvent",
                            "net.minecraft.sounds.SoundEvent"
                        )
                }
            }.toSet()
            supplierIndexes.takeIf { it.isNotEmpty() }?.let {
                ArmorSoundConstructorShape(constructor.parameters.size, it)
            }
        }
        if (constructorShapes.isEmpty()) continue

        for (constant in owner.entries) {
            val supplierSets = constructorShapes
                .filter { it.parameterCount == constant.arguments.size }
                .map { it.supplierIndexes }
                .distinct()
            val supplierIndexes = supplierSets.singleOrNull() ?: continue
            for (index in supplierIndexes) {
                val argument = constant.arguments.getOrNull(index) as? LambdaExpr ?: continue
                if (argument.parameters.isNotEmpty()) continue
                val event = argument.expressionBody.orElse(null) as? FieldAccessExpr ?: continue
                if (!armorBoundaryVanillaSoundEvent(event, unit)) continue
                if (!isVanillaHolderSoundEvent(event.nameAsString)) continue
                event.replace(
                    parser.parseExpression<Expression>("$event.value()")
                        .result.orElseThrow()
                )
                changed = true
            }
        }
    }
    return changed
}

private data class ArmorSoundConstructorShape(
    val parameterCount: Int,
    val supplierIndexes: Set<Int>
)

private data class ArmorTrimMixinPlan(
    val owner: ClassOrInterfaceDeclaration,
    val patternField: FieldDeclaration,
    val paletteMethod: MethodDeclaration,
    val paletteCall: MethodCallExpr,
    val textureVariable: VariableDeclarator,
    val injectedMethods: List<MethodDeclaration>
)

private fun migrateLegacyArmorTrimMixin(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    val plans = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .mapNotNull { armorTrimMixinPlan(it, unit) }
    val plan = plans.singleOrNull() ?: return false

    val insertion = plan.owner.members.indexOf(plan.patternField)
    if (insertion < 0) return false
    val materialField = parser.parseBodyDeclaration<FieldDeclaration>(
        """
        @Shadow
        @Final
        private Holder<TrimMaterial> material;
        """.trimIndent()
    ).result.orElse(null) ?: return false
    plan.owner.members.add(insertion, materialField)

    val oldParameterName = plan.paletteMethod.parameters.single().nameAsString
    plan.paletteMethod.modifiers.clear()
    plan.paletteMethod.addModifier(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC)
    plan.paletteMethod.parameters.clear()
    plan.paletteMethod.addParameter("Holder<TrimMaterial>", "trimMaterial")
    plan.paletteMethod.addParameter("Holder<ArmorMaterial>", oldParameterName)
    plan.paletteMethod.setBody(
        parser.parseBlock("{ throw new AssertionError(); }").result.orElseThrow()
    )

    plan.textureVariable.setType(
        "BiFunction<Boolean, Holder<ArmorMaterial>, ResourceLocation>"
    )
    plan.paletteCall.arguments.add(
        0,
        parser.parseExpression<Expression>("this.material").result.orElseThrow()
    )
    for (method in plan.injectedMethods) {
        method.parameters[0].setType("Holder<ArmorMaterial>")
    }
    return true
}

private fun armorTrimMixinPlan(
    owner: ClassOrInterfaceDeclaration,
    unit: CompilationUnit
): ArmorTrimMixinPlan? {
    if (!armorBoundaryCanonicalType(unit, owner.toString(), "ArmorTrim", ARMOR_TRIM)) {
        return null
    }
    val mixinAnnotation = owner.annotations.singleOrNull { it.nameAsString == "Mixin" }
        ?: return null
    if (!Regex("""\bArmorTrim\s*\.class\b""").containsMatchIn(mixinAnnotation.toString())) {
        return null
    }
    if (!armorBoundarySimpleNameAvailable(unit, "TrimMaterial", TRIM_MATERIAL)) {
        return null
    }
    if (owner.fields.any { field ->
            field.variables.any { it.nameAsString == "material" }
        }
    ) {
        return null
    }

    val patternField = owner.fields.singleOrNull { field ->
        field.variables.size == 1 &&
            field.variables.single().nameAsString == "pattern" &&
            armorBoundaryNormalizedType(field.elementType.asString()) == "Holder<TrimPattern>" &&
            field.isPrivate &&
            armorBoundaryHasAnnotation(field, "Shadow") &&
            armorBoundaryHasAnnotation(field, "Final")
    } ?: return null
    if (!armorBoundaryCanonicalType(unit, patternField.elementType.asString(), "Holder", HOLDER) ||
        !armorBoundaryCanonicalType(
            unit,
            patternField.elementType.asString(),
            "TrimPattern",
            TRIM_PATTERN
        )
    ) {
        return null
    }

    val paletteMethod = owner.methods.singleOrNull { method ->
        method.nameAsString == "getColorPaletteSuffix" &&
            method.type.asString() == "String" &&
            method.parameters.size == 1 &&
            armorBoundaryNormalizedType(method.parameters[0].type.asString()) == "ArmorMaterial" &&
            method.isAbstract &&
            method.modifiers.map { it.keyword }.toSet() ==
            setOf(Modifier.Keyword.PROTECTED, Modifier.Keyword.ABSTRACT) &&
            armorBoundaryHasAnnotation(method, "Shadow")
    } ?: return null
    if (!armorBoundaryCanonicalType(
            unit,
            paletteMethod.parameters[0].type.asString(),
            "ArmorMaterial",
            ARMOR_MATERIAL
        )
    ) {
        return null
    }

    val textureVariables = owner.fields.flatMap { field ->
        if (field.variables.size != 1 ||
            !field.isPrivate ||
            !field.isFinal ||
            armorBoundaryNormalizedType(field.elementType.asString()) !=
            "BiFunction<Boolean,ArmorMaterial,ResourceLocation>"
        ) {
            emptyList()
        } else {
            field.variables.toList()
        }
    }
    val textureVariable = textureVariables.singleOrNull() ?: return null
    if (!armorBoundaryCanonicalType(unit, textureVariable.type.asString(), "BiFunction", BI_FUNCTION) ||
        !armorBoundaryCanonicalType(
            unit,
            textureVariable.type.asString(),
            "ArmorMaterial",
            ARMOR_MATERIAL
        ) ||
        !armorBoundaryCanonicalType(
            unit,
            textureVariable.type.asString(),
            "ResourceLocation",
            RESOURCE_LOCATION
        )
    ) {
        return null
    }
    val memoize = textureVariable.initializer.orElse(null) as? MethodCallExpr
        ?: return null
    if (memoize.nameAsString != "memoize" ||
        memoize.arguments.size != 1 ||
        memoize.scope.isEmpty ||
        !armorBoundaryCanonicalType(
            unit,
            memoize.scope.get().toString(),
            "Util",
            UTIL
        )
    ) {
        return null
    }
    val textureLambda = memoize.arguments.single() as? LambdaExpr ?: return null
    if (textureLambda.parameters.size != 2) return null
    val armorParameter = textureLambda.parameters[1].nameAsString
    val paletteCalls = textureLambda.findAll(MethodCallExpr::class.java)
        .filter {
            it.findAncestor(LambdaExpr::class.java).orElse(null) === textureLambda &&
                it.nameAsString == paletteMethod.nameAsString &&
                it.scope.isEmpty &&
                it.arguments.size == 1 &&
                (it.arguments.single() as? NameExpr)?.nameAsString == armorParameter
        }
    val paletteCall = paletteCalls.singleOrNull() ?: return null

    val injected = listOf("innerTexture", "outerTexture").map { target ->
        owner.methods.singleOrNull { method ->
            armorBoundaryInjectedTarget(method) == target &&
                method.isPrivate &&
                method.type.isVoidType &&
                method.parameters.size == 2 &&
                armorBoundaryNormalizedType(method.parameters[0].type.asString()) ==
                "ArmorMaterial" &&
                armorBoundaryNormalizedType(method.parameters[1].type.asString()) ==
                "CallbackInfoReturnable<ResourceLocation>" &&
                armorBoundaryDeferredArmorComparison(
                    method,
                    method.parameters[0].nameAsString
                )
        } ?: return null
    }
    if (!armorBoundaryCanonicalType(
            unit,
            injected.first().parameters[1].type.asString(),
            "CallbackInfoReturnable",
            CALLBACK_INFO_RETURNABLE
        )
    ) {
        return null
    }

    return ArmorTrimMixinPlan(
        owner,
        patternField,
        paletteMethod,
        paletteCall,
        textureVariable,
        injected
    )
}

private fun armorBoundaryDeferredArmorComparison(
    method: MethodDeclaration,
    parameterName: String
): Boolean {
    val comparisons = method.findAll(BinaryExpr::class.java)
        .filter {
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
                it.operator in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS) &&
                (armorBoundaryComparisonSides(it.left, it.right, parameterName) ||
                    armorBoundaryComparisonSides(it.right, it.left, parameterName))
        }
    return comparisons.size == 1
}

private fun armorBoundaryComparisonSides(
    parameter: Expression,
    deferred: Expression,
    parameterName: String
): Boolean {
    if ((parameter as? NameExpr)?.nameAsString != parameterName) return false
    val field = deferred as? FieldAccessExpr ?: return false
    if (!Regex("""[A-Z][A-Z0-9_]*""").matches(field.nameAsString)) return false
    return field.scope.toString().substringAfterLast('.') == "AllArmorMaterials"
}

private fun armorBoundaryInjectedTarget(method: MethodDeclaration): String? {
    val annotation = method.annotations.singleOrNull { it.nameAsString == "Inject" }
        as? NormalAnnotationExpr
        ?: return null
    val value = annotation.pairs.singleOrNull { it.nameAsString == "method" }
        ?.value as? StringLiteralExpr
        ?: return null
    return value.value
}

private fun armorBoundaryVanillaSoundEvent(
    event: FieldAccessExpr,
    unit: CompilationUnit
): Boolean {
    val owner = event.scope.toString()
    if (owner == SOUND_EVENTS) return true
    return owner == "SoundEvents" &&
        armorBoundaryCanonicalType(unit, owner, "SoundEvents", SOUND_EVENTS)
}

private fun armorBoundaryCanonicalType(
    unit: CompilationUnit,
    typeText: String,
    simpleName: String,
    qualifiedName: String
): Boolean {
    if (Regex("""(?<![A-Za-z0-9_$])${Regex.escape(qualifiedName)}(?![A-Za-z0-9_$])""")
            .containsMatchIn(typeText)
    ) {
        return true
    }
    if (!Regex("""(?<![A-Za-z0-9_$])${Regex.escape(simpleName)}(?![A-Za-z0-9_$])""")
            .containsMatchIn(typeText)
    ) {
        return false
    }
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simpleName
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isAsterisk && it.name.identifier == simpleName
    }
    return imports.size == 1 && imports.single().nameAsString == qualifiedName
}

private fun armorBoundarySimpleNameAvailable(
    unit: CompilationUnit,
    simpleName: String,
    qualifiedName: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simpleName
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isAsterisk && it.name.identifier == simpleName
    }
    return imports.isEmpty() ||
        imports.size == 1 && imports.single().nameAsString == qualifiedName
}

private fun armorBoundaryHasAnnotation(
    field: FieldDeclaration,
    name: String
): Boolean = field.annotations.any { it.nameAsString == name }

private fun armorBoundaryHasAnnotation(
    method: MethodDeclaration,
    name: String
): Boolean = method.annotations.any { it.nameAsString == name }

private fun armorBoundaryNormalizedType(type: String): String =
    type.replace(Regex("""\s+"""), "")

private const val ARMOR_TRIM = "net.minecraft.world.item.armortrim.ArmorTrim"
private const val ARMOR_MATERIAL = "net.minecraft.world.item.ArmorMaterial"
private const val BI_FUNCTION = "java.util.function.BiFunction"
private const val CALLBACK_INFO_RETURNABLE =
    "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable"
private const val HOLDER = "net.minecraft.core.Holder"
private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
private const val SOUND_EVENTS = "net.minecraft.sounds.SoundEvents"
private const val TRIM_MATERIAL = "net.minecraft.world.item.armortrim.TrimMaterial"
private const val TRIM_PATTERN = "net.minecraft.world.item.armortrim.TrimPattern"
private const val UTIL = "net.minecraft.Util"
