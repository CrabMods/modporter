package com.modporter.core.transforms.structural

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
 * 1.20.5 replaced `RecipeSerializer#fromJson(id, json)` / `fromNetwork(id, buf)` / `toNetwork(buf, r)`
 * by `MapCodec<T> codec()` and `StreamCodec<RegistryFriendlyByteBuf, T> streamCodec()`, and recipes
 * stopped being told their id (only the `RecipeHolder` carries it).
 *
 * A serializer the structured rules could not turn into a record codec keeps its own JSON and
 * network code: its codec reads the recipe's map as the `JsonObject` its `fromJson` always read (and
 * writes through its `(JsonObject, T)` writer when it has one), its stream codec wraps
 * `toNetwork`/`fromNetwork`, so files and packets keep their format. A recipe that keeps its own id
 * gets the loaded holder's id as soon as the recipes are loaded (server start, reload, client sync);
 * until then it carries `LegacyRecipeCodecs.PENDING_ID`.
 */
internal object LegacyRecipeSerializerCodecs {
    const val RULE_ID = "struct-legacy-recipe-serializer-codecs"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val serializerHeader = Regex("""\bclass\s+($ID)\s*(?:<[^{]*?>)?[^{;]*\bimplements\s+[^{;]*?\bRecipeSerializer\s*<\s*([^{;]+?)\s*>[^{;]*\{""")
    private val fromJson = Regex("""\b(?:public\s+)?(?:final\s+)?[\w.<>?, ]+?\s+fromJson\s*\(\s*(?:final\s+)?ResourceLocation\s+$ID\s*,\s*(?:final\s+)?(?:com\.google\.gson\.)?JsonObject\s+$ID\s*\)""")
    private val fromNetwork = Regex("""\bfromNetwork\s*\(\s*(?:final\s+)?ResourceLocation\s+$ID\s*,\s*(?:final\s+)?(?:Registry)?FriendlyByteBuf\s+$ID\s*\)""")
    private val toNetwork = Regex("""\bvoid\s+toNetwork\s*\(\s*(?:final\s+)?(?:Registry)?FriendlyByteBuf\s+$ID\s*,""")

    fun migrate(projectDir: Path, modId: () -> String?, compatPackageSupplier: () -> String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val originalSources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val sources = originalSources.toMutableMap()
        val hasCandidate = sources.values.any { source ->
            legacySerializer(JavaSourceText.maskCommentsAndLiterals(source)) != null ||
                source.contains("extends ShapedRecipe") &&
                source.contains("extends ShapedRecipe.Serializer") &&
                source.contains("fromShaped(")
        }
        if (!hasCandidate) return emptyList()
        val compatPackage = compatPackageSupplier()
        val changes = mutableListOf<Change>()
        val gatedCraftingContainers = linkedSetOf<String>()

        for ((file, source) in sources.toMap()) {
            val migrated = migrateShapedRecipeSubclass(source, compatPackage) ?: continue
            sources[file] = migrated.source
            migrated.gatedContainer?.let(gatedCraftingContainers::add)
            changes += Change(file, 1, "Shaped recipe subclass keeps its pattern, matching hook, and serializer payload",
                "legacy ShapedRecipe constructor and Serializer hooks", "ShapedRecipePattern plus codec()/streamCodec()", Confidence.HIGH, RULE_ID)
        }
        if (gatedCraftingContainers.isNotEmpty()) {
            val recipeInputMethods = collectRecipeInputMethods(sources)
            for ((file, source) in sources.toMap()) {
                val migrated = migrateGatedCraftingInputCalls(
                    source,
                    gatedCraftingContainers,
                    recipeInputMethods,
                    compatPackage
                ) ?: continue
                sources[file] = migrated
                changes += Change(file, 1, "Crafting input conversion preserves the source container type used by recipe matching",
                    "container.asCraftInput()", "LegacyCraftingInputs.from(container)", Confidence.HIGH, RULE_ID)
            }
        }

        val serializers = sources.filterValues { source -> legacySerializer(JavaSourceText.maskCommentsAndLiterals(source)) != null }

        for ((file, source) in serializers) {
            val migrated = migrateSerializer(source, compatPackage) ?: continue
            sources[file] = migrated
            changes += Change(file, 1, "Recipe serializer keeps its JSON and network code behind codec()/streamCodec()",
                "fromJson/fromNetwork/toNetwork", "LegacyRecipeCodecs.codec/streamCodec", Confidence.HIGH, RULE_ID)
        }
        // recipes that keep their own id learn it from their holder
        if (serializers.isNotEmpty()) {
            for ((file, source) in sources.toMap()) {
                if (file in serializers) continue
                val migrated = identifiedRecipe(source, compatPackage) ?: continue
                sources[file] = migrated
                changes += Change(file, 1, "Recipe keeping its own id receives the loaded holder's id",
                    "ResourceLocation id set from the serializer", "LegacyRecipeCodecs.Identified", Confidence.HIGH, RULE_ID)
            }
        }
        if (changes.isEmpty()) return emptyList()
        if (!dryRun) {
            for ((file, source) in sources) {
                if (source != originalSources[file]) file.writeText(source)
            }
        }
        changes += compatSources(
            srcDir,
            compatPackage,
            modId(),
            serializers.isNotEmpty(),
            gatedCraftingContainers.isNotEmpty(),
            dryRun
        )
        return changes
    }

    private data class ShapedRecipeMigration(val source: String, val gatedContainer: String?)
    private data class RecipeInputMethod(
        val ownerName: String,
        val ownerQName: String,
        val entries: Set<String>,
        val name: String,
        val arity: Int,
        val inputPositions: Set<Int>
    )

    /**
     * A shaped-recipe subclass that extended the old vanilla serializer keeps both parts of its
     * format. The base shaped recipe is handled by vanilla's codec and stream codec; the subclass's
     * boolean remains an adjacent field/packet value. A legacy container type check is kept through
     * [LegacyCraftingInputs], because CraftingContainer.asCraftInput() otherwise erases its origin.
     */
    private fun migrateShapedRecipeSubclass(source: String, compatPackage: String): ShapedRecipeMigration? {
        if (!source.contains("extends ShapedRecipe") ||
            !source.contains("extends ShapedRecipe.Serializer") ||
            !source.contains("fromShaped(") ||
            !source.contains("fromJson(") ||
            !source.contains("fromNetwork(") ||
            !source.contains("toNetwork(")) return null
        var result = source
        var masked = JavaSourceText.maskCommentsAndLiterals(result)
        val outer = Regex("""\bclass\s+($ID)\s+extends\s+ShapedRecipe\s*\{""").find(masked) ?: return null
        val className = outer.groupValues[1]
        val outerClose = JavaSourceText.matchingBrace(masked, outer.range.last)
        if (outerClose < 0) return null

        val constructor = Regex(
            """(?m)^([ \t]*)(public|protected)\s+${Regex.escape(className)}\s*\(\s*(?:final\s+)?ResourceLocation\s+($ID)\s*,\s*(?:final\s+)?String\s+($ID)\s*,\s*(?:final\s+)?int\s+($ID)\s*,\s*(?:final\s+)?int\s+($ID)\s*,\s*(?:final\s+)?NonNullList\s*<\s*Ingredient\s*>\s+($ID)\s*,\s*(?:final\s+)?ItemStack\s+($ID)\s*,\s*(?:final\s+)?boolean\s+($ID)\s*\)\s*\{"""
        ).find(masked, outer.range.last + 1) ?: return null
        if (constructor.range.first > outerClose) return null
        val constructorClose = JavaSourceText.matchingBrace(masked, constructor.range.last)
        if (constructorClose < 0 || constructorClose > outerClose) return null
        val indent = constructor.groupValues[1]
        val visibility = constructor.groupValues[2]
        val idName = constructor.groupValues[3]
        val groupName = constructor.groupValues[4]
        val widthName = constructor.groupValues[5]
        val heightName = constructor.groupValues[6]
        val ingredientsName = constructor.groupValues[7]
        val outputName = constructor.groupValues[8]
        val mirroredName = constructor.groupValues[9]
        val constructorBody = result.substring(constructor.range.last + 1, constructorClose)
        val superCall = Regex(
            """super\s*\(\s*${Regex.escape(idName)}\s*,\s*${Regex.escape(groupName)}\s*,\s*([^,;]+?)\s*,\s*${Regex.escape(widthName)}\s*,\s*${Regex.escape(heightName)}\s*,\s*${Regex.escape(ingredientsName)}\s*,\s*${Regex.escape(outputName)}\s*\)\s*;"""
        ).find(constructorBody) ?: return null
        val category = superCall.groupValues[1].trim()
        val fieldAssignment = Regex("""this\.($ID)\s*=\s*${Regex.escape(mirroredName)}\s*;""").find(constructorBody) ?: return null
        val mirroredField = fieldAssignment.groupValues[1]
        val remainingConstructor = constructorBody
            .removeRange(fieldAssignment.range)
            .let { body ->
                val shiftedSuper = Regex(
                    """super\s*\(\s*${Regex.escape(idName)}\s*,\s*${Regex.escape(groupName)}\s*,\s*[^,;]+?\s*,\s*${Regex.escape(widthName)}\s*,\s*${Regex.escape(heightName)}\s*,\s*${Regex.escape(ingredientsName)}\s*,\s*${Regex.escape(outputName)}\s*\)\s*;"""
                ).find(body)
                if (shiftedSuper == null) body else body.removeRange(shiftedSuper.range)
            }
        if (JavaSourceText.maskCommentsAndLiterals(remainingConstructor).isNotBlank()) return null
        val memberIndent = "$indent    "
        val constructorReplacement = """
${indent}${visibility} $className(ResourceLocation $idName, String $groupName, int $widthName, int $heightName,
${memberIndent}NonNullList<Ingredient> $ingredientsName, ItemStack $outputName, boolean $mirroredName) {
${memberIndent}this($groupName, $category, new ShapedRecipePattern($widthName, $heightName, $ingredientsName, java.util.Optional.empty()), $outputName, $mirroredName);
$indent}

${indent}${visibility} $className(String $groupName, CraftingBookCategory category, ShapedRecipePattern pattern,
${memberIndent}ItemStack $outputName, boolean $mirroredName) {
${memberIndent}super($groupName, category, pattern, $outputName);
${memberIndent}this.$mirroredField = $mirroredName;
$indent}
""".trim('\n')
        result = result.substring(0, constructor.range.first) + constructorReplacement + result.substring(constructorClose + 1)

        masked = JavaSourceText.maskCommentsAndLiterals(result)
        val fromShaped = Regex(
            """(?m)^([ \t]*)private\s+static\s+${Regex.escape(className)}\s+fromShaped\s*\(\s*ShapedRecipe\s+($ID)\s*,\s*boolean\s+($ID)\s*\)\s*\{"""
        ).find(masked) ?: return null
        val fromShapedClose = JavaSourceText.matchingBrace(masked, fromShaped.range.last)
        if (fromShapedClose < 0) return null
        val recipeName = fromShaped.groupValues[2]
        val fromMirroredName = fromShaped.groupValues[3]
        val fromIndent = fromShaped.groupValues[1]
        val fromReplacement = """
${fromIndent}private static $className fromShaped(ShapedRecipe $recipeName, boolean $fromMirroredName) {
${fromIndent}    return new $className($recipeName.getGroup(), $recipeName.category(), $recipeName.pattern,
${fromIndent}        $recipeName.getResultItem(null), $fromMirroredName);
${fromIndent}}
""".trim('\n')
        result = result.substring(0, fromShaped.range.first) + fromReplacement + result.substring(fromShapedClose + 1)

        masked = JavaSourceText.maskCommentsAndLiterals(result)
        val matches = Regex(
            """\bboolean\s+matches\s*\(\s*CraftingContainer\s+($ID)\s*,\s*Level\s+($ID)\s*\)\s*\{"""
        ).find(masked) ?: return null
        val matchesClose = JavaSourceText.matchingBrace(masked, matches.range.last)
        if (matchesClose < 0) return null
        val inputName = matches.groupValues[1]
        val matchesText = result.substring(matches.range.first, matchesClose + 1)
        val gate = Regex("""if\s*\(\s*!\s*\(\s*${Regex.escape(inputName)}\s+instanceof\s+((?:$ID\.)*$ID)\s*\)\s*\)\s*return\s+false\s*;""")
            .find(JavaSourceText.maskCommentsAndLiterals(matchesText))
        val gatedContainer = gate?.groupValues?.get(1)
        var updatedMatches = matchesText
        if (gate != null) {
            updatedMatches = updatedMatches.replaceRange(
                gate.range,
                "if (!LegacyCraftingInputs.isFrom($inputName, ${gate.groupValues[1]}.class))\n\t\t\treturn false;"
            )
        }
        updatedMatches = updatedMatches
            .replaceFirst(Regex("""(\bboolean\s+matches\s*\(\s*)CraftingContainer"""), "$1CraftingInput")
            .replace(Regex("""\b${Regex.escape(inputName)}\.getWidth\(\)"""), "$inputName.width()")
            .replace(Regex("""\b${Regex.escape(inputName)}\.getHeight\(\)"""), "$inputName.height()")
            .replace(Regex("""\b${Regex.escape(inputName)}\.getContainerSize\(\)"""), "$inputName.size()")
        result = result.substring(0, matches.range.first) + updatedMatches + result.substring(matchesClose + 1)

        masked = JavaSourceText.maskCommentsAndLiterals(result)
        val specific = Regex("""\bboolean\s+($ID)\s*\(\s*CraftingContainer\s+($ID)\s*,\s*int\s+($ID)\s*,\s*int\s+($ID)\s*\)\s*\{""")
            .find(masked) ?: return null
        val specificClose = JavaSourceText.matchingBrace(masked, specific.range.last)
        if (specificClose < 0) return null
        val specificInput = specific.groupValues[2]
        val specificText = result.substring(specific.range.first, specificClose + 1)
            .replaceFirst(Regex("""(\bboolean\s+${Regex.escape(specific.groupValues[1])}\s*\(\s*)CraftingContainer"""), "$1CraftingInput")
            .replace(Regex("""\b${Regex.escape(specificInput)}\.getWidth\(\)"""), "$specificInput.width()")
            .replace(Regex("""\b${Regex.escape(specificInput)}\.getHeight\(\)"""), "$specificInput.height()")
            .replace(Regex("""\b${Regex.escape(specificInput)}\.getContainerSize\(\)"""), "$specificInput.size()")
        result = result.substring(0, specific.range.first) + specificText + result.substring(specificClose + 1)

        masked = JavaSourceText.maskCommentsAndLiterals(result)
        val getter = Regex("""\bboolean\s+($ID)\s*\(\s*\)\s*\{\s*return\s+(?:this\.)?${Regex.escape(mirroredField)}\s*;\s*\}""")
            .find(masked)?.groupValues?.get(1) ?: return null
        val serializer = Regex("""(?m)^([ \t]*)(?:public\s+)?static\s+class\s+($ID)\s+extends\s+ShapedRecipe\.Serializer\s*\{""")
            .find(masked) ?: return null
        val serializerClose = JavaSourceText.matchingBrace(masked, serializer.range.last)
        if (serializerClose < 0) return null
        val serializerBody = result.substring(serializer.range.last + 1, serializerClose)
        val json = Regex(
            """GsonHelper\.getAsBoolean\s*\(\s*$ID\s*,\s*"([^"]+)"\s*,\s*(true|false)\s*\)"""
        ).find(serializerBody) ?: return null
        if (!Regex("""readBoolean\s*\(\s*\)\s*&&\s*$ID\.readBoolean\s*\(\s*\)""").containsMatchIn(serializerBody) ||
            !Regex("""writeBoolean\s*\(\s*true\s*\)""").containsMatchIn(serializerBody) ||
            !Regex("""writeBoolean\s*\([^;]*${Regex.escape(getter)}\s*\(\s*\)\s*\)""").containsMatchIn(serializerBody)) return null
        val serializerIndent = serializer.groupValues[1]
        val inner = "$serializerIndent    "
        val jsonKey = json.groupValues[1]
        val defaultValue = json.groupValues[2]
        val serializerReplacement = """
${serializerIndent}public static class ${serializer.groupValues[2]} implements RecipeSerializer<$className> {
${inner}public static final com.mojang.serialization.MapCodec<$className> CODEC =
${inner}    com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
${inner}        RecipeSerializer.SHAPED_RECIPE.codec().forGetter(recipe -> recipe),
${inner}        com.mojang.serialization.Codec.BOOL.optionalFieldOf("$jsonKey", $defaultValue)
${inner}            .forGetter($className::$getter)
${inner}    ).apply(instance, $className::fromShaped));

${inner}public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, $className> STREAM_CODEC =
${inner}    net.minecraft.network.codec.StreamCodec.of(${serializer.groupValues[2]}::toNetwork, ${serializer.groupValues[2]}::fromNetwork);

${inner}@Override
${inner}public com.mojang.serialization.MapCodec<$className> codec() {
${inner}    return CODEC;
${inner}}

${inner}@Override
${inner}public net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, $className> streamCodec() {
${inner}    return STREAM_CODEC;
${inner}}

${inner}private static $className fromNetwork(net.minecraft.network.RegistryFriendlyByteBuf buffer) {
${inner}    ShapedRecipe recipe = ShapedRecipe.Serializer.STREAM_CODEC.decode(buffer);
${inner}    return fromShaped(recipe, buffer.readBoolean() && buffer.readBoolean());
${inner}}

${inner}private static void toNetwork(net.minecraft.network.RegistryFriendlyByteBuf buffer, $className recipe) {
${inner}    ShapedRecipe.Serializer.STREAM_CODEC.encode(buffer, recipe);
${inner}    buffer.writeBoolean(true);
${inner}    buffer.writeBoolean(recipe.$getter());
${inner}}
${serializerIndent}}
""".trim('\n')
        result = result.substring(0, serializer.range.first) + serializerReplacement + result.substring(serializerClose + 1)
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.crafting.CraftingInput")
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.crafting.CraftingBookCategory")
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.crafting.RecipeSerializer")
        result = JavaSourceText.addImport(result, "net.minecraft.world.item.crafting.ShapedRecipePattern")
        if (gatedContainer != null) result = JavaSourceText.addImport(result, "$compatPackage.LegacyCraftingInputs")
        for (unused in listOf(
            "com.google.gson.JsonObject",
            "net.minecraft.network.FriendlyByteBuf",
            "net.minecraft.util.GsonHelper"
        )) {
            if (!result.substringAfterLast("import $unused;").contains(unused.substringAfterLast('.'))) {
                result = JavaSourceText.removeImport(result, unused)
            }
        }
        return ShapedRecipeMigration(result, gatedContainer)
    }

    /**
     * Source method contracts such as `<I extends RecipeInput, R extends Recipe<I>> ... find(I, Level)`.
     * The owner comes from the declaring source file, and calls are accepted only through an explicit
     * `Owner.ENUM_CONSTANT.method(...)` receiver, so a same-named unrelated method cannot trigger it.
     */
    private fun collectRecipeInputMethods(sources: Map<Path, String>): Set<RecipeInputMethod> {
        val methods = linkedSetOf<RecipeInputMethod>()
        for ((_, source) in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(masked)?.groupValues?.get(1).orEmpty()
            for (enumDeclaration in Regex("""\benum\s+($ID)\b[^{;]*\{""").findAll(masked)) {
                val enumClose = JavaSourceText.matchingBrace(masked, enumDeclaration.range.last)
                if (enumClose < 0) continue
                val ownerName = enumDeclaration.groupValues[1]
                val ownerQName = if (packageName.isEmpty()) ownerName else "$packageName.$ownerName"
                val entries = collectEnumEntries(masked, enumDeclaration.range.last, enumClose)
                if (entries.isEmpty()) continue
                var cursor = enumDeclaration.range.last + 1
                while (true) {
                    val generic = Regex("""\b(?:public|protected|private)\s+(?:static\s+)?<""")
                        .find(masked, cursor) ?: break
                    if (generic.range.first >= enumClose) break
                    if (!isDirectEnumMember(masked, enumDeclaration.range.last, generic.range.first)) {
                        cursor = generic.range.last + 1
                        continue
                    }
                    val open = generic.range.last
                    val close = matchingAngle(masked, open)
                    if (close < 0 || close >= enumClose) break
                    cursor = close + 1
                    val typeParameters = JavaSourceText.splitTopLevelArgs(masked.substring(open + 1, close))
                    val inputs = typeParameters.mapNotNull { parameter ->
                        Regex("""^\s*($ID)\s+extends\s+(?:net\.minecraft\.world\.item\.crafting\.)?RecipeInput\s*$""")
                            .matchEntire(parameter)?.groupValues?.get(1)
                    }.toSet()
                    if (inputs.isEmpty()) continue
                    val tail = Regex("""^\s+[\w.$<>, ?@\[\]]+\s+($ID)\s*\(""")
                        .find(masked.substring(close + 1)) ?: continue
                    val openParen = close + 1 + tail.range.last
                    val closeParen = JavaSourceText.matchingParen(masked, openParen)
                    if (closeParen < 0 || closeParen >= enumClose) continue
                    val parameters = JavaSourceText.splitTopLevelArgs(masked.substring(openParen + 1, closeParen))
                    val positions = parameters.mapIndexedNotNull { index, parameter ->
                        val type = parameter.trim().substringBeforeLast(' ').removePrefix("final ").trim()
                        if (type in inputs) index else null
                    }.toSet()
                    if (positions.isNotEmpty()) {
                        methods += RecipeInputMethod(
                            ownerName,
                            ownerQName,
                            entries,
                            tail.groupValues[1],
                            parameters.size,
                            positions
                        )
                    }
                }
            }
        }
        return methods
    }

    private fun collectEnumEntries(masked: String, openBrace: Int, closeBrace: Int): Set<String> {
        val entries = linkedSetOf<String>()
        var parentheses = 0
        var braces = 0
        var brackets = 0
        var expectEntry = true
        var index = openBrace + 1
        while (index < closeBrace) {
            val char = masked[index]
            if (expectEntry && parentheses == 0 && braces == 0 && brackets == 0) {
                if (Character.isJavaIdentifierStart(char)) {
                    var end = index + 1
                    while (end < closeBrace && Character.isJavaIdentifierPart(masked[end])) end++
                    entries += masked.substring(index, end)
                    expectEntry = false
                    index = end
                    continue
                }
                if (char == ';') break
            }
            when (char) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '{' -> braces++
                '}' -> if (braces > 0) braces--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                ',' -> if (parentheses == 0 && braces == 0 && brackets == 0) expectEntry = true
                ';' -> if (parentheses == 0 && braces == 0 && brackets == 0) break
            }
            index++
        }
        return entries
    }

    private fun isDirectEnumMember(masked: String, openBrace: Int, offset: Int): Boolean {
        var depth = 0
        for (index in openBrace + 1 until offset) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth == 0
    }

    private fun matchingAngle(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '<' -> depth++
                '>' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    /** Replace conversions only for variables proven to be instances of a gated source container. */
    private fun migrateGatedCraftingInputCalls(
        source: String,
        gatedContainers: Set<String>,
        recipeInputMethods: Set<RecipeInputMethod>,
        compatPackage: String
    ): String? {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val variables = linkedSetOf<String>()
        for (container in gatedContainers) {
            val simple = container.substringAfterLast('.')
            Regex("""\b(?:final\s+)?(?:var|CraftingContainer|${Regex.escape(simple)}|${Regex.escape(container)})\s+($ID)\s*=\s*new\s+(?:${Regex.escape(simple)}|${Regex.escape(container)})\s*\(""")
                .findAll(masked)
                .forEach { variables += it.groupValues[1] }
        }
        if (variables.isEmpty()) return null
        var result = source
        var changed = false
        val sourcePackage = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
            .find(masked)?.groupValues?.get(1).orEmpty()
        val imports = Regex("""(?m)^\s*import\s+([\w.*]+)\s*;""")
            .findAll(masked).map { it.groupValues[1] }.toSet()
        val staticImports = Regex("""(?m)^\s*import\s+static\s+([\w.*]+)\s*;""")
            .findAll(masked).map { it.groupValues[1] }.toSet()
        for (variable in variables) {
            val call = Regex("""\b${Regex.escape(variable)}\s*\.\s*asCraftInput\s*\(\s*\)""")
            if (!call.containsMatchIn(JavaSourceText.maskCommentsAndLiterals(result))) continue
            result = call.replace(result, "LegacyCraftingInputs.from($variable)")
            changed = true
        }
        for (contract in recipeInputMethods) {
            val ownerPackage = contract.ownerQName.substringBeforeLast('.', "")
            val ownerReferences = linkedSetOf(contract.ownerQName)
            if (sourcePackage == ownerPackage ||
                contract.ownerQName in imports ||
                "$ownerPackage.*" in imports) {
                ownerReferences += contract.ownerName
            }
            val staticEntries = contract.entries.filter { entry ->
                "${contract.ownerQName}.$entry" in staticImports || "${contract.ownerQName}.*" in staticImports
            }
            for (ownerReference in ownerReferences) {
                var searchFrom = 0
                while (true) {
                    val resultMasked = JavaSourceText.maskCommentsAndLiterals(result)
                    val call = Regex(
                        """(?<![\w$.])${Regex.escape(ownerReference)}\s*\.\s*($ID)\s*\.\s*${Regex.escape(contract.name)}\s*\("""
                    ).find(resultMasked, searchFrom) ?: break
                    if (call.groupValues[1] !in contract.entries) {
                        searchFrom = call.range.last + 1
                        continue
                    }
                    val openParen = call.range.last
                    val closeParen = JavaSourceText.matchingParen(resultMasked, openParen)
                    if (closeParen < 0) break
                    val argumentsText = result.substring(openParen + 1, closeParen)
                    val arguments = JavaSourceText.splitTopLevelArgs(argumentsText)
                    if (arguments.size != contract.arity) {
                        searchFrom = closeParen + 1
                        continue
                    }
                    val replacements = contract.inputPositions.mapNotNull { position ->
                        val argument = arguments.getOrNull(position)?.trim() ?: return@mapNotNull null
                        if (argument !in variables) return@mapNotNull null
                        position to "LegacyCraftingInputs.from($argument)"
                    }.toMap()
                    if (replacements.isEmpty()) {
                        searchFrom = closeParen + 1
                        continue
                    }
                    val rebuilt = arguments.mapIndexed { index, argument ->
                        replacements[index] ?: argument
                    }.joinToString(", ")
                    result = result.substring(0, openParen + 1) + rebuilt + result.substring(closeParen)
                    changed = true
                    searchFrom = openParen + 1 + rebuilt.length
                }
            }
            for (entry in staticEntries) {
                var searchFrom = 0
                while (true) {
                    val resultMasked = JavaSourceText.maskCommentsAndLiterals(result)
                    val call = Regex(
                        """(?<![\w$.])${Regex.escape(entry)}\s*\.\s*${Regex.escape(contract.name)}\s*\("""
                    ).find(resultMasked, searchFrom) ?: break
                    val openParen = call.range.last
                    val closeParen = JavaSourceText.matchingParen(resultMasked, openParen)
                    if (closeParen < 0) break
                    val arguments = JavaSourceText.splitTopLevelArgs(result.substring(openParen + 1, closeParen))
                    if (arguments.size != contract.arity) {
                        searchFrom = closeParen + 1
                        continue
                    }
                    val replacements = contract.inputPositions.filter { position ->
                        arguments.getOrNull(position)?.trim() in variables
                    }.associateWith { position ->
                        "LegacyCraftingInputs.from(${arguments[position].trim()})"
                    }
                    if (replacements.isEmpty()) {
                        searchFrom = closeParen + 1
                        continue
                    }
                    val rebuilt = arguments.mapIndexed { index, argument ->
                        replacements[index] ?: argument
                    }.joinToString(", ")
                    result = result.substring(0, openParen + 1) + rebuilt + result.substring(closeParen)
                    changed = true
                    searchFrom = openParen + 1 + rebuilt.length
                }
            }
        }
        if (!changed) return null
        return JavaSourceText.addImport(result, "$compatPackage.LegacyCraftingInputs")
    }

    /** The serializer's class name and recipe type argument when it still has the 1.20.1 contract and no codec(). */
    private fun legacySerializer(masked: String): Pair<MatchResult, Int>? {
        val header = serializerHeader.find(masked) ?: return null
        val close = JavaSourceText.matchingBrace(masked, header.range.last)
        if (close < 0) return null
        val body = masked.substring(header.range.last, close)
        if (Regex("""\bcodec\s*\(\s*\)\s*\{""").containsMatchIn(body)) return null
        if (!fromJson.containsMatchIn(body) || !fromNetwork.containsMatchIn(body) || !toNetwork.containsMatchIn(body)) return null
        return header to close
    }

    fun migrateSerializer(source: String, compatPackage: String): String? {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val (header, close) = legacySerializer(masked) ?: return null
        val recipeType = source.substring(header.groups[2]!!.range).trim()
        val body = masked.substring(header.range.last, close)
        // a (JsonObject, T) writer the serializer already has encodes for data generation
        val writer = Regex("""\b(?:public|protected)\s+(?:final\s+)?void\s+($ID)\s*\(\s*(?:final\s+)?(?:com\.google\.gson\.)?JsonObject\s+$ID\s*,\s*(?:final\s+)?${Regex.escape(recipeType)}\s+$ID\s*\)""")
            .find(body)?.groupValues?.get(1)
        var updated = source.substring(header.range.last + 1, close)
        for (method in listOf("fromJson", "fromNetwork", "toNetwork")) {
            updated = updated.replace(Regex("""@Override(\s+(?:@[\w.]+\s+)*(?:public\s+)?(?:final\s+)?[\w.<>?, ]+?\s+$method\s*\()"""), "$1")
        }
        val indent = Regex("""(?m)^([ \t]+)\S""").find(updated)?.groupValues?.get(1) ?: "    "
        val methods = """

$indent@Override
${indent}public com.mojang.serialization.MapCodec<$recipeType> codec() {
$indent${indent}return LegacyRecipeCodecs.codec(this::fromJson, ${writer?.let { "this::$it" } ?: "null"});
$indent}

$indent@Override
${indent}public net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, $recipeType> streamCodec() {
$indent${indent}return LegacyRecipeCodecs.streamCodec(this::toNetwork, this::fromNetwork);
$indent}
"""
        val result = source.substring(0, header.range.last + 1) + methods + updated + source.substring(close)
        return JavaSourceText.addImport(result, "$compatPackage.LegacyRecipeCodecs")
    }

    /** A project recipe whose `getId()` returns its own field gets the holder id setter. */
    fun identifiedRecipe(source: String, compatPackage: String): String? {
        if (!source.contains("Recipe") || source.contains("LegacyRecipeCodecs.Identified")) return null
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val header = Regex("""\b(?:abstract\s+)?class\s+($ID)\s*(?:<[^{]*?>)?\s*(?:extends\s+[^{]+?)?\s*(\bimplements\s+[^{]*?\bRecipe\s*<[^{]*?)\{""").find(masked) ?: return null
        val close = JavaSourceText.matchingBrace(masked, header.range.last)
        if (close < 0) return null
        val body = masked.substring(header.range.last, close)
        val field = Regex("""\bResourceLocation\s+getId\s*\(\s*\)\s*\{\s*return\s+(?:this\.)?($ID)\s*;\s*\}""").find(body)?.groupValues?.get(1) ?: return null
        val declaration = Regex("""(?m)^([ \t]*)((?:(?:private|protected|public)\s+)?)(final\s+)?(ResourceLocation\s+${Regex.escape(field)}\s*[;=])""").find(body) ?: return null
        val bodyStart = header.range.last
        var result = source
        // the field becomes assignable, the class identified
        val fieldStart = bodyStart + declaration.range.first
        val fieldText = result.substring(fieldStart, bodyStart + declaration.range.last + 1).replaceFirst(Regex("""\bfinal\s+"""), "")
        result = result.substring(0, fieldStart) + fieldText + result.substring(bodyStart + declaration.range.last + 1)
        val shift = result.length - source.length
        val closeIndex = close + shift
        val indent = declaration.groupValues[1].ifEmpty { "    " }
        val setter = "\n${indent}@Override\n${indent}public void modporter\$setRecipeId(ResourceLocation id) {\n$indent${indent}this.$field = id;\n$indent}\n"
        result = result.substring(0, closeIndex) + setter + result.substring(closeIndex)
        val implementsStart = header.groups[2]!!.range.first
        result = result.substring(0, implementsStart) + "implements LegacyRecipeCodecs.Identified, " + result.substring(implementsStart + "implements".length).trimStart()
        return JavaSourceText.addImport(result, "$compatPackage.LegacyRecipeCodecs")
    }

    private fun compatSources(
        srcDir: Path,
        compatPackage: String,
        modId: String?,
        includeRecipeCodecs: Boolean,
        includeCraftingInputs: Boolean,
        dryRun: Boolean
    ): List<Change> {
        val dir = srcDir.resolve(compatPackage.replace('.', '/'))
        val codecs = dir.resolve("LegacyRecipeCodecs.java")
        val events = dir.resolve("LegacyRecipeIdEvents.java")
        val clientEvents = dir.resolve("LegacyRecipeIdClientEvents.java")
        val craftingInputs = dir.resolve("LegacyCraftingInputs.java")
        val modIdArgument = if (modId != null) "modid = \"$modId\"" else ""
        if (!dryRun) {
            dir.createDirectories()
            if (includeRecipeCodecs) {
                codecs.writeText("""
                package $compatPackage;

                import com.google.gson.JsonObject;
                import com.mojang.serialization.DataResult;
                import com.mojang.serialization.DynamicOps;
                import com.mojang.serialization.JsonOps;
                import com.mojang.serialization.MapCodec;
                import com.mojang.serialization.MapLike;
                import com.mojang.serialization.RecordBuilder;
                import java.util.function.BiConsumer;
                import java.util.function.BiFunction;
                import java.util.stream.Stream;
                import javax.annotation.Nullable;
                import net.minecraft.network.RegistryFriendlyByteBuf;
                import net.minecraft.network.codec.StreamCodec;
                import net.minecraft.resources.ResourceLocation;
                import net.minecraft.world.item.crafting.RecipeHolder;
                import net.minecraft.world.item.crafting.RecipeManager;

                /** Recipe serializers that keep their 1.20.1 JSON and network code (generated by ModPorter). */
                public final class LegacyRecipeCodecs {
                    /** Recipes decode before anyone knows their id; this stands in until the loaded holder's id is set. */
                    public static final ResourceLocation PENDING_ID = ResourceLocation.fromNamespaceAndPath("modporter", "pending_recipe_id");

                    public interface JsonReader<T> {
                        T read(ResourceLocation id, JsonObject json);
                    }

                    public interface JsonWriter<T> {
                        void write(JsonObject json, T recipe);
                    }

                    /** A recipe that keeps its own id. */
                    public interface Identified {
                        void modporter${'$'}setRecipeId(ResourceLocation id);
                    }

                    private LegacyRecipeCodecs() {
                    }

                    public static <T> MapCodec<T> codec(JsonReader<T> reader, @Nullable JsonWriter<T> writer) {
                        return new MapCodec<>() {
                            @Override
                            public <D> Stream<D> keys(DynamicOps<D> ops) {
                                return Stream.empty();
                            }

                            @Override
                            public <D> DataResult<T> decode(DynamicOps<D> ops, MapLike<D> input) {
                                JsonObject json = new JsonObject();
                                input.entries().forEach(entry -> ops.getStringValue(entry.getFirst()).result()
                                    .ifPresent(key -> json.add(key, ops.convertTo(JsonOps.INSTANCE, entry.getSecond()))));
                                try {
                                    return DataResult.success(reader.read(PENDING_ID, json));
                                } catch (RuntimeException e) {
                                    return DataResult.error(() -> String.valueOf(e.getMessage()));
                                }
                            }

                            @Override
                            public <D> RecordBuilder<D> encode(T recipe, DynamicOps<D> ops, RecordBuilder<D> prefix) {
                                if (writer == null) {
                                    return prefix.withErrorsFrom(DataResult.error(() -> "No JSON writer for recipe " + recipe));
                                }
                                JsonObject json = new JsonObject();
                                writer.write(json, recipe);
                                json.entrySet().forEach(entry -> prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue())));
                                return prefix;
                            }
                        };
                    }

                    public static <T> StreamCodec<RegistryFriendlyByteBuf, T> streamCodec(
                            BiConsumer<? super RegistryFriendlyByteBuf, T> writer,
                            BiFunction<ResourceLocation, ? super RegistryFriendlyByteBuf, T> reader) {
                        return StreamCodec.of(writer::accept, buffer -> reader.apply(PENDING_ID, buffer));
                    }

                    /** Every loaded recipe that keeps its own id takes its holder's. */
                    public static void assignIds(RecipeManager manager) {
                        for (RecipeHolder<?> holder : manager.getRecipes()) {
                            if (holder.value() instanceof Identified identified) {
                                identified.modporter${'$'}setRecipeId(holder.id());
                            }
                        }
                    }
                }
            """.trimIndent() + "\n")
                events.writeText("""
                package $compatPackage;

                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.fml.common.EventBusSubscriber;
                import net.neoforged.neoforge.event.OnDatapackSyncEvent;
                import net.neoforged.neoforge.event.server.ServerStartingEvent;

                /** Server recipes learn their ids once loaded and after every reload (generated by ModPorter). */
                @EventBusSubscriber($modIdArgument)
                public final class LegacyRecipeIdEvents {
                    private LegacyRecipeIdEvents() {
                    }

                    @SubscribeEvent
                    public static void onServerStarting(ServerStartingEvent event) {
                        LegacyRecipeCodecs.assignIds(event.getServer().getRecipeManager());
                    }

                    @SubscribeEvent
                    public static void onDatapackSync(OnDatapackSyncEvent event) {
                        LegacyRecipeCodecs.assignIds(event.getPlayerList().getServer().getRecipeManager());
                    }
                }
            """.trimIndent() + "\n")
                clientEvents.writeText("""
                package $compatPackage;

                import net.neoforged.api.distmarker.Dist;
                import net.neoforged.bus.api.SubscribeEvent;
                import net.neoforged.fml.common.EventBusSubscriber;
                import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;

                /** Client recipes learn their ids once synced (generated by ModPorter). */
                @EventBusSubscriber(${if (modId != null) "$modIdArgument, " else ""}value = Dist.CLIENT)
                public final class LegacyRecipeIdClientEvents {
                    private LegacyRecipeIdClientEvents() {
                    }

                    @SubscribeEvent
                    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
                        LegacyRecipeCodecs.assignIds(event.getRecipeManager());
                    }
                }
            """.trimIndent() + "\n")
            }
            if (includeCraftingInputs) {
                craftingInputs.writeText("""
                    package $compatPackage;

                    import java.lang.ref.ReferenceQueue;
                    import java.lang.ref.WeakReference;
                    import java.util.concurrent.ConcurrentHashMap;
                    import java.util.concurrent.ConcurrentMap;
                    import net.minecraft.world.inventory.CraftingContainer;
                    import net.minecraft.world.item.crafting.CraftingInput;

                    /**
                     * Keeps the source container class across CraftingContainer.asCraftInput()
                     * without retaining the container, its menu, or its world (generated by ModPorter).
                     */
                    public final class LegacyCraftingInputs {
                        private static final ReferenceQueue<CraftingInput> COLLECTED = new ReferenceQueue<>();
                        private static final ConcurrentMap<IdentityReference, Class<?>> ORIGINS = new ConcurrentHashMap<>();

                        private LegacyCraftingInputs() {
                        }

                        public static CraftingInput from(CraftingContainer container) {
                            drainCollected();
                            CraftingInput input = container.asCraftInput();
                            ORIGINS.put(new IdentityReference(input, COLLECTED), container.getClass());
                            return input;
                        }

                        public static boolean isFrom(CraftingInput input, Class<?> sourceType) {
                            drainCollected();
                            Class<?> origin = ORIGINS.get(new IdentityReference(input));
                            return origin != null && sourceType.isAssignableFrom(origin);
                        }

                        public static void clear() {
                            ORIGINS.clear();
                            while (COLLECTED.poll() != null) {
                                // Drain references queued before the clear.
                            }
                        }

                        private static void drainCollected() {
                            IdentityReference reference;
                            while ((reference = (IdentityReference) COLLECTED.poll()) != null) {
                                ORIGINS.remove(reference);
                            }
                        }

                        private static final class IdentityReference extends WeakReference<CraftingInput> {
                            private final int identityHash;

                            private IdentityReference(CraftingInput input) {
                                super(input);
                                this.identityHash = System.identityHashCode(input);
                            }

                            private IdentityReference(CraftingInput input, ReferenceQueue<CraftingInput> queue) {
                                super(input, queue);
                                this.identityHash = System.identityHashCode(input);
                            }

                            @Override
                            public int hashCode() {
                                return identityHash;
                            }

                            @Override
                            public boolean equals(Object other) {
                                if (this == other) {
                                    return true;
                                }
                                if (!(other instanceof IdentityReference reference)) {
                                    return false;
                                }
                                CraftingInput input = get();
                                return input != null && input == reference.get();
                            }
                        }
                    }
                """.trimIndent() + "\n")
            }
        }
        val generated = buildList {
            if (includeRecipeCodecs) addAll(listOf(codecs, events, clientEvents))
            if (includeCraftingInputs) add(craftingInputs)
        }
        return generated.map { file ->
            Change(file, 1, "Generated recipe codec bridge and id assignment", "(none)", file.fileName.toString(), Confidence.HIGH, RULE_ID)
        }
    }
}
