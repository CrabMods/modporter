package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.modporter.mapping.TargetMembers
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

// Structural migration rules, part 09 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateProjectMapEntryValueGetCalls, migrateEntityLevelAccessorCalls, cleanupEventBusSubscriber, migrateItemPickupEvent, removeObsoleteMethods, addBaseEntityBlockCodec, migrateNitrogenRecipeBuilders, migrateLegacyRecipeOutputBuilders.

internal fun StructuralRefactorPass.migrateLegacyPlacementBanConcreteRecipeSource(
    source: String,
    placementBanBaseClasses: Set<String>
): String {
    val className = javaTopLevelTypeName(source) ?: return source
    if (!source.contains("extends") || !source.contains("PlacementBanRecipeSerializer")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val baseAlternation = placementBanBaseClasses.joinToString("|") { Regex.escape(it) }
    val extendsMatch = Regex(
        """class\s+${Regex.escape(className)}\s+extends\s+($baseAlternation)\s*<\s*([^,>]+)\s*,\s*([^>]+)\s*>"""
    ).find(source) ?: return source
    val recipeBase = extendsMatch.groupValues[1]
    val objectType = extendsMatch.groupValues[2].trim()
    val ingredientType = extendsMatch.groupValues[3].trim()
    val recipeInputType = placementBanRecipeInputType(objectType, ingredientType)
    val ingredientCodec = placementBanIngredientCodec(ingredientType) ?: return source

    var result = source
    var changed = false
    result = result.replaceRange(
        extendsMatch.range,
        "class $className extends $recipeBase<$objectType, $ingredientType, $recipeInputType>"
    )
    changed = true

    val fullConstructor = Regex(
        """(?s)public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+$id\s*,\s*@Nullable\s+ResourceKey\s*<\s*Biome\s*>\s+$id\s*,\s*@Nullable\s+TagKey\s*<\s*Biome\s*>\s+$id\s*,\s*BlockStateIngredient\s+$id\s*,\s*${Regex.escape(ingredientType)}\s+($id)\s*\)\s*\{\s*super\(\s*([^,]+?)\s*,\s*$id\s*,\s*$id\s*,\s*$id\s*,\s*$id\s*,\s*\1\s*\)\s*;\s*\}"""
    )
    result = fullConstructor.replace(result) { match ->
        val ingredientName = match.groupValues[1]
        val recipeType = match.groupValues[2].trim()
        "public $className(Either<ResourceKey<Biome>, TagKey<Biome>> biome, Optional<BlockStateIngredient> bypassBlock, $ingredientType $ingredientName) {\n" +
            "        super($recipeType, biome, bypassBlock, $ingredientName);\n" +
            "    }"
    }

    val simpleConstructor = Regex(
        """(?s)public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+$id\s*,\s*@Nullable\s+ResourceKey\s*<\s*Biome\s*>\s+$id\s*,\s*@Nullable\s+TagKey\s*<\s*Biome\s*>\s+$id\s*,\s*BlockStateIngredient\s+$id\s*\)\s*\{\s*this\(\s*$id\s*,\s*$id\s*,\s*$id\s*,\s*$id\s*,\s*([^;)]+?)\s*\)\s*;\s*\}"""
    )
    result = simpleConstructor.replace(result) { match ->
        val defaultIngredient = match.groupValues[1].trim()
        "public $className(Either<ResourceKey<Biome>, TagKey<Biome>> biome, Optional<BlockStateIngredient> bypassBlock) {\n" +
            "        this(biome, bypassBlock, $defaultIngredient);\n" +
            "    }"
    }

    val nestedSerializer = Regex("""class\s+Serializer\s+extends\s+PlacementBanRecipeSerializer\s*<""").find(result)
    if (nestedSerializer != null) {
        result = replaceNestedClass(result, "Serializer", placementBanConcreteSerializerBody(className, objectType, ingredientType, recipeInputType, ingredientCodec))
    }

    result = addImportIfMissing(result, "com.mojang.datafixers.util.Either")
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    result = addImportIfMissing(result, "com.mojang.serialization.codecs.RecordCodecBuilder")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    result = addImportIfMissing(result, "java.util.Optional")
    result = addPlacementBanRecipeInputImport(result, recipeInputType)
    listOf(
        "com.google.gson.JsonElement",
        "com.google.gson.JsonObject",
        "com.google.gson.JsonSyntaxException",
        "net.minecraft.network.FriendlyByteBuf",
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.util.GsonHelper",
        "javax.annotation.Nullable"
    ).forEach { result = removeImport(result, it) }
    if (!changed) return source
    return result
}

internal fun StructuralRefactorPass.placementBanRecipeInputType(objectType: String, ingredientType: String): String {
    val objectSimple = objectType.substringAfterLast('.').trim()
    val ingredientSimple = ingredientType.substringAfterLast('.').trim()
    return when {
        objectSimple == "ItemStack" && ingredientSimple == "Ingredient" -> "SingleRecipeInput"
        objectSimple == "BlockState" && ingredientSimple == "BlockStateIngredient" -> "BlockStateRecipeInput"
        else -> "RecipeInput"
    }
}

internal fun StructuralRefactorPass.placementBanIngredientCodec(ingredientType: String): String? =
    when (ingredientType.substringAfterLast('.').trim()) {
        "Ingredient" -> "Ingredient"
        "BlockStateIngredient" -> "BlockStateIngredient"
        else -> null
    }

internal fun StructuralRefactorPass.addPlacementBanRecipeInputImport(source: String, recipeInputType: String): String =
    when (recipeInputType) {
        "SingleRecipeInput" -> addImportIfMissing(source, "net.minecraft.world.item.crafting.SingleRecipeInput")
        "BlockStateRecipeInput" -> addImportIfMissing(source, "com.aetherteam.nitrogen.recipe.input.BlockStateRecipeInput")
        else -> addImportIfMissing(source, "net.minecraft.world.item.crafting.RecipeInput")
    }

internal fun StructuralRefactorPass.placementBanConcreteSerializerBody(
    className: String,
    objectType: String,
    ingredientType: String,
    recipeInputType: String,
    ingredientCodecOwner: String
): String = """
public static class Serializer extends PlacementBanRecipeSerializer<$objectType, $ingredientType, $recipeInputType, $className> {
    public Serializer() {
        super($className::new);
    }

    @Override
    public MapCodec<$className> codec() {
        return RecordCodecBuilder.mapCodec(inst -> inst.group(
                BlockStateRecipeUtil.KEY_CODEC.fieldOf("biome").forGetter($className::getBiome),
                BlockStateIngredient.CODEC.optionalFieldOf("bypass").forGetter($className::getBypassBlock),
                $ingredientCodecOwner.CODEC.fieldOf("ingredient").forGetter($className::getIngredient)
        ).apply(inst, this.getFactory()));
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, $className> streamCodec() {
        return StreamCodec.of(this::toNetwork, this::fromNetwork);
    }

    public $className fromNetwork(RegistryFriendlyByteBuf buffer) {
        Either<ResourceKey<Biome>, TagKey<Biome>> biome = BlockStateRecipeUtil.STREAM_CODEC.decode(buffer);
        Optional<BlockStateIngredient> bypassBlock = buffer.readOptional((buf) -> BlockStateIngredient.CONTENTS_STREAM_CODEC.decode((RegistryFriendlyByteBuf) buf));
        $ingredientType ingredient = $ingredientCodecOwner.CONTENTS_STREAM_CODEC.decode(buffer);
        return new $className(biome, bypassBlock, ingredient);
    }

    public void toNetwork(RegistryFriendlyByteBuf buffer, $className recipe) {
        super.toNetwork(buffer, recipe);
        $ingredientCodecOwner.CONTENTS_STREAM_CODEC.encode(buffer, recipe.getIngredient());
    }
}
""".trimIndent()

internal fun StructuralRefactorPass.migrateLegacyPlacementBanReferenceAritiesSource(source: String): String {
    if (!source.contains("PlacementBanRecipeSerializer<") &&
        !source.contains("AbstractPlacementBanRecipeCategory<")) {
        return source
    }
    var result = source
    var changed = false
    val inputTypes = linkedSetOf<String>()
    listOf("PlacementBanRecipeSerializer", "AbstractPlacementBanRecipeCategory").forEach { typeName ->
        result = Regex("""\b${Regex.escape(typeName)}\s*<\s*([^,<>]+)\s*,\s*([^,<>]+)\s*,\s*([^,<>]+)\s*>""")
            .replace(result) { match ->
                val objectType = match.groupValues[1].trim()
                val ingredientType = match.groupValues[2].trim()
                val recipeType = match.groupValues[3].trim()
                val inputType = placementBanRecipeInputType(objectType, ingredientType)
                inputTypes += inputType
                changed = true
                "$typeName<$objectType, $ingredientType, $inputType, $recipeType>"
            }
    }
    result = Regex(
        """DeferredHolder\s*<\s*(PlacementBanRecipeSerializer\s*<[^;\r\n=]+?>)\s*,\s*(PlacementBanRecipeSerializer\s*<[^;\r\n=]+?>)\s*>"""
    ).replace(result) { match ->
        changed = true
        "DeferredHolder<RecipeSerializer<?>, ${match.groupValues[2].trim()}>"
    }
    if (!changed) return source
    inputTypes.forEach { result = addPlacementBanRecipeInputImport(result, it) }
    if (result.contains("DeferredHolder<RecipeSerializer<?>")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeSerializer")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanBuilderSource(source: String): String {
    if (!source.contains("PlacementBanBuilder") && !source.contains("implements RecipeBuilder")) return source
    migrateLegacyPlacementBanBaseBuilderSource(source)?.let { return it }
    migrateLegacyPlacementBanChildBuilderSource(source)?.let { return it }
    return source
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanBaseBuilderSource(source: String): String? {
    val className = javaTopLevelTypeName(source) ?: return null
    if (!source.contains("implements RecipeBuilder") ||
        !source.contains("BlockStateIngredient") ||
        !source.contains("biomeKey") ||
        !source.contains("biomeTag") ||
        !source.contains("getBypassBlock()") ||
        !source.contains("getBiomeKey()") ||
        !source.contains("getBiomeTag()")) {
        return null
    }
    if (source.contains("Either<ResourceKey<Biome>, TagKey<Biome>> biome") &&
        source.contains("Optional<BlockStateIngredient> bypassBlock")) {
        return null
    }
    val packageLine = Regex("""(?m)^package\s+[^;]+;""").find(source)?.value ?: return null
    val blockStateIngredientImport = javaImportForSimpleType(source, "BlockStateIngredient")
        ?: "com.aetherteam.nitrogen.recipe.BlockStateIngredient"
    return """
$packageLine

import $blockStateIngredientImport;
import com.mojang.datafixers.util.Either;
import net.minecraft.advancements.Criterion;
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;
import javax.annotation.Nullable;

public abstract class $className implements RecipeBuilder {
    private final Optional<BlockStateIngredient> bypassBlock;
    private final Either<ResourceKey<Biome>, TagKey<Biome>> biome;

    public $className(Optional<BlockStateIngredient> bypassBlock, Either<ResourceKey<Biome>, TagKey<Biome>> biome) {
        this.bypassBlock = bypassBlock;
        this.biome = biome;
    }

    @Override
    public RecipeBuilder group(@Nullable String group) {
        return this;
    }

    public Optional<BlockStateIngredient> getBypassBlock() {
        return this.bypassBlock;
    }

    public Either<ResourceKey<Biome>, TagKey<Biome>> getBiome() {
        return this.biome;
    }

    @Override
    public Item getResult() {
        return Items.AIR;
    }

    @Override
    public RecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
        return this;
    }
}
""".trimStart()
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanChildBuilderSource(source: String): String? {
    val className = javaTopLevelTypeName(source) ?: return null
    if (!source.contains("extends PlacementBanBuilder") ||
        !source.contains("PlacementBanRecipeSerializer") ||
        !source.contains("RecipeOutput") ||
        source.contains("Either<ResourceKey<Biome>, TagKey<Biome>> biome")) {
        return null
    }
    val serializerMatch = Regex(
        """PlacementBanRecipeSerializer\s*<\s*([^,<>]+)\s*,\s*([^,<>]+)\s*,\s*(?:(?:SingleRecipeInput|BlockStateRecipeInput|RecipeInput)\s*,\s*)?([^,<>]+)\s*>"""
    ).find(source) ?: return null
    val ingredientType = serializerMatch.groupValues[2].trim().substringAfterLast('.')
    val recipeClassName = serializerMatch.groupValues[3].trim().substringAfterLast('.')
    val id = """[A-Za-z_$][\w$]*"""
    val fieldName = Regex("""private\s+final\s+${Regex.escape(ingredientType)}\s+($id)\s*;""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: return null
    val packageLine = Regex("""(?m)^package\s+[^;]+;""").find(source)?.value ?: return null
    val recipeImport = javaImportForSimpleType(source, recipeClassName)?.let { "import $it;\n" }.orEmpty()
    val blockStateIngredientImport = javaImportForSimpleType(source, "BlockStateIngredient")
        ?: "com.aetherteam.nitrogen.recipe.BlockStateIngredient"
    val ingredientImport = if (ingredientType == "BlockStateIngredient") {
        ""
    } else {
        javaImportForSimpleType(source, ingredientType)?.let { "import $it;\n" }.orEmpty()
    }
    return """
$packageLine

${recipeImport}import $blockStateIngredientImport;
${ingredientImport}import com.mojang.datafixers.util.Either;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

public class $className extends PlacementBanBuilder {
    private final $ingredientType $fieldName;

    public $className($ingredientType $fieldName, Optional<BlockStateIngredient> bypassBlock, Either<ResourceKey<Biome>, TagKey<Biome>> biome) {
        super(bypassBlock, biome);
        this.$fieldName = $fieldName;
    }

    public static PlacementBanBuilder recipe($ingredientType $fieldName, Optional<BlockStateIngredient> bypassBlock, Either<ResourceKey<Biome>, TagKey<Biome>> biome) {
        return new $className($fieldName, bypassBlock, biome);
    }

    @Override
    public void save(RecipeOutput output, ResourceLocation id) {
        output.accept(id, new $recipeClassName(this.getBiome(), this.getBypassBlock(), this.$fieldName), null);
    }
}
""".trimStart()
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanBuilderCallSitesSource(
    source: String,
    placementBanBuilderClasses: Set<String>
): String {
    if (placementBanBuilderClasses.isEmpty() || !source.contains(".recipe(")) return source
    val nullableBypassVariables = Regex("""@Nullable\s+BlockStateIngredient\s+([A-Za-z_$][\w$]*)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    var changed = false
    var result = rewriteJavaCallWithOffset(source, "recipe") { receiver, args, callOffset ->
        val receiverName = receiver.substringAfterLast('.')
        if (receiverName !in placementBanBuilderClasses || args.size !in 3..5) return@rewriteJavaCallWithOffset null
        if (!args.last().trim().endsWith(".get()")) return@rewriteJavaCallWithOffset null
        val scope = enclosingJavaMethodText(source, callOffset) ?: source
        val biomeKinds = collectPlacementBanBiomeExpressionKinds(scope)
        val ingredient = args[0].trim()
        val bypassExpression: String
        val biomeExpression: String
        when (args.size) {
            3 -> {
                bypassExpression = "Optional.empty()"
                biomeExpression = placementBanBiomeEitherExpression(args[1], biomeKinds) ?: return@rewriteJavaCallWithOffset null
            }
            4 -> {
                bypassExpression = placementBanBypassOptionalExpression(args[1], nullableBypassVariables)
                biomeExpression = placementBanBiomeEitherExpression(args[2], biomeKinds) ?: return@rewriteJavaCallWithOffset null
            }
            else -> {
                bypassExpression = placementBanBypassOptionalExpression(args[1], nullableBypassVariables)
                val keyExpression = placementBanBiomeEitherExpression(args[2], biomeKinds)
                val tagExpression = placementBanBiomeEitherExpression(args[3], biomeKinds)
                biomeExpression = when {
                    args[2].trim() == "null" && tagExpression != null -> tagExpression
                    args[3].trim() == "null" && keyExpression != null -> keyExpression
                    else -> return@rewriteJavaCallWithOffset null
                }
            }
        }
        changed = true
        "$receiver.recipe($ingredient, $bypassExpression, $biomeExpression)"
    }
    if (!changed) return source
    result = addImportIfMissing(result, "com.mojang.datafixers.util.Either")
    result = addImportIfMissing(result, "java.util.Optional")
    return result
}

internal fun StructuralRefactorPass.enclosingJavaMethodText(source: String, offset: Int): String? {
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)*(?:<[^>{;]+>\s+)?[\w$.\[\]<>?,\s]+\s+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*\{"""
    )
    return methodPattern.findAll(source)
        .filter { it.range.first <= offset }
        .mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace >= 0 && closeBrace >= offset) {
                source.substring(match.range.first, closeBrace + 1)
            } else {
                null
            }
        }
        .lastOrNull()
}

internal fun StructuralRefactorPass.collectPlacementBanBiomeExpressionKinds(source: String): Map<String, String> {
    val id = """[A-Za-z_$][\w$]*"""
    val result = linkedMapOf<String, String>()
    Regex("""ResourceKey\s*<\s*Biome\s*>\s+($id)\b""")
        .findAll(source)
        .forEach { result[it.groupValues[1]] = "key" }
    Regex("""TagKey\s*<\s*Biome\s*>\s+($id)\b""")
        .findAll(source)
        .forEach { result[it.groupValues[1]] = "tag" }
    return result
}

internal fun StructuralRefactorPass.placementBanBiomeEitherExpression(rawExpression: String, biomeKinds: Map<String, String>): String? {
    val expression = rawExpression.trim()
    if (expression == "null") return null
    if (expression.startsWith("Either.left(") || expression.startsWith("Either.right(")) return expression
    val variable = expression.removePrefix("this.")
    return when (biomeKinds[variable]) {
        "key" -> "Either.left($expression)"
        "tag" -> "Either.right($expression)"
        else -> null
    }
}

internal fun StructuralRefactorPass.placementBanBypassOptionalExpression(
    rawExpression: String,
    nullableBypassVariables: Set<String>
): String {
    val expression = rawExpression.trim()
    if (expression == "null" || expression.endsWith(".EMPTY")) return "Optional.empty()"
    if (expression.startsWith("Optional.")) return expression
    val variable = expression.removePrefix("this.")
    return if (variable in nullableBypassVariables) {
        "Optional.ofNullable($expression)"
    } else {
        "Optional.of($expression)"
    }
}

internal fun StructuralRefactorPass.javaImportForSimpleType(source: String, simpleType: String): String? =
    Regex("""(?m)^import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(simpleType)})\s*;""")
        .find(source)
        ?.groupValues
        ?.get(1)

internal fun StructuralRefactorPass.migrateNitrogenBlockPropertyPairRuntimeAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BlockPropertyPair")) return source
    var result = source
    var changed = false
    if (executableCode.contains(".getFluid()") && executableCode.contains("LiquidBlock")) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b([A-Za-z_$][\w$]*)\.getFluid\(\)""")
        ) { match ->
            changed = true
            "${match.groupValues[1]}.fluid.builtInRegistryHolder()"
        }
    }
    val id = """[A-Za-z_$][\w$]*"""
    val loopPattern = Regex(
        """(?m)^([ \t]*)for\s*\(\s*Map\.Entry\s*<\s*Property\s*<\s*\?\s*>\s*,\s*Comparable\s*<\s*\?\s*>\s*>\s+($id)\s*:\s*($id)\.properties\(\)\.entrySet\(\)\s*\)\s*\{"""
    )
    var cursor = 0
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = loopPattern.find(executableResult, cursor) ?: break
        val openBrace = executableResult.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableResult, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val indent = match.groupValues[1]
        val entry = match.groupValues[2]
        val pair = match.groupValues[3]
        val body = result.substring(openBrace + 1, closeBrace)
        val shiftedBody = body.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else "    $line"
        }
        val replacement = "${indent}if ($pair.properties().isPresent()) {\n" +
            "${indent}    for (Map.Entry<Property<?>, Comparable<?>> $entry : $pair.properties().get().entrySet()) {$shiftedBody\n" +
            "${indent}    }\n" +
            "${indent}}"
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
        changed = true
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateRecipeDisplayRecipeIdWithoutHolderSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY,
    recipeImplementationClasses: Set<String> = emptySet()
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""\bextends\s+BasicDisplay\b""").containsMatchIn(executableCode) ||
        !executableCode.contains("Optional.of(") ||
        !executableCode.contains(".getId(")) {
        return source
    }
    val recipeIdOptionalPattern = Regex("""Optional\.of\(\s*([A-Za-z_$][\w$]*)\.getId\(\s*\)\s*\)""")
    return replaceExecutableRegex(source, recipeIdOptionalPattern) { match ->
        val receiver = match.groupValues[1]
        if (recipeDisplayRecipeReceiverAt(
                executableCode,
                match.range.first,
                receiver,
                javaInheritanceIndex,
                recipeImplementationClasses
            )) {
            "Optional.empty()"
        } else {
            match.value
        }
    }
}

internal data class JavaMethodScope(
    val header: String,
    val parameters: List<String>
)

internal fun StructuralRefactorPass.recipeDisplayRecipeReceiverAt(
    executableCode: String,
    offset: Int,
    receiver: String,
    javaInheritanceIndex: JavaInheritanceIndex,
    recipeImplementationClasses: Set<String>
): Boolean {
    val method = enclosingJavaMethodScope(executableCode, offset) ?: return false
    val receiverType = method.parameters
        .mapNotNull(::javaParameterTypeAndName)
        .firstOrNull { (_, name) -> name == receiver }
        ?.first
        ?: return false
    val typeParameterBounds = recipeDisplayTypeParameterBoundsAt(executableCode, offset)
    return isJavaRecipeType(receiverType, typeParameterBounds, javaInheritanceIndex, recipeImplementationClasses)
}

internal fun StructuralRefactorPass.recipeDisplayTypeParameterBoundsAt(source: String, offset: Int): Map<String, String> {
    val bounds = linkedMapOf<String, String>()
    enclosingJavaClassHeader(source, offset)?.let { header ->
        bounds.putAll(recipeDisplayTypeParameterBoundsFromHeader(header))
    }
    enclosingJavaMethodScope(source, offset)?.let { method ->
        bounds.putAll(recipeDisplayTypeParameterBoundsFromHeader(method.header))
    }
    return bounds
}

internal fun StructuralRefactorPass.isJavaRecipeType(
    type: String,
    typeParameterBounds: Map<String, String>,
    javaInheritanceIndex: JavaInheritanceIndex,
    recipeImplementationClasses: Set<String>,
    visitedTypeParameters: Set<String> = emptySet()
): Boolean {
    val normalized = type.trim()
        .replace(Regex("""^\?\s+extends\s+"""), "")
        .replace("...", "[]")
        .trim()
    val rawType = rawJavaTypeName(normalized)
    if (rawType == "Recipe") return true
    if (rawType in recipeImplementationClasses) return true
    if (javaInheritanceIndex.inherits(rawType, recipeImplementationClasses + setOf("Recipe"))) return true
    val bound = typeParameterBounds[rawType] ?: return false
    if (rawType in visitedTypeParameters) return false
    return isJavaRecipeType(
        bound,
        typeParameterBounds,
        javaInheritanceIndex,
        recipeImplementationClasses,
        visitedTypeParameters + rawType
    )
}

internal fun StructuralRefactorPass.enclosingJavaMethodScope(source: String, offset: Int): JavaMethodScope? {
    val methodPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|synchronized|strictfp|native)\s+)+(?:(?:<[^;{}()]+>\s+)?[A-Za-z_$][\w$<>\[\].?,\s]*\s+[A-Za-z_$][\w$]*|(?:<[^;{}()]+>\s+)?[A-Za-z_$][\w$]*)\s*\(([^()]*)\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    return methodPattern.findAll(source)
        .filter { it.range.first < offset }
        .lastOrNull { match ->
            val openBrace = source.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            closeBrace >= offset
        }
        ?.let { match ->
            val openBrace = source.indexOf('{', match.range.last - 1)
            JavaMethodScope(
                header = source.substring(match.range.first, openBrace).trim(),
                parameters = splitTopLevelJavaArgs(match.groupValues[1])
            )
        }
}

internal fun StructuralRefactorPass.enclosingJavaClassHeader(source: String, offset: Int): String? {
    val classPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|record)\s+[A-Za-z_$][\w$]*(?:\s*<[^{};]+>)?[^;{]*\{"""
    )
    return classPattern.findAll(source)
        .filter { it.range.first < offset }
        .mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace >= 0 && closeBrace >= offset) {
                source.substring(match.range.first, openBrace).trim()
            } else {
                null
            }
        }
        .lastOrNull()
}

internal fun StructuralRefactorPass.recipeDisplayTypeParameterBoundsFromHeader(header: String): Map<String, String> {
    val parameterLists = listOfNotNull(
        javaClassTypeParameterText(header),
        javaMethodTypeParameterText(header)
    )
    return parameterLists
        .flatMap { splitTopLevelJavaArgs(it) }
        .mapNotNull { parameter ->
            Regex("""^\s*([A-Za-z_$][\w$]*)\s+extends\s+(.+?)\s*$""")
                .find(parameter)
                ?.let { it.groupValues[1] to it.groupValues[2].trim() }
        }
        .toMap()
}

internal fun StructuralRefactorPass.javaClassTypeParameterText(header: String): String? {
    val match = Regex("""\b(?:class|record|interface)\s+[A-Za-z_$][\w$]*\s*<""").find(header) ?: return null
    val openAngle = header.indexOf('<', match.range.last - 1)
    val closeAngle = findMatchingJavaAngle(header, openAngle)
    return if (openAngle >= 0 && closeAngle > openAngle) header.substring(openAngle + 1, closeAngle) else null
}

internal fun StructuralRefactorPass.javaMethodTypeParameterText(header: String): String? {
    val match = Regex(
        """(?m)^[ \t]*(?:(?:@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|synchronized|strictfp|native)\s+)+<"""
    ).find(header) ?: return null
    val openAngle = header.indexOf('<', match.range.last - 1)
    val closeAngle = findMatchingJavaAngle(header, openAngle)
    return if (openAngle >= 0 && closeAngle > openAngle) header.substring(openAngle + 1, closeAngle) else null
}

internal fun StructuralRefactorPass.findMatchingJavaAngle(source: String, openAngle: Int): Int {
    if (openAngle < 0 || source.getOrNull(openAngle) != '<') return -1
    var depth = 0
    for (index in openAngle until source.length) {
        when (source[index]) {
            '<' -> depth++
            '>' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return -1
}

internal fun StructuralRefactorPass.javaParameterTypeAndName(parameter: String): Pair<String, String>? {
    val cleaned = parameter.trim()
        .replace(Regex("""@[A-Za-z_$][\w$.]*(?:\([^)]*\))?\s*"""), "")
        .replace(Regex("""\bfinal\s+"""), "")
        .trim()
    val match = Regex("""(.+?)\s+([A-Za-z_$][\w$]*)(?:\s*\[\s*])?\s*$""")
        .find(cleaned)
        ?: return null
    return match.groupValues[1].trim() to match.groupValues[2]
}

internal fun StructuralRefactorPass.migrateLegacyPlacementBanDisplaySource(
    source: String,
    placementBanBaseClasses: Set<String>,
    placementBanDisplayClasses: Set<String>
): String {
    if (source.contains("PlacementBanRecipeSerializer")) return source
    if (placementBanBaseClasses.none { source.contains(it) }) return source
    var result = source
    var changed = false
    placementBanBaseClasses.forEach { baseClass ->
        result = Regex("""${Regex.escape(baseClass)}\s*<\s*\?\s*,\s*\?\s*>""")
            .replace(result) {
                changed = true
                "$baseClass<?, ?, ?>"
            }
        result = Regex("""([A-Za-z_$][\w$]*)\s+extends\s+${Regex.escape(baseClass)}\s*<\s*([^,>]+)\s*,\s*([^,>]+)\s*>""")
            .replace(result) { match ->
                changed = true
                "F extends RecipeInput, ${match.groupValues[1]} extends $baseClass<${match.groupValues[2].trim()}, ${match.groupValues[3].trim()}, F>"
            }
    }
    result = migratePlacementBanBypassBlockOptionalAccess(result)
    result = replacePlacementBanJeiSetRecipe(result)
    result = migratePlacementBanBiomeAccess(result, placementBanBaseClasses, placementBanDisplayClasses)

    result = Regex("""private\s+final\s+BlockStateIngredient\s+bypassBlock\s*;""")
        .replace(result) {
            changed = true
            "private final Optional<BlockStateIngredient> bypassBlock;"
        }
    result = Regex(
        """(?s)\s*private\s+final\s+Optional\s*<\s*ResourceKey\s*<\s*Biome\s*>\s*>\s+biomeKey\s*;\s*private\s+final\s+Optional\s*<\s*TagKey\s*<\s*Biome\s*>\s*>\s+biomeTag\s*;"""
    ).replace(result) {
        changed = true
        "\n    private final Either<ResourceKey<Biome>, TagKey<Biome>> biome;"
    }
    result = Regex(
        """CategoryIdentifier<\? extends PlacementBanRecipeDisplay<([A-Za-z_$][\w$]*)>>\s+categoryIdentifier,\s*List<EntryIngredient>\s+inputs,\s*BlockStateIngredient\s+bypassBlock,\s*Optional<ResourceKey<Biome>>\s+biomeKey,\s*Optional<TagKey<Biome>>\s+biomeTag"""
    ).replace(result) { match ->
        changed = true
        "CategoryIdentifier<? extends PlacementBanRecipeDisplay<${match.groupValues[1]}>> categoryIdentifier, List<EntryIngredient> inputs, Either<ResourceKey<Biome>, TagKey<Biome>> biome, Optional<BlockStateIngredient> bypassBlock"
    }
    result = result
        .replace("this.bypassBlock = bypassBlock;\n        this.biomeKey = biomeKey;\n        this.biomeTag = biomeTag;", "this.biome = biome;\n        this.bypassBlock = bypassBlock;")

    result = Regex("""public\s+BlockStateIngredient\s+getBypassBlock\s*\(\s*\)""")
        .replace(result) {
            changed = true
            "public Optional<BlockStateIngredient> getBypassBlock()"
        }
    result = Regex(
        """(?s)@Nullable\s*\r?\n\s*public\s+ResourceKey\s*<\s*Biome\s*>\s+getBiomeKey\s*\(\s*\)\s*\{\s*return\s+this\.biomeKey\.orElse\(null\)\s*;\s*\}\s*@Nullable\s*\r?\n\s*public\s+TagKey\s*<\s*Biome\s*>\s+getBiomeTag\s*\(\s*\)\s*\{\s*return\s+this\.biomeTag\.orElse\(null\)\s*;\s*\}"""
    ).replace(result) {
        changed = true
        "public Either<ResourceKey<Biome>, TagKey<Biome>> getBiome() {\n" +
            "        return this.biome;\n" +
            "    }"
    }

    if (!changed && result == source) return source
    if (result.contains("Either<ResourceKey<Biome>, TagKey<Biome>>")) {
        result = addImportIfMissing(result, "com.mojang.datafixers.util.Either")
    }
    if (result.contains("Optional<BlockStateIngredient>") || result.contains("Optional.empty()")) {
        result = addImportIfMissing(result, "java.util.Optional")
    }
    if (result.contains("BlockStateIngredient")) {
        result = addImportIfMissing(result, javaImportForSimpleType(source, "BlockStateIngredient") ?: "com.aetherteam.nitrogen.recipe.BlockStateIngredient")
    }
    if (result.contains("RecipeInput")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeInput")
    }
    return result
}

internal enum class PlacementBanBiomeReceiverKind {
    RECIPE,
    DISPLAY
}

internal data class PlacementBanBiomeReceiverTypes(
    val recipeReceivers: Set<String>,
    val displayReceivers: Set<String>,
    val thisKind: PlacementBanBiomeReceiverKind?
)

internal fun StructuralRefactorPass.migratePlacementBanBiomeAccess(
    source: String,
    placementBanBaseClasses: Set<String>,
    placementBanDisplayClasses: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getBiomeKey()") && !executableCode.contains("getBiomeTag()")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val receiverExpression = """$id(?:\.$id(?:\(\))?)*"""
    val receiverTypes = placementBanBiomeReceiverTypes(
        source,
        executableCode,
        placementBanBaseClasses,
        placementBanDisplayClasses
    )

    var result = replaceExecutableRegex(
        source,
        Regex("""Optional\.ofNullable\(\s*($receiverExpression)\.getBiomeKey\(\)\s*\)\s*,\s*Optional\.ofNullable\(\s*($receiverExpression)\.getBiomeTag\(\)\s*\)""")
    ) { match ->
        val receiver = match.groupValues[1]
        if (receiver == match.groupValues[2] &&
            placementBanBiomeReceiverKind(receiver, receiverTypes) != null
        ) {
            "$receiver.getBiome()"
        } else {
            match.value
        }
    }

    result = replaceExecutableRegex(
        result,
        Regex("""this\(\s*([^,\r\n]+)\s*,\s*([^,\r\n]+)\s*,\s*($receiverExpression)\.getBypassBlock\(\)\s*,\s*\3\.getBiome\(\)\s*,""")
    ) { match ->
        val category = match.groupValues[1].trim()
        val inputs = match.groupValues[2].trim()
        val receiver = match.groupValues[3]
        if (placementBanBiomeReceiverKind(receiver, receiverTypes) == null) {
            match.value
        } else {
            "this($category,\n                $inputs,\n                $receiver.getBiome(),\n                $receiver.getBypassBlock(),"
        }
    }

    result = replaceExecutableRegex(
        result,
        Regex("""this\.populateBiomeInformation\(\s*($receiverExpression)\.getBiomeKey\(\)\s*,\s*($receiverExpression)\.getBiomeTag\(\)\s*,\s*([^);]+)\s*\)\s*;""")
    ) { match ->
        val receiver = match.groupValues[1]
        if (receiver != match.groupValues[2]) return@replaceExecutableRegex match.value
        when (placementBanBiomeReceiverKind(receiver, receiverTypes)) {
            PlacementBanBiomeReceiverKind.DISPLAY ->
                "this.populateBiomeInformation($receiver.getBiome().left(), $receiver.getBiome().right(), ${match.groupValues[3].trim()});"
            PlacementBanBiomeReceiverKind.RECIPE ->
                "this.populateBiomeInformation($receiver.getBiome().left().orElse(null), $receiver.getBiome().right().orElse(null), ${match.groupValues[3].trim()});"
            null -> match.value
        }
    }

    result = replaceExecutableRegex(
        result,
        Regex("""($receiverExpression)\.getBiomeKey\(\)\s*,\s*($receiverExpression)\.getBiomeTag\(\)""")
    ) { match ->
        val receiver = match.groupValues[1]
        if (receiver == match.groupValues[2]) {
            when (placementBanBiomeReceiverKind(receiver, receiverTypes)) {
                PlacementBanBiomeReceiverKind.DISPLAY ->
                    "$receiver.getBiome().left(), $receiver.getBiome().right()"
                PlacementBanBiomeReceiverKind.RECIPE ->
                    "$receiver.getBiome().left().orElse(null), $receiver.getBiome().right().orElse(null)"
                null -> match.value
            }
        } else {
            match.value
        }
    }
    return result
}

internal fun StructuralRefactorPass.placementBanBiomeReceiverTypes(
    source: String,
    executableCode: String,
    placementBanBaseClasses: Set<String>,
    placementBanDisplayClasses: Set<String>
): PlacementBanBiomeReceiverTypes {
    val id = """[A-Za-z_$][\w$]*"""
    val recipeTypes = placementBanBaseClasses.toMutableSet()
    if (placementBanBaseClasses.isNotEmpty()) {
        val baseAlternation = placementBanBaseClasses.joinToString("|") { Regex.escape(it) }
        Regex("""\b($id)\s+extends\s+(?:$id\.)?(?:$baseAlternation)\b""")
            .findAll(executableCode)
            .mapTo(recipeTypes) { it.groupValues[1] }
    }
    val displayTypes = placementBanDisplayClasses.toMutableSet()
    Regex("""\bclass\s+($id)(?:\s*<[^{}]+>)?\s+extends\s+(?:$id\.)?BasicDisplay\b""")
        .findAll(executableCode)
        .filter { placementBanBaseClasses.any { baseClass -> executableCode.contains(baseClass) } }
        .mapTo(displayTypes) { it.groupValues[1] }

    fun collectReceivers(typeNames: Set<String>): Set<String> {
        if (typeNames.isEmpty()) return emptySet()
        val typeAlternation = typeNames.joinToString("|") { Regex.escape(it) }
        return Regex("""\b(?:final\s+)?(?:$id\.)?(?:$typeAlternation)(?:\s*<[^,;=(){}\r\n<>]+>)?(?:\s*\[\s*])?\s+($id)\b""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .filterNot { it in setOf("extends", "implements", "return", "new") }
            .toSet()
    }

    val className = javaTopLevelTypeName(source)
    val thisKind = when (className) {
        in displayTypes -> PlacementBanBiomeReceiverKind.DISPLAY
        in recipeTypes -> PlacementBanBiomeReceiverKind.RECIPE
        else -> null
    }
    return PlacementBanBiomeReceiverTypes(
        recipeReceivers = collectReceivers(recipeTypes),
        displayReceivers = collectReceivers(displayTypes),
        thisKind = thisKind
    )
}

internal fun StructuralRefactorPass.placementBanBiomeReceiverKind(
    receiver: String,
    receiverTypes: PlacementBanBiomeReceiverTypes
): PlacementBanBiomeReceiverKind? {
    val root = receiver.substringBefore('.')
    if (receiver == "this") return receiverTypes.thisKind
    if (root in receiverTypes.recipeReceivers) return PlacementBanBiomeReceiverKind.RECIPE
    if (root in receiverTypes.displayReceivers) {
        return if (receiver == root) {
            PlacementBanBiomeReceiverKind.DISPLAY
        } else if (receiver.startsWith("$root.getRecipe()")) {
            PlacementBanBiomeReceiverKind.RECIPE
        } else {
            null
        }
    }
    if (receiver.startsWith("this.getRecipe()") &&
        receiverTypes.thisKind == PlacementBanBiomeReceiverKind.DISPLAY
    ) {
        return PlacementBanBiomeReceiverKind.RECIPE
    }
    return null
}

internal fun StructuralRefactorPass.migratePlacementBanBypassBlockOptionalAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getBypassBlock()")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val receiverExpression = """$id(?:\.$id(?:\(\))?)*"""
    var result = replaceExecutableRegex(
        source,
        Regex("""\b((?:final\s+)?)(?:BlockStateIngredient|var)\s+($id)\s*=\s*($receiverExpression)\.getBypassBlock\(\)\s*;""")
    ) { match ->
        "${match.groupValues[1]}Optional<BlockStateIngredient> ${match.groupValues[2]} = ${match.groupValues[3]}.getBypassBlock();"
    }

    val executableResult = maskJavaCommentsAndLiterals(result)
    val bypassBlockLocals = Regex(
        """\bOptional\s*<\s*BlockStateIngredient\s*>\s+($id)\s*=\s*($receiverExpression)\.getBypassBlock\(\)\s*;"""
    ).findAll(executableResult)
        .map { it.groupValues[1] }
        .toSet()

    result = replaceExecutableRegex(
        result,
        Regex("""new\s+ArrayList\s*<>\s*\(\s*REIUtils\.toIngredientList\(\s*($receiverExpression)\.getBypassBlock\(\)(?:\.get\(\))?\.getPairs\(\)\s*\)\s*\)""")
    ) { match ->
        val receiver = match.groupValues[1]
        "new ArrayList<>($receiver.getBypassBlock().map(blockStateIngredient -> REIUtils.toIngredientList(blockStateIngredient.getPairs())).orElse(List.of()))"
    }

    result = replaceExecutableRegex(
        result,
        Regex("""($receiverExpression)\.getBypassBlock\(\)\s*==\s*null\s*\|\|\s*($receiverExpression)\.getBypassBlock\(\)\.isEmpty\(\)""")
    ) { match ->
        val receiver = match.groupValues[1]
        if (receiver == match.groupValues[2]) {
            "$receiver.getBypassBlock().isEmpty() || $receiver.getBypassBlock().get().isEmpty()"
        } else {
            match.value
        }
    }

    result = replaceExecutableRegex(
        result,
        Regex("""($receiverExpression)\.getBypassBlock\(\)\.getPairs\(\)""")
    ) { match ->
        "${match.groupValues[1]}.getBypassBlock().get().getPairs()"
    }

    bypassBlockLocals.forEach { local ->
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$])${Regex.escape(local)}\s*!=\s*null\s*&&\s*!\s*${Regex.escape(local)}\.isEmpty\(\)""")
        ) {
            "$local.isPresent() && !$local.get().isEmpty()"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""($receiverExpression)\.getInputEntries\(\)\.get\(\s*0\s*\)\s*,\s*${Regex.escape(local)}(?:\.get\(\))?\.getPairs\(\)""")
        ) { match ->
            "${match.groupValues[1]}.getInputEntries().get(1), $local.get().getPairs()"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""(?<![\w$])${Regex.escape(local)}\.getPairs\(\)""")
        ) {
            "$local.get().getPairs()"
        }
    }
    return result
}

internal fun StructuralRefactorPass.replacePlacementBanJeiSetRecipe(source: String): String {
    if (!source.contains("void setRecipe(") ||
        !source.contains("IRecipeLayoutBuilder") ||
        !source.contains("recipe.getBypassBlock()") ||
        !source.contains("this.setupIngredients(pairs)")) {
        return source
    }
    val signature = Regex(
        """(?m)^([ \t]*)public\s+void\s+setRecipe\s*\(\s*IRecipeLayoutBuilder\s+([A-Za-z_$][\w$]*)\s*,\s*R\s+([A-Za-z_$][\w$]*)\s*,\s*IFocusGroup\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    ).find(source) ?: return source
    val openBrace = source.indexOf('{', signature.range.last - 1)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return source
    val indent = signature.groupValues[1]
    val builder = signature.groupValues[2]
    val recipe = signature.groupValues[3]
    val replacement = """
${indent}public void setRecipe(IRecipeLayoutBuilder $builder, R $recipe, IFocusGroup ${signature.groupValues[4]}) {
${indent}    Optional<BlockStateIngredient> bypassBlockIngredient = $recipe.getBypassBlock();
${indent}    if (bypassBlockIngredient.isPresent() && !bypassBlockIngredient.get().isEmpty()) {
${indent}        BlockPropertyPair[] pairs = bypassBlockIngredient.get().getPairs();
${indent}        if (pairs != null) {
${indent}            List<Object> ingredients = this.setupIngredients(pairs);
${indent}            $builder.addSlot(RecipeIngredientRole.INPUT, 99, 1).addIngredientsUnsafe(ingredients)
${indent}                    .setCustomRenderer(Services.PLATFORM.getFluidHelper().getFluidIngredientType(), new FluidStateRenderer(Services.PLATFORM.getFluidHelper()))
${indent}                    .setCustomRenderer(VanillaTypes.ITEM_STACK, new BlockStateRenderer(pairs));
${indent}        }
${indent}    }
${indent}}
""".trimEnd()
    return source.substring(0, signature.range.first) + replacement + source.substring(closeBrace + 1)
}

internal fun StructuralRefactorPass.recipeLikeBaseTypes(): Set<String> = setOf(
    "Recipe",
    "CraftingRecipe",
    "AbstractCookingRecipe",
    "SingleItemRecipe"
)

internal fun StructuralRefactorPass.recipeLikeGenericTypeVariables(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex
): Set<String> =
    Regex("""\b([A-Z][A-Za-z0-9_$]*)\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)(?:\s*<[^>{}]+>)?""")
        .findAll(source)
        .mapNotNull { match ->
            val bound = match.groupValues[2].substringAfterLast('.')
            match.groupValues[1].takeIf {
                bound in recipeLikeBaseTypes() || javaInheritanceIndex.inherits(bound, recipeLikeBaseTypes())
            }
        }
        .toSet()

internal fun MutableSet<String>.addRegexReceiver(methodName: String) {
    add("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*\.${Regex.escape(methodName)}\(\)""")
}

internal fun StructuralRefactorPass.declaredVariablesOfSimpleType(source: String, simpleType: String): Set<String> =
    Regex("""\b${Regex.escape(simpleType)}(?:\s*<[^;=(){}]+>)?\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

internal fun StructuralRefactorPass.declaredVariablesOfSimpleTypes(source: String, simpleTypes: Set<String>): Set<String> =
    simpleTypes.flatMap { declaredVariablesOfSimpleType(source, it) }.toSet()

internal fun StructuralRefactorPass.replaceNetworkEncodeCalls(
    source: String,
    receivers: Set<String>,
    codec: String,
    bufferExpression: (String) -> String,
    onChange: () -> Unit
): String {
    if (receivers.isEmpty()) return source
    var result = source
    receivers.forEach { receiver ->
        val receiverPattern = if (receiver.contains("\\")) receiver else Regex.escape(receiver)
        result = Regex("""(?<![\w$])($receiverPattern)\.toNetwork\s*\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match ->
                onChange()
                "$codec.encode(${bufferExpression(match.groupValues[2])}, ${match.groupValues[1]})"
            }
    }
    return result
}

internal fun StructuralRefactorPass.migrateCacheableFunctionOptionalBoundaries(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("CacheableFunction") ||
        (!executableCode.contains("getFunction()") &&
            !executableCode.contains("BlockStateRecipeUtil.executeFunction("))) {
        return source
    }

    var result = source
    var changed = false
    result = replaceExecutableRegex(
        result,
        Regex("""\bCacheableFunction\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]*\.getFunction\(\))\s*;""")
    ) { match ->
        changed = true
        "Optional<CacheableFunction> ${match.groupValues[1]} = ${match.groupValues[2].trim()};"
    }
    if (maskJavaCommentsAndLiterals(result).contains("BlockStateRecipeUtil.executeFunction(")) {
        result = replaceExecutableRegex(
            result,
            Regex("""@Nullable\s+CacheableFunction\s+([A-Za-z_$][\w$]*)""")
        ) { match ->
            changed = true
            "Optional<CacheableFunction> ${match.groupValues[1]}"
        }
    }
    if (changed) {
        result = addExecutableImportIfMissing(result, "java.util.Optional")
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderAccess(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("RecipeManager") ||
        (!executableCode.contains(".getRecipes()") &&
            !executableCode.contains(".byKey(") &&
            !executableCode.contains(".getRecipeFor("))) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val optionalRecipeHolderVariables = Regex(
        """\b(?:java\.util\.)?Optional\s*<\s*(?:net\.minecraft\.world\.item\.crafting\.)?RecipeHolder\s*<\s*($id(?:\.$id)*)\s*>\s*>\s+($id)\b"""
    ).findAll(executableCode)
        .map { it.groupValues[2] to it.groupValues[1].substringAfterLast('.') }
        .toMap()
    val recipeCollectionPattern = Regex(
        """\b((?:java\.util\.)?Collection)\s*<\s*Recipe\s*<\s*\?\s*>\s*>\s+($id)\s*=\s*([^;]*\.getRecipes\(\))\s*;"""
    )
    val collectionNames = recipeCollectionPattern.findAll(executableCode)
        .map { it.groupValues[2] }
        .toMutableSet()
    val recipeHolderMethods = linkedSetOf<String>()
    val methodPattern = Regex(
        """(?m)^([ \t]*(?:(?:public|protected|private|static|final)\s+)*)Recipe\s*<\s*\?\s*>\s+($id)\s*\([^;{}]*\)\s*\{"""
    )
    methodPattern.findAll(executableCode).forEach { match ->
        val openBrace = executableCode.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace >= 0 && closeBrace > openBrace &&
            executableCode.substring(openBrace + 1, closeBrace).contains(".byKey(")) {
            recipeHolderMethods += match.groupValues[2]
        }
    }
    val optionalRecipeContainerNames = Regex(
        """\b(?:java\.util\.)?List\s*<\s*(?:java\.util\.)?Optional\s*<\s*\?\s+extends\s+Recipe\s*<\s*\?\s*>\s*>\s*>\s+($id)\b"""
    ).findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    val hasDuplicateRecipeHolderValueMap = Regex("""\.map\(\s*RecipeHolder::value\s*\)\s*\.map\(\s*RecipeHolder::value\s*\)""")
        .containsMatchIn(executableCode)
    val optionalRecipeByKeyPattern = Regex(
        """\b(?:java\.util\.)?Optional\s*<\s*\?\s+extends\s+Recipe\s*<\s*\?\s*>\s*>\s+($id)\s*=\s*([^;]*\.byKey\([^;]+?\))(?!\s*\.map\(\s*RecipeHolder::value\s*\))\s*;"""
    )
    val hasOptionalRecipeByKey = optionalRecipeByKeyPattern.containsMatchIn(executableCode)
    if (collectionNames.isEmpty() &&
        recipeHolderMethods.isEmpty() &&
        optionalRecipeContainerNames.isEmpty() &&
        optionalRecipeHolderVariables.isEmpty() &&
        !hasDuplicateRecipeHolderValueMap &&
        !hasOptionalRecipeByKey) return source

    var result = source
    var changed = false
    result = replaceExecutableRegex(
        result,
        Regex("""\.map\(\s*RecipeHolder::value\s*\)\s*\.map\(\s*RecipeHolder::value\s*\)""")
    ) {
        changed = true
        ".map(RecipeHolder::value)"
    }
    optionalRecipeHolderVariables.forEach { (optionalName, recipeType) ->
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(recipeType)}\s+($id)\s*=\s*${Regex.escape(optionalName)}\.get\(\s*\)\s*;""")
        ) { match ->
            changed = true
            "$recipeType ${match.groupValues[1]} = $optionalName.get().value();"
        }
    }
    for (containerName in optionalRecipeContainerNames) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(containerName)}\.add\(\s*([^;\r\n]*?\.byKey\([^;\r\n]+?\))(?!\s*\.map\(RecipeHolder::value\))\s*\)\s*;""")
        ) { match ->
            changed = true
            "$containerName.add(${match.groupValues[1].trim()}.map(RecipeHolder::value));"
        }
    }
    result = replaceExecutableRegex(result, recipeCollectionPattern) { match ->
        changed = true
        "${match.groupValues[1]}<RecipeHolder<?>> ${match.groupValues[2]} = ${match.groupValues[3].trim()};"
    }
    for (collectionName in collectionNames) {
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^([ \t]*)for\s*\(\s*Recipe\s*<\s*\?\s*>\s+($id)\s*:\s*${Regex.escape(collectionName)}\s*\)\s*\{""")
        ) { match ->
            changed = true
            val indent = match.groupValues[1]
            val recipeVariable = match.groupValues[2]
            val holderVariable = if (recipeVariable.endsWith("Holder")) {
                "${recipeVariable}ValueHolder"
            } else {
                "${recipeVariable}Holder"
            }
            "${indent}for (RecipeHolder<?> $holderVariable : $collectionName) {\n" +
                "${indent}    Recipe<?> $recipeVariable = $holderVariable.value();"
        }
    }
    for (methodName in recipeHolderMethods) {
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^([ \t]*(?:(?:public|protected|private|static|final)\s+)*)Recipe\s*<\s*\?\s*>(\s+${Regex.escape(methodName)}\s*\()""")
        ) { match ->
            changed = true
            "${match.groupValues[1]}RecipeHolder<?>${match.groupValues[2]}"
        }
    }
    result = replaceExecutableRegex(result, optionalRecipeByKeyPattern) { match ->
        changed = true
        "Optional<? extends Recipe<?>> ${match.groupValues[1]} = ${match.groupValues[2].trim()}.map(RecipeHolder::value);"
    }

    val holderVariables = linkedSetOf<String>()
    if (recipeHolderMethods.isNotEmpty()) {
        val methodAlternation = recipeHolderMethods.joinToString("|") { Regex.escape(it) }
        val assignmentPattern = Regex("""\bRecipe\s*<\s*\?\s*>\s+($id)\s*=\s*($methodAlternation)\s*\(""")
        assignmentPattern.findAll(maskJavaCommentsAndLiterals(result)).forEach {
            holderVariables += it.groupValues[1]
        }
        result = replaceExecutableRegex(result, assignmentPattern) { match ->
            changed = true
            "RecipeHolder<?> ${match.groupValues[1]} = ${match.groupValues[2]}("
        }
    }
    for (variable in holderVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getResultItem\(""")
        ) {
            changed = true
            "$variable.value().getResultItem("
        }
    }

    val registryAccessVariables = Regex("""\.getResultItem\(\s*($id)\s*\)""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    for (variable in registryAccessVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\bRegistryAccess\s+${Regex.escape(variable)}\b""")
        ) {
            changed = true
            "HolderLookup.Provider $variable"
        }
    }
    if (!changed) return source
    result = addExecutableImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    if (maskJavaCommentsAndLiterals(result).contains("HolderLookup.Provider")) {
        result = addExecutableImportIfMissing(result, "net.minecraft.core.HolderLookup")
        val withoutRegistryAccess = removeExecutableImport(result, "net.minecraft.core.RegistryAccess")
        if (!Regex("""\bRegistryAccess\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutRegistryAccess))) {
            result = withoutRegistryAccess
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateGetAllRecipesForSingleHolderLookups(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("getAllRecipesFor(") ||
        !executableCode.contains(".findFirst()") ||
        !executableCode.contains("Recipe<")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val convertedVariables = linkedSetOf<String>()
    val singleLookupPattern = Regex(
        """(?s)\b(?:net\.minecraft\.world\.item\.crafting\.)?Recipe\s*<\s*[^;=]+?\s*>\s+($id)\s*=\s*([^;]*?getAllRecipesFor\s*\([^;]*?\)\s*\.stream\s*\(\s*\)\s*\.filter\s*\([^;]*?\)\s*\.findFirst\s*\(\s*\)\s*\.get\s*\(\s*\))\s*;"""
    )
    var result = replaceExecutableRegex(source, singleLookupPattern) { match ->
        val variable = match.groupValues[1]
        convertedVariables += variable
        "RecipeHolder<?> $variable = ${match.groupValues[2].trim()};"
    }
    if (convertedVariables.isEmpty()) return source

    convertedVariables.forEach { variable ->
        result = migrateRecipeHolderBareInstanceofBlocks(result, variable)
        result = migrateRecipeHolderInlineInstanceofConditions(result, variable)
        val escapedVariable = Regex.escape(variable)
        result = replaceExecutableRegex(
            result,
            Regex("""\b$escapedVariable\s+instanceof\s+($id(?:\.$id)*)\s+($id)\b""")
        ) { match ->
            "$variable.value() instanceof ${match.groupValues[1]} ${match.groupValues[2]}"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""\(\s*($id(?:\.$id)*)\s*\)\s*$escapedVariable\b""")
        ) { match ->
            "(${match.groupValues[1]}) $variable.value()"
        }
    }

    result = addExecutableImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    val withoutRecipeImport = removeExecutableImport(result, "net.minecraft.world.item.crafting.Recipe")
    if (!hasSimpleTypeReference(withoutRecipeImport, "Recipe")) {
        result = withoutRecipeImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderInlineInstanceofConditions(source: String, variable: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val escapedVariable = Regex.escape(variable)
    val instancePattern = Regex("""\b$escapedVariable\s+instanceof\s+($id(?:\.$id)*)\s*&&""")
    var result = source
    var cursor = 0
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val ifMatch = Regex("""\bif\s*\(""").find(executableCode, cursor) ?: break
        val openParen = executableCode.indexOf('(', ifMatch.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = ifMatch.range.last + 1
            continue
        }
        val condition = result.substring(openParen + 1, closeParen)
        val executableCondition = executableCode.substring(openParen + 1, closeParen)
        val instanceMatch = instancePattern.find(executableCondition)
        if (instanceMatch == null) {
            cursor = closeParen + 1
            continue
        }

        val recipeType = instanceMatch.groupValues[1]
        val typedVariable = recipeType.substringAfterLast('.')
            .replaceFirstChar { it.lowercase() }
            .let { if (it == variable) "${it}Value" else it }
        var migratedCondition = replaceExecutableRegex(
            condition,
            Regex("""\(\s*${Regex.escape(recipeType)}\s*\)\s*$escapedVariable\.value\(\)""")
        ) { typedVariable }
        migratedCondition = replaceExecutableRegex(
            migratedCondition,
            Regex("""\(\s*${Regex.escape(recipeType)}\s*\)\s*$escapedVariable\b""")
        ) { typedVariable }
        migratedCondition = replaceExecutableRegex(
            migratedCondition,
            Regex("""\b$escapedVariable\.""")
        ) { "$typedVariable." }
        migratedCondition = replaceExecutableRegex(migratedCondition, instancePattern) {
            "$variable.value() instanceof $recipeType $typedVariable &&"
        }
        if (migratedCondition != condition) {
            result = result.substring(0, openParen + 1) + migratedCondition + result.substring(closeParen)
            cursor = openParen + 1 + migratedCondition.length
        } else {
            cursor = closeParen + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderBareInstanceofBlocks(source: String, variable: String): String {
    val id = """[A-Za-z_$][\w$]*"""
    val pattern = Regex("""if\s*\(\s*${Regex.escape(variable)}\s+instanceof\s+($id(?:\.$id)*)\s*\)\s*\{""")
    var result = source
    var cursor = 0
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val match = pattern.find(executableCode, cursor) ?: break
        val sourceMatch = pattern.find(result, match.range.first)
            ?.takeIf { it.range == match.range }
        if (sourceMatch == null) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = executableCode.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val recipeType = sourceMatch.groupValues[1]
        val typedVariable = recipeType.substringAfterLast('.')
            .replaceFirstChar { it.lowercase() }
            .let { if (it == variable) "${it}Value" else it }
        val body = result.substring(openBrace + 1, closeBrace)
        var migratedBody = replaceExecutableRegex(
            body,
            Regex("""\b${Regex.escape(variable)}\.""")
        ) { "$typedVariable." }
        migratedBody = replaceExecutableRegex(
            migratedBody,
            Regex("""\(\s*${Regex.escape(recipeType)}\s*\)\s*${Regex.escape(variable)}\b""")
        ) { typedVariable }
        val replacement = "if ($variable.value() instanceof $recipeType $typedVariable) {$migratedBody}"
        result = result.substring(0, match.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = match.range.first + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.collapseDuplicateRecipeHolderValueMaps(source: String): String =
    replaceExecutableRegex(
        source,
        Regex("""\.map\(\s*RecipeHolder::value\s*\)\s*\.map\(\s*RecipeHolder::value\s*\)""")
    ) { ".map(RecipeHolder::value)" }

internal fun StructuralRefactorPass.migrateLegacyItemMaxDamageCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(".getMaxDamage()") ||
        !Regex("""\bItem\s+[A-Za-z_$][\w$]*\b""").containsMatchIn(executableCode)) {
        return source
    }
    var result = source
    val itemVariables = Regex("""\bItem\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in itemVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getMaxDamage\(\)""")
        ) { "$variable.getDefaultInstance().getMaxDamage()" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderIdAndLocalMmlibApi(source: String): String {
    if (!source.contains("RecipeHolder") &&
        !source.contains("RegistryAccess") &&
        !source.contains("trackRecipeExperience") &&
        !source.contains("BuiltInRegistries.RECIPE_SERIALIZER") &&
        !source.contains("recipe.setId(id)") &&
        !source.contains("recipe.getId()")) {
        return source
    }

    var result = source

    if (result.contains("DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER")) {
        result = result.replace(
            "DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER",
            "DeferredRegister.create(Registries.RECIPE_SERIALIZER"
        )
        result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
        if (!Regex("""\bBuiltInRegistries\.""").containsMatchIn(result)) {
            result = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
        }
    }

    if (result.contains("getResultItem(RegistryAccess")) {
        result = Regex("""getResultItem\(\s*RegistryAccess\s+([A-Za-z_$][\w$]*)\s*\)""")
            .replace(result) { match -> "getResultItem(HolderLookup.Provider ${match.groupValues[1]})" }
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
        if (!Regex("""\bRegistryAccess\b""").containsMatchIn(removeImport(result, "net.minecraft.core.RegistryAccess"))) {
            result = removeImport(result, "net.minecraft.core.RegistryAccess")
        }
    }

    val holderListVariables = mutableMapOf<String, String>()
    val id = """[A-Za-z_$][\w$]*"""
    val recipeHolderListPattern = Regex(
        """(?s)List<($id)>\s+($id)\s*=\s*([^;]*getRecipesFor\([^;]*\))\.stream\(\)\.map\(RecipeHolder::value\)\.toList\(\);"""
    )
    result = recipeHolderListPattern.replace(result) { match ->
        val recipeType = match.groupValues[1]
        val listName = match.groupValues[2]
        val recipeCall = match.groupValues[3].trim()
        holderListVariables[listName] = recipeType
        "List<RecipeHolder<$recipeType>> $listName = $recipeCall;"
    }
    val directRecipeHolderListPattern = Regex(
        """(?s)List<($id)>\s+($id)\s*=\s*([^;]*getRecipesFor\([^;]*\))\s*;"""
    )
    result = directRecipeHolderListPattern.replace(result) { match ->
        val recipeType = match.groupValues[1]
        if (recipeType == "RecipeHolder" || recipeType == "ItemStack") {
            match.value
        } else {
            val listName = match.groupValues[2]
            val recipeCall = match.groupValues[3].trim()
            holderListVariables[listName] = recipeType
            "List<RecipeHolder<$recipeType>> $listName = $recipeCall;"
        }
    }

    val recipeVariablesInHolderLoops = mutableSetOf<String>()
    for ((listName, recipeType) in holderListVariables) {
        val loopPattern = Regex(
            """(?m)^([ \t]*)for\s*\(\s*$recipeType\s+([A-Za-z_$][\w$]*)\s*:\s*${Regex.escape(listName)}\s*\)\s*\{"""
        )
        result = loopPattern.replace(result) { match ->
            val indent = match.groupValues[1]
            val recipeVar = match.groupValues[2]
            recipeVariablesInHolderLoops.add(recipeVar)
            "${indent}for (RecipeHolder<$recipeType> holder : $listName) {\n${indent}    $recipeType $recipeVar = holder.value();"
        }
    }
    val optionalRecipeToHolderList = mutableMapOf<String, String>()
    for ((listName, recipeType) in holderListVariables) {
        val optionalStreamPattern = Regex(
            """Optional<$recipeType>\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(listName)}\.stream\(\)(?!\s*\.map\(RecipeHolder::value\))"""
        )
        result = optionalStreamPattern.replace(result) { match ->
            val optionalName = match.groupValues[1]
            optionalRecipeToHolderList[optionalName] = listName
            "Optional<$recipeType> $optionalName = $listName.stream()\n                .map(RecipeHolder::value)"
        }
    }
    for (recipeVar in recipeVariablesInHolderLoops) {
        result = Regex("""lastRecipeID\s*=\s*${Regex.escape(recipeVar)}\.getId\(\)\s*;""")
            .replace(result, "lastRecipeID = holder.id();")
    }
    for ((optionalName, listName) in optionalRecipeToHolderList) {
        result = Regex("""lastRecipeID\s*=\s*${Regex.escape(optionalName)}\.get\(\)\.id\(\)\s*;""")
            .replace(
                result,
                "lastRecipeID = $listName.stream()\n                .filter(h -> h.value() == $optionalName.get()).findFirst()\n                .map(RecipeHolder::id).orElse(null);"
            )
    }

    val optionalRecipeHolderPattern = Regex(
        """(?s)Optional<($id)>\s+($id)\s*=\s*([^;]*getRecipeFor\([^;]*\))\.map\(RecipeHolder::value\)\s*;"""
    )
    val optionalValueTypes = SourceValueTypes(this, result)
    // Level, MinecraftServer and the other platform owners of a no-argument getRecipeManager()
    // all return the RecipeManager, so such a call needs no receiver typing.
    val platformGetRecipeManagerReturnsRecipeManager = TargetMembers.methodsNamed("getRecipeManager")
        .filter { (_, descriptor) -> descriptor.startsWith("()") }
        .let { methods ->
            methods.isNotEmpty() && methods.all { (_, descriptor) ->
                descriptor == "()Lnet/minecraft/world/item/crafting/RecipeManager;"
            }
        }
    val optionalParser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    // A recipe-valued Optional may be empty. Keep that behavior at its return boundary,
    // and confine binding rewrites to the method that declares the local.
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodSource = result.substring(method.range)
        val executable = maskJavaCommentsAndLiterals(methodSource)
        if (Regex("""\b(?:class|interface|record)\b""").containsMatchIn(executable)) continue
        val matches = optionalRecipeHolderPattern.findAll(executable).filter { match ->
            val call = optionalParser.parseExpression<Expression>(methodSource.substring(match.groups[3]!!.range)).result.orElse(null) as? MethodCallExpr
            val receiver = call?.scope?.orElse(null)
            call?.nameAsString == "getRecipeFor" && receiver != null && (
                optionalValueTypes.typeOf(receiver.toString(), method.range.first + match.range.first) ==
                    "net.minecraft.world.item.crafting.RecipeManager" ||
                    (receiver is MethodCallExpr && receiver.nameAsString == "getRecipeManager" &&
                        receiver.arguments.isEmpty() && platformGetRecipeManagerReturnsRecipeManager)
                )
        }.toList()
        if (matches.isEmpty()) continue
        // A local is in scope from its declaration to the end of its enclosing block, and Java
        // forbids redeclaring it there, so each declaration rewrites only that span. Sibling
        // blocks may reuse the name for unrelated values.
        var rewritten = methodSource
        for (match in matches.sortedByDescending { it.range.first }) {
            val recipeType = match.groupValues[1]
            val optionalName = Regex.escape(match.groupValues[2])
            val masked = maskJavaCommentsAndLiterals(rewritten)
            val scopeEnd = enclosingJavaBlockEnd(masked, match.range.first)
            val scopeStart = match.range.last + 1
            val scopeMasked = masked.substring(scopeStart, scopeEnd)
            val uses = Regex("""(?<![\w$.])$optionalName(?![\w$])""").findAll(scopeMasked).count()
            val supported = listOf(
                Regex("""(?<![\w$.])$optionalName\.(?:isPresent|isEmpty)\(\)"""),
                Regex("""\breturn\s+$optionalName\s*;"""),
                Regex("""(?<![\w$.])$optionalName\.get\(\)\.(?:getId\(\)|assemble\(|getResultItem\()"""),
                // `T local = optional.get();` keeps the recipe value; later rules route the
                // holder to the holder-typed APIs (setRecipeUsed)
                Regex("""=\s*$optionalName\.get\(\)\s*;""")
            ).sumOf { it.findAll(scopeMasked).count() }
            if (uses != supported) continue
            var scope = rewritten.substring(scopeStart, scopeEnd)
            scope = replaceExecutableRegex(scope, Regex("""\breturn\s+$optionalName\s*;""")) {
                "return ${match.groupValues[2]}.map(RecipeHolder::value);"
            }
            scope = replaceExecutableRegex(scope, Regex("""(?<![\w$.])($optionalName)\.get\(\)\.getId\(\)""")) {
                "${it.groupValues[1]}.get().id()"
            }
            scope = replaceExecutableRegex(scope, Regex("""(?<![\w$.])($optionalName)\.get\(\)\.(assemble|getResultItem)\(""")) {
                "${it.groupValues[1]}.get().value().${it.groupValues[2]}("
            }
            scope = replaceExecutableRegex(scope, Regex("""=(\s*)($optionalName)\.get\(\)(\s*);""")) {
                "=${it.groupValues[1]}${it.groupValues[2]}.get().value()${it.groupValues[3]};"
            }
            rewritten = rewritten.substring(0, match.range.first) +
                "Optional<RecipeHolder<$recipeType>> ${match.groupValues[2]} = ${match.groupValues[3].trim()};" +
                scope + rewritten.substring(scopeEnd)
        }
        result = result.replaceRange(method.range, rewritten)
    }

    result = Regex("""lastRecipeID\s*=\s*([A-Za-z_$][\w$]*)\.get\(\)\.getId\(\)\s*;""")
        .replace(result) { match -> "lastRecipeID = ${match.groupValues[1]}.get().id();" }
    for ((optionalName, listName) in optionalRecipeToHolderList) {
        result = Regex("""lastRecipeID\s*=\s*${Regex.escape(optionalName)}\.get\(\)\.id\(\)\s*;""")
            .replace(
                result,
                "lastRecipeID = $listName.stream()\n                .filter(h -> h.value() == $optionalName.get()).findFirst()\n                .map(RecipeHolder::id).orElse(null);"
            )
    }

    Regex("""Optional<RecipeHolder<[^>]+>>\s+([A-Za-z_$][\w$]*)""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
        .forEach { optionalHolder ->
            result = Regex("""${Regex.escape(optionalHolder)}\.get\(\)\.(assemble|getResultItem)\(""")
                .replace(result) { match -> "$optionalHolder.get().value().${match.groupValues[1]}(" }
        }

    result = migrateRecipeManagerByKeyHolderAccessSource(result)
    result = migrateLegacyRecipeBuilderResultIds(result)
    result = removeLegacyRecipeInstanceIdMethods(result)
    result = migrateRecipeExperienceTrackingSource(result)

    if (result.contains("RecipeHolder<")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderLoopTypeParameters(source: String): String {
    if (!source.contains("getAllRecipesFor(") || !source.contains("RecipeType<")) return source
    val id = """[A-Za-z_$][\w$]*"""
    var result = source
    var searchStart = 0
    val methodPattern = Regex(
        """<\s*($id)\s+extends\s+($id)\s*>[^{;]*?\([^)]*\bRecipeType\s*<\s*\1\s*>\s+($id)\b[^)]*\)\s*\{"""
    )
    var changed = false
    while (true) {
        val match = methodPattern.find(result, searchStart) ?: break
        val openBrace = match.range.last
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            searchStart = match.range.last + 1
            continue
        }

        val typeParam = match.groupValues[1]
        val superParam = match.groupValues[2]
        val recipeTypeVar = match.groupValues[3]
        val body = result.substring(openBrace + 1, closeBrace)
        val loopPattern = Regex(
            """for\s*\(\s*RecipeHolder\s*<\s*${Regex.escape(superParam)}\s*>\s+($id)\s*:\s*([^;\r\n]*getAllRecipesFor\s*\(\s*${Regex.escape(recipeTypeVar)}\s*\)[^;\r\n]*)\)"""
        )
        var migratedBody = loopPattern.replace(body) { loop ->
            "for (RecipeHolder<$typeParam> ${loop.groupValues[1]} : ${loop.groupValues[2]})"
        }
        val directLoopPattern = Regex(
            """(?m)^([ \t]*)for\s*\(\s*${Regex.escape(superParam)}\s+($id)\s*:\s*([^;\r\n]*getAllRecipesFor\s*\(\s*${Regex.escape(recipeTypeVar)}\s*\)[^;\r\n]*)\)\s*\{"""
        )
        migratedBody = directLoopPattern.replace(migratedBody) { loop ->
            val indent = loop.groupValues[1]
            val recipeVar = loop.groupValues[2]
            val recipeCall = loop.groupValues[3]
            "${indent}for (RecipeHolder<$typeParam> ${recipeVar}Holder : $recipeCall) {\n${indent}    $superParam $recipeVar = ${recipeVar}Holder.value();"
        }
        if (migratedBody != body) {
            changed = true
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            searchStart = openBrace + 1 + migratedBody.length
        } else {
            searchStart = closeBrace + 1
        }
    }
    if (changed) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeHolderInstanceofGetAllRecipesForLoops(source: String): String {
    if (!source.contains("getAllRecipesFor(") || !source.contains("instanceof")) return source
    val id = """[A-Za-z_$][\w$]*"""
    val loopPattern = Regex(
        """(?m)^([ \t]*)for\s*\(\s*Recipe\s*<\s*\?\s*>\s+($id)\s*:\s*([^;\r\n]*getAllRecipesFor\([^;\r\n]*\))\s*\)\s*\{"""
    )
    var result = source
    var cursor = 0
    var changed = false
    while (true) {
        val loop = loopPattern.find(result, cursor) ?: break
        val openBrace = result.indexOf('{', loop.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = loop.range.last + 1
            continue
        }

        val recipeVar = loop.groupValues[2]
        val body = result.substring(openBrace + 1, closeBrace)
        val ifPattern = Regex("""(?s)^\s*if\s*\(\s*${Regex.escape(recipeVar)}\s+instanceof\s+($id)\s+($id)\s*\)\s*\{""")
        val ifMatch = ifPattern.find(body)
        if (ifMatch == null) {
            cursor = closeBrace + 1
            continue
        }
        val ifOpenBrace = ifMatch.range.last
        val ifCloseBrace = findMatchingBrace(body, ifOpenBrace)
        if (ifCloseBrace < 0 || body.substring(ifCloseBrace + 1).isNotBlank()) {
            cursor = closeBrace + 1
            continue
        }

        val indent = loop.groupValues[1]
        val recipeCall = loop.groupValues[3].trim()
        val recipeType = ifMatch.groupValues[1]
        val typedVar = ifMatch.groupValues[2]
        val innerBody = body.substring(ifOpenBrace + 1, ifCloseBrace).trimEnd()
        val declarationIndent = Regex("""\r?\n([ \t]*)\S""")
            .find(innerBody)
            ?.groupValues
            ?.get(1)
            ?: "$indent    "
        val replacement = "${indent}for (RecipeHolder<$recipeType> ${typedVar}Holder : $recipeCall) {\n" +
            "${declarationIndent}$recipeType $typedVar = ${typedVar}Holder.value();" +
            innerBody +
            "\n$indent}"
        result = result.substring(0, loop.range.first) + replacement + result.substring(closeBrace + 1)
        cursor = loop.range.first + replacement.length
        changed = true
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
    if (!Regex("""\bRecipe\s*[<\[]""").containsMatchIn(removeImport(result, "net.minecraft.world.item.crafting.Recipe"))) {
        result = removeImport(result, "net.minecraft.world.item.crafting.Recipe")
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeManagerByKeyHolderAccessSource(source: String): String {
    if (!source.contains(".byKey(") || !source.contains(".get()")) return source
    val id = """[A-Za-z_$][\w$]*"""
    return Regex("""(?s)byKey\(.*?\)\.ifPresent\(\s*($id)\s*->\s*(.*?)\)\s*;""")
        .replace(source) { match ->
            val recipeVar = match.groupValues[1]
            match.value
                .replace("$recipeVar.value().get()", "$recipeVar.value()")
                .replace("$recipeVar.get()", "$recipeVar.value()")
        }
}

internal fun StructuralRefactorPass.migrateRecipeBookCategoryFinderRecipeHolders(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("registerRecipeCategoryFinder(") || !executableCode.contains(" instanceof ")) return source
    val id = """[A-Za-z_$][\w$]*"""
    var changed = false
    val result = rewriteExecutableJavaCall(source, "registerRecipeCategoryFinder") { receiver, args ->
        if (args.size < 2) return@rewriteExecutableJavaCall null
        val lambda = args[1]
        val lambdaMatch = Regex(
            """(?s)^\s*(?:\(\s*(?:[\w$.\s?<>]+\s+)?($id)\s*\)|($id))\s*->\s*\{(.*)}\s*$"""
        ).find(lambda) ?: return@rewriteExecutableJavaCall null
        val recipeParam = (lambdaMatch.groupValues[1].ifBlank { lambdaMatch.groupValues[2] }).trim()
        val body = lambdaMatch.groupValues[3]
        val instanceOfPattern = Regex("""\b${Regex.escape(recipeParam)}\s+instanceof\b""")
        if (!instanceOfPattern.containsMatchIn(body)) return@rewriteExecutableJavaCall null
        val valueVarBase = "${recipeParam}Value"
        var valueVar = valueVarBase
        var suffix = 2
        while (Regex("""\b${Regex.escape(valueVar)}\b""").containsMatchIn(body)) {
            valueVar = "$valueVarBase$suffix"
            suffix++
        }
        val bodyIndent = Regex("""\r?\n([ \t]*)\S""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: "    "
        val migratedBody = instanceOfPattern.replace(body) { "$valueVar instanceof" }
        val normalizedBody = if (migratedBody.startsWith("\n")) migratedBody else "\n$bodyIndent$migratedBody"
        changed = true
        val migratedLambda = "$recipeParam -> {\n${bodyIndent}var $valueVar = $recipeParam.value();$normalizedBody}"
        val migratedArgs = args.toMutableList()
        migratedArgs[1] = migratedLambda
        "$receiver.registerRecipeCategoryFinder(${migratedArgs.joinToString(", ") { it.trim() }})"
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateLegacyCraftingRecipeBoundaries(source: String): String {
    if (!source.contains("getRecipeManager()") &&
        !source.contains("CraftingContainer") &&
        !source.contains("Recipe<Container>") &&
        !(source.contains("CraftingInput") && source.contains("RegistryAccess"))) {
        return source
    }

    var result = source
    var needsCraftingInput = false
    var needsRecipeHolder = false
    var needsHolderLookup = false

    result = migrateLegacyRecordShapedCraftingRecipeSource(result)
    result = migrateLegacyGsonRegistryRecipeSerializerSource(result)
    result = migrateRecipeHolderLoopTypeParameters(result)
    result = migrateRecipeHolderInstanceofGetAllRecipesForLoops(result)

    val id = """[A-Za-z_$][\w$]*"""
    val annotations = """(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*"""
    result = Regex(
        """assemble\(\s*($annotations)CraftingInput\s+($id)\s*,\s*($annotations)RegistryAccess\s+($id)\s*\)"""
    ).replace(result) { match ->
        needsHolderLookup = true
        "assemble(${match.groupValues[1]}CraftingInput ${match.groupValues[2]}, ${match.groupValues[3]}HolderLookup.Provider ${match.groupValues[4]})"
    }
    if (needsHolderLookup) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
        if (!Regex("""\bRegistryAccess\b""").containsMatchIn(removeImport(result, "net.minecraft.core.RegistryAccess"))) {
            result = removeImport(result, "net.minecraft.core.RegistryAccess")
        }
    }

    if (result.contains("@Mixin") && result.contains("method = \"assemble\"") && result.contains("CraftingContainer")) {
        var migratedCraftingMixin = false
        result = Regex(
            """CraftingContainer\s+($id)\s*,\s*(HolderLookup\.Provider|RegistryAccess)\s+($id)"""
        ).replace(result) { match ->
            migratedCraftingMixin = true
            needsCraftingInput = true
            if (match.groupValues[2] == "RegistryAccess") needsHolderLookup = true
            "CraftingInput ${match.groupValues[1]}, HolderLookup.Provider ${match.groupValues[3]}"
        }
        if (migratedCraftingMixin) {
            result = Regex("""method\s*=\s*"assemble"""")
                .replace(result) {
                    """method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/core/HolderLookup${'$'}Provider;)Lnet/minecraft/world/item/ItemStack;""""
                }
        }
    }

    if (result.contains("implements CraftingRecipe") && result.contains("CraftingContainer")) {
        result = Regex(
            """matches\(\s*($annotations)CraftingContainer\s+($id)\s*,\s*($annotations)Level\s+($id)\s*\)"""
        ).replace(result) { match ->
            needsCraftingInput = true
            "matches(${match.groupValues[1]}CraftingInput ${match.groupValues[2]}, ${match.groupValues[3]}Level ${match.groupValues[4]})"
        }
        result = Regex(
            """assemble\(\s*($annotations)CraftingContainer\s+($id)\s*,\s*($annotations)(HolderLookup\.Provider|RegistryAccess)\s+($id)\s*\)"""
        ).replace(result) { match ->
            needsCraftingInput = true
            if (match.groupValues[4] == "RegistryAccess") needsHolderLookup = true
            "assemble(${match.groupValues[1]}CraftingInput ${match.groupValues[2]}, ${match.groupValues[3]}HolderLookup.Provider ${match.groupValues[5]})"
        }
    }

    val craftingContainerVariables = Regex("""\bCraftingContainer\s+($id)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    fun craftingInputArgument(arg: String): String {
        val trimmed = arg.trim()
        val receiver = trimmed.removePrefix("this.")
        return if (receiver in craftingContainerVariables && !trimmed.endsWith(".asCraftInput()")) {
            "$trimmed.asCraftInput()"
        } else {
            arg
        }
    }
    if (craftingContainerVariables.isNotEmpty()) {
        result = rewriteJavaInvocationArguments(result, "getRecipeFor") { args ->
            if (args.size >= 3 && args[0].trim() == "RecipeType.CRAFTING") {
                val migrated = args.toMutableList()
                migrated[1] = craftingInputArgument(args[1])
                migrated.takeIf { it != args }
            } else {
                null
            }
        }
        result = rewriteJavaInvocationArguments(result, "getRecipesFor") { args ->
            if (args.size >= 3 && args[0].trim() == "RecipeType.CRAFTING") {
                val migrated = args.toMutableList()
                migrated[1] = craftingInputArgument(args[1])
                migrated.takeIf { it != args }
            } else {
                null
            }
        }
        result = rewriteJavaInvocationArguments(result, "getRemainingItemsFor") { args ->
            if (args.size >= 3 && args[0].trim() == "RecipeType.CRAFTING") {
                val migrated = args.toMutableList()
                migrated[1] = craftingInputArgument(args[1])
                migrated.takeIf { it != args }
            } else {
                null
            }
        }
        result = Regex("""for\s*\(\s*Recipe\s*<\s*CraftingContainer\s*>\s+($id)\s*:\s*([^;\r\n]+?getRecipesFor\([^;\r\n]+?\))\s*\)\s*\{""")
            .replace(result) { match ->
                val recipeVar = match.groupValues[1]
                needsRecipeHolder = true
                "for (RecipeHolder<CraftingRecipe> ${recipeVar}Holder : ${match.groupValues[2]}) {\n\t\t\tCraftingRecipe $recipeVar = ${recipeVar}Holder.value();"
            }
    }

    val recipeMatchesHolderVariables = mutableSetOf<String>()
    result = Regex(
        """(?m)(public\s+boolean\s+recipeMatches\s*\()\s*Recipe\s*<\s*\?\s+super\s+CraftingContainer\s*>\s+($id)\s*(\))"""
    ).replace(result) { match ->
        val recipeVar = match.groupValues[2]
        recipeMatchesHolderVariables.add(recipeVar)
        needsRecipeHolder = true
        "${match.groupValues[1]}RecipeHolder<CraftingRecipe> $recipeVar${match.groupValues[3]}"
    }
    for (recipeVar in recipeMatchesHolderVariables) {
        result = rewriteJavaCall(result, "matches") { receiver, args ->
            if (receiver != recipeVar || args.size != 2) return@rewriteJavaCall null
            val migratedInput = craftingInputArgument(args[0])
            if (migratedInput == args[0]) return@rewriteJavaCall null
            needsCraftingInput = true
            "$recipeVar.value().matches(${migratedInput.trim()}, ${args[1].trim()})"
        }
    }

    val recipeHolderLocalAssignments = mutableMapOf<String, String>()
    result = Regex("""(?m)^([ \t]*)CraftingRecipe\s+($id)\s*=\s*($id)\.get\(\)(?:\.value\(\))?\s*;""")
        .replace(result) { match ->
            val optionalName = match.groupValues[3]
            val optionalHolderPattern = Regex(
                """\bOptional\s*<\s*RecipeHolder\s*<\s*CraftingRecipe\s*>\s*>\s+${Regex.escape(optionalName)}\b"""
            )
            if (!optionalHolderPattern.containsMatchIn(result)) return@replace match.value
            val indent = match.groupValues[1]
            val recipeVar = match.groupValues[2]
            val holderVar = "${recipeVar}Holder"
            recipeHolderLocalAssignments[recipeVar] = holderVar
            needsRecipeHolder = true
            "${indent}RecipeHolder<CraftingRecipe> $holderVar = $optionalName.get();\n${indent}CraftingRecipe $recipeVar = $holderVar.value();"
        }
    for ((recipeVar, holderVar) in recipeHolderLocalAssignments) {
        result = rewriteJavaInvocationArguments(result, "setRecipeUsed") { args ->
            val migrated = args.map { arg ->
                if (arg.trim() == recipeVar) holderVar else arg
            }
            migrated.takeIf { it != args }
        }
    }

    val filteredHolderListPattern = Regex(
        """(?s)List<($id)>\s+($id)\s*=\s*([^;]*getAllRecipesFor\([^;]*\))\s*;\s*\2\s*=\s*\2\.stream\(\)\.filter\(\s*($id)\s*->\s*(.*?)\)\s*(?://[^\r\n]*)?\s*\.collect\(Collectors\.toList\(\)\)\s*;"""
    )
    result = filteredHolderListPattern.replace(result) { match ->
        val recipeType = match.groupValues[1]
        val listName = match.groupValues[2]
        val recipeCall = match.groupValues[3].trim()
        val recipeVar = match.groupValues[4]
        val expression = match.groupValues[5]
            .replace("$recipeVar.getId()", "holder.id()")
            .trim()
        needsRecipeHolder = true
        """
List<$recipeType> $listName = $recipeCall.stream()
        .filter(holder -> {
            $recipeType $recipeVar = holder.value();
            return $expression;
        })
        .map(RecipeHolder::value)
        .collect(Collectors.toList());
""".trimEnd()
    }

    result = Regex("""new\s+ArrayList<>\(\s*([^;\r\n]*getAllRecipesFor\([^;\r\n]*\))\s*\)""")
        .replace(result) { match ->
            needsRecipeHolder = true
            "new ArrayList<>(${match.groupValues[1].trim()}.stream().map(RecipeHolder::value).toList())"
        }
    result = Regex("""([A-Za-z_$][\w$]*)\.addAll\(\s*([^;\r\n]*getAllRecipesFor\([^;\r\n]*\))\s*\)\s*;""")
        .replace(result) { match ->
            needsRecipeHolder = true
            "${match.groupValues[1]}.addAll(${match.groupValues[2].trim()}.stream().map(RecipeHolder::value).toList());"
        }
    result = Regex("""(registration\.addRecipes\(\s*[^,]+,\s*)([^;\r\n]*getAllRecipesFor\([^;\r\n]*\))(\s*\))""")
        .replace(result) { match ->
            needsRecipeHolder = true
            "${match.groupValues[1]}${match.groupValues[2].trim()}.stream().map(RecipeHolder::value).toList()${match.groupValues[3]}"
        }
    result = Regex("""(getAllRecipesFor\([^;\r\n]*\))\.forEach\(""")
        .replace(result) { match ->
            needsRecipeHolder = true
            "${match.groupValues[1]}.stream().map(RecipeHolder::value).forEach("
        }

    result = Regex("""for\s*\(\s*Recipe<\?>\s+($id)\s*:\s*([^;\r\n]*getRecipes\(\))\s*\)\s*\{""")
        .replace(result) { match ->
            val recipeVar = match.groupValues[1]
            needsRecipeHolder = true
            "for (RecipeHolder<?> ${recipeVar}Holder : ${match.groupValues[2]}) {\n\t\t\t\tRecipe<?> $recipeVar = ${recipeVar}Holder.value();"
        }
    result = Regex("""for\s*\(\s*($id)\s+($id)\s*:\s*([^;\r\n]*getAllRecipesFor\([^;\r\n]*\))\s*\)\s*\{""")
        .replace(result) { match ->
            val recipeType = match.groupValues[1]
            val recipeVar = match.groupValues[2]
            val recipeCall = match.groupValues[3]
            when (recipeType) {
                "RecipeHolder" -> match.value
                "var" -> {
                    needsRecipeHolder = true
                    "for (Object ${recipeVar}Holder : $recipeCall) {\n\t\t\t\tvar $recipeVar = ((RecipeHolder<?>) ${recipeVar}Holder).value();"
                }
                else -> {
                    needsRecipeHolder = true
                    "for (RecipeHolder<$recipeType> ${recipeVar}Holder : $recipeCall) {\n\t\t\t\t$recipeType $recipeVar = ${recipeVar}Holder.value();"
                }
            }
        }
    result = Regex("""\b([A-Za-z_$][\w$]*)\.getId\(\)""")
        .replace(result) { match ->
            val receiver = match.groupValues[1]
            if (result.contains("${receiver}Holder.value()")) {
                "${receiver}Holder.id()"
            } else {
                match.value
            }
        }

    val recipeArraySignature = Regex(
        """(?m)(@SuppressWarnings\([^)]*\)\s*)?private\s+static\s+CraftingRecipe\[\]\s+($id)\s*\(\s*CraftingContainer\s+($id)\s*,\s*Level\s+($id)\s*\)"""
    )
    result = recipeArraySignature.replace(result) { match ->
        needsCraftingInput = true
        needsRecipeHolder = true
        "${match.groupValues[1]}private static java.util.List<RecipeHolder<CraftingRecipe>> ${match.groupValues[2]}(CraftingInput ${match.groupValues[3]}, Level ${match.groupValues[4]})"
    }
    result = Regex("""toArray\(\s*new\s+CraftingRecipe\s*\[\s*0\s*]\s*\)""")
        .replace(result, "toList()")
    result = Regex("""(\bgetRecipeManager\(\)\.getRecipesFor\([^;\r\n]+?\))\.toList\(\)""")
        .replace(result, "$1")
    result = Regex("""(?m)^([ \t]*)private\s+void\s+chooseRecipe\s*\(\s*CraftingContainer\s+($id)\s*\)""")
        .replace(result) { match ->
            needsCraftingInput = true
            "${match.groupValues[1]}private void chooseRecipe(CraftingInput ${match.groupValues[2]})"
        }
    result = Regex("""chooseRecipe\(\s*((?:this\.)?$id)\s*\)""")
        .replace(result) { match ->
            val receiver = match.groupValues[1]
            if (receiver.endsWith("Matrix") || receiver.endsWith("Container")) {
                "chooseRecipe($receiver.asCraftInput())"
            } else {
                match.value
            }
        }
    val chooseRecipeMethod = javaDeclaredMethodText(result, "chooseRecipe") ?: ""
    if (chooseRecipeMethod.contains("CraftingRecipe[] recipes = getRecipesFor(")) {
        val originalChooseRecipeMethod: String = chooseRecipeMethod
        var replacement: String = originalChooseRecipeMethod
        replacement = replacement.replace("CraftingRecipe[] recipes = getRecipesFor(", "java.util.List<RecipeHolder<CraftingRecipe>> recipes = getRecipesFor(")
        replacement = replacement.replace("recipes.length", "recipes.size()")
        replacement = Regex("""recipes\s*\[\s*([^\]]+)\s*]""")
            .replace(replacement) { match -> "recipes.get(${match.groupValues[1].trim()})" }
        replacement = Regex("""CraftingRecipe\s+($id)\s*=\s*recipes\.get\(""")
            .replace(replacement) { match -> "RecipeHolder<CraftingRecipe> ${match.groupValues[1]} = recipes.get(" }
        replacement = Regex("""\b($id)\.isSpecial\(\)""")
            .replace(replacement) { match ->
                val receiver = match.groupValues[1]
                if (replacement.contains("RecipeHolder<CraftingRecipe> $receiver")) {
                    "$receiver.value().isSpecial()"
                } else {
                    match.value
                }
            }
        replacement = Regex("""\b($id)\.assemble\(\s*($id)\s*,""")
            .replace(replacement) { match ->
                val receiver = match.groupValues[1]
                val input = match.groupValues[2]
                if (replacement.contains("RecipeHolder<CraftingRecipe> $receiver")) {
                    "$receiver.value().assemble($input,"
                } else {
                    match.value
                }
            }
        replacement = Regex("""getRecipeBook\(\)\.contains\(\s*($id)\s*\)""")
            .replace(replacement) { match ->
                val receiver = match.groupValues[1]
                if (replacement.contains("RecipeHolder<CraftingRecipe> $receiver")) {
                    "getRecipeBook().contains($receiver.id())"
                } else {
                    match.value
                }
            }
        result = result.replace(originalChooseRecipeMethod, replacement)
        needsRecipeHolder = true
    }
    val recipeHolderVariables = Regex("""\bRecipeHolder\s*<[^>]+>\s+($id)\b""")
        .findAll(result)
        .map { it.groupValues[1] }
        .toSet()
    for (holder in recipeHolderVariables) {
        result = replaceExecutableRegex(result, Regex("""\b${Regex.escape(holder)}\.getId\(\)""")) {
            "$holder.id()"
        }
    }
    result = Regex("""\b($id)\.assemble\(\s*((?:this\.)?$id)\s*,""")
        .replace(result) { match ->
            val receiver = match.groupValues[1]
            val input = match.groupValues[2]
            val migratedInput = craftingInputArgument(input)
            if (migratedInput != input) {
                needsCraftingInput = true
                "$receiver.assemble(${migratedInput.trim()},"
            } else {
                match.value
            }
        }

    result = migrateCraftingInputSizeCallsForDeclaredScopes(result)

    if (needsCraftingInput) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.CraftingInput")
        if (!Regex("""\bCraftingContainer\b""").containsMatchIn(removeImport(result, "net.minecraft.world.inventory.CraftingContainer"))) {
            result = removeImport(result, "net.minecraft.world.inventory.CraftingContainer")
        }
    }
    if (needsRecipeHolder) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.CraftingRecipe")
        if (!Regex("""\bRecipe\s*<""").containsMatchIn(result)) {
            result = removeImport(result, "net.minecraft.world.item.crafting.Recipe")
        }
    }
    if (needsHolderLookup) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
        if (!Regex("""\bRegistryAccess\b""").containsMatchIn(removeImport(result, "net.minecraft.core.RegistryAccess"))) {
            result = removeImport(result, "net.minecraft.core.RegistryAccess")
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateRepairItemRecipeEnchantmentFilterRedirect(source: String): String {
    if (!source.contains("@Mixin(RepairItemRecipe.class)") ||
        !source.contains("@Redirect") ||
        !source.contains("Ljava/util/stream/Stream;filter") ||
        !source.contains("getAllEnchantments(")) {
        return source
    }

    val executable = maskJavaCommentsAndLiterals(source)
    val id = """[A-Za-z_$][\w$]*"""
    for (method in javaMethodRangesIncludingDefault(executable).asReversed()) {
        val methodText = source.substring(method.range)
        if (!methodText.contains("Stream<") ||
            !methodText.contains("getAllEnchantments(") ||
            !methodText.contains("EnchantmentHelper.getItemEnchantmentLevel(")) {
            continue
        }
        val annotationStart = source.lastIndexOf("@Redirect", method.range.first)
        if (annotationStart < 0) continue
        val annotationText = source.substring(annotationStart, method.range.first)
        if (!annotationText.contains("Ljava/util/stream/Stream;filter")) continue

        val parameters = javaMethodParameters(methodText)
        val inputName = parameters.firstOrNull {
            simpleJavaTypeName(it.type) in setOf("CraftingInput", "CraftingContainer")
        }?.name ?: continue
        val lookupName = parameters.firstOrNull {
            simpleJavaTypeName(it.type) == "RegistryAccess" ||
                (simpleJavaTypeName(it.type) == "Provider" && it.type.contains("HolderLookup"))
        }?.name ?: continue
        val stackNames = Regex("""\bItemStack\s+($id)\s*=\s*ItemStack\.EMPTY\s*;""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .take(2)
            .toList()
        if (stackNames.size != 2) continue
        val inheritanceKey = Regex("""\b$id\.get\(\s*($id(?:\.$id)+)\s*\)""")
            .findAll(methodText)
            .map { it.groupValues[1] }
            .firstOrNull()
            ?: continue
        val declaration = Regex(
            """(?m)^([ \t]*)(?:(public|protected|private)\s+)?(static\s+)?[A-Za-z_$][\w$<>\[\].?,\s]*\s+${Regex.escape(method.name)}\s*\("""
        ).find(methodText) ?: continue
        val indent = javaIndentAt(source, annotationStart)
        val methodIndent = declaration.groupValues[1]
        val visibility = declaration.groupValues[2].ifBlank { "private" }
        val staticModifier = declaration.groupValues[3]
        val replacement = repairItemRecipeInheritanceInjectMethod(
            indent = indent,
            methodIndent = methodIndent,
            visibility = visibility,
            staticModifier = staticModifier,
            methodName = method.name,
            inputName = inputName,
            lookupName = lookupName,
            firstStack = stackNames[0],
            secondStack = stackNames[1],
            inheritanceKey = inheritanceKey
        )
        var result = source.substring(0, annotationStart) + replacement + source.substring(method.range.last + 1)
        result = addImportIfMissing(result, "org.spongepowered.asm.mixin.injection.Inject")
        result = addImportIfMissing(result, "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable")
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.CraftingInput")
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
        result = removeUnusedSimpleImport(result, "org.spongepowered.asm.mixin.injection.Redirect", "Redirect")
        result = removeUnusedSimpleImport(result, "java.util.function.Predicate", "Predicate")
        result = removeUnusedSimpleImport(result, "java.util.stream.Stream", "Stream")
        result = removeUnusedSimpleImport(result, "net.minecraft.world.inventory.CraftingContainer", "CraftingContainer")
        result = removeUnusedSimpleImport(result, "net.minecraft.core.RegistryAccess", "RegistryAccess")
        return result
    }
    return source
}

internal fun StructuralRefactorPass.repairItemRecipeInheritanceInjectMethod(
    indent: String,
    methodIndent: String,
    visibility: String,
    staticModifier: String,
    methodName: String,
    inputName: String,
    lookupName: String,
    firstStack: String,
    secondStack: String,
    inheritanceKey: String
): String {
    val inner = methodIndent + "    "
    val continuation = inner + "        "
    val staticPart = staticModifier.ifBlank { "" }
    val firstAlias = "modporterFirstStack"
    val secondAlias = "modporterSecondStack"
    return """
${indent}@Inject(
${indent}        method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/core/HolderLookup${'$'}Provider;)Lnet/minecraft/world/item/ItemStack;",
${indent}        at = @At("HEAD"),
${indent}        cancellable = true
${indent})
${methodIndent}$visibility ${staticPart}void $methodName(CraftingInput $inputName, HolderLookup.Provider $lookupName, CallbackInfoReturnable<ItemStack> cir) {
${inner}ItemStack $firstStack = ItemStack.EMPTY;
${inner}ItemStack $secondStack = ItemStack.EMPTY;
${inner}for (int i = 0; i < $inputName.size(); i++) {
${inner}    ItemStack stack = $inputName.getItem(i);
${inner}    if (stack.isEmpty()) {
${inner}        continue;
${inner}    }
${inner}    if ($firstStack.isEmpty()) {
${inner}        $firstStack = stack;
${inner}    } else {
${inner}        $secondStack = stack;
${inner}        break;
${inner}    }
${inner}}
${inner}if ($firstStack.isEmpty() || $secondStack.isEmpty() ||
${continuation}!$secondStack.is($firstStack.getItem()) ||
${continuation}$firstStack.getCount() != 1 ||
${continuation}$secondStack.getCount() != 1 ||
${continuation}!$firstStack.has(net.minecraft.core.component.DataComponents.MAX_DAMAGE) ||
${continuation}!$secondStack.has(net.minecraft.core.component.DataComponents.MAX_DAMAGE) ||
${continuation}!$firstStack.has(net.minecraft.core.component.DataComponents.DAMAGE) ||
${continuation}!$secondStack.has(net.minecraft.core.component.DataComponents.DAMAGE) ||
${continuation}!$firstStack.isRepairable() ||
${continuation}!$secondStack.isRepairable()) {
${inner}    return;
${inner}}
${inner}final ItemStack $firstAlias = $firstStack;
${inner}final ItemStack $secondAlias = $secondStack;
${inner}int modporterFirstInheritance = $lookupName.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
${inner}        .flatMap(registry -> registry.get($inheritanceKey))
${inner}        .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, $firstAlias))
${inner}        .orElse(0);
${inner}int modporterSecondInheritance = $lookupName.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)
${inner}        .flatMap(registry -> registry.get($inheritanceKey))
${inner}        .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, $secondAlias))
${inner}        .orElse(0);
${inner}if (modporterFirstInheritance <= 0 && modporterSecondInheritance <= 0) {
${inner}    return;
${inner}}
${inner}int modporterMaxDamage = Math.max($firstAlias.getMaxDamage(), $secondAlias.getMaxDamage());
${inner}int modporterFirstRemaining = $firstAlias.getMaxDamage() - $firstAlias.getDamageValue();
${inner}int modporterSecondRemaining = $secondAlias.getMaxDamage() - $secondAlias.getDamageValue();
${inner}int modporterRepaired = modporterFirstRemaining + modporterSecondRemaining + modporterMaxDamage * 5 / 100;
${inner}ItemStack modporterResult = new ItemStack($firstAlias.getItem());
${inner}modporterResult.set(net.minecraft.core.component.DataComponents.MAX_DAMAGE, modporterMaxDamage);
${inner}modporterResult.setDamageValue(Math.max(modporterMaxDamage - modporterRepaired, 0));
${inner}ItemStack modporterInheritedStack = modporterFirstInheritance > 0 ? $firstAlias : $secondAlias;
${inner}net.minecraft.world.item.enchantment.ItemEnchantments modporterEnchantments = modporterInheritedStack.getAllEnchantments($lookupName.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT));
${inner}EnchantmentHelper.setEnchantments(modporterResult, modporterEnchantments);
${inner}cir.setReturnValue(modporterResult);
${methodIndent}}
""".trimStart()
}

internal fun StructuralRefactorPass.migrateLegacyRecordShapedCraftingRecipeSource(source: String): String {
    if (!source.contains("implements CraftingRecipe") ||
        !source.contains("implements RecipeSerializer<") ||
        !source.contains("fromJson(") ||
        !source.contains("fromNetwork(") ||
        !source.contains("toNetwork(")) {
        return source
    }

    val declaration = Regex(
        """public\s+record\s+([A-Za-z_$][\w$]*)\s*\(\s*ResourceLocation\s+[A-Za-z_$][\w$]*\s*,\s*int\s+cost\s*,\s*int\s+width\s*,\s*int\s+height\s*,\s*Ingredient\s+input\s*,\s*int\s+count\s*,\s*NonNullList<Ingredient>\s+resultItems\s*\)\s+implements\s+CraftingRecipe(?:\s*,\s*ShapedRecipe)?\s*"""
    ).find(source) ?: return source
    val className = declaration.groupValues[1]
    if (!source.contains("implements RecipeSerializer<$className>")) return source

    val openBrace = source.indexOf('{', declaration.range.last)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(source, openBrace)
    if (closeBrace < 0) return source

    val body = source.substring(openBrace + 1, closeBrace)
    val serializerExpr = Regex("""(?s)public\s+RecipeSerializer<\?>\s+getSerializer\(\)\s*\{\s*return\s+([^;]+);\s*\}""")
        .find(body)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return source
    val recipeTypeExpr = Regex("""(?s)public\s+RecipeType<\?>\s+getType\(\)\s*\{\s*return\s+([^;]+);\s*\}""")
        .find(body)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: return source

    var result = source.substring(0, declaration.range.first) +
        legacyRecordShapedCraftingRecipeClassBody(className, serializerExpr, recipeTypeExpr) +
        source.substring(closeBrace + 1)

    listOf(
        "com.google.common.collect.Maps",
        "com.google.common.collect.Sets",
        "com.google.gson.JsonArray",
        "com.google.gson.JsonElement",
        "com.google.gson.JsonObject",
        "com.google.gson.JsonSyntaxException",
        "net.minecraft.network.FriendlyByteBuf",
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.util.GsonHelper",
        "org.jetbrains.annotations.Nullable",
        "java.util.Map",
        "java.util.Set",
    ).forEach { result = removeImport(result, it) }
    result = addImportIfMissing(result, "com.mojang.serialization.Codec")
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    result = addImportIfMissing(result, "com.mojang.serialization.codecs.RecordCodecBuilder")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.CraftingInput")
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.ShapedRecipePattern")
    return result
}

internal fun StructuralRefactorPass.legacyRecordShapedCraftingRecipeClassBody(
    className: String,
    serializerExpr: String,
    recipeTypeExpr: String
): String = """
public class $className extends ShapedRecipe {
    private final int cost;
    private final Ingredient input;
    private final int count;
    private final NonNullList<Ingredient> resultItems;

    public $className(int cost, int width, int height, Ingredient input, int count, NonNullList<Ingredient> resultItems) {
        this(cost, input, count, new ShapedRecipePattern(width, height, resultItems, java.util.Optional.empty()));
    }

    public $className(int cost, Ingredient input, int count, ShapedRecipePattern pattern) {
        super("uncrafting", CraftingBookCategory.MISC, pattern, new ItemStack(Items.AIR, count));
        this.cost = cost;
        this.input = input;
        this.count = count;
        this.resultItems = pattern.ingredients();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider access) {
        return ItemStack.EMPTY;
    }

    public boolean isItemStackAnIngredient(ItemStack stack) {
        return java.util.Arrays.stream(this.input.getItems()).anyMatch(i -> stack.is(i.getItem()) && stack.getCount() >= this.count);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return $serializerExpr;
    }

    @Override
    public RecipeType<?> getType() {
        return $recipeTypeExpr;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return this.resultItems;
    }

    public int cost() {
        return this.cost;
    }

    public int getCost() {
        return this.cost;
    }

    public Ingredient input() {
        return this.input;
    }

    public int count() {
        return this.count;
    }

    public int getCount() {
        return this.count;
    }

    public int width() {
        return this.pattern.width();
    }

    public int height() {
        return this.pattern.height();
    }

    public NonNullList<Ingredient> resultItems() {
        return this.resultItems;
    }

    public static class Serializer implements RecipeSerializer<$className> {
        public static final MapCodec<$className> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.INT.optionalFieldOf("cost", -1).forGetter($className::cost),
                Ingredient.CODEC.fieldOf("input").forGetter($className::input),
                Codec.INT.optionalFieldOf("input_count", 1).forGetter($className::count),
                ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.pattern)
        ).apply(instance, $className::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.of(
                Serializer::toNetwork,
                Serializer::fromNetwork
        );

        @Override
        public MapCodec<$className> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, $className> streamCodec() {
            return STREAM_CODEC;
        }

        private static $className fromNetwork(RegistryFriendlyByteBuf buffer) {
            int cost = buffer.readVarInt();
            Ingredient input = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
            int count = buffer.readVarInt();
            ShapedRecipePattern pattern = ShapedRecipePattern.STREAM_CODEC.decode(buffer);
            return new $className(cost, input, count, pattern);
        }

        private static void toNetwork(RegistryFriendlyByteBuf buffer, $className recipe) {
            buffer.writeVarInt(recipe.cost);
            Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.input);
            buffer.writeVarInt(recipe.count);
            ShapedRecipePattern.STREAM_CODEC.encode(buffer, recipe.pattern);
        }
    }
}
""".trimIndent()

internal data class RecipeComponentSpec(
    val type: String,
    val name: String,
    val localName: String,
    val jsonField: String,
    val registry: String? = null,
    val blockState: Boolean = false,
    val booleanField: Boolean = false
)

internal fun StructuralRefactorPass.migrateLegacyGsonRegistryRecipeSerializerSource(source: String): String {
    if (!source.contains("implements Recipe<Container>") ||
        !source.contains("implements RecipeSerializer<") ||
        !source.contains("fromJson(") ||
        !source.contains("fromNetwork(") ||
        !source.contains("toNetwork(")) {
        return source
    }

    val recordMatch = Regex(
        """public\s+record\s+([A-Za-z_$][\w$]*)\s*\(\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*,\s*([^)]*?)\s*\)\s+implements\s+Recipe<Container>"""
    ).find(source) ?: return source
    val className = recordMatch.groupValues[1]
    val idComponent = recordMatch.groupValues[2]
    val componentText = recordMatch.groupValues[3].trim()
    if (!source.contains("implements RecipeSerializer<$className>")) return source

    val components = splitTopLevelJavaArgs(componentText).mapNotNull { component ->
        Regex("""(.+?)\s+([A-Za-z_$][\w$]*)$""")
            .find(component.trim())
            ?.let { it.groupValues[2] to it.groupValues[1].trim() }
    }
    if (components.isEmpty()) return source

    val fromJsonMethod = javaMethodText(source, "fromJson") ?: return source
    val fromNetworkMethod = javaMethodText(source, "fromNetwork") ?: return source
    val toNetworkMethod = javaMethodText(source, "toNetwork") ?: return source
    val constructorArgs = constructorArgsForClass(fromJsonMethod, className) ?: return source
    if (constructorArgs.size != components.size + 1) return source

    val jsonRegistryFields = Regex(
        """(?m)\b(Block|EntityType<\?>)\s+([A-Za-z_$][\w$]*)\s*=\s*BuiltInRegistries\.([A-Z_]+)\.get\(\s*ResourceLocation\.tryParse\(\s*GsonHelper\.getAsString\(\s*object\s*,\s*"([^"]+)"\s*\)\s*\)\s*\)\s*;"""
    ).findAll(fromJsonMethod).associateBy { it.groupValues[2] }
    val jsonBooleanFields = Regex(
        """(?m)\bboolean\s+([A-Za-z_$][\w$]*)\s*=\s*GsonHelper\.getAsBoolean\(\s*object\s*,\s*"([^"]+)"\s*\)\s*;"""
    ).findAll(fromJsonMethod).associateBy { it.groupValues[1] }

    val specs = mutableListOf<RecipeComponentSpec>()
    for ((index, component) in components.withIndex()) {
        val constructorArg = constructorArgs[index + 1].trim()
        val componentName = component.first
        val componentType = component.second
        val blockArg = Regex("""^([A-Za-z_$][\w$]*)\.defaultBlockState\(\)$""").find(constructorArg)
        if (blockArg != null && componentType == "BlockState") {
            val local = blockArg.groupValues[1]
            val registryMatch = jsonRegistryFields[local] ?: return source
            if (registryMatch.groupValues[3] != "BLOCK") return source
            specs.add(
                RecipeComponentSpec(
                    type = componentType,
                    name = componentName,
                    localName = local,
                    jsonField = registryMatch.groupValues[4],
                    registry = "BLOCK",
                    blockState = true
                )
            )
            continue
        }

        val registryMatch = jsonRegistryFields[constructorArg]
        if (registryMatch != null && componentType.startsWith("EntityType")) {
            specs.add(
                RecipeComponentSpec(
                    type = componentType,
                    name = componentName,
                    localName = constructorArg,
                    jsonField = registryMatch.groupValues[4],
                    registry = registryMatch.groupValues[3]
                )
            )
            continue
        }

        val booleanMatch = jsonBooleanFields[constructorArg]
        if (booleanMatch != null && componentType == "boolean") {
            specs.add(
                RecipeComponentSpec(
                    type = componentType,
                    name = componentName,
                    localName = constructorArg,
                    jsonField = booleanMatch.groupValues[2],
                    booleanField = true
                )
            )
            continue
        }
        return source
    }

    if (!serializerNetworkMatchesSpecs(fromNetworkMethod, toNetworkMethod, specs)) return source

    var result = source
    result = result.replaceRange(
        recordMatch.range,
        "public record $className($componentText) implements Recipe<RecipeInput>"
    )
    result = removeLegacyRecipeIdMethod(result, idComponent)
    result = removeLeadingRecipeIdConstructorArgument(result, className, components.size + 1)
    result = replaceNestedClass(result, "Serializer", legacyRegistryRecipeSerializerBody(className, specs))

    result = result.replace("matches(Container ", "matches(RecipeInput ")
    result = result.replace("assemble(Container ", "assemble(RecipeInput ")
    result = removeImport(result, "com.google.gson.JsonObject")
    result = removeImport(result, "com.google.gson.JsonParseException")
    result = removeImport(result, "net.minecraft.network.FriendlyByteBuf")
    result = removeImport(result, "net.minecraft.util.GsonHelper")
    result = removeImport(result, "org.jetbrains.annotations.Nullable")
    if (!Regex("""\bContainer\b""").containsMatchIn(removeImport(result, "net.minecraft.world.Container"))) {
        result = removeImport(result, "net.minecraft.world.Container")
    }
    if (!Regex("""\bResourceLocation\b""").containsMatchIn(removeImport(result, "net.minecraft.resources.ResourceLocation"))) {
        result = removeImport(result, "net.minecraft.resources.ResourceLocation")
    }
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    if (specs.any { it.booleanField }) {
        result = addImportIfMissing(result, "com.mojang.serialization.Codec")
    }
    result = addImportIfMissing(result, "com.mojang.serialization.codecs.RecordCodecBuilder")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeInput")
    result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    if (result.contains("BuiltInRegistries.")) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.BuiltInRegistries")
    }
    return result
}

internal fun StructuralRefactorPass.constructorArgsForClass(source: String, className: String): List<String>? {
    val pattern = Regex("""new\s+${Regex.escape(className)}\s*\(""")
    val match = pattern.find(source) ?: return null
    val openParen = match.range.last
    val closeParen = findMatchingParen(source, openParen)
    if (closeParen < 0) return null
    return splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
}

internal fun StructuralRefactorPass.serializerNetworkMatchesSpecs(fromNetwork: String, toNetwork: String, specs: List<RecipeComponentSpec>): Boolean {
    for (spec in specs) {
        if (spec.blockState) {
            if (!Regex("""\bBlock\s+${Regex.escape(spec.localName)}\s*=\s*buffer\.readRegistryIdUnsafe\(BuiltInRegistries\.BLOCK\)""").containsMatchIn(fromNetwork)) {
                return false
            }
            if (!Regex("""buffer\.writeRegistryIdUnsafe\(BuiltInRegistries\.BLOCK,\s*recipe\.${Regex.escape(spec.name)}\(\)\.getBlock\(\)\s*\)""").containsMatchIn(toNetwork)) {
                return false
            }
        } else if (spec.registry == "ENTITY_TYPE") {
            if (!Regex("""\bEntityType<\?>\s+${Regex.escape(spec.localName)}\s*=\s*buffer\.readRegistryIdUnsafe\(BuiltInRegistries\.ENTITY_TYPE\)""").containsMatchIn(fromNetwork)) {
                return false
            }
            if (!Regex("""buffer\.writeRegistryIdUnsafe\(BuiltInRegistries\.ENTITY_TYPE,\s*recipe\.${Regex.escape(spec.name)}\(\)\s*\)""").containsMatchIn(toNetwork)) {
                return false
            }
        } else if (spec.booleanField) {
            if (!Regex("""\bboolean\s+${Regex.escape(spec.localName)}\s*=\s*buffer\.readBoolean\(\)""").containsMatchIn(fromNetwork)) {
                return false
            }
            if (!Regex("""buffer\.writeBoolean\(\s*recipe\.${Regex.escape(spec.name)}\(\)\s*\)""").containsMatchIn(toNetwork)) {
                return false
            }
        }
    }
    return true
}

internal fun StructuralRefactorPass.removeLegacyRecipeIdMethod(source: String, idComponent: String): String =
    Regex(
        """(?s)\s*@Override\s*\r?\n\s*public\s+ResourceLocation\s+getId\(\)\s*\{\s*return\s+this\.${Regex.escape(idComponent)}\s*;\s*\}\s*"""
    ).replace(source, "\n\n")

internal fun StructuralRefactorPass.removeLeadingRecipeIdConstructorArgument(source: String, className: String, oldArgCount: Int): String {
    val pattern = Regex("""new\s+${Regex.escape(className)}\s*\(""")
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    while (cursor < source.length) {
        val match = pattern.find(source, cursor) ?: break
        val openParen = match.range.last
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == oldArgCount) {
            migrated.append(source, cursor, match.range.first)
            migrated.append("new $className(")
            migrated.append(args.drop(1).joinToString(", ") { it.trim() })
            migrated.append(")")
            cursor = closeParen + 1
            changed = true
        } else {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
        }
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.legacyRegistryRecipeSerializerBody(className: String, specs: List<RecipeComponentSpec>): String {
    val codecFields = specs.joinToString(",\n") { spec ->
        when {
            spec.blockState ->
                """                        BuiltInRegistries.BLOCK.byNameCodec().fieldOf("${spec.jsonField}").forGetter(recipe -> recipe.${spec.name}().getBlock())"""
            spec.registry == "ENTITY_TYPE" ->
                """                        BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("${spec.jsonField}").forGetter($className::${spec.name})"""
            spec.booleanField ->
                """                        Codec.BOOL.fieldOf("${spec.jsonField}").forGetter($className::${spec.name})"""
            else -> error("Unsupported recipe component spec: $spec")
        }
    }
    val lambdaArgs = specs.joinToString(", ") { it.localName }
    val constructorArgs = specs.joinToString(", ") { spec ->
        if (spec.blockState) "${spec.localName}.defaultBlockState()" else spec.localName
    }
    val decodeLines = specs.joinToString("\n") { spec ->
        when {
            spec.blockState ->
                """                    Block ${spec.localName} = net.minecraft.network.codec.ByteBufCodecs.registry(Registries.BLOCK).decode(buffer);"""
            spec.registry == "ENTITY_TYPE" ->
                """                    EntityType<?> ${spec.localName} = net.minecraft.network.codec.ByteBufCodecs.registry(Registries.ENTITY_TYPE).decode(buffer);"""
            spec.booleanField ->
                """                    boolean ${spec.localName} = buffer.readBoolean();"""
            else -> error("Unsupported recipe component spec: $spec")
        }
    }
    val encodeLines = specs.joinToString("\n") { spec ->
        when {
            spec.blockState ->
                """                    net.minecraft.network.codec.ByteBufCodecs.registry(Registries.BLOCK).encode(buffer, recipe.${spec.name}().getBlock());"""
            spec.registry == "ENTITY_TYPE" ->
                """                    net.minecraft.network.codec.ByteBufCodecs.registry(Registries.ENTITY_TYPE).encode(buffer, recipe.${spec.name}());"""
            spec.booleanField ->
                """                    buffer.writeBoolean(recipe.${spec.name}());"""
            else -> error("Unsupported recipe component spec: $spec")
        }
    }
    return """
            public static class Serializer implements RecipeSerializer<$className> {
                private static final MapCodec<$className> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
$codecFields
                ).apply(instance, ($lambdaArgs) -> new $className($constructorArgs)));

                private static final StreamCodec<RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.of(
                        Serializer::toNetwork,
                        Serializer::fromNetwork
                );

                @Override
                public MapCodec<$className> codec() {
                    return CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, $className> streamCodec() {
                    return STREAM_CODEC;
                }

                private static $className fromNetwork(RegistryFriendlyByteBuf buffer) {
$decodeLines
                    return new $className($constructorArgs);
                }

                private static void toNetwork(RegistryFriendlyByteBuf buffer, $className recipe) {
$encodeLines
                }
            }
        """.trimIndent()
}

internal fun StructuralRefactorPass.migrateLegacyRecipeBuilderResultIds(source: String): String {
    if (!source.contains("recipe.setId(id)") && !source.contains("recipe.getId()")) return source
    var result = source
    result = result.replace("consumer.accept(result.getId(), result.recipe, null);", "consumer.accept(id, result.recipe, null);")
    result = Regex("""(?m)^([ \t]*)(private\s+final\s+[A-Za-z_$][\w$]*Recipe\s+recipe\s*=\s*new\s+[A-Za-z_$][\w$]*Recipe\(\);\s*)$""")
        .replace(result) { match ->
            val indent = match.groupValues[1]
            if (result.substring(0, match.range.first).takeLast(200).contains("private final ResourceLocation id;")) {
                match.value
            } else {
                "${indent}private final ResourceLocation id;\n${match.value}"
            }
        }
    result = result.replace("recipe.setId(id);", "this.id = id;")
    result = result.replace("return recipe.getId();", "return this.id;")
    return result
}

internal fun StructuralRefactorPass.removeLegacyRecipeInstanceIdMethods(source: String): String {
    if (!source.contains("extends AbstractRecipe")) return source
    if (!source.contains("getId()") || !source.contains("return this.id;")) return source
    var result = Regex(
        """(?s)\s*@Override\s*\r?\n\s*public\s+ResourceLocation\s+getId\(\)\s*\{\s*return\s+this\.id\s*;\s*\}\s*"""
    ).replace(source, "\n")
    if (!Regex("""\bResourceLocation\b""").containsMatchIn(removeImport(result, "net.minecraft.resources.ResourceLocation"))) {
        result = removeImport(result, "net.minecraft.resources.ResourceLocation")
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecipeExperienceTrackingSource(source: String): String {
    if (!source.contains("trackRecipeExperience")) return source
    var result = source
    if (result.contains("lastRecipeID")) {
        result = Regex("""trackRecipeExperience\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match ->
                if (match.groupValues[1] == "lastRecipeID") {
                    match.value
                } else {
                    "trackRecipeExperience(lastRecipeID);"
                }
            }
    }
    result = Regex(
        """(?s)public\s+void\s+trackRecipeExperience\(\s*@Nullable\s+Recipe<\?>\s+recipe\s*\)\s*\{\s*if\s*\(\s*recipe\s*!=\s*null\s*\)\s*\{.*?experienceTracker\.addTo\(\s*recipeID\s*,\s*1\s*\)\s*;\s*\}\s*\}"""
    ).replace(result) {
        """public void trackRecipeExperience(@Nullable ResourceLocation recipeId) {
        if (recipeId != null) {
            experienceTracker.addTo(recipeId, 1);
        }
    }"""
    }
    result = Regex(
        """(?s)public\s+void\s+trackRecipeExperience\(\s*@Nullable\s+Recipe<\?>\s+recipe\s*\)\s*\{\s*if\s*\(\s*recipe\s*!=\s*null\s*\)\s*\{\s*ResourceLocation\s+recipeID\s*=\s*recipe\.getId\(\)\s*;\s*(.*?)\s*\}\s*\}"""
    ).replace(result) { match ->
        val body = match.groupValues[1].replace("recipeID", "recipeId").trimEnd()
        """public void trackRecipeExperience(@Nullable ResourceLocation recipeId) {
        if (recipeId != null) {
            $body
        }
    }"""
    }
    if (!Regex("""\bRecipe\s*<""").containsMatchIn(result)) {
        result = removeImport(result, "net.minecraft.world.item.crafting.Recipe")
    }
    return result
}

internal fun StructuralRefactorPass.migrateMerchantOfferItemCosts(source: String): String {
    if (!source.contains("new MerchantOffer(") || !source.contains("ItemStack")) return source

    val stackCosts = mutableMapOf<String, String>()

    fun itemCostFromItemStackArgs(args: List<String>): String? =
        when (args.size) {
            1 -> "new net.minecraft.world.item.trading.ItemCost(${args[0].trim()})"
            2 -> "new net.minecraft.world.item.trading.ItemCost(${args[0].trim()}, ${args[1].trim()})"
            else -> null
        }

    fun itemCostFromExpression(expression: String): String? {
        val trimmed = trimJavaArgumentForInlineCall(expression)
        stackCosts[trimmed]?.let { return it }
        if (!trimmed.startsWith("new ItemStack(") && !trimmed.startsWith("new net.minecraft.world.item.ItemStack(")) {
            return null
        }
        val openParen = trimmed.indexOf('(')
        val closeParen = findMatchingParen(trimmed, openParen)
        if (openParen < 0 || closeParen != trimmed.length - 1) return null
        return itemCostFromItemStackArgs(splitTopLevelJavaArgs(trimmed.substring(openParen + 1, closeParen)))
    }

    val assignmentPattern = Regex("""ItemStack\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+ItemStack\s*\(""")
    assignmentPattern.findAll(source).forEach { match ->
        val openParen = match.range.last
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) return@forEach
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        itemCostFromItemStackArgs(args)?.let { stackCosts[match.groupValues[1]] = it }
    }

    val migrated = StringBuilder()
    var cursor = 0
    val constructorPattern = Regex("""new\s+MerchantOffer\s*\(""")
    while (cursor < source.length) {
        val match = constructorPattern.find(source, cursor) ?: break
        val openParen = match.range.last
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == 5) {
            val cost = itemCostFromExpression(args[0])
            if (cost != null) {
                migrated.append(source, cursor, match.range.first)
                migrated.append("new MerchantOffer(")
                migrated.append(cost)
                migrated.append(", ")
                migrated.append(args.drop(1).joinToString(", ") { trimJavaArgumentForInlineCall(it) })
                migrated.append(")")
                cursor = closeParen + 1
                continue
            }
        }
        if (args.size == 6 && args[1].trim() == "ItemStack.EMPTY") {
            val cost = itemCostFromExpression(args[0])
            if (cost != null) {
                migrated.append(source, cursor, match.range.first)
                migrated.append("new MerchantOffer(")
                migrated.append(cost)
                migrated.append(", java.util.Optional.empty(), ")
                migrated.append(args.drop(2).joinToString(", ") { trimJavaArgumentForInlineCall(it) })
                migrated.append(")")
                cursor = closeParen + 1
                continue
            }
        }
        migrated.append(source, cursor, closeParen + 1)
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.trimJavaArgumentForInlineCall(argument: String): String {
    val trimmed = argument.trim()
    val result = StringBuilder(trimmed.length)
    var index = 0
    var inLineComment = false
    var inString = false
    var inTextBlock = false
    var inChar = false
    var escaped = false
    while (index < trimmed.length) {
        val ch = trimmed[index]
        val next = trimmed.getOrNull(index + 1)
        val nextTwo = trimmed.getOrNull(index + 2)
        when {
            inLineComment -> {
                if (ch == '\r' || ch == '\n') {
                    inLineComment = false
                    result.append(ch)
                }
            }
            inTextBlock -> {
                result.append(ch)
                if (ch == '"' && next == '"' && nextTwo == '"') {
                    result.append("  ")
                    index += 2
                    inTextBlock = false
                }
            }
            inString -> {
                result.append(ch)
                if (ch == '"' && !escaped) inString = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
            }
            inChar -> {
                result.append(ch)
                if (ch == '\'' && !escaped) inChar = false
                escaped = ch == '\\' && !escaped
                if (ch != '\\') escaped = false
            }
            ch == '"' && next == '"' && nextTwo == '"' -> {
                result.append("\"\"\"")
                index += 2
                inTextBlock = true
                escaped = false
            }
            ch == '"' -> {
                result.append(ch)
                inString = true
                escaped = false
            }
            ch == '\'' -> {
                result.append(ch)
                inChar = true
                escaped = false
            }
            ch == '/' && next == '/' -> {
                inLineComment = true
                index++
            }
            else -> result.append(ch)
        }
        index++
    }
    return result.toString().trim()
}

internal fun StructuralRefactorPass.migrateMerchantOffersNbtCodecs(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("MerchantOffers") && !source.contains(".createTag()")) return source
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""\b([A-Za-z_$][\w$]*)\.createTag\(\)""")
    ) { match ->
        val receiver = match.groupValues[1]
        if (!isMerchantOffersValue(result, match.range.first, receiver)) {
            return@replaceExecutableRegex match.value
        }
        val registries = registryAccessExpressionAt(result, match.range.first, javaInheritanceIndex)
            ?: return@replaceExecutableRegex match.value
        "(net.minecraft.nbt.CompoundTag) MerchantOffers.CODEC.encodeStart($registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), $receiver).getOrThrow()"
    }
    result = rewriteExecutableJavaCallWithOffset(result, "createTag") { receiver, args, offset ->
        val normalizedReceiver = receiver.trim()
        if (args.isNotEmpty() || (normalizedReceiver != "getOffers()" && !normalizedReceiver.endsWith(".getOffers()"))) {
            return@rewriteExecutableJavaCallWithOffset null
        }
        val registries = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
            ?: return@rewriteExecutableJavaCallWithOffset null
        "(net.minecraft.nbt.CompoundTag) MerchantOffers.CODEC.encodeStart($registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), $normalizedReceiver).getOrThrow()"
    }
    result = rewriteExecutableJavaNewWithOffset(result, "MerchantOffers") { args, offset ->
        if (args.size != 1) return@rewriteExecutableJavaNewWithOffset null
        val registries = registryAccessExpressionAt(result, offset, javaInheritanceIndex)
            ?: return@rewriteExecutableJavaNewWithOffset null
        "MerchantOffers.CODEC.parse($registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), ${args[0].trim()}).result().orElseGet(MerchantOffers::new)"
    }
    return result
}

internal fun StructuralRefactorPass.isMerchantOffersValue(source: String, offset: Int, name: String): Boolean {
    val scope = enclosingMethodText(maskJavaCommentsAndLiterals(source), offset) ?: maskJavaCommentsAndLiterals(source)
    val localType = javaLocalVariableTypes(scope)[name]
    if (localType == "MerchantOffers" || localType == "net.minecraft.world.item.trading.MerchantOffers") return true
    val sourceType = javaLocalVariableTypes(maskJavaCommentsAndLiterals(source))[name]
    return sourceType == "MerchantOffers" || sourceType == "net.minecraft.world.item.trading.MerchantOffers"
}

internal fun StructuralRefactorPass.migrateCopiedCustomDataWrites(source: String): String {
    if (!source.contains("DataComponents.CUSTOM_DATA") || !source.contains(".copyTag()")) return source
    return Regex(
        """(?m)^([ \t]*)net\.minecraft\.nbt\.CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.getOrDefault\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\);\s*\r?\n\1\2\.put\(([^;]+);\s*(?!\r?\n\1\3\.set\(net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA)"""
    ).replace(source) { match ->
        val indent = match.groupValues[1]
        val tag = match.groupValues[2]
        val stack = match.groupValues[3]
        val putArgs = match.groupValues[4].trim().removeSuffix(")")
        "${indent}net.minecraft.nbt.CompoundTag $tag = $stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();\n" +
            "$indent$tag.put($putArgs);\n" +
            "$indent$stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of($tag));"
    }
}

internal fun StructuralRefactorPass.migrateStructurePlaceSettingsLiquidSettingsSource(source: String): String {
    if (!source.contains("setKeepLiquids(") && !source.contains("shouldKeepLiquids()")) return source
    var result = rewriteStructurePlaceSetKeepLiquidsCalls(source)
    result = replaceExecutableRegex(
        result,
        Regex("""\.shouldKeepLiquids\(\)""")
    ) { ".shouldApplyWaterlogging()" }
    if (result.contains("LiquidSettings.")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings")
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyRecipeJsonCodecHelpers(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("fromJson(") &&
        !executableCode.contains("getItemStack(") &&
        !executableCode.contains("hasNoElements(") &&
        !executableCode.contains(".CODEC.byName(")) {
        return source
    }

    var result = source
    result = rewriteExecutableJavaCall(result, "hasNoElements") { receiver, args ->
        if (!receiver.endsWith("CommonHooks") || args.size != 1) return@rewriteExecutableJavaCall null
        "${args[0].trim()}.hasNoItems()"
    }
    result = rewriteExecutableJavaCall(result, "fromJson") { receiver, args ->
        val simpleReceiver = receiver.substringAfterLast('.')
        if (simpleReceiver != "Ingredient" || args.isEmpty() || args.size > 2) return@rewriteExecutableJavaCall null
        val codec = if (args.getOrNull(1)?.trim() == "true") {
            "Ingredient.CODEC"
        } else {
            "Ingredient.CODEC_NONEMPTY"
        }
        "$codec.parse(JsonOps.INSTANCE, ${args[0].trim()}).getOrThrow()"
    }
    result = rewriteExecutableJavaCall(result, "getItemStack") { receiver, args ->
        if (!receiver.endsWith("CraftingHelper") || args.isEmpty()) return@rewriteExecutableJavaCall null
        "ItemStack.CODEC.parse(JsonOps.INSTANCE, ${args[0].trim()}).getOrThrow()"
    }
    result = rewriteExecutableJavaCall(result, "byName") { receiver, args ->
        if (!receiver.endsWith(".CODEC") || args.size != 2) return@rewriteExecutableJavaCall null
        val name = args[0].trim()
        val fallback = args[1].trim()
        "$receiver.parse(JsonOps.INSTANCE, $name == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive($name)).result().orElse($fallback)"
    }

    if (result.contains("JsonOps.INSTANCE")) {
        result = addImportIfMissing(result, "com.mojang.serialization.JsonOps")
    }
    result = removeImport(result, "net.neoforged.neoforge.common.crafting.CraftingHelper")
    result = removeImport(result, "net.minecraftforge.common.crafting.CraftingHelper")
    return result
}

internal fun StructuralRefactorPass.rewriteStructurePlaceSetKeepLiquidsCalls(source: String): String {
    var result = source
    var cursor = 0
    val token = ".setKeepLiquids("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) return result
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size != 1) {
            cursor = closeParen + 1
            continue
        }
        val keepLiquids = args.single().trim()
        val liquidSettings = when (keepLiquids) {
            "true" -> "LiquidSettings.APPLY_WATERLOGGING"
            "false" -> "LiquidSettings.IGNORE_WATERLOGGING"
            else -> "${parenthesizedJavaExpression(keepLiquids)} ? LiquidSettings.APPLY_WATERLOGGING : LiquidSettings.IGNORE_WATERLOGGING"
        }
        val replacement = ".setLiquidSettings($liquidSettings)"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
}

internal fun StructuralRefactorPass.parenthesizedJavaExpression(expression: String): String =
    if (Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").matches(expression)) {
        expression
    } else {
        "($expression)"
    }

internal fun StructuralRefactorPass.migrateVanillaToolItemConstructors(source: String): String {
    val constructorPattern = Regex("""new\s+((?:net\.minecraft\.world\.item\.)?(?:AxeItem|PickaxeItem|HoeItem|ShovelItem))\s*\(""")
    val migrated = StringBuilder()
    var cursor = 0
    while (cursor < source.length) {
        val match = constructorPattern.find(source, cursor) ?: break
        val openParen = match.range.last
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) break

        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 4) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }

        val tier = args[0].trim()
        val attackDamage = args[1].trim()
        val attackSpeed = args[2].trim()
        val properties = args[3].trim()
        migrated.append(source, cursor, match.range.first)
        migrated.append("new ")
        migrated.append(match.groupValues[1])
        migrated.append("(")
        migrated.append(tier)
        migrated.append(", ")
        migrated.append(properties)
        migrated.append(".attributes(net.minecraft.world.item.DiggerItem.createAttributes(")
        migrated.append(tier)
        migrated.append(", ")
        migrated.append(attackDamage)
        migrated.append(", ")
        migrated.append(attackSpeed)
        migrated.append(")))")
        cursor = closeParen + 1
    }
    if (cursor == 0) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.migrateMmlibBlockLootConstructor(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("extends AbstartctBlockLoot")) return source
    val className = Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+AbstartctBlockLoot\b""")
        .find(executableCode)
        ?.groupValues
        ?.get(1)
        ?: return source
    if (Regex("""\b${Regex.escape(className)}\s*\(\s*(?:net\.minecraft\.core\.)?HolderLookup\.Provider\s+provider\s*\)""")
            .containsMatchIn(executableCode)) {
        return source
    }

    var result = source
    result = replaceExecutableRegex(result, Regex(
        """(?s)\s*public\s+${Regex.escape(className)}\s*\(\s*Set\s*<\s*Item\s*>\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*\1\s*\);\s*\}\s*"""
    )) { "\n" }
    result = replaceExecutableRegex(result, Regex(
        """(?s)\s*public\s+${Regex.escape(className)}\s*\(\s*\)\s*\{\s*super\(\s*Set\.of\(\)\s*\);\s*\}\s*"""
    )) { "\n" }

    val constructor = """

    public $className(net.minecraft.core.HolderLookup.Provider provider) {
        super(provider);
    }
""".trimEnd()
    val classHeaderPattern = Regex("""public\s+class\s+${Regex.escape(className)}\s+extends\s+AbstartctBlockLoot\s*\{\s*""")
    val executableResult = maskJavaCommentsAndLiterals(result)
    val classHeader = classHeaderPattern.find(executableResult) ?: return result
    val classHeaderText = result.substring(classHeader.range.first, classHeader.range.last + 1)
    result = result.substring(0, classHeader.range.first) +
        classHeaderText.trimEnd() +
        constructor +
        "\n" +
        result.substring(classHeader.range.last + 1)
    val remainingExecutableCode = maskJavaCommentsAndLiterals(result)
    if (!remainingExecutableCode.contains("Set<")) {
        result = removeExecutableImport(result, "java.util.Set")
    }
    if (!remainingExecutableCode.contains(" Item") && !remainingExecutableCode.contains("<Item>")) {
        result = removeExecutableImport(result, "net.minecraft.world.item.Item")
    }
    return result
}

internal fun StructuralRefactorPass.findMatchingParen(source: String, openParen: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in openParen until source.length) {
        val ch = source[i]
        if (inString) {
            escaped = ch == '\\' && !escaped
            if (ch == '"' && !escaped) inString = false
            if (ch != '\\') escaped = false
            continue
        }
        when (ch) {
            '"' -> inString = true
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

internal fun StructuralRefactorPass.findMatchingAngle(source: String, openAngle: Int): Int {
    var depth = 0
    for (i in openAngle until source.length) {
        when (source[i]) {
            '<' -> depth++
            '>' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

internal fun StructuralRefactorPass.rewriteJavaCall(
    source: String,
    methodName: String,
    transform: (receiver: String, args: List<String>) -> String?
): String =
    rewriteExecutableJavaCall(source, methodName, transform)

internal fun StructuralRefactorPass.rewriteExecutableJavaCall(
    source: String,
    methodName: String,
    transform: (receiver: String, args: List<String>) -> String?
): String {
    var result = source
    var cursor = 0
    val token = ".${methodName}("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + methodName.length + 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) {
            cursor = closeParen + 1
            continue
        }
        val receiver = result.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val replacement = transform(receiver, args)
        if (replacement == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, receiverStart) + replacement + result.substring(closeParen + 1)
        cursor = receiverStart + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.rewriteExecutableJavaCallWithOffset(
    source: String,
    methodName: String,
    transform: (receiver: String, args: List<String>, callOffset: Int) -> String?
): String {
    // Calls are rewritten from the last to the first: the text before a call never changes while it
    // is looked at, so callers that inspect [source] around `callOffset` (scope, declarations,
    // enclosing method) see the very text the offset belongs to.
    var result = source
    val token = ".${methodName}("
    val tokens = Regex(Regex.escape(token)).findAll(maskJavaCommentsAndLiterals(source)).map { it.range.first }.toList()
    for (tokenIndex in tokens.asReversed()) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        if (!executableCode.startsWith(token, tokenIndex)) continue
        val openParen = tokenIndex + methodName.length + 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) continue
        val receiverStart = findExpressionReceiverStart(executableCode, tokenIndex)
        if (receiverStart < 0 || receiverStart >= tokenIndex) continue
        val receiver = result.substring(receiverStart, tokenIndex).trim()
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val replacement = transform(receiver, args, tokenIndex) ?: continue
        result = result.substring(0, receiverStart) + replacement + result.substring(closeParen + 1)
    }
    return result
}

internal fun StructuralRefactorPass.rewriteJavaCallWithOffset(
    source: String,
    methodName: String,
    transform: (receiver: String, args: List<String>, callOffset: Int) -> String?
): String =
    rewriteExecutableJavaCallWithOffset(source, methodName, transform)

internal fun StructuralRefactorPass.rewriteJavaInvocationArguments(
    source: String,
    methodName: String,
    transform: (args: List<String>) -> List<String>?
): String =
    rewriteExecutableJavaInvocationArguments(source, methodName, transform)

internal fun StructuralRefactorPass.rewriteExecutableJavaInvocationArguments(
    source: String,
    methodName: String,
    transform: (args: List<String>) -> List<String>?
): String {
    var result = source
    var cursor = 0
    val token = "$methodName("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '$')) {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val originalArgsSource = result.substring(openParen + 1, closeParen)
        val args = splitTopLevelJavaArgs(originalArgsSource)
        val migratedArgs = transform(args)
        if (migratedArgs == null || migratedArgs == args) {
            cursor = closeParen + 1
            continue
        }
        val replacementArgs = migratedArgs.joinToString(", ") { it.trim() }
        result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
        cursor = openParen + 1 + replacementArgs.length
    }
    return result
}

internal fun StructuralRefactorPass.rewriteJavaInvocationArgumentsWithOffset(
    source: String,
    methodName: String,
    transform: (args: List<String>, offset: Int) -> List<String>?
): String =
    rewriteExecutableJavaInvocationArgumentsWithOffset(source, methodName, transform)

internal fun StructuralRefactorPass.rewriteExecutableJavaInvocationArgumentsWithOffset(
    source: String,
    methodName: String,
    transform: (args: List<String>, offset: Int) -> List<String>?
): String {
    // last to first, as rewriteExecutableJavaCallWithOffset: callers read scope around `offset`
    var result = source
    val token = "$methodName("
    val tokens = Regex(Regex.escape(token)).findAll(maskJavaCommentsAndLiterals(source)).map { it.range.first }.toList()
    for (tokenIndex in tokens.asReversed()) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        if (!executableCode.startsWith(token, tokenIndex)) continue
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '$')) continue
        val openParen = tokenIndex + methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) continue
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val migratedArgs = transform(args, tokenIndex)
        if (migratedArgs == null || migratedArgs == args) continue
        val replacementArgs = migratedArgs.joinToString(", ") { it.trim() }
        result = result.substring(0, openParen + 1) + replacementArgs + result.substring(closeParen)
    }
    return result
}

internal fun StructuralRefactorPass.rewriteExecutableJavaInvocationWithOffset(
    source: String,
    methodName: String,
    transform: (args: List<String>, offset: Int) -> String?
): String {
    var result = source
    var cursor = 0
    val token = "$methodName("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '$')) {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val replacement = transform(args, tokenIndex)
        if (replacement == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.rewriteJavaNew(
    source: String,
    className: String,
    transform: (args: List<String>) -> String?
): String =
    rewriteExecutableJavaNew(source, className, transform)

internal fun StructuralRefactorPass.rewriteExecutableJavaNew(
    source: String,
    className: String,
    transform: (args: List<String>) -> String?
): String {
    var result = source
    var cursor = 0
    val token = "new $className("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val replacement = transform(args)
        if (replacement == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.rewriteExecutableJavaNewWithOffset(
    source: String,
    className: String,
    transform: (args: List<String>, offset: Int) -> String?
): String {
    var result = source
    var cursor = 0
    val token = "new $className("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val replacement = transform(args, tokenIndex)
        if (replacement == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.replaceExecutableJavaRegex(
    source: String,
    pattern: Regex,
    transform: (MatchResult) -> String
): String = replaceExecutableRegex(source, pattern, transform)

internal fun StructuralRefactorPass.rewriteSuperConstructorCalls(
    source: String,
    transform: (args: List<String>) -> String?
): String =
    rewriteExecutableSuperConstructorCalls(source, transform)

internal fun StructuralRefactorPass.rewriteExecutableSuperConstructorCalls(
    source: String,
    transform: (args: List<String>) -> String?
): String {
    var result = source
    var cursor = 0
    val token = "super("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '.')) {
            cursor = tokenIndex + token.length
            continue
        }
        val openParen = tokenIndex + token.length - 1
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        val replacement = transform(args)
        if (replacement == null) {
            cursor = closeParen + 1
            continue
        }
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.splitTopLevelJavaArgs(args: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var parenDepth = 0
    var braceDepth = 0
    var bracketDepth = 0
    var angleDepth = 0
    var inString = false
    var escaped = false
    for (i in args.indices) {
        val ch = args[i]
        if (inString) {
            escaped = ch == '\\' && !escaped
            if (ch == '"' && !escaped) inString = false
            if (ch != '\\') escaped = false
            continue
        }
        when (ch) {
            '"' -> inString = true
            '(' -> parenDepth++
            ')' -> parenDepth--
            '{' -> braceDepth++
            '}' -> braceDepth--
            '[' -> bracketDepth++
            ']' -> bracketDepth--
            '<' -> angleDepth++
            '>' -> if (angleDepth > 0) angleDepth--
            ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                result.add(args.substring(start, i))
                start = i + 1
            }
        }
    }
    result.add(args.substring(start))
    return result.map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun StructuralRefactorPass.legacyMmlibBlockStateHelpers(): String = """

    private void log(Supplier<? extends RotatedPillarBlock> block) {
        logBlock(block.get());
    }

    private void stageBlock(Supplier<? extends Block> blockSupplier, IntegerProperty ageProperty, Property<?>... ignored) {
        Block block = blockSupplier.get();
        getVariantBuilder(block).forAllStates(state -> {
            int age = state.getValue(ageProperty);
            String modelName = name(block) + "_stage" + age;
            return ConfiguredModel.builder()
                    .modelFile(models().crop(modelName, texture(modelName)))
                    .build();
        });
    }

    private void facingSlabBlock(Supplier<? extends SlabBlock> slabSupplier, ResourceLocation side, ResourceLocation bottom, ResourceLocation top) {
        SlabBlock slab = slabSupplier.get();
        ModelFile bottomModel = models().slab(name(slab), side, bottom, top);
        ModelFile topModel = models().slabTop(name(slab) + "_top", side, bottom, top);
        ModelFile doubleModel = models().cubeBottomTop(name(slab) + "_double", side, bottom, top);
        getVariantBuilder(slab).forAllStates(state -> {
            SlabType type = state.getValue(SlabBlock.TYPE);
            ModelFile model = type == SlabType.DOUBLE ? doubleModel : type == SlabType.TOP ? topModel : bottomModel;
            int yRot = ((int) state.getValue(cn.mcmod_mmf.mmlib.block.FacingSlab.FACING).toYRot() + 180) % 360;
            return ConfiguredModel.builder()
                    .modelFile(model)
                    .rotationY(yRot)
                    .build();
        });
    }
""".trimEnd()

internal fun StructuralRefactorPass.migrateVerifiedCompat121ApiSource(source: String): String {
    var result = source
    var needsEpicFightEvent = false

    result = result
        .replace(
            "\"yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent\$Modify\"",
            "\"yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent\$ModifyEntity\""
        )
        .replace(
            "\"yesman.epicfight.client.ClientEngine\"",
            "\"yesman.epicfight.api.client.event.EpicFightClientEventHooks\""
        )

    if (result.contains("import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;")) {
        result = removeImport(result, "yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent")
        result = removeImport(result, "yesman.epicfight.client.ClientEngine")
        result = removeImport(result, "net.neoforged.bus.api.SubscribeEvent")
        result = addImportIfMissing(result, "yesman.epicfight.api.client.event.EpicFightClientEventHooks")
        result = addImportIfMissing(result, "yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent")
        needsEpicFightEvent = true

        result = result.replace(
            "modEventBus.addListener(EpicFightClientHelper::onModifyPatchedRenderers);\n        NeoForge.EVENT_BUS.register(EpicFightClientHelper.class);",
            "EpicFightClientEventHooks.Registry.MODIFY_PATCHED_ENTITY.registerEvent(EpicFightClientHelper::onModifyPatchedRenderers);\n        NeoForge.EVENT_BUS.addListener(EpicFightClientHelper::onClientTick);"
        )
        result = result.replace(
            "private static void onModifyPatchedRenderers(PatchedRenderersEvent.Modify event)",
            "private static void onModifyPatchedRenderers(RegisterPatchedRenderersEvent.ModifyEntity event)"
        )
        result = Regex("""\n\s*@SubscribeEvent\s*\r?\n(\s*public\s+static\s+void\s+onClientTick)""")
            .replace(result, "\n$1")
        result = result.replace(
            """        ClientEngine clientEngine = ClientEngine.getInstance();
        RenderEngine renderEngine = clientEngine != null ? clientEngine.renderEngine : null;""",
            """        RenderEngine renderEngine = RenderEngine.getInstance();"""
        )
    }

    result = migrateNitrogenTooltipPredicateLambdas(result)

    if (!needsEpicFightEvent && !result.contains("RegisterPatchedRenderersEvent")) {
        result = removeImport(result, "yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent")
    }
    return result
}

internal fun StructuralRefactorPass.migrateNitrogenTooltipPredicateLambdas(source: String): String {
    val token = "TooltipListeners.PREDICATES.put"
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains(token)) return source
    val migrated = StringBuilder()
    var cursor = 0
    var changed = false
    while (cursor < source.length) {
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + token.length
        if (openParen >= executableCode.length || executableCode[openParen] != '(') {
            migrated.append(source, cursor, openParen)
            cursor = openParen
            continue
        }
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size != 2) {
            migrated.append(source, cursor, closeParen + 1)
            cursor = closeParen + 1
            continue
        }
        val lambda = migrateNitrogenTooltipPredicateLambda(args[1])
        if (lambda == args[1]) {
            migrated.append(source, cursor, closeParen + 1)
        } else {
            migrated.append(source, cursor, openParen + 1)
            migrated.append(args[0].trim())
            migrated.append(", ")
            migrated.append(lambda)
            migrated.append(")")
            changed = true
        }
        cursor = closeParen + 1
    }
    if (!changed) return source
    migrated.append(source, cursor, source.length)
    return migrated.toString()
}

internal fun StructuralRefactorPass.migrateNitrogenTooltipPredicateLambda(lambdaArg: String): String {
    val lambda = lambdaArg.trim()
    val header = Regex("""^\(\s*([^)]*?)\s*\)\s*->""").find(lambda) ?: return lambdaArg
    val params = splitTopLevelJavaArgs(header.groupValues[1])
    if (params.size != 4) return lambdaArg
    val contextName = uniqueLocalName(lambda, "context")
    return "(${params[0]}, ${params[1]}, ${params[2]}, $contextName, ${params[3]}) ->" +
        lambda.substring(header.range.last + 1)
}

internal data class JavaParameterInfo(
    val type: String,
    val name: String
)

internal data class LegacyRecipeSerializerParam(
    val type: String,
    val name: String,
    val codecLine: String,
    val networkReadLine: String,
    val networkWriteLine: String,
    val constructorArg: String = name,
    val optionalCompoundTag: Boolean = false,
    val needsCodec: Boolean = false,
    val needsBuiltInRegistries: Boolean = false,
    val needsRegistries: Boolean = false,
    val needsJsonSyntaxException: Boolean = false
)

internal fun StructuralRefactorPass.migrateCustomRecipeSerializer121Source(source: String): String {
    if (!source.contains("implements RecipeSerializer<") || !source.contains("fromJson(")) return source
    var result = migrateLegacyFactoryCookingRecipeSerializerSource(source)
    result = migrateLegacyShapedRecipeSerializerSource(result)
    result = migrateLegacyStructuredRecipeSerializerSource(result)
    result = migrateLegacyRecipeJsonCodecHelpers(result)
    return result
}

internal fun StructuralRefactorPass.collectLegacyOptionalCompoundRecipeTagOwners(javaFiles: List<Path>): Set<String> =
    javaFiles.mapNotNull { javaFile ->
        val source = runCatching { javaFile.readText() }.getOrNull() ?: return@mapNotNull null
        legacyStructuredRecipeSerializerOptionalCompoundOwner(source)
    }.toSet()

internal fun StructuralRefactorPass.collectLegacyCookingRecipeClasses(javaFiles: List<Path>): Set<String> {
    val superTypes = javaFiles.mapNotNull { javaFile ->
        val source = runCatching { javaFile.readText() }.getOrNull() ?: return@mapNotNull null
        val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
        val superType = Regex("""\bclass\s+${Regex.escape(className)}(?:\s*<[^>{}]+>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.substringAfterLast('.')
            ?: return@mapNotNull null
        className to superType
    }.toMap()

    val cookingClasses = superTypes
        .filterValues { it == "AbstractCookingRecipe" }
        .keys
        .toMutableSet()
    var changed: Boolean
    do {
        changed = false
        superTypes.forEach { (className, superType) ->
            if (superType in cookingClasses && cookingClasses.add(className)) {
                changed = true
            }
        }
    } while (changed)
    return cookingClasses
}

internal data class InheritedRecipeStateAccessor(
    val owner: String,
    val fieldName: String,
    val fieldType: String,
    val accessorName: String
)

internal fun StructuralRefactorPass.collectInheritedRecipeStateAccessors(javaFiles: List<Path>): List<InheritedRecipeStateAccessor> {
    val accessors = mutableListOf<InheritedRecipeStateAccessor>()
    javaFiles.forEach { javaFile ->
        val source = runCatching { javaFile.readText() }.getOrNull() ?: return@forEach
        collectJavaClassDeclarations(source).forEach { declaration ->
            val body = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
            val privateFields = Regex(
                """(?m)^[ \t]*private\s+(?!static\b)(?:final\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?(?:\s*<[^;\r\n]+>)?(?:\[\])?)\s+([A-Za-z_$][\w$]*)\s*(?:=[^;\r\n]*)?;"""
            ).findAll(body).map { match ->
                match.groupValues[2] to match.groupValues[1].trim()
            }.toMap()
            if (privateFields.isEmpty()) return@forEach

            privateFields.forEach { (fieldName, fieldType) ->
                val methodPattern = Regex(
                    """(?s)\b(?:public|protected)\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?(?:\s*<[^{};()]+>)?(?:\[\])?)\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{\s*return\s+(?:this\.)?${Regex.escape(fieldName)}\s*;\s*\}"""
                )
                methodPattern.findAll(body)
                    .filter { simpleJavaTypeName(it.groupValues[1]) == simpleJavaTypeName(fieldType) }
                    .forEach { method ->
                        accessors += InheritedRecipeStateAccessor(
                            owner = declaration.name,
                            fieldName = fieldName,
                            fieldType = fieldType,
                            accessorName = method.groupValues[2]
                        )
                    }
            }
        }
    }
    return accessors.distinct()
}

internal fun StructuralRefactorPass.migrateInheritedRecipeStateAccessSource(
    source: String,
    inheritedAccessors: List<InheritedRecipeStateAccessor>,
    javaInheritanceIndex: JavaInheritanceIndex
): String {
    if (!source.contains(".") || (!source.contains("RecipeSerializer") && !source.contains("RecordCodecBuilder"))) {
        return source
    }
    var result = source
    collectJavaClassDeclarations(source).forEach { declaration ->
        val applicableAccessors = inheritedAccessors.filter { accessor ->
            javaInheritanceIndex.inherits(declaration.directSuper, setOf(accessor.owner)) &&
                !javaClassDeclaresField(source, declaration, accessor.fieldName)
        }
        applicableAccessors.forEach { accessor ->
            result = replaceRecipeStateFieldReads(
                result,
                declaration,
                accessor.fieldName,
                accessorName = accessor.accessorName
            )
        }

        forwardedAbstractCookingTimeParameters(source, declaration, javaInheritanceIndex)
            .forEach { fieldName ->
                result = replaceRecipeStateFieldReads(
                    result,
                    declaration,
                    fieldName,
                    accessorName = "getCookingTime"
                )
            }
    }
    return result
}

internal fun StructuralRefactorPass.replaceRecipeStateFieldReads(
    source: String,
    declaration: JavaClassDeclaration,
    fieldName: String,
    accessorName: String
): String {
    if (!source.contains(".$fieldName")) return source
    val receivers = recipeStateReceiversForField(source, declaration, fieldName)
    if (receivers.isEmpty()) return source
    var result = source
    receivers.forEach { receiver ->
        result = Regex("""\b${Regex.escape(receiver)}\.${Regex.escape(fieldName)}\b""")
            .replace(result, "$receiver.$accessorName()")
    }
    return result
}

internal fun StructuralRefactorPass.recipeStateReceiversForField(
    source: String,
    declaration: JavaClassDeclaration,
    fieldName: String
): Set<String> {
    val body = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
    val id = """[A-Za-z_$][\w$]*"""
    val receivers = linkedSetOf<String>()
    Regex("""\b${Regex.escape(declaration.name)}(?:\s*<[^;{}()]+>)?\s+($id)\b""")
        .findAll(body)
        .forEach { receivers += it.groupValues[1] }
    Regex("""\(\s*($id)\s*\)\s*->\s*\1\.${Regex.escape(fieldName)}\b""")
        .findAll(body)
        .forEach { receivers += it.groupValues[1] }
    Regex("""\b($id)\s*->\s*\1\.${Regex.escape(fieldName)}\b""")
        .findAll(body)
        .forEach { receivers += it.groupValues[1] }
    return receivers
}

internal fun StructuralRefactorPass.forwardedAbstractCookingTimeParameters(
    source: String,
    declaration: JavaClassDeclaration,
    javaInheritanceIndex: JavaInheritanceIndex
): Set<String> {
    if (!javaClassExtendsAny(declaration, setOf("AbstractCookingRecipe"), javaInheritanceIndex)) {
        return emptySet()
    }
    val body = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
    val forwarded = linkedSetOf<String>()
    Regex("""(?s)\b(?:public|protected|private)\s+${Regex.escape(declaration.name)}\s*\(([^()]*)\)\s*\{""")
        .findAll(body)
        .forEach { constructor ->
            val params = parseJavaParameters(constructor.groupValues[1])
            val intParams = params
                .filter { simpleJavaTypeName(it.type) == "int" }
                .map { it.name }
                .toSet()
            if (intParams.isEmpty()) return@forEach
            val openBrace = body.indexOf('{', constructor.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(body, openBrace) else -1
            if (openBrace < 0 || closeBrace < 0) return@forEach
            val constructorBody = body.substring(openBrace + 1, closeBrace)
            val superMatch = Regex("""\bsuper\s*\(""").find(constructorBody) ?: return@forEach
            val superOpen = superMatch.range.last
            val superClose = findMatchingParen(constructorBody, superOpen)
            if (superClose < 0) return@forEach
            val superArgs = splitTopLevelJavaArgs(constructorBody.substring(superOpen + 1, superClose))
                .map { it.trim() }
            val finalArg = superArgs.lastOrNull() ?: return@forEach
            if (finalArg in intParams && !javaClassDeclaresField(source, declaration, finalArg)) {
                forwarded += finalArg
            }
        }
    return forwarded
}

internal fun StructuralRefactorPass.javaClassDeclaresField(source: String, declaration: JavaClassDeclaration, fieldName: String): Boolean {
    val body = source.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
    return Regex("""(?m)^[ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?[A-Za-z_$][\w$]*(?:\s*<[^;\r\n]+>)?(?:\[\])?\s+${Regex.escape(fieldName)}\s*(?:=[^;\r\n]*)?;""")
        .containsMatchIn(body)
}

internal fun StructuralRefactorPass.simpleJavaTypeName(type: String): String =
    type.trim()
        .replace(Regex("""<[^<>]*>"""), "")
        .replace("...", "[]")
        .substringAfterLast('.')
        .trim()

internal fun StructuralRefactorPass.legacyStructuredRecipeSerializerOptionalCompoundOwner(source: String): String? {
    val className = javaTopLevelTypeName(source) ?: return null
    if (!source.contains("implements RecipeSerializer<$className>") ||
        !source.contains("fromJson(ResourceLocation") ||
        !source.contains("fromNetwork(ResourceLocation") ||
        !source.contains("toNetwork(FriendlyByteBuf")) {
        return null
    }

    val constructorMatch = Regex("""public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*,\s*([^)]*)\)""")
        .find(source)
        ?: return null
    val legacyIdName = constructorMatch.groupValues[1]
    val constructorParams = parseJavaParameters(constructorMatch.groupValues[2])
    if (constructorParams.isEmpty()) return null

    val fromJsonMethod = javaMethodText(source, "fromJson") ?: return null
    val fromNetworkMethod = javaMethodText(source, "fromNetwork") ?: return null
    val toNetworkMethod = javaMethodText(source, "toNetwork") ?: return null
    val fromJsonArgs = constructorArgsForClass(fromJsonMethod, className) ?: return null
    val fromNetworkArgs = constructorArgsForClass(fromNetworkMethod, className) ?: return null
    if (fromJsonArgs.size != constructorParams.size + 1 ||
        fromNetworkArgs.size != constructorParams.size + 1 ||
        fromJsonArgs.first().trim() !in setOf(legacyIdName, "id", "recipeId", "recipeLocation") ||
        fromNetworkArgs.first().trim() !in setOf(legacyIdName, "id", "recipeId", "recipeLocation")) {
        return null
    }
    if (fromJsonArgs.drop(1).any { !isJavaIdentifier(it.trim()) } ||
        fromNetworkArgs.drop(1).any { !isJavaIdentifier(it.trim()) }) {
        return null
    }

    val params = constructorParams.mapIndexed { index, param ->
        legacyRecipeSerializerParam(
            param,
            fromJsonMethod,
            fromNetworkMethod,
            toNetworkMethod,
            fromJsonArgs[index + 1].trim(),
            fromNetworkArgs[index + 1].trim()
        ) ?: return null
    }
    return className.takeIf { params.any { it.optionalCompoundTag } }
}

internal fun StructuralRefactorPass.migrateLegacyCookingRecipeConstructorsSource(
    source: String,
    legacyCookingRecipeClasses: Set<String>
): String {
    val className = javaTopLevelTypeName(source) ?: return source
    if (className !in legacyCookingRecipeClasses) return source

    var result = source
    val idNames = Regex("""public\s+${Regex.escape(className)}\s*\([^)]*\bResourceLocation\s+([A-Za-z_$][\w$]*)\s*,\s*String\s+group""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toMutableSet()
    idNames.add("id")
    idNames.add("recipeId")
    idNames.add("recipeLocation")

    result = Regex("""(public\s+${Regex.escape(className)}\s*\(\s*[^)]*?)\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*,\s*String\s+group""")
        .replace(result) { match -> "${match.groupValues[1]}String group" }
    idNames.forEach { idName ->
        result = Regex("""super\(([^;\r\n]*?),\s*${Regex.escape(idName)}\s*,\s*group\s*,""")
            .replace(result) { match -> "super(${match.groupValues[1]}, group," }
    }

    val beforeInputMigration = result
    result = Regex("""(\bItemStack\s+assemble\s*\(\s*)Container(\s+[A-Za-z_$][\w$]*\s*,\s*HolderLookup\.Provider\s+[A-Za-z_$][\w$]*\s*\))""")
        .replace(result) { match -> "${match.groupValues[1]}SingleRecipeInput${match.groupValues[2]}" }
    if (result != beforeInputMigration) {
        result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
        val withoutContainerImport = removeImport(result, "net.minecraft.world.Container")
        if (!hasSimpleTypeReference(withoutContainerImport, "Container")) {
            result = withoutContainerImport
        }
    }

    val withoutResourceLocationImport = removeImport(result, "net.minecraft.resources.ResourceLocation")
    if (!hasSimpleTypeReference(withoutResourceLocationImport, "ResourceLocation")) {
        result = withoutResourceLocationImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyFactoryCookingRecipeSerializerSource(source: String): String {
    if (!source.contains("CookieBaker<") ||
        !source.contains("this.factory.create(") ||
        !source.contains("fromNetwork(ResourceLocation") ||
        !Regex("""toNetwork\s*\(\s*(?:FriendlyByteBuf|RegistryFriendlyByteBuf)\s+""").containsMatchIn(source)) {
        return source
    }

    val classMatch = Regex(
        """(?s)public\s+class\s+([A-Za-z_$][\w$]*)\s*<\s*T\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s+implements\s+RecipeSerializer\s*<\s*T\s*>\s*\{"""
    ).find(source) ?: return source
    val className = classMatch.groupValues[1]
    val recipeBase = classMatch.groupValues[2].substringAfterLast('.')
    val openBrace = source.indexOf('{', classMatch.range.last)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return source

    val fromJsonMethod = javaMethodText(source, "fromJson") ?: return source
    val fromNetworkMethod = javaMethodText(source, "fromNetwork") ?: return source
    val toNetworkMethod = javaMethodText(source, "toNetwork") ?: return source
    val factoryArgs = Regex("""this\.factory\.create\s*\(([^;]+)\)""")
        .find(fromJsonMethod)
        ?.groupValues
        ?.get(1)
        ?.let(::splitTopLevelJavaArgs)
        ?: return source
    if (factoryArgs.size != 7 || factoryArgs.first().trim() !in setOf("id", "recipeId", "recipeLocation")) {
        return source
    }
    val categoryMatch = Regex("""([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*=\s*buffer\.readEnum\(\s*([A-Za-z_$][\w$]*)\.class\s*\)""")
        .find(fromNetworkMethod)
        ?: return source
    val categoryType = categoryMatch.groupValues[1]
    if (categoryType != categoryMatch.groupValues[3]) return source
    val id = """[A-Za-z_$][\w$]*"""
    val categoryWriteMatch = Regex("""Objects\.requireNonNullElse\(\s*recipe\.($id)\(\)\s*,\s*${Regex.escape(categoryType)}\.($id)\s*\)""")
        .find(toNetworkMethod)
        ?: return source
    val categoryAccessor = categoryWriteMatch.groupValues[1]
    val categoryDefault = categoryWriteMatch.groupValues[2]
    val resultAccessor = Regex("""ItemStack\.(?:OPTIONAL_)?STREAM_CODEC\.encode\(\s*buffer\s*,\s*recipe\.($id)\(\)\s*\)""")
        .find(toNetworkMethod)
        ?.groupValues
        ?.get(1)
        ?: Regex("""buffer\.writeItem\(\s*recipe\.($id)\(\)\s*\)""")
            .find(toNetworkMethod)
            ?.groupValues
            ?.get(1)
        ?: return source
    if (!fromJsonMethod.contains("$categoryType.CODEC.byName(") ||
        !fromJsonMethod.contains("Ingredient.fromJson(") ||
        !fromNetworkMethod.contains("Ingredient.fromNetwork(buffer)") ||
        !(fromNetworkMethod.contains("buffer.readItem()") || fromNetworkMethod.contains("ItemStack.STREAM_CODEC.decode(buffer)") || fromNetworkMethod.contains("ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)")) ||
        !toNetworkMethod.contains(".toNetwork(buffer)") ||
        !(toNetworkMethod.contains("buffer.writeItem(") || toNetworkMethod.contains("ItemStack.STREAM_CODEC.encode(buffer,") || toNetworkMethod.contains("ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer,"))) {
        return source
    }

    val replacement = """
            public class $className<T extends $recipeBase> implements RecipeSerializer<T> {
                private final $className.CookieBaker<T> factory;
                private final MapCodec<T> codec;
                private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec;

                public $className($className.CookieBaker<T> factory, int defaultCookingTime) {
                    this.factory = factory;
                    this.codec = RecordCodecBuilder.mapCodec((instance) -> instance.group(
                            Codec.STRING.optionalFieldOf("group", "").forGetter(AbstractCookingRecipe::getGroup),
                            $categoryType.CODEC.fieldOf("category").forGetter($recipeBase::$categoryAccessor),
                            Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter((recipe) -> recipe.getIngredients().get(0)),
                            ItemStack.CODEC.fieldOf("result").forGetter($recipeBase::$resultAccessor),
                            Codec.FLOAT.fieldOf("experience").orElse(0.0F).forGetter(AbstractCookingRecipe::getExperience),
                            Codec.INT.fieldOf("cookingtime").orElse(defaultCookingTime).forGetter(AbstractCookingRecipe::getCookingTime)
                    ).apply(instance, factory::create));
                    this.streamCodec = StreamCodec.of(this::toNetwork, this::fromNetwork);
                }

                @Override
                public MapCodec<T> codec() {
                    return this.codec;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
                    return this.streamCodec;
                }

                public T fromNetwork(RegistryFriendlyByteBuf buffer) {
                    String group = buffer.readUtf();
                    $categoryType category = buffer.readEnum($categoryType.class);
                    Ingredient ingredient = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
                    ItemStack result = ItemStack.STREAM_CODEC.decode(buffer);
                    float experience = buffer.readFloat();
                    int cookingTime = buffer.readVarInt();
                    return this.factory.create(group, category, ingredient, result, experience, cookingTime);
                }

                public void toNetwork(RegistryFriendlyByteBuf buffer, T recipe) {
                    buffer.writeUtf(recipe.getGroup());
                    buffer.writeEnum(Objects.requireNonNullElse(recipe.$categoryAccessor(), $categoryType.$categoryDefault));
                    Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.getIngredients().get(0));
                    ItemStack.STREAM_CODEC.encode(buffer, recipe.$resultAccessor());
                    buffer.writeFloat(recipe.getExperience());
                    buffer.writeVarInt(recipe.getCookingTime());
                }

                public interface CookieBaker<T extends $recipeBase> {
                    T create(String group, $categoryType category, Ingredient ingredient, ItemStack result, float experience, int cookingTime);
                }
            }
        """.trimIndent()

    var result = source.substring(0, classMatch.range.first) + replacement + source.substring(closeBrace + 1)
    listOf(
        "com.google.gson.JsonElement",
        "com.google.gson.JsonObject",
        "com.google.gson.JsonSyntaxException",
        "net.minecraft.network.FriendlyByteBuf",
        "net.minecraft.resources.ResourceLocation",
        "net.minecraft.util.GsonHelper",
        "net.neoforged.neoforge.registries.ForgeRegistries",
        "net.minecraftforge.registries.ForgeRegistries",
        "javax.annotation.Nullable"
    ).forEach { result = removeImport(result, it) }
    result = addImportIfMissing(result, "com.mojang.serialization.Codec")
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    result = addImportIfMissing(result, "com.mojang.serialization.codecs.RecordCodecBuilder")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.AbstractCookingRecipe")
    result = addImportIfMissing(result, "java.util.Objects")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyShapedRecipeSerializerSource(source: String): String {
    val className = javaTopLevelTypeName(source) ?: return source
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("implements RecipeSerializer<$className>") ||
        !executableCode.contains("fromJson(ResourceLocation") ||
        !executableCode.contains("fromNetwork(ResourceLocation") ||
        !executableCode.contains("dissolvePattern(") ||
        !executableCode.contains("patternFromJson(") ||
        !executableCode.contains("keyFromJson(")) {
        return source
    }

    val id = """[A-Za-z_$][\w$]*"""
    val constructorPattern = Regex(
        """public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+($id)\s*,\s*String\s+($id)\s*,\s*CraftingBookCategory\s+($id)\s*,\s*int\s+($id)\s*,\s*int\s+($id)\s*,\s*NonNullList\s*<\s*Ingredient\s*>\s+($id)\s*,\s*ItemStack\s+($id)\s*,\s*boolean\s+($id)\s*\)"""
    )
    val constructorMatch = constructorPattern.find(executableCode) ?: return source
    val legacyIdName = constructorMatch.groupValues[1]
    val groupParam = constructorMatch.groupValues[2]
    val categoryParam = constructorMatch.groupValues[3]
    val widthParam = constructorMatch.groupValues[4]
    val heightParam = constructorMatch.groupValues[5]
    val ingredientsParam = constructorMatch.groupValues[6]
    val resultParam = constructorMatch.groupValues[7]
    val showNotificationParam = constructorMatch.groupValues[8]
    val openBrace = executableCode.indexOf('{', constructorMatch.range.last)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
    if (openBrace < 0 || closeBrace < 0) return source
    val constructorBody = executableCode.substring(openBrace + 1, closeBrace)
    val legacyIdField = Regex("""\bthis\.($id)\s*=\s*${Regex.escape(legacyIdName)}\s*;""")
        .find(constructorBody)
        ?.groupValues
        ?.get(1)

    fun assignedField(parameterName: String): String =
        Regex("""\bthis\.($id)\s*=\s*${Regex.escape(parameterName)}\s*;""")
            .find(constructorBody)
            ?.groupValues
            ?.get(1)
            ?: parameterName.removePrefix("p").replaceFirstChar { it.lowercase() }

    val groupField = assignedField(groupParam)
    val categoryField = assignedField(categoryParam)
    val widthField = assignedField(widthParam)
    val heightField = assignedField(heightParam)
    val ingredientsField = assignedField(ingredientsParam)
    val resultField = assignedField(resultParam)
    val showNotificationField = assignedField(showNotificationParam)

    var result = source
    result = removeLegacyRecipeIdState(result, legacyIdName)
    if (legacyIdField != null) {
        result = removeLegacyRecipeIdState(result, legacyIdField)
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^[ \t]*this\.${Regex.escape(legacyIdField)}\s*=\s*${Regex.escape(legacyIdName)}\s*;\s*\r?\n""")
        ) { "" }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+$id\s*,\s*""")
    ) { "public $className(" }
    result = replaceExecutableRegex(
        result,
        Regex("""\bthis\(\s*${Regex.escape(legacyIdName)}\s*,\s*""")
    ) { "this(" }

    val helpers = legacyShapedRecipeCodecHelpers(
        className = className,
        widthField = widthField,
        heightField = heightField,
        ingredientsField = ingredientsField
    )
    if (!maskJavaCommentsAndLiterals(result).contains("codecPattern()")) {
        val serializerClass = Regex("""(?m)^[ \t]*public\s+static\s+class\s+Serializer\b""")
            .find(maskJavaCommentsAndLiterals(result))
            ?: return result
        result = result.substring(0, serializerClass.range.first).trimEnd() +
            "\n\n" +
            helpers +
            "\n\n" +
            result.substring(serializerClass.range.first)
    }

    result = replaceNestedClass(
        result,
        "Serializer",
        legacyShapedRecipeSerializerBody(
            className = className,
            groupField = groupField,
            categoryField = categoryField,
            widthField = widthField,
            heightField = heightField,
            ingredientsField = ingredientsField,
            resultField = resultField,
            showNotificationField = showNotificationField
        )
    )

    listOf(
        "net.minecraft.network.FriendlyByteBuf",
        "javax.annotation.Nullable"
    ).forEach { result = removeImport(result, it) }
    result = addImportIfMissing(result, "com.mojang.serialization.Codec")
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    result = addImportIfMissing(result, "com.mojang.serialization.codecs.RecordCodecBuilder")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    val withoutResourceLocationImport = removeImport(result, "net.minecraft.resources.ResourceLocation")
    if (!hasSimpleTypeReference(withoutResourceLocationImport, "ResourceLocation")) {
        result = withoutResourceLocationImport
    }
    return result
}

internal fun StructuralRefactorPass.legacyShapedRecipeCodecHelpers(
    className: String,
    widthField: String,
    heightField: String,
    ingredientsField: String
): String =
    """
    private static final String CODEC_KEY_SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private java.util.List<String> codecPattern() {
        java.util.Map<Ingredient, String> keyByIngredient = new java.util.IdentityHashMap<>();
        java.util.List<String> pattern = new java.util.ArrayList<>();
        for (int y = 0; y < this.$heightField; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < this.$widthField; x++) {
                Ingredient ingredient = this.$ingredientsField.get(x + y * this.$widthField);
                row.append(ingredient.isEmpty() ? " " : codecSymbolFor(ingredient, keyByIngredient));
            }
            pattern.add(row.toString());
        }
        return pattern;
    }

    private java.util.Map<String, Ingredient> codecKey() {
        java.util.Map<Ingredient, String> keyByIngredient = new java.util.IdentityHashMap<>();
        java.util.Map<String, Ingredient> key = new java.util.LinkedHashMap<>();
        for (Ingredient ingredient : this.$ingredientsField) {
            if (!ingredient.isEmpty()) {
                key.put(codecSymbolFor(ingredient, keyByIngredient), ingredient);
            }
        }
        return key;
    }

    private static String codecSymbolFor(Ingredient ingredient, java.util.Map<Ingredient, String> keyByIngredient) {
        String existing = keyByIngredient.get(ingredient);
        if (existing != null) {
            return existing;
        }
        int index = keyByIngredient.size();
        if (index >= CODEC_KEY_SYMBOLS.length()) {
            throw new IllegalStateException("Too many distinct ingredients for $className shaped recipe codec");
        }
        String symbol = String.valueOf(CODEC_KEY_SYMBOLS.charAt(index));
        keyByIngredient.put(ingredient, symbol);
        return symbol;
    }

    private static String[] patternFromCodec(java.util.List<String> pattern) {
        if (pattern.size() > MAX_HEIGHT) {
            throw new JsonSyntaxException("Invalid pattern: too many rows, " + MAX_HEIGHT + " is maximum");
        }
        if (pattern.isEmpty()) {
            throw new JsonSyntaxException("Invalid pattern: empty pattern not allowed");
        }
        String[] rows = pattern.toArray(String[]::new);
        for (int i = 0; i < rows.length; i++) {
            if (rows[i].length() > MAX_WIDTH) {
                throw new JsonSyntaxException("Invalid pattern: too many columns, " + MAX_WIDTH + " is maximum");
            }
            if (i > 0 && rows[0].length() != rows[i].length()) {
                throw new JsonSyntaxException("Invalid pattern: each row must be the same width");
            }
        }
        return rows;
    }

    private static java.util.Map<String, Ingredient> keyFromCodec(java.util.Map<String, Ingredient> key) {
        java.util.Map<String, Ingredient> normalized = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Ingredient> entry : key.entrySet()) {
            if (entry.getKey().length() != 1) {
                throw new JsonSyntaxException("Invalid key entry: '" + entry.getKey() + "' is an invalid symbol (must be 1 character only).");
            }
            if (" ".equals(entry.getKey())) {
                throw new JsonSyntaxException("Invalid key entry: ' ' is a reserved symbol.");
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        normalized.put(" ", Ingredient.EMPTY);
        return normalized;
    }
        """.trimIndent()

internal fun StructuralRefactorPass.legacyShapedRecipeSerializerBody(
    className: String,
    groupField: String,
    categoryField: String,
    widthField: String,
    heightField: String,
    ingredientsField: String,
    resultField: String,
    showNotificationField: String
): String =
    """
            public static class Serializer implements RecipeSerializer<$className> {
                private static final MapCodec<$className> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.STRING.optionalFieldOf("group", "").forGetter((recipe) -> recipe.$groupField),
                        CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC).forGetter((recipe) -> recipe.$categoryField),
                        Codec.STRING.listOf().fieldOf("pattern").forGetter($className::codecPattern),
                        Codec.unboundedMap(Codec.STRING, Ingredient.CODEC_NONEMPTY).fieldOf("key").forGetter($className::codecKey),
                        ItemStack.CODEC.fieldOf("result").forGetter((recipe) -> recipe.$resultField),
                        Codec.BOOL.optionalFieldOf("show_notification", true).forGetter((recipe) -> recipe.$showNotificationField)
                ).apply(instance, Serializer::fromCodec));

                private static final StreamCodec<RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.of(
                        Serializer::toNetwork,
                        Serializer::fromNetwork
                );

                @Override
                public MapCodec<$className> codec() {
                    return CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, $className> streamCodec() {
                    return STREAM_CODEC;
                }

                private static $className fromCodec(String group, CraftingBookCategory category, java.util.List<String> pattern, java.util.Map<String, Ingredient> key, ItemStack result, boolean showNotification) {
                    String[] rows = $className.shrink($className.patternFromCodec(pattern));
                    if (rows.length == 0) {
                        throw new JsonSyntaxException("Invalid pattern: empty pattern not allowed");
                    }
                    int width = rows[0].length();
                    int height = rows.length;
                    NonNullList<Ingredient> ingredients = $className.dissolvePattern(rows, $className.keyFromCodec(key), width, height);
                    return new $className(group, category, width, height, ingredients, result, showNotification);
                }

                private static $className fromNetwork(RegistryFriendlyByteBuf buffer) {
                    int width = buffer.readVarInt();
                    int height = buffer.readVarInt();
                    String group = buffer.readUtf();
                    CraftingBookCategory category = buffer.readEnum(CraftingBookCategory.class);
                    NonNullList<Ingredient> ingredients = NonNullList.withSize(width * height, Ingredient.EMPTY);
                    for (int i = 0; i < ingredients.size(); ++i) {
                        ingredients.set(i, Ingredient.CONTENTS_STREAM_CODEC.decode(buffer));
                    }
                    ItemStack result = ItemStack.STREAM_CODEC.decode(buffer);
                    boolean showNotification = buffer.readBoolean();
                    return new $className(group, category, width, height, ingredients, result, showNotification);
                }

                private static void toNetwork(RegistryFriendlyByteBuf buffer, $className recipe) {
                    buffer.writeVarInt(recipe.$widthField);
                    buffer.writeVarInt(recipe.$heightField);
                    buffer.writeUtf(recipe.$groupField);
                    buffer.writeEnum(recipe.$categoryField);
                    for (Ingredient ingredient : recipe.$ingredientsField) {
                        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, ingredient);
                    }
                    ItemStack.STREAM_CODEC.encode(buffer, recipe.$resultField);
                    buffer.writeBoolean(recipe.$showNotificationField);
                }
            }
        """.trimIndent()

internal fun StructuralRefactorPass.migrateLegacyStructuredRecipeSerializerSource(source: String): String {
    val className = javaTopLevelTypeName(source) ?: return source
    if (!source.contains("implements RecipeSerializer<$className>") ||
        !source.contains("fromJson(ResourceLocation") ||
        !source.contains("fromNetwork(ResourceLocation") ||
        !Regex("""toNetwork\s*\(\s*(?:FriendlyByteBuf|RegistryFriendlyByteBuf)\s+""").containsMatchIn(source)) {
        return source
    }

    val constructorMatch = Regex("""public\s+${Regex.escape(className)}\s*\(\s*ResourceLocation\s+([A-Za-z_$][\w$]*)\s*,\s*([^)]*)\)""")
        .find(source)
        ?: return source
    val legacyIdName = constructorMatch.groupValues[1]
    val constructorParams = parseJavaParameters(constructorMatch.groupValues[2])
    if (constructorParams.isEmpty()) return source

    val fromJsonMethod = javaMethodText(source, "fromJson") ?: return source
    val fromNetworkMethod = javaMethodText(source, "fromNetwork") ?: return source
    val toNetworkMethod = javaMethodText(source, "toNetwork") ?: return source
    val fromJsonArgs = constructorArgsForClass(fromJsonMethod, className) ?: return source
    val fromNetworkArgs = constructorArgsForClass(fromNetworkMethod, className) ?: return source
    if (fromJsonArgs.size != constructorParams.size + 1 ||
        fromNetworkArgs.size != constructorParams.size + 1 ||
        fromJsonArgs.first().trim() !in setOf(legacyIdName, "id", "recipeId", "recipeLocation") ||
        fromNetworkArgs.first().trim() !in setOf(legacyIdName, "id", "recipeId", "recipeLocation")) {
        return source
    }
    if (fromJsonArgs.drop(1).any { !isJavaIdentifier(it.trim()) } ||
        fromNetworkArgs.drop(1).any { !isJavaIdentifier(it.trim()) }) {
        return source
    }

    val params = constructorParams.mapIndexed { index, param ->
        legacyRecipeSerializerParam(
            param,
            fromJsonMethod,
            fromNetworkMethod,
            toNetworkMethod,
            fromJsonArgs[index + 1].trim(),
            fromNetworkArgs[index + 1].trim()
        ) ?: return source
    }

    val migratedConstructorParams = constructorParams.joinToString(", ") { constructorParam ->
        val migratedParam = params.first { it.name == constructorParam.name }
        "${migratedParam.type} ${constructorParam.name}"
    }

    var result = source
    result = result.replaceRange(constructorMatch.range, "public $className($migratedConstructorParams)")
    result = removeLegacyRecipeIdState(result, legacyIdName)
    result = migrateLegacyRecipeInputTypeForSingleStack(result)
    for (param in params.filter { it.optionalCompoundTag }) {
        result = result.replace(Regex("""(?m)^([ \t]*(?:private|protected|public)\s+final\s+)CompoundTag(\s+${Regex.escape(param.name)}\s*;)"""), "$1Optional<CompoundTag>$2")
        result = result.replace(Regex("""(?m)^([ \t]*)public\s+CompoundTag\s+get${param.name.replaceFirstChar { it.uppercase() }}\s*\(\s*\)"""), "$1public Optional<CompoundTag> get${param.name.replaceFirstChar { it.uppercase() }}()")
    }
    result = replaceNestedClass(result, "Serializer", legacyStructuredRecipeSerializerBody(className, params))

    listOf(
        "com.google.gson.JsonObject",
        "net.minecraft.network.FriendlyByteBuf",
        "net.minecraft.util.GsonHelper",
        "net.neoforged.neoforge.common.crafting.CraftingHelper",
        "net.minecraftforge.common.crafting.CraftingHelper",
        "javax.annotation.Nullable"
    ).forEach { result = removeImport(result, it) }
    result = addImportIfMissing(result, "com.mojang.serialization.Codec")
    result = addImportIfMissing(result, "com.mojang.serialization.MapCodec")
    result = addImportIfMissing(result, "com.mojang.serialization.codecs.RecordCodecBuilder")
    result = addImportIfMissing(result, "net.minecraft.network.RegistryFriendlyByteBuf")
    result = addImportIfMissing(result, "net.minecraft.network.codec.StreamCodec")
    if (params.any { it.needsBuiltInRegistries }) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.BuiltInRegistries")
    }
    if (params.any { it.needsRegistries }) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    }
    if (params.any { it.needsJsonSyntaxException }) {
        result = addImportIfMissing(result, "com.google.gson.JsonSyntaxException")
    } else {
        result = removeImport(result, "com.google.gson.JsonSyntaxException")
    }
    if (params.any { it.optionalCompoundTag }) {
        result = addImportIfMissing(result, "java.util.Optional")
    }
    val withoutResourceLocationImport = removeImport(result, "net.minecraft.resources.ResourceLocation")
    if (!hasSimpleTypeReference(withoutResourceLocationImport, "ResourceLocation")) {
        result = withoutResourceLocationImport
    }
    result = removeImport(result, "net.minecraft.core.RegistryAccess")
    result = migrateOptionalCompoundRecipeTagAccess(result, setOf(className))
    return result
}

internal fun StructuralRefactorPass.parseJavaParameters(paramsText: String): List<JavaParameterInfo> =
    splitTopLevelJavaArgs(paramsText).mapNotNull { raw ->
        val cleaned = raw.trim()
            .replace(Regex("""^(?:@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\([^)]*\))?\s+)+"""), "")
            .removePrefix("final ")
            .trim()
        Regex("""(.+?)\s+([A-Za-z_$][\w$]*)$""")
            .find(cleaned)
            ?.let { JavaParameterInfo(it.groupValues[1].trim(), it.groupValues[2]) }
    }

internal fun StructuralRefactorPass.isJavaIdentifier(value: String): Boolean =
    Regex("""[A-Za-z_$][\w$]*""").matches(value)

internal fun StructuralRefactorPass.legacyRecipeSerializerParam(
    param: JavaParameterInfo,
    fromJsonMethod: String,
    fromNetworkMethod: String,
    toNetworkMethod: String,
    fromJsonArgName: String = param.name,
    fromNetworkArgName: String = param.name
): LegacyRecipeSerializerParam? {
    val type = param.type.removePrefix("@Nullable").trim()
    val name = param.name
    val jsonLocal = fromJsonArgName.removePrefix("this.").substringAfterLast('.')
    val networkLocal = fromNetworkArgName.removePrefix("this.").substringAfterLast('.')
    val jsonVar = Regex("""JsonObject\s+([A-Za-z_$][\w$]*)""").find(fromJsonMethod)?.groupValues?.get(1) ?: "json"
    val bufferVar = Regex("""(?:FriendlyByteBuf|RegistryFriendlyByteBuf)\s+([A-Za-z_$][\w$]*)""").find(fromNetworkMethod)?.groupValues?.get(1) ?: "buffer"
    val recipeVar = Regex("""\b${Regex.escape(type.substringAfterLast('.'))}\s+([A-Za-z_$][\w$]*)\s*\)""")
        .find(toNetworkMethod)
        ?.groupValues
        ?.get(1)
        ?: Regex("""\b[A-Za-z_$][\w$<>?,\s]+\s+([A-Za-z_$][\w$]*)\s*\)""").find(toNetworkMethod)?.groupValues?.get(1)
        ?: "recipe"

    fun requireWrite(pattern: Regex): Boolean = pattern.containsMatchIn(toNetworkMethod)
    fun getter(): String = "(recipe) -> recipe.$name"
    fun recipeField(): String = "$recipeVar.$name"
    fun gsonStringFieldForLocal(localName: String): String? =
        Regex("""String\s+${Regex.escape(localName)}\s*=\s*GsonHelper\.getAsString\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
    fun resourceLocationFieldForLocal(localName: String): String? {
        Regex("""ResourceLocation\s+${Regex.escape(localName)}\s*=\s*ResourceLocation\.(?:parse|tryParse)\(\s*GsonHelper\.getAsString\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*\)\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
            ?.let { return it }
        val sourceStringLocal = Regex("""ResourceLocation\s+${Regex.escape(localName)}\s*=\s*ResourceLocation\.(?:parse|tryParse)\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
            ?: return null
        return gsonStringFieldForLocal(sourceStringLocal)
    }

    if (type == "String") {
        val match = Regex("""String\s+${Regex.escape(jsonLocal)}\s*=\s*GsonHelper\.getAsString\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*,\s*([^)]+)\)""")
            .find(fromJsonMethod)
            ?: return null
        if (!fromNetworkMethod.contains("String $networkLocal = $bufferVar.readUtf()") ||
            !requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeUtf\("""))) return null
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """Codec.STRING.optionalFieldOf("${match.groupValues[1]}", ${match.groupValues[2].trim()}).forGetter(${getter()})""",
            networkReadLine = "String $name = buffer.readUtf();",
            networkWriteLine = "buffer.writeUtf(${recipeField()});",
            needsCodec = true
        )
    }

    if (type == "Ingredient") {
        val field = Regex("""Ingredient\s+${Regex.escape(jsonLocal)}\s*=\s*Ingredient\.fromJson\(\s*GsonHelper\.getAsJsonObject\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*\)\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
            ?: Regex("""Ingredient\s+${Regex.escape(jsonLocal)}\s*=\s*Ingredient\.fromJson\(""")
                .find(fromJsonMethod)
                ?.let { name }
            ?: return null
        if (!fromNetworkMethod.contains("Ingredient $networkLocal = Ingredient.fromNetwork($bufferVar)") ||
            !requireWrite(Regex("""${Regex.escape(recipeField())}\.toNetwork\(\s*${Regex.escape(bufferVar)}\s*\)"""))) return null
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """Ingredient.CODEC_NONEMPTY.fieldOf("$field").forGetter(${getter()})""",
            networkReadLine = "Ingredient $name = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);",
            networkWriteLine = "Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, ${recipeField()});"
        )
    }

    if (type == "int") {
        val match = Regex("""int\s+${Regex.escape(jsonLocal)}\s*=\s*GsonHelper\.getAsInt\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*,\s*([^)]+)\)""")
            .find(fromJsonMethod)
            ?: return null
        val usesVarInt = fromNetworkMethod.contains("int $networkLocal = $bufferVar.readVarInt()") &&
            requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeVarInt\("""))
        val usesInt = fromNetworkMethod.contains("int $networkLocal = $bufferVar.readInt()") &&
            requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeInt\("""))
        if (!usesVarInt && !usesInt) return null
        val readMethod = if (usesInt) "readInt" else "readVarInt"
        val writeMethod = if (usesInt) "writeInt" else "writeVarInt"
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """Codec.INT.fieldOf("${match.groupValues[1]}").orElse(${match.groupValues[2].trim()}).forGetter(${getter()})""",
            networkReadLine = "int $name = buffer.$readMethod();",
            networkWriteLine = "buffer.$writeMethod(${recipeField()});",
            needsCodec = true
        )
    }

    if (type == "ResourceLocation") {
        val field = resourceLocationFieldForLocal(jsonLocal) ?: return null
        if (!fromNetworkMethod.contains("ResourceLocation $networkLocal = $bufferVar.readResourceLocation()") ||
            !requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeResourceLocation\("""))) return null
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """ResourceLocation.CODEC.fieldOf("$field").forGetter(${getter()})""",
            networkReadLine = "ResourceLocation $name = buffer.readResourceLocation();",
            networkWriteLine = "buffer.writeResourceLocation(${recipeField()});"
        )
    }

    if (type == "Item") {
        val directField = Regex("""Item\s+${Regex.escape(jsonLocal)}\s*=\s*BuiltInRegistries\.ITEM\.get\(\s*ResourceLocation\.(?:parse|tryParse)\(\s*GsonHelper\.getAsString\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*\)\s*\)\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
        val resourceLocal = Regex("""Item\s+${Regex.escape(jsonLocal)}\s*=\s*BuiltInRegistries\.ITEM\.get\(\s*([A-Za-z_$][\w$]*)\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
        val field = directField ?: resourceLocal?.let(::resourceLocationFieldForLocal) ?: return null
        val usesVarInt = fromNetworkMethod.contains("Item $networkLocal = BuiltInRegistries.ITEM.byId($bufferVar.readVarInt())") &&
            requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeVarInt\(\s*BuiltInRegistries\.ITEM\.getId\("""))
        val usesReadById = fromNetworkMethod.contains("Item $networkLocal = $bufferVar.readById(BuiltInRegistries.ITEM)") &&
            requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeId\(\s*BuiltInRegistries\.ITEM\s*,"""))
        if (!usesVarInt && !usesReadById) return null
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """BuiltInRegistries.ITEM.byNameCodec().fieldOf("$field").forGetter(${getter()})""",
            networkReadLine = "Item $name = BuiltInRegistries.ITEM.byId(buffer.readVarInt());",
            networkWriteLine = "buffer.writeVarInt(BuiltInRegistries.ITEM.getId(${recipeField()}));",
            needsBuiltInRegistries = true
        )
    }

    if (type.startsWith("EntityType")) {
        val field = Regex("""EntityType<\?>\s+${Regex.escape(jsonLocal)}\s*=\s*EntityType\.byString\(\s*GsonHelper\.getAsString\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*\)\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
            ?: return null
        if (!fromNetworkMethod.contains("EntityType<?> $networkLocal = EntityType.byString($bufferVar.readUtf())") ||
            !requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeUtf\(\s*EntityType\.getKey\("""))) return null
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("$field").forGetter(${getter()})""",
            networkReadLine = """EntityType<?> $name = EntityType.byString(buffer.readUtf()).orElseThrow(() -> new JsonSyntaxException("Entity type cannot be found"));""",
            networkWriteLine = "buffer.writeUtf(EntityType.getKey(${recipeField()}).toString());",
            needsBuiltInRegistries = true,
            needsJsonSyntaxException = true
        )
    }

    if (type == "CompoundTag") {
        val field = Regex("""${Regex.escape(jsonVar)}\.has\(\s*"([^"]+)"\s*\)""")
            .find(fromJsonMethod)
            ?.groupValues
            ?.get(1)
            ?: return null
        if (!fromNetworkMethod.contains("$bufferVar.readBoolean()") ||
            !fromNetworkMethod.contains("$networkLocal = $bufferVar.readNbt()") ||
            !toNetworkMethod.contains("$recipeVar.$name != null") ||
            !toNetworkMethod.contains("$bufferVar.writeNbt($recipeVar.$name)")) return null
        return LegacyRecipeSerializerParam(
            type = "Optional<CompoundTag>",
            name = name,
            codecLine = """CompoundTag.CODEC.optionalFieldOf("$field").forGetter(${getter()})""",
            networkReadLine = "Optional<CompoundTag> $name = buffer.readOptional(RegistryFriendlyByteBuf::readNbt);",
            networkWriteLine = "buffer.writeOptional(${recipeField()}, RegistryFriendlyByteBuf::writeNbt);",
            optionalCompoundTag = true
        )
    }

    val enumMatch = Regex("""${Regex.escape(type)}\s+${Regex.escape(jsonLocal)}\s*=\s*${Regex.escape(type)}\.CODEC\.byName\(\s*GsonHelper\.getAsString\(\s*${Regex.escape(jsonVar)}\s*,\s*"([^"]+)"\s*,\s*null\s*\)\s*,\s*([^)]+)\)""")
        .find(fromJsonMethod)
    if (enumMatch != null) {
        if (!fromNetworkMethod.contains("$type $networkLocal = $bufferVar.readEnum($type.class)") ||
            !requireWrite(Regex("""${Regex.escape(bufferVar)}\.writeEnum\("""))) return null
        return LegacyRecipeSerializerParam(
            type = type,
            name = name,
            codecLine = """$type.CODEC.fieldOf("${enumMatch.groupValues[1]}").orElse(${enumMatch.groupValues[2].trim()}).forGetter(${getter()})""",
            networkReadLine = "$type $name = buffer.readEnum($type.class);",
            networkWriteLine = "buffer.writeEnum(${recipeField()});"
        )
    }

    return null
}

internal fun StructuralRefactorPass.legacyStructuredRecipeSerializerBody(className: String, params: List<LegacyRecipeSerializerParam>): String {
    val codecLines = params.joinToString(",\n") { "                        ${it.codecLine}" }
    val constructorArgs = params.joinToString(", ") { it.constructorArg }
    val readLines = params.joinToString("\n") { "                    ${it.networkReadLine}" }
    val writeLines = params.joinToString("\n") { "                    ${it.networkWriteLine}" }
    return """
            public static class Serializer implements RecipeSerializer<$className> {
                private static final MapCodec<$className> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
$codecLines
                ).apply(instance, $className::new));

                private static final StreamCodec<RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.of(
                        Serializer::toNetwork,
                        Serializer::fromNetwork
                );

                @Override
                public MapCodec<$className> codec() {
                    return CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, $className> streamCodec() {
                    return STREAM_CODEC;
                }

                private static $className fromNetwork(RegistryFriendlyByteBuf buffer) {
$readLines
                    return new $className($constructorArgs);
                }

                private static void toNetwork(RegistryFriendlyByteBuf buffer, $className recipe) {
$writeLines
                }
            }
        """.trimIndent()
}

internal fun StructuralRefactorPass.removeLegacyRecipeIdState(source: String, idName: String): String {
    var result = source
    result = Regex("""(?m)^[ \t]*(?:private|protected|public)\s+final\s+ResourceLocation\s+${Regex.escape(idName)}\s*;\s*\r?\n""")
        .replace(result, "")
    result = Regex("""(?m)^[ \t]*this\.${Regex.escape(idName)}\s*=\s*${Regex.escape(idName)}\s*;\s*\r?\n""")
        .replace(result, "")
    result = removeDeclaredMethod(result, "getId")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyRecipeInputTypeForSingleStack(source: String): String {
    if (!source.contains("Recipe<Container>") || !source.contains(".getItem(0)")) return source
    var result = source.replace("Recipe<Container>", "Recipe<SingleRecipeInput>")
    result = Regex("""(?m)^([ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|protected|private)?\s*(?:boolean|ItemStack)\s+(?:matches|assemble)\s*\(\s*)Container(\s+[A-Za-z_$][\w$]*\s*,\s*(?:Level|HolderLookup\.Provider)\s+[A-Za-z_$][\w$]*\s*\))""")
        .replace(result) { match -> "${match.groupValues[1]}SingleRecipeInput${match.groupValues[2]}" }
    result = addImportIfMissing(result, "net.minecraft.world.item.crafting.SingleRecipeInput")
    val withoutContainerImport = removeImport(result, "net.minecraft.world.Container")
    if (!hasSimpleTypeReference(withoutContainerImport, "Container")) {
        result = withoutContainerImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateOptionalCompoundRecipeTagAccess(
    source: String,
    optionalCompoundRecipeTagOwners: Set<String> = emptySet()
): String {
    if (optionalCompoundRecipeTagOwners.isEmpty() || !source.contains(".getTag()")) return source

    val ownerPattern = optionalCompoundRecipeTagOwners.joinToString("|") { Regex.escape(it) }
    val directVariables = Regex("""\b(?:[A-Za-z_$][\w$]*\.)*(?:$ownerPattern)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
    val holderVariables = Regex("""\bRecipeHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*(?:$ownerPattern)\s*>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    var result = source
    holderVariables.forEach { variable ->
        result = Regex("""CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(variable)}\.value\(\)\.getTag\(\)\s*;""")
            .replace(result) { match -> "CompoundTag ${match.groupValues[1]} = $variable.value().getTag().orElse(null);" }
    }
    directVariables.forEach { variable ->
        result = Regex("""CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(variable)}\.getTag\(\)\s*;""")
            .replace(result) { match -> "CompoundTag ${match.groupValues[1]} = $variable.getTag().orElse(null);" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeferredHolderGenericsSource(
    source: String,
    deferredHolderRegistryBaseHints: Map<String, String> = emptyMap()
): String {
    var result = source
    fun replaceExecutable(pattern: Regex, replacement: (MatchResult) -> String) {
        result = replaceExecutableRegex(result, pattern, replacement)
    }
    fun replaceExecutableLiteral(literal: String, replacement: String) {
        replaceExecutable(Regex(Regex.escape(literal))) { replacement }
    }
    replaceExecutableLiteral("DeferredRegister<LootItemFunctionType>", "DeferredRegister<LootItemFunctionType<?>>")
    deferredHolderRegistryBaseHints.forEach { (concrete, base) ->
        replaceExecutable(
            Regex("""DeferredHolder\s*<\s*((?:[A-Za-z_$][\w$]*\.)*)${Regex.escape(concrete)}\s*,\s*(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(concrete)}\s*>""")
        ) { match -> "DeferredHolder<$base, ${match.groupValues[1]}$concrete>" }
    }
    replaceExecutableLiteral("DeferredHolder<LiquidBlock, LiquidBlock>", "DeferredHolder<Block, LiquidBlock>")
    replaceExecutableLiteral("DeferredHolder<DynamicFluidType, DynamicFluidType>", "DeferredHolder<FluidType, DynamicFluidType>")
    replaceExecutableLiteral("DeferredHolder<ItemFoodBase, ItemFoodBase>", "DeferredHolder<Item, ItemFoodBase>")
    listOf(
        "BaseEntityBlock",
        "BlockAttachedEntity",
        "BushBlock",
        "ButtonBlock",
        "CeilingHangingSignBlock",
        "DoorBlock",
        "FallingBlock",
        "FenceBlock",
        "FenceGateBlock",
        "FlowerPotBlock",
        "HorizontalDirectionalBlock",
        "LadderBlock",
        "LeavesBlock",
        "PipeBlock",
        "PressurePlateBlock",
        "RotatedPillarBlock",
        "SaplingBlock",
        "SignBlock",
        "SlabBlock",
        "StairBlock",
        "StandingSignBlock",
        "TrapDoorBlock",
        "WallHangingSignBlock",
        "WallSignBlock",
        "WaterlilyBlock"
    ).forEach { blockSubtype ->
        replaceExecutable(
            Regex("""DeferredHolder\s*<\s*${Regex.escape(blockSubtype)}\s*,\s*${Regex.escape(blockSubtype)}\s*>""")
        ) { "DeferredHolder<Block, $blockSubtype>" }
    }
    listOf(
        "ArmorItem",
        "BowItem",
        "BucketItem",
        "CrossbowItem",
        "DiggerItem",
        "ShieldItem",
        "SpawnEggItem",
        "SwordItem",
        "TieredItem",
        "TridentItem"
    ).forEach { itemSubtype ->
        replaceExecutable(
            Regex("""DeferredHolder\s*<\s*${Regex.escape(itemSubtype)}\s*,\s*${Regex.escape(itemSubtype)}\s*>""")
        ) { "DeferredHolder<Item, $itemSubtype>" }
    }
    if (maskJavaCommentsAndLiterals(result).contains("DeferredRegister<Item>")) {
        replaceExecutable(Regex(
            """(?m)^([ \t]*)(?!@SuppressWarnings\("unchecked"\)\s*\r?\n)\s*private\s+static\s+<V\s+extends\s+Item>\s+DeferredHolder\s*<\s*V\s*,\s*V\s*>\s+register\("""
        )) { match ->
            "${match.groupValues[1]}@SuppressWarnings(\"unchecked\")\n" +
                "${match.groupValues[1]}private static <V extends Item> DeferredHolder<Item, V> register("
        }
        if (maskJavaCommentsAndLiterals(result).contains("DeferredHolder<Item, V> register(")) {
            replaceExecutableLiteral(
                "return ITEMS.register(name, item);",
                "return (DeferredHolder<Item, V>) (DeferredHolder<?, ?>) ITEMS.register(name, item);"
            )
            replaceExecutable(
                Regex("""DeferredHolder\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*,\s*\1\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*register\s*\(""")
            ) { match ->
                val concrete = match.groupValues[1].trim()
                if (concrete.substringAfterLast('.') == "Item") {
                    match.value
                } else {
                    "DeferredHolder<Item, $concrete> ${match.groupValues[2]} = register("
                }
            }
        }
    }
    replaceExecutable(Regex("""DeferredHolder<EntityType<\s*([^>]+?)\s*>,\s*EntityType<\s*([^>]+?)\s*>>""")) { match ->
        "DeferredHolder<EntityType<?>, EntityType<${match.groupValues[2].trim()}>>"
    }
    replaceExecutable(Regex("""DeferredHolder<BlockEntityType<\s*([^>]+?)\s*>,\s*BlockEntityType<\s*([^>]+?)\s*>>""")) { match ->
        "DeferredHolder<BlockEntityType<?>, BlockEntityType<${match.groupValues[2].trim()}>>"
    }
    replaceExecutable(Regex(
        """(?s)DeferredHolder\s*<\s*RecipeSerializer\s*<\s*([^>]+?)\s*>\s*,\s*RecipeSerializer\s*<\s*\1\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]*?\b((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)::new\s*\))"""
    )) { match ->
        "DeferredHolder<RecipeSerializer<?>, ${match.groupValues[4]}> ${match.groupValues[2]} = ${match.groupValues[3]}"
    }
    replaceExecutable(Regex("""DeferredHolder<RecipeSerializer<\s*([^>]+?)\s*>,\s*RecipeSerializer<\s*([^>]+?)\s*>>""")) { match ->
        "DeferredHolder<RecipeSerializer<?>, RecipeSerializer<${match.groupValues[2].trim()}>>"
    }
    replaceExecutable(Regex(
        """(?s)DeferredHolder<RecipeSerializer<\?>,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)>\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]*?\bnew\s+SimpleCraftingRecipeSerializer<>\(\s*\1::new\s*\))"""
    )) { match ->
        val rhsRange = match.groups[3]?.range ?: match.range
        val rhs = result.substring(rhsRange.first, rhsRange.last + 1)
        "DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<${match.groupValues[1]}>> ${match.groupValues[2]} = $rhs"
    }
    listOf(
        "RecipeType",
        "Feature",
        "StructureType",
        "StructureProcessorType",
        "StructurePlacementType",
        "TrunkPlacerType",
        "FoliagePlacerType",
        "TreeDecoratorType",
        "PlacementModifierType",
        "StructurePoolElementType"
    ).forEach { registryType ->
        replaceExecutable(Regex("""DeferredHolder<${Regex.escape(registryType)}<\s*([^>]+?)\s*>,\s*${Regex.escape(registryType)}<\s*([^>]+?)\s*>>""")) { match ->
            "DeferredHolder<$registryType<?>, $registryType<${match.groupValues[2].trim()}>>"
        }
    }
    replaceExecutable(Regex(
        """DeferredHolder<T,\s*T>\s+([A-Za-z_$][\w$]*)\((String\s+name,\s*(?:java\.util\.function\.)?Supplier)\s*<\s*(?:Block|T)\s*>\s+block\)"""
    )) { match ->
        "DeferredHolder<Block, T> ${match.groupValues[1]}(${match.groupValues[2]}<T> block)"
    }
    replaceExecutable(Regex(
        """DeferredHolder<Block,\s*T>\s+([A-Za-z_$][\w$]*)\((String\s+name,\s*(?:java\.util\.function\.)?Supplier)\s*<\s*Block\s*>\s+block\)"""
    )) { match ->
        "DeferredHolder<Block, T> ${match.groupValues[1]}(${match.groupValues[2]}<T> block)"
    }
    if (maskJavaCommentsAndLiterals(result).contains("DeferredRegister<Block>") &&
        Regex("""<\s*T\s+extends\s+Block\s*>""").containsMatchIn(maskJavaCommentsAndLiterals(result)) &&
        maskJavaCommentsAndLiterals(result).contains("DeferredHolder<Block, T>")
    ) {
        replaceExecutable(Regex(
            """DeferredHolder\s*<\s*Block\s*,\s*\?\s+extends\s+Block\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Z][A-Z0-9_]*)\.register\("""
        )) { match -> "DeferredHolder<Block, T> ${match.groupValues[1]} = ${match.groupValues[2]}.register(" }
        replaceExecutable(Regex("""return\s+\(DeferredHolder<T,\s*T>\)\s+([A-Za-z_$][\w$]*)\s*;""")) { match -> "return ${match.groupValues[1]};" }
        replaceExecutable(Regex("""return\s+\(DeferredHolder<Block,\s*T>\)\s+([A-Za-z_$][\w$]*)\s*;""")) { match -> "return ${match.groupValues[1]};" }
        replaceExecutable(Regex("""return\s+\(DeferredHolder<Block,\s*T>\)\s+\(DeferredHolder<\?,\s*\?>\)\s+([A-Za-z_$][\w$]*)\s*;""")) { match -> "return ${match.groupValues[1]};" }
    }
    listOf(
        "StructureType",
        "StructureProcessorType",
        "StructurePlacementType"
    ).forEach { registryType ->
        replaceExecutable(Regex(
            """DeferredHolder<${Regex.escape(registryType)}<P>,\s*${Regex.escape(registryType)}<P>>\s+([A-Za-z_$][\w$]*)\("""
        )) { match ->
            "DeferredHolder<$registryType<?>, $registryType<P>> ${match.groupValues[1]}("
        }
    }
    result = migrateDeferredHolderRegisterBaseGenerics(result)
    replaceExecutable(Regex("""registerBlockItem\s*\(\s*String\s+name\s*,\s*DeferredHolder\s*<\s*T\s*,\s*T\s*>\s+block\s*\)""")) {
        "registerBlockItem(String name, DeferredHolder<Block, T> block)"
    }
    replaceExecutable(Regex("""registerBlockItem\s*\(\s*String\s+name\s*,\s*DeferredHolder\s*<\s*Block\s*,\s*T\s*>\s+block\s*\)""")) {
        "registerBlockItem(String name, DeferredHolder<Block, T> block)"
    }
    replaceExecutable(Regex("""DeferredHolder\s*<\s*Block\s*,\s*Item\s*>\s+(registerBlockItem\s*\()""")) { match ->
        "DeferredHolder<Item, BlockItem> ${match.groupValues[1]}"
    }
    val executableResult = maskJavaCommentsAndLiterals(result)
    if (executableResult.contains("DeferredHolder<Item,") || executableResult.contains("DeferredHolder<Item,")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.Item")
    }
    if (maskJavaCommentsAndLiterals(result).contains("DeferredHolder<Item, BlockItem>")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.BlockItem")
    }
    if (maskJavaCommentsAndLiterals(result).contains("DeferredHolder<Block,") || maskJavaCommentsAndLiterals(result).contains("DeferredHolder<Block,")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.block.Block")
    }
    return result
}

internal fun StructuralRefactorPass.migrateDeferredHolderRegisterBaseGenerics(source: String): String {
    val sourceExecutableCode = maskJavaCommentsAndLiterals(source)
    if (!sourceExecutableCode.contains("DeferredRegister<") || !sourceExecutableCode.contains("DeferredHolder")) return source
    val registryBaseByName = Regex(
        """\bDeferredRegister\s*<\s*(.+?)\s*>\s+([A-Za-z_$][\w$]*)\s*="""
    ).findAll(sourceExecutableCode)
        .associate { match -> match.groupValues[2] to match.groupValues[1].trim() }
    if (registryBaseByName.isEmpty()) return source

    var result = source
    var executableResult = sourceExecutableCode
    var cursor = 0
    val holderPattern = Regex("""DeferredHolder\s*<""")
    while (true) {
        val match = holderPattern.find(executableResult, cursor) ?: break
        val openAngle = executableResult.indexOf('<', match.range.first)
        if (openAngle < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeAngle = findMatchingAngle(executableResult, openAngle)
        if (closeAngle < 0) {
            cursor = match.range.last + 1
            continue
        }
        val afterHolder = executableResult.substring(closeAngle + 1)
        val assignment = Regex("""^\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.register\s*\(""")
            .find(afterHolder)
        if (assignment == null) {
            cursor = closeAngle + 1
            continue
        }
        val registryName = assignment.groupValues[2]
        val registryBase = registryBaseByName[registryName]
        if (registryBase == null) {
            cursor = closeAngle + 1
            continue
        }
        val holderArgs = splitTopLevelJavaArgs(result.substring(openAngle + 1, closeAngle))
        if (holderArgs.size != 2 || holderArgs[0].trim() == registryBase) {
            cursor = closeAngle + 1
            continue
        }
        val replacementArgs = "$registryBase, ${holderArgs[1].trim()}"
        result = result.substring(0, openAngle + 1) + replacementArgs + result.substring(closeAngle)
        executableResult = maskJavaCommentsAndLiterals(result)
        cursor = openAngle + 1 + replacementArgs.length
    }

    val blockHelperNames = Regex(
        """\bstatic\s*<\s*([A-Za-z_$][\w$]*)\s+extends\s+(?:[A-Za-z_$][\w$]*\.)*Block\s*>\s+DeferredHolder\s*<\s*Block\s*,\s*\1\s*>\s+([A-Za-z_$][\w$]*)\s*\("""
    ).findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[2] }
        .toSet()
    for (helperName in blockHelperNames) {
        result = replaceExecutableRegex(
            result,
            Regex(
                """DeferredHolder\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*,\s*\1\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(helperName)}\s*\("""
            )
        ) { match ->
            val concrete = match.groupValues[1].trim()
            if (concrete.substringAfterLast('.') == "Block") {
                match.value
            } else {
                "DeferredHolder<Block, $concrete> ${match.groupValues[2]} = $helperName("
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateMapEntryValueGetCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if ((!executableCode.contains("Map.Entry") && !executableCode.contains("Entry<")) ||
        !executableCode.contains(".get()")
    ) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    val id = """[A-Za-z_$][\w$]*"""
    val entryType = """(?:(?:java\.util\.)?Map\.)?Entry"""
    Regex("""for\s*\(\s*$entryType\s*<[^;\r\n]+>\s+($id)\s*:\s*[^;\r\n]+?\.entrySet\s*\(\s*\)\s*\{""")
        .findAll(executableCode)
        .forEach { loop ->
            val variable = loop.groupValues[1]
            val openBrace = executableCode.indexOf('{', loop.range.last - 1)
            if (openBrace < 0) return@forEach
            val closeBrace = findMatchingBrace(executableCode, openBrace)
            if (closeBrace <= openBrace) return@forEach
            Regex("""(?<![\w$])${Regex.escape(variable)}\.get\s*\(\s*\)""")
                .findAll(executableCode.substring(openBrace + 1, closeBrace))
                .forEach { match ->
                    val start = openBrace + 1 + match.range.first
                    val end = openBrace + 1 + match.range.last
                    edits += start..end to "$variable.getValue()"
                }
        }
    val entryVariables = Regex("""\b$entryType\s*<[^;\r\n=()]+>\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toMutableSet()
    Regex("""for\s*\(\s*$entryType\s*<[^;\r\n]+>\s+([A-Za-z_$][\w$]*)\s*:""")
        .findAll(executableCode)
        .mapTo(entryVariables) { it.groupValues[1] }
    Regex("""(?s)for\s*\(\s*$entryType\s*<.*?>\s+([A-Za-z_$][\w$]*)\s*:\s*[^)]*?\.entrySet\s*\(\s*\)\s*\)""")
        .findAll(executableCode)
        .mapTo(entryVariables) { it.groupValues[1] }
    Regex("""(?<![\w$])([A-Za-z_$][\w$]*)\.get\s*\(\s*\)""")
        .findAll(executableCode)
        .forEach { match ->
        val variable = match.groupValues[1]
        if (variable in entryVariables) {
            edits += match.range to "$variable.getValue()"
        }
    }
    val distinctEdits = edits.distinctBy { it.first }
    return if (distinctEdits.isEmpty()) source else applyStringEdits(source, distinctEdits)
}

internal fun StructuralRefactorPass.migrateProjectMapEntryValueGetCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val original = javaFile.readText()
            val migrated = migrateMapEntryValueGetCalls(original)
            if (migrated == original) return@forEach
            if (!dryRun) {
                javaFile.writeText(migrated)
            }
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Migrate Map.Entry value reads away from legacy delegate get calls",
                before = "entry.get()",
                after = "entry.getValue()",
                confidence = Confidence.HIGH,
                ruleId = "struct-map-entry-value-get"
            ))
        }
    return changes
}

internal fun StructuralRefactorPass.migrateFluidHolderConstructorArgumentsSource(
    source: String,
    fluidHolderFields: Set<String>,
    fluidHolderTupleFields: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("DeferredHolder") && fluidHolderFields.isEmpty() && fluidHolderTupleFields.isEmpty()) return source

    val localFluidHolderFields = Regex(
        """\b(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*Fluid\s*,[^;\r\n=]+>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    val localFluidHolderTupleFields = Regex(
        """\b(?:net\.minecraft\.util\.)?Tuple\s*<\s*(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*Fluid\s*,[^>]+>\s*,\s*(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*Fluid\s*,[^>]+>\s*>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()

    fun unwrapFluidHolderArgument(argument: String): String {
        val trimmed = argument.trim()
        if (trimmed.endsWith(".get()") || trimmed.endsWith(".value()")) return argument
        if (trimmed in localFluidHolderFields || trimmed in fluidHolderFields) {
            return "${argument.trimEnd()}.get()"
        }
        val tupleAccess = Regex("""(.+)\.(getA|getB)\(\)""").matchEntire(trimmed)
        if (tupleAccess != null) {
            val receiver = tupleAccess.groupValues[1].trim()
            if (receiver in localFluidHolderTupleFields || receiver in fluidHolderTupleFields) {
                return "${argument.trimEnd()}.get()"
            }
        }
        return argument
    }

    fun rewriteFirstFluidArgument(className: String, current: String): String =
        rewriteExecutableJavaNew(current, className) { args ->
            if (args.isEmpty()) return@rewriteExecutableJavaNew null
            val migratedFirst = unwrapFluidHolderArgument(args[0])
            if (migratedFirst == args[0]) return@rewriteExecutableJavaNew null
            val migratedArgs = args.toMutableList()
            migratedArgs[0] = migratedFirst
            "new $className(${migratedArgs.joinToString(", ") { it.trim() }})"
        }

    var result = source
    result = rewriteFirstFluidArgument("BucketItem", result)
    result = rewriteFirstFluidArgument("LiquidBlock", result)
    result = rewriteFirstFluidArgument("FluidStack", result)
    return result
}

internal fun StructuralRefactorPass.replaceMethodBody(source: String, methodName: String, replacement: String): String =
    replaceExecutableMethodBody(source, methodName, replacement)

internal fun StructuralRefactorPass.replaceExecutableMethodBody(source: String, methodName: String, replacement: String): String {
    val pattern = Regex("""(?m)(?:@\w+(?:\([^)]*\))?\s*)*(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\].?]+\s+${Regex.escape(methodName)}\s*\(""")
    val executableCode = maskJavaCommentsAndLiterals(source)
    val match = pattern.find(executableCode) ?: return source
    val openBrace = executableCode.indexOf('{', match.range.first)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(executableCode, openBrace)
    if (closeBrace < 0) return source
    return source.substring(0, match.range.first) + replacement + source.substring(closeBrace + 1)
}

internal fun StructuralRefactorPass.removeDeclaredMethod(source: String, methodName: String): String {
    val pattern = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+${Regex.escape(methodName)}\s*\("""
    )
    val executableCode = maskJavaCommentsAndLiterals(source)
    val match = pattern.find(executableCode) ?: return source
    val openBrace = executableCode.indexOf('{', match.range.last)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(executableCode, openBrace)
    if (closeBrace < 0) return source
    return source.substring(0, match.range.first).trimEnd() + "\n\n" + source.substring(closeBrace + 1).trimStart('\r', '\n')
}

internal fun StructuralRefactorPass.replaceNestedClass(source: String, className: String, replacement: String): String {
    val pattern = Regex("""public\s+static\s+class\s+${Regex.escape(className)}\b""")
    val executableCode = maskJavaCommentsAndLiterals(source)
    val match = pattern.find(executableCode) ?: return source
    val openBrace = executableCode.indexOf('{', match.range.first)
    if (openBrace < 0) return source
    val closeBrace = findMatchingBrace(executableCode, openBrace)
    if (closeBrace < 0) return source
    return source.substring(0, match.range.first) + replacement + source.substring(closeBrace + 1)
}

internal fun StructuralRefactorPass.insertBeforeLastClassBrace(source: String, insertion: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val closeBrace = executableCode.lastIndexOf('}')
    if (closeBrace < 0) return source
    return source.substring(0, closeBrace).trimEnd() + "\n" + insertion + "\n" + source.substring(closeBrace)
}

internal fun StructuralRefactorPass.removeImport(source: String, importName: String): String =
    removeExecutableImport(source, importName)

internal fun StructuralRefactorPass.packageNameOf(source: String): String =
    Regex("""(?m)^package\s+([\w.]+)\s*;""")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?: ""

internal fun StructuralRefactorPass.hasSimpleTypeReference(source: String, typeName: String): Boolean =
    Regex("""(?<![.\w$])${Regex.escape(typeName)}(?![\w$])""")
        .containsMatchIn(source)

internal data class JavaInheritanceType(
    val file: Path,
    val packageName: String,
    val className: String,
    val parentType: String?,
    val imports: Map<String, String>,
    val wildcardImports: Set<String>,
    val source: String
) {
    val fqn: String
        get() = if (packageName.isBlank()) className else "$packageName.$className"
}

internal fun StructuralRefactorPass.migrateEntityLevelAccessorCalls(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()

    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()
    val types = javaFiles.mapNotNull { file -> readJavaInheritanceType(file) }
    if (types.isEmpty()) return emptyList()

    val typeByFqn = types.associateBy { it.fqn }
    val entityMemo = mutableMapOf<String, Boolean>()

    fun resolveTypeReference(rawType: String, owner: JavaInheritanceType): String? {
        val normalized = normalizeJavaTypeReference(rawType) ?: return null
        if (normalized.contains(".")) {
            if (normalized.first().isLowerCase()) return normalized
            val firstSegment = normalized.substringBefore('.')
            owner.imports[firstSegment]?.let { imported ->
                return "$imported.${normalized.substringAfter('.')}"
            }
            val samePackage = if (owner.packageName.isBlank()) firstSegment else "${owner.packageName}.$firstSegment"
            if (samePackage in typeByFqn) {
                return "$samePackage.${normalized.substringAfter('.')}"
            }
            return normalized
        }

        owner.imports[normalized]?.let { return it }
        if (owner.packageName.isNotBlank()) {
            val samePackage = "${owner.packageName}.$normalized"
            if (samePackage in typeByFqn) return samePackage
        }
        owner.wildcardImports.forEach { pkg ->
            val candidate = "$pkg.$normalized"
            if (candidate in typeByFqn || pkg.startsWith("net.minecraft.world.entity")) {
                return candidate
            }
        }
        return normalized
    }

    fun isEntityDerivedFqn(typeName: String, seen: Set<String> = emptySet()): Boolean {
        val normalized = normalizeJavaTypeReference(typeName) ?: return false
        entityMemo[normalized]?.let { return it }
        if (normalized in seen) return false
        if (isMinecraftEntityClass(normalized)) {
            entityMemo[normalized] = true
            return true
        }

        val sourceType = typeByFqn[normalized]
        val parent = sourceType?.parentType
        if (sourceType == null || parent == null) {
            entityMemo[normalized] = false
            return false
        }
        val parentFqn = resolveTypeReference(parent, sourceType)
        val result = parentFqn != null && isEntityDerivedFqn(parentFqn, seen + normalized)
        entityMemo[normalized] = result
        return result
    }

    fun isEntityTypeReference(typeName: String, owner: JavaInheritanceType): Boolean {
        val fqn = resolveTypeReference(typeName, owner) ?: return false
        return isEntityDerivedFqn(fqn)
    }

    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val changes = mutableListOf<Change>()

    for (type in types) {
        val original = type.file.readText()
        if (!original.contains(".getLevel()") && !original.contains("this.getLevel()")) continue

        var migrated = original
        if (isEntityDerivedFqn(type.fqn) && !declaresGetLevelMethod(original)) {
            migrated = replaceExecutableRegex(
                migrated,
                Regex("""(?<![\w$])this\.getLevel\(\)""")
            ) { "this.level()" }
        }

        val entityVariables = collectEntityVariableNames(original, type, parser, ::isEntityTypeReference)
        if (entityVariables.isNotEmpty()) {
            migrated = replaceEntityVariableGetLevelCalls(migrated, entityVariables)
        }

        if (migrated != original) {
            if (!dryRun) {
                type.file.writeText(migrated)
            }
            changes.add(Change(
                file = type.file,
                line = 0,
                description = "Migrate Entity#getLevel() accessor to Entity#level() using source inheritance",
                before = "entity.getLevel()",
                after = "entity.level()",
                confidence = Confidence.HIGH,
                ruleId = "struct-entity-getlevel-accessor"
            ))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.readJavaInheritanceType(file: Path): JavaInheritanceType? {
    val source = file.readText()
    val executableCode = maskJavaCommentsAndLiterals(source)
    val className = javaTopLevelTypeName(executableCode) ?: return null
    val imports = linkedMapOf<String, String>()
    val wildcardImports = linkedSetOf<String>()
    Regex("""(?m)^[ \t]*import\s+(?!static\b)([\w.]+(?:\.\*)?)\s*;""")
        .findAll(executableCode)
        .forEach { match ->
            val importName = match.groupValues[1]
            if (importName.endsWith(".*")) {
                wildcardImports += importName.removeSuffix(".*")
            } else {
                imports[importName.substringAfterLast('.')] = importName
            }
        }
    val id = """[A-Za-z_$][\w$]*"""
    val parentType = Regex(
        """(?s)\bclass\s+${Regex.escape(className)}(?:\s*<[^>{};]+>)?\s+extends\s+($id(?:\.$id)*)"""
    ).find(executableCode)?.groupValues?.get(1)
    return JavaInheritanceType(
        file = file,
        packageName = packageNameOf(executableCode),
        className = className,
        parentType = parentType,
        imports = imports,
        wildcardImports = wildcardImports,
        source = source
    )
}

internal fun StructuralRefactorPass.normalizeJavaTypeReference(typeName: String): String? {
    var normalized = typeName.trim()
    if (normalized.isBlank()) return null
    normalized = Regex("""^@\w+(?:\([^)]*\))?\s+""").replace(normalized, "")
    normalized = normalized.removePrefix("final ").trim()
    normalized = Regex("""^\?\s+extends\s+""").replace(normalized, "")
    normalized = Regex("""^\?\s+super\s+""").replace(normalized, "")
    normalized = normalized.substringBefore('<').substringBefore('[').trim()
    return if (Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").matches(normalized)) {
        normalized
    } else {
        null
    }
}

internal fun StructuralRefactorPass.isMinecraftEntityClass(typeName: String): Boolean {
    if (!typeName.startsWith("net.minecraft.world.entity.")) return false
    val simpleName = typeName.substringAfterLast('.')
    val knownEntityClassNames = setOf(
        "Entity",
        "LivingEntity",
        "Mob",
        "PathfinderMob",
        "AgeableMob",
        "TamableAnimal",
        "Animal",
        "AbstractGolem",
        "Monster",
        "FlyingMob",
        "WaterAnimal",
        "AbstractFish",
        "AbstractSchoolingFish",
        "AbstractHorse",
        "AbstractChestedHorse",
        "Horse",
        "Llama",
        "TraderLlama",
        "SkeletonHorse",
        "ZombieHorse",
        "Player",
        "Projectile",
        "ThrowableProjectile",
        "ThrowableItemProjectile",
        "AbstractArrow",
        "Arrow",
        "SpectralArrow",
        "Fireball",
        "LargeFireball",
        "SmallFireball",
        "AbstractHurtingProjectile",
        "WitherSkull",
        "ItemEntity",
        "ExperienceOrb",
        "PrimedTnt",
        "FallingBlockEntity",
        "HangingEntity",
        "Painting",
        "ItemFrame",
        "GlowItemFrame",
        "ArmorStand",
        "LightningBolt",
        "EndCrystal",
        "AbstractVillager",
        "Villager",
        "WanderingTrader",
        "Raider",
        "EnderDragon"
    )
    if (simpleName in knownEntityClassNames) return true
    return false
}

internal fun StructuralRefactorPass.declaresGetLevelMethod(source: String): Boolean {
    val executableCode = maskJavaCommentsAndLiterals(source)
    return Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|synchronized|native)\s+)*(?:[A-Za-z_$][\w$]*(?:\s*<[^;{}()]*>)?(?:\s*\[\s*])?(?:\.[A-Za-z_$][\w$]*)*)\s+getLevel\s*\("""
    ).containsMatchIn(executableCode)
}

internal fun StructuralRefactorPass.collectEntityVariableNames(
    source: String,
    owner: JavaInheritanceType,
    parser: JavaParser,
    isEntityTypeReference: (String, JavaInheritanceType) -> Boolean
): Set<String> {
    val parseResult = parser.parse(source)
    if (!parseResult.isSuccessful) return emptySet()
    val cu = parseResult.result.orElse(null) ?: return emptySet()
    val declarations = linkedMapOf<String, MutableList<Boolean>>()

    fun record(name: String, typeName: String) {
        declarations.getOrPut(name) { mutableListOf() } += isEntityTypeReference(typeName, owner)
    }

    cu.findAll(Parameter::class.java).forEach { parameter ->
        record(parameter.nameAsString, parameter.type.asString())
    }
    cu.findAll(VariableDeclarator::class.java).forEach { variable ->
        record(variable.nameAsString, variable.type.asString())
    }

    val id = """[A-Za-z_$][\w$]*"""
    val executableCode = maskJavaCommentsAndLiterals(source)
    Regex("""\binstanceof\s+($id(?:\.$id)*)\s+($id)\b""")
        .findAll(executableCode)
        .forEach { match ->
            record(match.groupValues[2], match.groupValues[1])
        }

    return declarations
        .filterValues { markers -> markers.isNotEmpty() && markers.all { it } }
        .keys
}

internal fun StructuralRefactorPass.replaceEntityVariableGetLevelCalls(source: String, entityVariables: Set<String>): String {
    var result = source
    entityVariables
        .sortedByDescending { it.length }
        .forEach { variable ->
            result = replaceExecutableRegex(
                result,
                Regex("""(?<![\w$])${Regex.escape(variable)}\.getLevel\(\)""")
            ) { "$variable.level()" }
        }
    return result
}

internal fun StructuralRefactorPass.findMatchingBrace(source: String, openBrace: Int): Int {
    var depth = 0
    var i = openBrace
    while (i < source.length) {
        if (i + 2 < source.length && source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"') {
            i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length - 1 else it + 2 }
            i++
            continue
        }
        if (i + 1 < source.length && source[i] == '/' && source[i + 1] == '/') {
            i = source.indexOf('\n', i + 2).let { if (it < 0) source.length else it + 1 }
            continue
        }
        if (i + 1 < source.length && source[i] == '/' && source[i + 1] == '*') {
            i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
            continue
        }
        when (source[i]) {
            '"' -> i = skipJavaStringLiteral(source, i)
            '\'' -> i = skipJavaCharLiteral(source, i)
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

internal fun StructuralRefactorPass.migrateEventBusSubscriberStaticMethodsSource(source: String): String {
    val executableSource = maskJavaCommentsAndLiterals(source)
    if (!Regex("""@\s*(?:Mod\.)?EventBusSubscriber\b""").containsMatchIn(executableSource) ||
        !Regex("""@\s*SubscribeEvent\b""").containsMatchIn(executableSource)) return source
    var result = source
    var cursor = 0
    val subscriberAnnotation = Regex("""@(?:Mod\.)?EventBusSubscriber\b(?:\([^)]*\))?""")
    val classDeclaration = Regex("""\b(?:(?:public|protected|private|abstract|final|static)\s+)*(?:class|enum)\s+[A-Za-z_$][\w$]*\b""")

    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val annotation = subscriberAnnotation.find(executableResult, cursor) ?: break
        val declaration = classDeclaration.find(executableResult, annotation.range.last + 1) ?: break
        val openBrace = executableResult.indexOf('{', declaration.range.last)
        if (openBrace < 0) {
            cursor = declaration.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(result, openBrace)
        if (closeBrace < 0) {
            cursor = openBrace + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val migratedBody = makeSubscribeEventMethodsStatic(body)
        if (migratedBody != body) {
            result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
            cursor = openBrace + 1 + migratedBody.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.makeSubscribeEventMethodsStatic(body: String): String {
    val methodPattern = Regex(
        """(?m)^([ \t]*@SubscribeEvent(?:\([^)]*\))?[ \t]*(?:\r?\n[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?[ \t]*)*\r?\n)([ \t]*)((?:(?:public|protected|private|final|synchronized|static)\s+)*)(void\s+[A-Za-z_$][\w$]*\s*\()"""
    )
    return replaceExecutableRegex(body, methodPattern) { match ->
        fun originalGroup(index: Int): String =
            match.groups[index]?.range?.let { body.substring(it.first, it.last + 1) }.orEmpty()

        val modifiers = match.groupValues[3]
        if (Regex("""\bstatic\b""").containsMatchIn(modifiers)) {
            body.substring(match.range.first, match.range.last + 1)
        } else {
            "${originalGroup(1)}${originalGroup(2)}${originalGroup(3)}static ${originalGroup(4)}"
        }
    }
}

/**
 * Clean up @EventBusSubscriber annotations:
 * 1. Remove @EventBusSubscriber from classes without any @SubscribeEvent methods
 * 2. Remove the deprecated explicit game-bus parameter from @EventBusSubscriber
 */
internal fun StructuralRefactorPass.cleanupEventBusSubscriber(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            var text = javaFile.toFile().readText()
            val executableText = maskJavaCommentsAndLiterals(text)
            if (!executableText.contains("@EventBusSubscriber") &&
                !executableText.contains("@Mod.EventBusSubscriber")) return@forEach

            val original = text
            val hasSubscribeEvent = Regex("""@\s*SubscribeEvent\b""").containsMatchIn(executableText)

            if (!hasSubscribeEvent) {
                text = replaceExecutableRegex(
                    text,
                    Regex("""(?m)^[ \t]*@(?:Mod\.)?EventBusSubscriber\b(?:\([^)]*\))?\s*\r?\n""")
                ) { "" }
                text = removeExecutableImport(text, "net.neoforged.fml.common.EventBusSubscriber")
                text = removeExecutableImport(text, "net.neoforged.fml.common.Mod.EventBusSubscriber")
            } else {
                text = removeExecutableEventBusSubscriberForgeBusParameter(text)
                if (!Regex("""\bBus\s*\.""").containsMatchIn(maskJavaCommentsAndLiterals(text))) {
                    text = removeExecutableImport(text, "net.neoforged.fml.common.EventBusSubscriber.Bus")
                    text = removeExecutableImport(text, "net.neoforged.fml.common.Mod.EventBusSubscriber.Bus")
                }
            }

            if (text != original) {
                if (!dryRun) {
                    javaFile.toFile().writeText(text)
                }
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = if (!hasSubscribeEvent)
                        "Remove @EventBusSubscriber from class without @SubscribeEvent methods"
                    else
                        "Remove deprecated bus= parameter from @EventBusSubscriber",
                    before = "@EventBusSubscriber(..., bus = Bus.FORGE)",
                    after = if (!hasSubscribeEvent) "(removed)" else "@EventBusSubscriber(...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-cleanup-eventbus-subscriber"
                ))
            }
        }

    return changes
}

internal fun StructuralRefactorPass.removeExecutableEventBusSubscriberForgeBusParameter(source: String): String {
    var result = source
    executableEventBusSubscriberAnnotationRanges(source).asReversed().forEach { range ->
        val annotation = result.substring(range.first, range.last + 1)
        val busExpression = """(?:(?:Mod\.)?EventBusSubscriber\.)?Bus\.(?:FORGE|GAME)"""
        var migrated = replaceExecutableRegex(
            annotation,
            Regex("""bus\s*=\s*$busExpression\s*,\s*""")
        ) { "" }
        migrated = replaceExecutableRegex(
            migrated,
            Regex(""",\s*bus\s*=\s*$busExpression""")
        ) { "" }
        migrated = replaceExecutableRegex(
            migrated,
            Regex("""\(\s*bus\s*=\s*$busExpression\s*\)""")
        ) { "" }
        if (migrated != annotation) {
            result = result.replaceRange(range, migrated)
        }
    }
    return result
}

/**
 * Migrate ItemEntityPickupEvent.Pre API changes in NeoForge 1.21.1:
 * - event.setCanceled(true) -> event.setCanPickup(TriState.FALSE)
 * - event.getEntity() -> event.getPlayer() (within pickup event methods only)
 */
internal fun StructuralRefactorPass.migrateItemPickupEvent(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    data class PickupEventEdit(
        val range: IntRange,
        val replacement: String,
        val description: String,
        val ruleId: String
    )

    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()

    for (file in javaFiles) {
        val content = file.readText()
        val executableContent = maskJavaCommentsAndLiterals(content)
        if (!executableContent.contains("ItemEntityPickupEvent")) continue

        val imports = javaNonStaticImports(executableContent)
        val edits = mutableListOf<PickupEventEdit>()

        for (method in javaMethodRangesIncludingDefault(content)) {
            val eventParameters = javaMethodParameters(method.header)
                .filter { isItemEntityPickupPreType(it.type, imports) }
            if (eventParameters.isEmpty()) continue

            val executableMethod = executableContent.substring(method.range)
            for (parameter in eventParameters) {
                val receiver = Regex.escape(parameter.name)
                Regex("""\b$receiver\s*\.\s*setCanceled\s*\(\s*true\s*\)""")
                    .findAll(executableMethod)
                    .forEach { match ->
                        edits += PickupEventEdit(
                            range = (method.range.first + match.range.first)..(method.range.first + match.range.last),
                            replacement = "${parameter.name}.setCanPickup(TriState.FALSE)",
                            description = "ItemEntityPickupEvent: setCanceled(true) -> setCanPickup(TriState.FALSE)",
                            ruleId = "struct-pickup-event-setcanpickup"
                        )
                    }
                Regex("""\b$receiver\s*\.\s*getEntity\s*\(\s*\)""")
                    .findAll(executableMethod)
                    .forEach { match ->
                        edits += PickupEventEdit(
                            range = (method.range.first + match.range.first)..(method.range.first + match.range.last),
                            replacement = "${parameter.name}.getPlayer()",
                            description = "ItemEntityPickupEvent: getEntity() -> getPlayer()",
                            ruleId = "struct-pickup-event-getplayer"
                        )
                    }
            }
        }

        if (edits.isEmpty()) continue

        var migrated = content
        for (edit in edits.sortedByDescending { it.range.first }) {
            changes.add(Change(
                file = file,
                line = lineNumberAt(content, edit.range.first),
                description = edit.description,
                before = content.substring(edit.range).trim(),
                after = edit.replacement,
                confidence = Confidence.HIGH,
                ruleId = edit.ruleId
            ))
            migrated = migrated.replaceRange(edit.range, edit.replacement)
        }

        if (edits.any { it.ruleId == "struct-pickup-event-setcanpickup" } &&
            !maskJavaCommentsAndLiterals(migrated).contains("import net.neoforged.neoforge.common.util.TriState;")) {
            val withImport = addExecutableImportIfMissing(migrated, "net.neoforged.neoforge.common.util.TriState")
            if (withImport != migrated) {
                changes.add(Change(
                    file = file,
                    line = lineNumberAt(withImport, withImport.indexOf("import net.neoforged.neoforge.common.util.TriState;")),
                    description = "Add TriState import for ItemEntityPickupEvent migration",
                    before = "",
                    after = "import net.neoforged.neoforge.common.util.TriState;",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-pickup-event-tristate-import"
                ))
                migrated = withImport
            }
        }

        if (!dryRun) {
            file.writeText(migrated)
        }
    }

    return changes
}

internal fun StructuralRefactorPass.isItemEntityPickupPreType(rawType: String, imports: Map<String, String>): Boolean {
    val normalized = normalizeJavaTypeReference(rawType) ?: return false
    val resolved = if (normalized.contains(".")) {
        val firstSegment = normalized.substringBefore('.')
        imports[firstSegment]?.let { imported -> "$imported.${normalized.substringAfter('.')}" } ?: normalized
    } else {
        imports[normalized] ?: normalized
    }
    return resolved == "ItemEntityPickupEvent.Pre" ||
        resolved == "net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre"
}

/**
 * Rename entity lifecycle methods that were renamed in 1.21:
 * - onAddedToWorld() → onAddedToLevel()
 * - onRemovedFromWorld() → onRemovedFromLevel()
 * Preserves all original logic — only the method name changes.
 */
internal fun StructuralRefactorPass.removeObsoleteMethods(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val methodRenames = mapOf(
        "onAddedToWorld" to "onAddedToLevel",
        "onRemovedFromWorld" to "onRemovedFromLevel"
    )

    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()

    for (file in javaFiles) {
        var content = file.readText()
        var executableContent = maskJavaCommentsAndLiterals(content)
        if (methodRenames.keys.none { executableContent.contains(it) } && !executableContent.contains("getAddEntityPacket")) continue

        var modified = false
        for ((oldName, newName) in methodRenames) {
            executableContent = maskJavaCommentsAndLiterals(content)
            if (!executableContent.contains(oldName)) continue

            // Replace method declarations: void onAddedToWorld( → void onAddedToLevel(
            val declPattern = Regex("""(void\s+)${Regex.escape(oldName)}(\s*\()""")
            val beforeRename = content
            content = replaceExecutableRegex(content, declPattern) { match ->
                "${match.groupValues[1]}$newName${match.groupValues[2]}"
            }
            if (content != beforeRename) {
                // Also replace super.onAddedToWorld() → super.onAddedToLevel()
                content = replaceExecutableRegex(content, Regex("""\bsuper\.${Regex.escape(oldName)}\(""")) {
                    "super.$newName("
                }
                // Replace any this.onAddedToWorld() calls
                content = replaceExecutableRegex(content, Regex("""\bthis\.${Regex.escape(oldName)}\(""")) {
                    "this.$newName("
                }

                modified = true
                changes.add(Change(
                    file = file,
                    line = 0,
                    description = "Rename $oldName() → $newName() (renamed in 1.21)",
                    before = "void $oldName()",
                    after = "void $newName()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-rename-lifecycle-method"
                ))
            }
        }

        executableContent = maskJavaCommentsAndLiterals(content)
        if (executableContent.contains("getAddEntityPacket")) {
            val beforeRemoval = content
            content = removeMethodByName(content, "getAddEntityPacket")
            if (content != beforeRemoval) {
                if (!Regex("""\bPacket<""").containsMatchIn(maskJavaCommentsAndLiterals(removeImport(content, "net.minecraft.network.protocol.Packet")))) {
                    content = removeImport(content, "net.minecraft.network.protocol.Packet")
                }
                if (!Regex("""\bClientGamePacketListener\b""").containsMatchIn(maskJavaCommentsAndLiterals(removeImport(content, "net.minecraft.network.protocol.game.ClientGamePacketListener")))) {
                    content = removeImport(content, "net.minecraft.network.protocol.game.ClientGamePacketListener")
                }
                if (!Regex("""\bClientboundAddEntityPacket\b""").containsMatchIn(maskJavaCommentsAndLiterals(removeImport(content, "net.minecraft.network.protocol.game.ClientboundAddEntityPacket")))) {
                    content = removeImport(content, "net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                }
                content = removeImport(content, "net.neoforged.neoforge.network.NetworkHooks")
                content = Regex("""(?m)^[ \t]*//.*NetworkHooks removed[^\r\n]*\r?\n""").replace(content, "")
                modified = true
                changes.add(Change(
                    file = file,
                    line = 0,
                    description = "Remove legacy getAddEntityPacket() override removed from Entity in 1.21",
                    before = "getAddEntityPacket() override",
                    after = "default 1.21 entity spawning path",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-remove-legacy-entity-spawn-packet"
                ))
            }
        }

        if (modified && !dryRun) {
            file.writeText(content)
        }
    }

    return changes
}

/**
 * Add codec() override for concrete Block subclasses that still rely on
 * the now-abstract 1.21 Block codec surface.
 */
internal fun StructuralRefactorPass.addBaseEntityBlockCodec(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()

    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()
    data class JavaClassInfo(val parent: String, val isAbstract: Boolean)
    data class BlockCodecSignature(val bound: String, val visibility: String, val exactReturn: Boolean = false)
    val classParents = javaFiles.mapNotNull { candidate ->
        val source = candidate.readText()
        // type parameters may stand between the name and `extends` (`AbstractBellBlock<BE extends ...>`)
        val match = Regex("""(?m)\bpublic\s+(abstract\s+)?class\s+(\w+)\s*(?:<[^{]*?>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)""")
            .find(source)
            ?: return@mapNotNull null
        match.groupValues[2] to JavaClassInfo(
            parent = match.groupValues[3].substringAfterLast('.'),
            isAbstract = match.groupValues[1].isNotEmpty()
        )
    }.toMap()

    // what the class table cannot express: AbstractChestBlock's codec bound is generic in its
    // block entity, and BaseHorizonBlock is a library class (mmlib) outside the table
    fun knownCodecSignature(className: String): BlockCodecSignature? = when (className) {
        "AbstractChestBlock" -> BlockCodecSignature("net.minecraft.world.level.block.AbstractChestBlock", "protected")
        "BaseHorizonBlock" -> BlockCodecSignature("net.minecraft.world.level.block.HorizontalDirectionalBlock", "protected")
        else -> null
    }

    val concreteExactCodecParents = setOf(
        "LadderBlock",
        "MushroomBlock",
        "StandingSignBlock",
        "CeilingHangingSignBlock",
        "WallHangingSignBlock",
        "FlowerPotBlock",
        "WaterlilyBlock"
    )

    // the nearest codec() a 1.21.1 block class declares or inherits, read from the class table:
    // its access and whether it returns MapCodec<? extends Bound> or exactly MapCodec<Bound>
    fun platformCodecSignature(simpleParent: String): BlockCodecSignature? {
        if (simpleParent in classParents) return null
        val signatures = TargetMembers.classesNamed(simpleParent)
            .filter { TargetMembers.inheritsFrom(it, "net.minecraft.world.level.block.Block") }
            .map { owner ->
                val method = TargetMembers.inheritedMethod(owner, "codec", "()Lcom/mojang/serialization/MapCodec;") ?: return null
                val generic = Regex("""^\(\)Lcom/mojang/serialization/MapCodec<(\+?)L([\w/$]+);>;$""").find(method.signature) ?: return null
                if (method.access != "public" && method.access != "protected") return null
                BlockCodecSignature(generic.groupValues[2].replace('/', '.').replace('$', '.'), method.access, generic.groupValues[1].isEmpty())
            }
            .distinct()
        return signatures.singleOrNull()
    }

    fun resolveCodecSignature(parentClass: String, seen: Set<String> = emptySet()): BlockCodecSignature? {
        val simpleParent = parentClass.substringAfterLast('.')
        if (simpleParent in concreteExactCodecParents) return null
        platformCodecSignature(simpleParent)?.let { return it }
        knownCodecSignature(simpleParent)?.let { return it }
        if (simpleParent in seen) return null
        val parentInfo = classParents[simpleParent]
        if (parentInfo != null) {
            return resolveCodecSignature(parentInfo.parent, seen + simpleParent)
        }
        return if (simpleParent.endsWith("Block") && !simpleParent.endsWith("BlockItem")) {
            BlockCodecSignature("net.minecraft.world.level.block.Block", "protected")
        } else {
            null
        }
    }

    for (file in javaFiles) {
        val content = file.readText()
        if (!content.contains("extends ")) continue
        if (content.contains("MapCodec<")) continue // Already has codec

        // Extract class name
        val classMatch = Regex("""(?m)\bpublic\s+(?:final\s+)?(?!abstract\b)class\s+(\w+)\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)""")
            .find(content)
            ?: continue
        val className = classMatch.groupValues[1]
        val parentClass = classMatch.groupValues[2].substringAfterLast('.')
        val codecSignature = resolveCodecSignature(parentClass) ?: continue
        val hasSimpleCodecConstructor = Regex(
            """(?:public|protected|private)?\s*${Regex.escape(className)}\s*\(\s*(?:(?:net\.minecraft\.world\.level\.block\.state\.)?BlockBehaviour\.)?Properties\s+\w+\s*\)"""
        ).containsMatchIn(content)
        val noArgSuperConstructor = Regex(
            """(?s)(?:public|protected|private)?\s*${Regex.escape(className)}\s*\(\s*\)\s*\{\s*super\((.*?)\);\s*\}"""
        ).find(content)
        val registryConstructor = if (!hasSimpleCodecConstructor && noArgSuperConstructor == null) {
            findBlockRegistryHolderExpression(projectDir, className)
                ?: findBlockConstructorExpression(projectDir, className)
                    ?.takeIf { isSelfContainedUnitBlockConstructor(it, className) }
        } else {
            null
        }
        val recordCodecInitializer = if (!hasSimpleCodecConstructor && noArgSuperConstructor == null && registryConstructor == null) {
            buildCodecBackedBlockConstructorInitializer(content, className)
        } else {
            null
        }
        if (parentClass != "BaseEntityBlock" &&
            !hasSimpleCodecConstructor &&
            noArgSuperConstructor == null &&
            registryConstructor == null &&
            recordCodecInitializer == null) continue
        if (parentClass == "BaseEntityBlock" &&
            !hasSimpleCodecConstructor &&
            noArgSuperConstructor == null &&
            registryConstructor == null &&
            recordCodecInitializer == null) continue

        // Find the opening brace of the class
        val classStart = content.indexOf('{', classMatch.range.first)
        if (classStart < 0) continue

        val codecBound = if (parentClass == "AbstractChestBlock") {
            val blockEntityType = Regex("""extends\s+(?:net\.minecraft\.world\.level\.block\.)?AbstractChestBlock\s*<\s*([^>]+)\s*>""")
                .find(content)
                ?.groupValues
                ?.get(1)
                ?.trim()
            if (blockEntityType != null) {
                "net.minecraft.world.level.block.AbstractChestBlock<$blockEntityType>"
            } else {
                codecSignature.bound
            }
        } else {
            codecSignature.bound
        }
        val codecVisibility = codecSignature.visibility
        val codecFieldType = if (codecSignature.exactReturn) codecBound else className
        val codecReturnType = if (codecSignature.exactReturn) codecBound else "? extends $codecBound"
        val propertiesConstructor = if (!hasSimpleCodecConstructor && noArgSuperConstructor != null) {
            """

    public $className(net.minecraft.world.level.block.state.BlockBehaviour.Properties properties) {
        super(properties);
    }
"""
        } else {
            ""
        }
        val codecInitializer = if (hasSimpleCodecConstructor || noArgSuperConstructor != null) {
            BlockCodecInitializer("simpleCodec($className::new)", true)
        } else if (registryConstructor != null) {
            BlockCodecInitializer("com.mojang.serialization.MapCodec.unit(() -> $registryConstructor)", true)
        } else {
            recordCodecInitializer ?: continue
        }
            .let { initializer ->
                val expression = initializer.expression
                if (!codecSignature.exactReturn) {
                    initializer
                } else {
                    initializer.copy(expression = expression
                        .replace(Regex("""forGetter\(\s*o\s*->\s*o\.([A-Za-z_$][\w$]*)\s*\)""")) { match ->
                            "forGetter(block -> (($className) block).${match.groupValues[1]})"
                        }
                        .replace(
                            "$className::getType",
                            "block -> (($className) block).getType()"
                        ))
                }
            }
        val codecMethod = if (codecInitializer.staticField) {
            """
    public static final com.mojang.serialization.MapCodec<$codecFieldType> CODEC = ${codecInitializer.expression};

    @Override
    $codecVisibility com.mojang.serialization.MapCodec<$codecReturnType> codec() {
        return CODEC;
    }
"""
        } else {
            """
    @Override
    $codecVisibility com.mojang.serialization.MapCodec<$codecReturnType> codec() {
        return ${codecInitializer.expression};
    }
"""
        } + propertiesConstructor
        val newContent = content.substring(0, classStart + 1) + codecMethod + content.substring(classStart + 1)

        changes.add(Change(
            file = file,
            line = content.substring(0, classStart).count { it == '\n' } + 1,
            description = "Add codec() override for Block subclass ($className)",
            before = "class $className extends $parentClass",
            after = "class $className + CODEC field + codec() override",
            confidence = Confidence.HIGH,
            ruleId = "struct-base-entity-block-codec"
        ))

        if (!dryRun) {
            file.writeText(newContent)
        }
    }

    return changes
}

internal data class JavaConstructorParam(val type: String, val name: String)
internal data class BlockCodecInitializer(val expression: String, val staticField: Boolean)

internal fun StructuralRefactorPass.buildCodecBackedBlockConstructorInitializer(content: String, className: String): BlockCodecInitializer? {
    Regex("""(?m)(?:public|protected|private)?\s*${Regex.escape(className)}\s*\(""")
        .findAll(content)
        .forEach { constructorMatch ->
            val openParen = content.indexOf('(', constructorMatch.range.first)
            if (openParen < 0) return@forEach
            val closeParen = findMatchingParen(content, openParen)
            if (closeParen < 0) return@forEach
            val params = splitTopLevelJavaArgs(content.substring(openParen + 1, closeParen))
                .mapNotNull { parseJavaConstructorParam(it) }
            if (params.size != 2) return@forEach

            val propertyParam = params.singleOrNull { isBlockPropertiesType(it.type) } ?: return@forEach
            val codecParam = params.singleOrNull { !isBlockPropertiesType(it.type) } ?: return@forEach
            if (codecParam.type.contains("<")) return@forEach

            val openBrace = content.indexOf('{', closeParen)
            if (openBrace < 0) return@forEach
            val closeBrace = findMatchingBrace(content, openBrace)
            if (closeBrace < 0) return@forEach
            val body = content.substring(openBrace + 1, closeBrace)

            val assignedField = Regex("""this\.([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(codecParam.name)}\s*;""")
                .find(body)
                ?.groupValues
                ?.get(1)
            val fieldGetter = assignedField?.let { "o -> o.$it" }
                ?: codecConstructorGetter(className, codecParam)
                ?: return@forEach
            val fieldName = codecParam.name
            val constructorArgs = params.joinToString(", ") { param ->
                if (param.name == propertyParam.name) "properties" else codecParam.name
            }
            val codecExpression = codecExpressionForConstructorParam(codecParam, content)
            if (codecExpression != null) {
                return BlockCodecInitializer("""com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
            $codecExpression.fieldOf("$fieldName").forGetter($fieldGetter),
            propertiesCodec()
        ).apply(instance, (${codecParam.name}, properties) -> new $className($constructorArgs)))""", true)
            }
            val forwardsCodecParamToSuper = Regex(
                """\bsuper\s*\(\s*${Regex.escape(codecParam.name)}\s*,\s*${Regex.escape(propertyParam.name)}\s*\)"""
            ).containsMatchIn(body) || Regex(
                """\bsuper\s*\(\s*${Regex.escape(propertyParam.name)}\s*,\s*${Regex.escape(codecParam.name)}\s*\)"""
            ).containsMatchIn(body)
            val inheritedGetter = if (forwardsCodecParamToSuper && codecParam.name in setOf("type", "kind")) {
                "this.getType()"
            } else {
                null
            }
            if (assignedField != null || inheritedGetter != null) {
                val instanceConstructorArgs = params.joinToString(", ") { param ->
                    when (param.name) {
                        propertyParam.name -> "properties"
                        codecParam.name -> inheritedGetter ?: "this.$assignedField"
                        else -> param.name
                    }
                }
                return BlockCodecInitializer(
                    "simpleCodec(properties -> new $className($instanceConstructorArgs))",
                    false
                )
            }
        }
    return null
}

internal fun StructuralRefactorPass.parseJavaConstructorParam(raw: String): JavaConstructorParam? {
    val cleaned = raw
        .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
        .replace(Regex("""\bfinal\s+"""), "")
        .trim()
    val split = cleaned.lastIndexOf(' ')
    if (split <= 0 || split == cleaned.lastIndex) return null
    return JavaConstructorParam(
        type = cleaned.substring(0, split).trim(),
        name = cleaned.substring(split + 1).trim()
    )
}

internal fun StructuralRefactorPass.isBlockPropertiesType(type: String): Boolean =
    type == "Properties" ||
        type == "BlockBehaviour.Properties" ||
        type == "net.minecraft.world.level.block.state.BlockBehaviour.Properties"

internal fun StructuralRefactorPass.codecConstructorGetter(className: String, param: JavaConstructorParam): String? {
    return when {
        param.name == "type" -> "$className::getType"
        param.name == "kind" -> "$className::getType"
        else -> null
    }
}

internal fun StructuralRefactorPass.codecExpressionForConstructorParam(param: JavaConstructorParam, content: String): String? {
    val simpleType = param.type.substringAfterLast('.')
    if (Regex("""\b(?:com\.mojang\.serialization\.)?Codec\s*<\s*${Regex.escape(simpleType)}\s*>\s+CODEC\b""")
            .containsMatchIn(content)) {
        return "${param.type}.CODEC"
    }
    return when (simpleType) {
        "BlockSetType",
        "DyeColor",
        "Direction",
        "ResourceLocation",
        "WoodType" -> "${param.type}.CODEC"
        else -> null
    }
}

internal fun StructuralRefactorPass.isSelfContainedUnitBlockConstructor(expression: String, className: String): Boolean {
    val openParen = expression.indexOf('(')
    if (openParen < 0) return false
    val closeParen = findMatchingParen(expression, openParen)
    if (closeParen < 0) return false
    val args = splitTopLevelJavaArgs(expression.substring(openParen + 1, closeParen))
    return expression.startsWith("new $className(") && args.all(::isJavaLiteralConstructorArg)
}

internal fun StructuralRefactorPass.isJavaLiteralConstructorArg(arg: String): Boolean {
    val trimmed = arg.trim()
    return trimmed == "true" ||
        trimmed == "false" ||
        trimmed == "null" ||
        Regex("""[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?[fFdDlL]?""").matches(trimmed) ||
        Regex(""""(?:\\.|[^"\\])*"""").matches(trimmed) ||
        Regex("""'(?:\\.|[^'\\])'""").matches(trimmed)
}

internal fun StructuralRefactorPass.findBlockRegistryHolderExpression(projectDir: Path, className: String): String? {
    val registrationPattern = Regex(
        """(?s)public\s+static\s+final\s+[^;=]*<[^;=]*\b${Regex.escape(className)}\b[^;=]*>\s+([A-Z0-9_]+)\s*=\s*[^;]*new\s+${Regex.escape(className)}\s*\("""
    )
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()

    for (file in javaFiles) {
        val source = file.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        val match = registrationPattern.find(executableCode) ?: continue
        val holderName = match.groupValues[1]
        val packageName = packageNameOf(source)
        val typeBlocks = javaTypeBlocks(source, executableCode)
        val registryClassName = javaTypeBlockContainingOffset(match.range.first, typeBlocks)?.name ?: continue
        return listOf(packageName, registryClassName, holderName)
            .filter { it.isNotBlank() }
            .joinToString(".") + ".get()"
    }
    return null
}

internal fun StructuralRefactorPass.findBlockConstructorExpression(projectDir: Path, className: String): String? {
    val constructorPattern = Regex("""new\s+${Regex.escape(className)}\s*\(([^();\r\n{}]*)\)""")
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()

    for (file in javaFiles) {
        val source = file.readText()
        for (match in constructorPattern.findAll(source)) {
            val args = match.groupValues[1].trim()
            if (args.contains("BlockBehaviour.Properties") || args.contains(".Properties")) continue
            if (args.contains("->") || args.contains("{") || args.contains("}")) continue
            return "new $className($args)"
        }
    }
    return null
}

internal data class JavaSourceType(
    val file: Path,
    val packageName: String,
    val className: String,
    val source: String
) {
    val fqn: String
        get() = if (packageName.isBlank()) className else "$packageName.$className"
}

internal fun StructuralRefactorPass.collectJavaSourceTypes(javaFiles: List<Path>): List<JavaSourceType> =
    javaFiles.mapNotNull { file ->
        val source = file.readText()
        val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
        JavaSourceType(file, packageNameOf(source), className, source)
    }

internal data class NitrogenRecipeSerializerSource(
    val type: JavaSourceType,
    val recipeClassName: String
)

internal data class NitrogenRecipeFactoryReference(
    val ownerClassName: String,
    val ownerFqn: String,
    val fieldName: String,
    val recipeFactory: String
)

internal fun StructuralRefactorPass.migrateNitrogenRecipeBuilders(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.extension == "java" }
        .toList()

    fun readTypes(): List<JavaSourceType> =
        javaFiles.mapNotNull { file ->
            val source = file.readText()
            val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
            JavaSourceType(file, packageNameOf(source), className, source)
        }

    var types = readTypes()
    val typeByName = types.groupBy { it.className }
    val serializers = collectNitrogenRecipeSerializers(types)
    val serializerByName = serializers.associateBy { it.type.className }
    val registryFactories = collectNitrogenRecipeFactoryReferences(types, typeByName)
    val migratedBiomeBuilderClasses = linkedSetOf<String>()

    for (type in types) {
        val source = type.file.readText()
        if (!source.contains("extends BlockStateRecipeBuilder") ||
            !source.contains("BlockStateRecipeBuilder.Result") ||
            !source.contains("biomeKey") ||
            !source.contains("biomeTag") ||
            !source.contains("BlockStateIngredient") ||
            !source.contains("BlockPropertyPair")) {
            continue
        }

        val expectedSerializerName = type.className.removeSuffix("Builder") + "Serializer"
        val serializer = serializerByName[expectedSerializerName]
            ?: continue
        val recipeType = typeByName[serializer.recipeClassName]?.singleOrNull() ?: continue
        val migrated = nitrogenBiomeParameterRecipeBuilderSource(type, recipeType, serializer.type)
        if (migrated != source) {
            migratedBiomeBuilderClasses += type.className
            changes.add(Change(
                file = type.file,
                line = 0,
                description = "Migrate Nitrogen biome recipe builder to 1.21 RecipeBuilder factory shape",
                before = "extends BlockStateRecipeBuilder; Result extends BlockStateRecipeBuilder.Result",
                after = "implements RecipeBuilder; RecipeOutput.accept(id, recipe, null)",
                confidence = Confidence.HIGH,
                ruleId = "struct-nitrogen-recipe-builder-121"
            ))
            if (!dryRun) {
                type.file.writeText(migrated)
            }
        }
    }

    if (!dryRun) {
        types = readTypes()
    }
    val biomeBuilderClasses = types
        .filter { type ->
            val source = type.file.readText()
            source.contains("class ${type.className}") &&
                source.contains("implements RecipeBuilder") &&
                source.contains("Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome") &&
                source.contains("BiomeParameterRecipeSerializer.Factory<?>")
        }
        .map { it.className }
        .toSet() + migratedBiomeBuilderClasses

    if (registryFactories.isNotEmpty()) {
        val propertyMapParameterPositions = collectNitrogenPropertyMapParameterPositions(javaFiles.map { it.readText() })
        for (file in javaFiles) {
            val source = file.readText()
            val migrated = migrateNitrogenRecipeBuilderCallSitesSource(
                source,
                registryFactories,
                biomeBuilderClasses,
                propertyMapParameterPositions
            )
            if (migrated != source) {
                changes.add(Change(
                    file = file,
                    line = 0,
                    description = "Migrate Nitrogen recipe builder call sites from serializer holders to recipe factories",
                    before = "BlockStateRecipeBuilder.recipe(..., SERIALIZER.get())",
                    after = "BlockStateRecipeBuilder.recipe(..., RecipeClass::new)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-nitrogen-recipe-builder-121"
                ))
                if (!dryRun) {
                    file.writeText(migrated)
                }
            }
        }
    }

    return changes
}

internal fun StructuralRefactorPass.collectNitrogenRecipeSerializers(types: List<JavaSourceType>): List<NitrogenRecipeSerializerSource> {
    val id = """[A-Za-z_$][\w$]*"""
    val serializerPattern = Regex(
        """class\s+($id)\s*<\s*T\s+extends\s+($id)\s*>\s+extends\s+BlockStateRecipeSerializer\s*<\s*T\s*>"""
    )
    return types.mapNotNull { type ->
        val match = serializerPattern.find(type.source) ?: return@mapNotNull null
        if (!Regex("""\binterface\s+Factory\s*<\s*T\b""").containsMatchIn(type.source) ||
            !type.source.contains("Optional<Either<ResourceKey<Biome>, TagKey<Biome>>>")) {
            return@mapNotNull null
        }
        NitrogenRecipeSerializerSource(type, match.groupValues[2])
    }
}

internal fun StructuralRefactorPass.collectNitrogenRecipeFactoryReferences(
    types: List<JavaSourceType>,
    typeByName: Map<String, List<JavaSourceType>>
): List<NitrogenRecipeFactoryReference> {
    val id = """[A-Za-z_$][\w$]*"""
    val fieldPattern = Regex(
        """(?s)\bDeferredHolder\s*<[^;=]*?(?:BiomeParameterRecipeSerializer|BlockStateRecipeSerializer)\s*<\s*($id)\s*>[^;=]*>\s+($id)\s*=\s*[^;]+;"""
    )
    return types.flatMap { owner ->
        fieldPattern.findAll(owner.source).mapNotNull { match ->
            val recipeClass = match.groupValues[1]
            val fieldName = match.groupValues[2]
            val recipeType = typeByName[recipeClass]?.singleOrNull() ?: return@mapNotNull null
            NitrogenRecipeFactoryReference(
                ownerClassName = owner.className,
                ownerFqn = owner.fqn,
                fieldName = fieldName,
                recipeFactory = "${recipeType.fqn}::new"
            )
        }.toList()
    }
}

internal fun StructuralRefactorPass.nitrogenBiomeParameterRecipeBuilderSource(
    builder: JavaSourceType,
    recipeType: JavaSourceType,
    serializerType: JavaSourceType
): String {
    val recipeImport = if (recipeType.packageName == builder.packageName) "" else "import ${recipeType.fqn};\n"
    val serializerImport = if (serializerType.packageName == builder.packageName) "" else "import ${serializerType.fqn};\n"
    return """package ${builder.packageName};

${recipeImport}${serializerImport}import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.advancements.Criterion;
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;
import javax.annotation.Nullable;

public class ${builder.className} implements RecipeBuilder {
    private final Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome;
    private final BlockPropertyPair result;
    private final BlockStateIngredient ingredient;
    private Optional<ResourceLocation> function = Optional.empty();
    private final ${serializerType.className}.Factory<?> factory;

    public ${builder.className}(BlockPropertyPair result, BlockStateIngredient ingredient, Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, ${serializerType.className}.Factory<?> factory) {
        this.result = result;
        this.ingredient = ingredient;
        this.biome = biome;
        this.factory = factory;
    }

    public static ${builder.className} recipe(BlockStateIngredient ingredient, Block result, ResourceKey<Biome> biomeKey, ${serializerType.className}.Factory<?> factory) {
        return recipe(BlockPropertyPair.of(result, Optional.empty()), ingredient, Optional.of(Either.left(biomeKey)), factory);
    }

    public static ${builder.className} recipe(BlockStateIngredient ingredient, BlockPropertyPair resultPair, ResourceKey<Biome> biomeKey, ${serializerType.className}.Factory<?> factory) {
        return recipe(BlockPropertyPair.of(resultPair.block(), resultPair.properties()), ingredient, Optional.of(Either.left(biomeKey)), factory);
    }

    public static ${builder.className} recipe(BlockStateIngredient ingredient, Block resultBlock, Reference2ObjectArrayMap<Property<?>, Comparable<?>> resultProperties, ResourceKey<Biome> biomeKey, ${serializerType.className}.Factory<?> factory) {
        return recipe(BlockPropertyPair.of(resultBlock, Optional.ofNullable(resultProperties)), ingredient, Optional.of(Either.left(biomeKey)), factory);
    }

    public static ${builder.className} recipe(BlockStateIngredient ingredient, Block result, TagKey<Biome> biomeTag, ${serializerType.className}.Factory<?> factory) {
        return recipe(BlockPropertyPair.of(result, Optional.empty()), ingredient, Optional.of(Either.right(biomeTag)), factory);
    }

    public static ${builder.className} recipe(BlockStateIngredient ingredient, BlockPropertyPair resultPair, TagKey<Biome> biomeTag, ${serializerType.className}.Factory<?> factory) {
        return recipe(BlockPropertyPair.of(resultPair.block(), resultPair.properties()), ingredient, Optional.of(Either.right(biomeTag)), factory);
    }

    public static ${builder.className} recipe(BlockStateIngredient ingredient, Block resultBlock, Reference2ObjectArrayMap<Property<?>, Comparable<?>> resultProperties, TagKey<Biome> biomeTag, ${serializerType.className}.Factory<?> factory) {
        return recipe(BlockPropertyPair.of(resultBlock, Optional.of(resultProperties)), ingredient, Optional.of(Either.right(biomeTag)), factory);
    }

    public static ${builder.className} recipe(BlockPropertyPair result, BlockStateIngredient ingredient, Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, ${serializerType.className}.Factory<?> factory) {
        return new ${builder.className}(result, ingredient, biome, factory);
    }

    public RecipeBuilder function(Optional<ResourceLocation> function) {
        this.function = function;
        return this;
    }

    @Override
    public RecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
        return this;
    }

    @Override
    public RecipeBuilder group(@Nullable String groupName) {
        return this;
    }

    @Override
    public Item getResult() {
        return Items.AIR;
    }

    @Override
    public void save(RecipeOutput recipeOutput, ResourceLocation id) {
        ${recipeType.className} recipe = this.factory.create(this.biome, this.ingredient, this.result, this.function);
        recipeOutput.accept(id, recipe, null);
    }
}
"""
}

internal fun StructuralRefactorPass.migrateNitrogenRecipeBuilderCallSitesSource(
    source: String,
    registryFactories: List<NitrogenRecipeFactoryReference>,
    biomeBuilderClasses: Set<String>,
    projectPropertyMapParameterPositions: Map<String, Set<Int>> = emptyMap()
): String {
    val propertyMapParameterPositions = mergeNitrogenPropertyMapParameterPositions(
        projectPropertyMapParameterPositions,
        collectNitrogenPropertyMapParameterPositions(source)
    )
    val containsPropertyMapCall = propertyMapParameterPositions.keys.any { methodName ->
        Regex("""\b${Regex.escape(methodName)}\s*\(""").containsMatchIn(source)
    }
    if (!source.contains("RecipeBuilder.recipe(") &&
        !biomeBuilderClasses.any { source.contains("$it.recipe(") } &&
        !source.contains("BlockPropertyPair.of(") &&
        !source.contains(".pair(") &&
        !containsPropertyMapCall) {
        return source
    }

    val factoryByExpression = registryFactories.flatMap { reference ->
        listOf(
            "${reference.ownerClassName}.${reference.fieldName}.get()" to reference.recipeFactory,
            "${reference.ownerFqn}.${reference.fieldName}.get()" to reference.recipeFactory
        )
    }.toMap()

    val propertyVariables = Regex(
        """\b(?:Map|Reference2ObjectArrayMap)\s*<\s*Property\s*<\s*\?\s*>\s*,\s*Comparable\s*<\s*\?\s*>\s*>\s+([A-Za-z_$][\w$]*)"""
    ).findAll(source).map { it.groupValues[1] }.toSet()
    var result = source
    result = rewriteJavaCall(result, "recipe") { receiver, args ->
        if (args.isEmpty()) return@rewriteJavaCall null
        val simpleReceiver = receiver.substringAfterLast('.')
        val factory = factoryByExpression[args.last().trim()] ?: return@rewriteJavaCall null
        val migratedArgs = args.dropLast(1).map { it.trim() } + factory
        when {
            simpleReceiver == "BlockStateRecipeBuilder" ->
                "$receiver.recipe(${migratedArgs.joinToString(", ")})"
            simpleReceiver in biomeBuilderClasses && args.size == 3 ->
                "BlockStateRecipeBuilder.recipe(${migratedArgs.joinToString(", ")})"
            simpleReceiver in biomeBuilderClasses ->
                "$receiver.recipe(${migratedArgs.joinToString(", ")})"
            else -> null
        }
    }

    for (builderClass in biomeBuilderClasses) {
        result = Regex(
            """(?s)\b((?:public|protected|private)\s+)BlockStateRecipeBuilder(\s+[A-Za-z_$][\w$]*\s*\([^)]*\)\s*\{\s*return\s+(?:[A-Za-z_$][\w$]*\.)?${Regex.escape(builderClass)}\.recipe\s*\()"""
        ).replace(result) { match -> "${match.groupValues[1]}$builderClass${match.groupValues[2]}" }
    }

    result = result.replace(
        Regex("""\bMap\s*<\s*Property\s*<\s*\?\s*>\s*,\s*Comparable\s*<\s*\?\s*>\s*>"""),
        "Reference2ObjectArrayMap<Property<?>, Comparable<?>>"
    )

    propertyMapParameterPositions.forEach { (methodName, positions) ->
        result = rewriteJavaInvocationArguments(result, methodName) { args ->
            var changed = false
            val migrated = args.toMutableList()
            positions.forEach { index ->
                val arg = migrated.getOrNull(index) ?: return@forEach
                val propertyExpression = nitrogenPropertyMapDirectExpression(arg, propertyVariables) ?: return@forEach
                migrated[index] = propertyExpression
                changed = true
            }
            if (changed) migrated else null
        }
    }

    result = rewriteJavaCall(result, "pair") { receiver, args ->
        if (receiver != "this" || args.size != 2) return@rewriteJavaCall null
        val propertyExpression = nitrogenPropertyMapOptionalExpression(args[1], propertyVariables) ?: return@rewriteJavaCall null
        "this.pair(${args[0].trim()}, $propertyExpression)"
    }

    result = rewriteJavaCall(result, "of") { receiver, args ->
        if (receiver.substringAfterLast('.') != "BlockPropertyPair" || args.size != 2) return@rewriteJavaCall null
        val propertyExpression = nitrogenPropertyMapOptionalExpression(args[1], propertyVariables) ?: return@rewriteJavaCall null
        "$receiver.of(${args[0].trim()}, $propertyExpression)"
    }

    if (result.contains("Reference2ObjectArrayMap<") || result.contains("new Reference2ObjectArrayMap<>")) {
        result = addImportIfMissing(result, "it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap")
    }
    if (result.contains("Optional.")) {
        result = addImportIfMissing(result, "java.util.Optional")
    }
    if (result.contains("new Property<?>[]{")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.block.state.properties.Property")
    }
    if (!Regex("""\bMap\s*<|\bMap\.""").containsMatchIn(result)) {
        result = removeImport(result, "java.util.Map")
    }
    return result
}

internal fun StructuralRefactorPass.collectNitrogenPropertyMapParameterPositions(source: String): Map<String, Set<Int>> {
    val positionsByMethod = linkedMapOf<String, MutableSet<Int>>()
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:public|protected|private)\s+([\w<>\[\].?,\s]+?)\s+([A-Za-z_$][\w$]*)\s*\("""
    )
    methodPattern.findAll(source).forEach { match ->
        val openParen = source.indexOf('(', match.range.last - 1)
        if (openParen < 0) return@forEach
        val closeParen = findMatchingParen(source, openParen)
        if (closeParen < 0) return@forEach
        val returnType = match.groupValues[1].trim()
        val methodName = match.groupValues[2]
        if (returnType.substringAfterLast('.').contains("BlockPropertyPair")) return@forEach
        val params = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        params.forEachIndexed { index, param ->
            if (Regex("""\b(?:Map|Reference2ObjectArrayMap)\s*<\s*Property\s*<\s*\?\s*>\s*,\s*Comparable\s*<\s*\?\s*>\s*>""").containsMatchIn(param)) {
                positionsByMethod.getOrPut(methodName) { linkedSetOf() }.add(index)
            }
        }
    }
    return positionsByMethod.mapValues { it.value.toSet() }
}

internal fun StructuralRefactorPass.collectNitrogenPropertyMapParameterPositions(sources: Iterable<String>): Map<String, Set<Int>> {
    val positionsByMethod = linkedMapOf<String, MutableSet<Int>>()
    sources.forEach { source ->
        collectNitrogenPropertyMapParameterPositions(source).forEach { (methodName, positions) ->
            positionsByMethod.getOrPut(methodName) { linkedSetOf() }.addAll(positions)
        }
    }
    return positionsByMethod.mapValues { it.value.toSet() }
}

internal fun StructuralRefactorPass.mergeNitrogenPropertyMapParameterPositions(
    first: Map<String, Set<Int>>,
    second: Map<String, Set<Int>>
): Map<String, Set<Int>> {
    if (first.isEmpty()) return second
    if (second.isEmpty()) return first
    val merged = linkedMapOf<String, MutableSet<Int>>()
    listOf(first, second).forEach { map ->
        map.forEach { (methodName, positions) ->
            merged.getOrPut(methodName) { linkedSetOf() }.addAll(positions)
        }
    }
    return merged.mapValues { it.value.toSet() }
}

internal fun StructuralRefactorPass.nitrogenPropertyMapOptionalExpression(expression: String, propertyVariables: Set<String>): String? {
    val trimmed = expression.trim()
    if (trimmed in propertyVariables) return "Optional.of($trimmed)"
    val mapCall = Regex("""Map\.of\s*\((.*)\)\s*""", RegexOption.DOT_MATCHES_ALL).matchEntire(trimmed) ?: return null
    val args = splitTopLevelJavaArgs(mapCall.groupValues[1]).map { it.trim() }.filter { it.isNotEmpty() }
    if (args.isEmpty()) return "Optional.empty()"
    if (args.size % 2 != 0) return null
    val keys = args.filterIndexed { index, _ -> index % 2 == 0 }.joinToString(", ")
    val values = args.filterIndexed { index, _ -> index % 2 == 1 }.joinToString(", ")
    return "Optional.of(new Reference2ObjectArrayMap<>(new Property<?>[]{$keys}, new Comparable<?>[]{$values}))"
}

internal fun StructuralRefactorPass.nitrogenPropertyMapDirectExpression(expression: String, propertyVariables: Set<String>): String? {
    val trimmed = expression.trim()
    if (trimmed in propertyVariables) return trimmed
    val optionalExpression = nitrogenPropertyMapOptionalExpression(trimmed, propertyVariables) ?: return null
    if (optionalExpression == "Optional.empty()") return "new Reference2ObjectArrayMap<>()"
    return optionalExpression.removePrefix("Optional.of(").removeSuffix(")")
}

internal fun StructuralRefactorPass.migrateLegacyRecipeOutputBuilders(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val javaFiles = Files.walk(projectDir)
        .filter { it.extension == "java" }
        .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
            rel.startsWith("build/") || rel.contains("/build/")
        }}
        .toList()
    val recipeConstructors = collectRecipeConstructors(javaFiles)
    val recipeSerializerFactoryHints = collectRecipeSerializerFactoryHints(projectDir)
    val recipeBuilderFactoryInterfaces = collectRecipeBuilderFactoryInterfaceHints(
        javaFiles,
        recipeSerializerFactoryHints
    )

    for (file in javaFiles) {
        val original = file.readText()
        var content = migrateRecipeSerializerFactoryCallSites(original, recipeSerializerFactoryHints)
        content = migrateGenericCookingRecipeOutputBuilderSource(
            content,
            recipeSerializerFactoryHints,
            recipeBuilderFactoryInterfaces
        )
        if (content != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate serializer-backed cooking builders to explicit recipe factories",
                before = "builder methods keep only a RecipeSerializer and emit Result adapters",
                after = "builder methods accept a serializer-derived factory and emit RecipeOutput recipe instances",
                confidence = Confidence.HIGH,
                ruleId = "struct-recipe-builder-factory-output"
            ))
            if (!dryRun) {
                file.writeText(content)
            }
        }
    }

    val acceptPattern = Regex(
        """(?s)([ \t]*)consumer\.accept\(\s*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.Result)\((.*?)\)\s*\);"""
    )
    for (file in javaFiles) {
        var content = file.readText()
        if (!content.contains("class Result") || !content.contains("RecipeOutput")) continue
        val original = content
        content = migrateConstructableRecipeOutputResultAdapters(content, recipeConstructors)
        if (content != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate constructable legacy Result adapters to RecipeOutput recipe instances",
                before = "output.accept(new Builder.Result(...)); class Result implements RecipeOutput",
                after = "output.accept(id, new Recipe(...), advancement); Result adapter removed",
                confidence = Confidence.HIGH,
                ruleId = "struct-legacy-recipe-output-result-instance"
            ))
            if (!dryRun) {
                file.writeText(content)
            }
            continue
        }
        val resultClassText = legacyRecipeOutputResultClassText(content) ?: continue
        if (!Regex("""\bprivate\s+final\s+[A-Za-z_$][\w$]*(?:\s*<[^;]+>)?\s+recipe\s*(?:=|;)""").containsMatchIn(resultClassText)) {
            continue
        }
        content = acceptPattern.replace(content) { match ->
            val indent = match.groupValues[1]
            val resultType = match.groupValues[2]
            val args = match.groupValues[3]
            "${indent}$resultType result = new $resultType($args);\n${indent}consumer.accept(result.getId(), result.recipe, null);"
        }
        content = content.replace("class Result implements RecipeOutput", "class Result")
        content = Regex("""(?m)^[ \t]*@Override\r?\n""").replace(content, "")

        if (content != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate FinishedRecipe-style Result adapter to RecipeOutput.accept(id, recipe, advancement)",
                before = "consumer.accept(new Builder.Result(...)); class Result implements RecipeOutput",
                after = "consumer.accept(result.getId(), result.recipe, null); class Result",
                confidence = Confidence.HIGH,
                ruleId = "struct-legacy-recipe-output-builder"
            ))
            if (!dryRun) {
                file.writeText(content)
            }
        }
    }

    changes.addAll(removeUnusedLegacyRecipeOutputResultClasses(javaFiles, dryRun))
    return changes
}

internal data class RecipeConstructorSignature(
    val className: String,
    val params: List<JavaParameterInfo>,
    val defaultsByParam: Map<String, String> = emptyMap()
)

internal fun StructuralRefactorPass.collectRecipeConstructors(javaFiles: List<Path>): Map<String, List<RecipeConstructorSignature>> {
    val constructors = linkedMapOf<String, MutableList<RecipeConstructorSignature>>()
    javaFiles.forEach { file ->
        val source = file.readText()
        val className = javaTopLevelTypeName(source) ?: return@forEach
        if (!className.endsWith("Recipe")) return@forEach
        val constructorDefaults = recipeConstructorDefaults(source)
        Regex("""(?m)^[ \t]*public\s+${Regex.escape(className)}\s*\(([^()]*)\)""")
            .findAll(source)
            .forEach { match ->
                val params = parseJavaParameters(match.groupValues[1])
                constructors.getOrPut(className) { mutableListOf() }
                    .add(RecipeConstructorSignature(className, params, constructorDefaults.filterKeys { key ->
                        params.any { it.name == key }
                    }))
            }
    }
    return constructors
}

internal fun StructuralRefactorPass.recipeConstructorDefaults(source: String): Map<String, String> {
    val imports = javaImportsBySimpleName(source)
    val packageName = packageNameOf(source)
    val defaults = linkedMapOf<String, String>()
    Regex("""\.fieldOf\(\s*"([^"]+)"\s*\)\.orElse\(\s*([^)\r\n]+)\s*\)""")
        .findAll(source)
        .forEach { match ->
            defaults[match.groupValues[1]] = qualifyJavaStaticExpression(
                match.groupValues[2].trim(),
                imports,
                packageName
            )
        }
    return defaults
}

internal fun StructuralRefactorPass.javaImportsBySimpleName(source: String): Map<String, String> =
    Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
        .findAll(source)
        .associate { match -> match.groupValues[1].substringAfterLast('.') to match.groupValues[1] }

internal fun StructuralRefactorPass.qualifyJavaStaticExpression(
    expression: String,
    imports: Map<String, String>,
    packageName: String?
): String {
    val match = Regex("""^([A-Za-z_$][\w$]*)\.(.+)$""").find(expression) ?: return expression
    val owner = match.groupValues[1]
    val member = match.groupValues[2]
    val qualifiedOwner = imports[owner] ?: packageName?.let { "$it.$owner" } ?: return expression
    return "$qualifiedOwner.$member"
}

internal fun StructuralRefactorPass.migrateRecipeSerializerFactoryCallSites(
    source: String,
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (recipeSerializerFactoryHints.fieldToFactory.isEmpty() || !executableCode.contains(".generic(")) return source
    var changed = false
    val result = rewriteExecutableJavaCall(source, "generic") { receiver, args ->
        if (receiver.isBlank() || receiver == "SimpleCookingRecipeBuilder" || args.size < 2) {
            return@rewriteExecutableJavaCall null
        }
        val serializerExpression = normalizeRecipeSerializerExpression(args.last())
        val factory = recipeSerializerFactoryHints.fieldToFactory[serializerExpression] ?: return@rewriteExecutableJavaCall null
        changed = true
        "$receiver.generic(${args.joinToString(", ") { it.trim() }}, $factory)"
    }
    return if (changed) result else source
}

internal fun StructuralRefactorPass.migrateGenericCookingRecipeOutputBuilderSource(
    source: String,
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints,
    recipeBuilderFactoryInterfaces: Map<String, Set<RecipeFactoryInterfaceHint>>
): String {
    if (!source.contains("implements RecipeBuilder") ||
        !source.contains("RecipeSerializer<? extends AbstractCookingRecipe>") ||
        !source.contains("class Result implements RecipeOutput") ||
        !source.contains(".accept(new ")) {
        return source
    }
    val className = javaTopLevelTypeName(source) ?: return source
    val serializerField = Regex(
        """private\s+final\s+RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+([A-Za-z_$][\w$]*)\s*;"""
    ).find(source)?.groupValues?.get(1) ?: return source
    val resultClassText = legacyRecipeOutputResultClassText(source) ?: return source
    val resultConstructor = Regex("""public\s+Result\s*\(([^()]*)\)""")
        .find(resultClassText)
        ?: return source
    val resultParams = parseJavaParameters(resultConstructor.groupValues[1])
    val factoryHint = referencedRecipeFactoryInterfaces(source, recipeSerializerFactoryHints).singleOrNull()
        ?: recipeBuilderFactoryInterfaces[className]?.singleOrNull()
        ?: return source
    val resultParamTypes = resultParams.associate { it.name to it.type }
    val outputRecipeArgs = factoryHint.params.map { param ->
        val resultParam = resultParams.firstOrNull { it.name == param.name } ?: return source
        val sourceType = resultParamTypes[resultParam.name].orEmpty()
        RecipeFactoryArgument(resultParam.name, migrateRecipeOutputResultArgument(resultParam.name, sourceType, param.type))
    }

    var result = source
    result = Regex(
        """private\s+final\s+RecipeSerializer\s*<\s*\?\s+extends\s+AbstractCookingRecipe\s*>\s+${Regex.escape(serializerField)}\s*;"""
    ).replace(result, "private final ${factoryHint.type} factory;")

    result = addFactoryParameterToBuilderConstructor(result, className, serializerField, factoryHint.type)
    result = addFactoryParameterToStaticBuilderMethods(result, className, serializerField, factoryHint.type)
    result = result.replace("this.$serializerField = $serializerField;", "this.factory = ${serializerField}Factory;")

    val acceptPattern = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.accept\(\s*new\s+(?:${Regex.escape(className)}\.)?Result\s*\(""")
    var cursor = 0
    var changed = false
    while (true) {
        val match = acceptPattern.find(result, cursor) ?: break
        val openParen = result.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(result, openParen) else -1
        if (openParen < 0 || closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val acceptClose = nextNonWhitespaceIndex(result, closeParen + 1)
        if (acceptClose < 0 || result[acceptClose] != ')') {
            cursor = closeParen + 1
            continue
        }
        val semicolon = nextNonWhitespaceIndex(result, acceptClose + 1)
        if (semicolon < 0 || result[semicolon] != ';') {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen)).map { it.trim() }
        if (args.size != resultParams.size) {
            cursor = closeParen + 1
            continue
        }
        val argsByName = resultParams.mapIndexed { index, param -> param.name to args[index] }.toMap()
        val idExpression = argsByName["id"] ?: argsByName["recipeId"]
        if (idExpression == null) {
            cursor = closeParen + 1
            continue
        }
        val advancement = argsByName["advancement"]
        val advancementId = argsByName["advancementId"]
        val advancementExpression = if (advancement != null && advancementId != null) {
            "$advancement.build($advancementId)"
        } else {
            "null"
        }
        val factoryArgs = outputRecipeArgs.joinToString(", ") { argument ->
            val expression = argsByName[argument.sourceName] ?: return@joinToString argument.expression
            Regex("""\b${Regex.escape(argument.sourceName)}\b""").replace(argument.expression, expression)
        }
        val indent = match.groupValues[1]
        val output = match.groupValues[2]
        val replacement = "${indent}AbstractCookingRecipe recipe = this.factory.create($factoryArgs);\n" +
            "${indent}$output.accept($idExpression, recipe, $advancementExpression);"
        result = result.substring(0, match.range.first) + replacement + result.substring(semicolon + 1)
        cursor = match.range.first + replacement.length
        changed = true
    }
    if (!changed) return source
    result = removeLegacyResultClass(result)
    var withoutJsonObjectImport = removeImport(result, "com.google.gson.JsonObject")
    if (!Regex("""\bJsonObject\b""").containsMatchIn(withoutJsonObjectImport)) {
        result = withoutJsonObjectImport
    }
    var withoutJsonOpsImport = removeImport(result, "com.mojang.serialization.JsonOps")
    if (!Regex("""\bJsonOps\b""").containsMatchIn(withoutJsonOpsImport)) {
        result = withoutJsonOpsImport
    }
    result = removeImport(result, "java.util.function.Consumer")
    if (result.contains("new ItemStack(")) {
        result = addImportIfMissing(result, "net.minecraft.world.item.ItemStack")
    }
    return result
}

internal data class RecipeFactoryArgument(
    val sourceName: String,
    val expression: String
)

internal fun StructuralRefactorPass.referencedRecipeFactoryInterfaces(
    source: String,
    recipeSerializerFactoryHints: RecipeSerializerFactoryHints
): Set<RecipeFactoryInterfaceHint> =
    Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\.get\s*\(\s*\)""")
        .findAll(source)
        .mapNotNull { match ->
            recipeSerializerFactoryHints.fieldToFactoryInterface[match.groupValues[1]]
        }
        .toSet()
