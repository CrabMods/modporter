package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A 1.20.1 data generation result (`implements FinishedRecipe`, declaring `serializeRecipeData`)
 * that holds the recipe it was built for is written on 1.21.1 by handing that recipe to
 * `RecipeOutput.accept(id, recipe, advancement, conditions...)`: the recipe's serializer encodes it,
 * the result's conditions become NeoForge's conditions, and a result whose `serializeAdvancement()`
 * returned null passes no advancement. The result keeps its id and gains `saveTo(RecipeOutput)`;
 * `output.accept(new Result(...))` becomes `new Result(...).saveTo(output)`, and the FinishedRecipe
 * contract methods nothing calls any more go.
 *
 * A delegating record is different: 1.21 builders receive a RecipeOutput rather than returning a
 * FinishedRecipe. A record that only forwarded the old result while adding conditions becomes a
 * RecipeOutput decorator. If it replaced a serialized result id for an item from an absent mod, the
 * owning RecipeProvider encodes the real recipe first and changes only that encoded stack's id.
 *
 * A result without a recipe object (raw ingredients written field by field) is not this shape.
 */
internal object LegacyFinishedRecipeResults {
    const val RULE_ID = "struct-legacy-finished-recipe-result"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val resultHeader = Regex("""\bclass\s+($ID)\s*(<[^{]*?>)?\s*(implements\s+[^{]*?\b(?:RecipeOutput|FinishedRecipe)\b[^{]*?)\{""")
    private val contractMethods = listOf("serializeRecipeData", "serializeAdvancement", "getAdvancementId")

    private class Result(val name: String, val recipeField: String, val conditionsField: String?)
    private data class RecordComponent(val type: String, val name: String)
    private data class RecipeOutputDecorator(
        val name: String,
        val declarationStart: Int,
        val declarationEnd: Int,
        val components: List<RecordComponent>,
        val wrappedIndex: Int,
        val conditionsIndex: Int?,
        val outputOverrideIndex: Int?,
        val ownerName: String,
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }.toMutableMap()
        if (sources.values.none { it.contains("serializeRecipeData") }) return emptyList()
        val recipeTypes = projectRecipeTypes(sources.values)
        val changes = mutableListOf<Change>()
        val results = mutableListOf<Result>()

        for ((file, source) in sources.toMap()) {
            var updated = source
            while (true) {
                val masked = JavaSourceText.maskCommentsAndLiterals(updated)
                val (rewritten, result) = rewriteResultClass(updated, masked, recipeTypes) ?: break
                updated = rewritten
                results += result
            }
            if (updated != source) {
                sources[file] = updated
                changes += Change(file, 1, "FinishedRecipe-style result saves its recipe through RecipeOutput.accept",
                    "implements FinishedRecipe / serializeRecipeData", "saveTo(RecipeOutput)", Confidence.HIGH, RULE_ID)
            }
        }

        // output.accept(new Result(...)) -> new Result(...).saveTo(output)
        if (results.isNotEmpty()) {
            for ((file, source) in sources.toMap()) {
                var updated = source
                for (result in results) {
                    updated = Regex("""(?<![\w$.])((?:$ID\.)*$ID)\s*\.\s*accept\s*\(\s*(new\s+(?:$ID\.)*${Regex.escape(result.name)}\s*(?:<[^>]*>)?\s*\()""").let { call ->
                        var text = updated
                        var cursor = 0
                        while (true) {
                            val masked = JavaSourceText.maskCommentsAndLiterals(text)
                            val match = call.find(masked, cursor) ?: break
                            val newOpen = match.groups[2]!!.range.last
                            val newClose = JavaSourceText.matchingParen(masked, newOpen)
                            val acceptClose = if (newClose < 0) -1 else masked.indexOf(')', newClose + 1)
                            if (acceptClose < 0 || masked.substring(newClose + 1, acceptClose).isNotBlank()) {
                                cursor = match.range.last
                                continue
                            }
                            val created = text.substring(match.groups[2]!!.range.first, newClose + 1)
                            val replacement = "$created.saveTo(${match.groupValues[1]})"
                            text = text.substring(0, match.range.first) + replacement + text.substring(acceptClose + 1)
                            cursor = match.range.first + replacement.length
                        }
                        text
                    }
                }
                if (updated != source) {
                    sources[file] = updated
                    changes += Change(file, 1, "Data generation hands built recipes to RecipeOutput through their results",
                        "output.accept(new Result(...))", "new Result(...).saveTo(output)", Confidence.HIGH, RULE_ID)
                }
            }
        }

        val supertypes = projectSupertypes(sources.values)
        for ((file, source) in sources.toMap()) {
            var updated = source
            val outputOverrideOwners = linkedSetOf<String>()
            var migratedAny = false

            while (true) {
                var migratedThisPass = false
                val decorators = findRecipeOutputDecorators(updated, supertypes)
                    .sortedByDescending { it.declarationStart }
                for (decorator in decorators) {
                    val withoutDeclaration = updated.removeRange(decorator.declarationStart, decorator.declarationEnd)
                    val rewritten = rewriteDecoratorSaveCalls(withoutDeclaration, decorator) ?: continue
                    updated = rewritten
                    migratedAny = true
                    migratedThisPass = true
                    if (decorator.outputOverrideIndex != null) outputOverrideOwners += decorator.ownerName
                    break
                }
                if (!migratedThisPass) break
            }

            for (owner in outputOverrideOwners) {
                injectRawRecipeOutputBridge(updated, owner)?.let { updated = it }
                    ?: continue
            }
            if (migratedAny && updated != source) {
                sources[file] = updated
                changes += Change(
                    file,
                    1,
                    "FinishedRecipe delegate records become RecipeOutput decorators",
                    "builder.save(result -> output.accept(new FinishedRecipe decorator(...)), id)",
                    "builder.save(decorated RecipeOutput, id)",
                    Confidence.HIGH,
                    RULE_ID
                )
            }
        }

        if (!dryRun) {
            changes.map { it.file }.distinct().forEach { file -> sources[file]?.let { file.writeText(it) } }
        }
        return changes
    }

    private fun findRecipeOutputDecorators(
        source: String,
        supertypes: Map<String, List<String>>,
    ): List<RecipeOutputDecorator> {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val records = mutableListOf<RecipeOutputDecorator>()
        val recordStart = Regex("""\brecord\s+($ID)\s*\(""")
        for (match in recordStart.findAll(masked)) {
            val componentOpen = match.range.last
            val componentClose = JavaSourceText.matchingParen(masked, componentOpen)
            if (componentClose < 0) continue
            val bodyOpen = masked.indexOf('{', componentClose + 1)
            if (bodyOpen < 0) continue
            val headerTail = masked.substring(componentClose + 1, bodyOpen)
            if (!Regex("""\bimplements\b[^{]*\b(?:FinishedRecipe|RecipeOutput)\b""").containsMatchIn(headerTail)) continue
            val bodyClose = JavaSourceText.matchingBrace(masked, bodyOpen)
            if (bodyClose < 0) continue

            val components = JavaSourceText.splitTopLevelArgs(source.substring(componentOpen + 1, componentClose))
                .mapNotNull(::parseRecordComponent)
            if (components.isEmpty()) continue
            val wrappedIndex = components.indexOfFirst { it.type.substringAfterLast('.') in setOf("FinishedRecipe", "RecipeOutput") }
            if (wrappedIndex < 0) continue
            val wrapped = components[wrappedIndex].name
            val body = source.substring(bodyOpen + 1, bodyClose)
            val bodyMasked = masked.substring(bodyOpen + 1, bodyClose)
            if (!Regex("""\bvoid\s+serializeRecipeData\s*\(""").containsMatchIn(bodyMasked)) continue
            if (!Regex("""\b${Regex.escape(wrapped)}\s*\.\s*serializeRecipeData\s*\(""").containsMatchIn(bodyMasked)) continue
            if (!delegatesContractMethods(bodyMasked, wrapped)) continue

            val conditionsIndex = components.indexOfFirst {
                Regex("""(?:^|[.<])List\s*<[^>]*\bICondition\b[^>]*>""").containsMatchIn(it.type.replace(" ", ""))
            }.takeIf { it >= 0 }
            val outputMatch = Regex(
                """\.\s*addProperty\s*\(\s*"result"\s*,\s*($ID)\s*\.\s*toString\s*\(\s*\)\s*\)"""
            ).find(body)
            val outputOverrideIndex = outputMatch?.groupValues?.get(1)?.let { name ->
                components.indexOfFirst {
                    it.name == name && it.type.substringAfterLast('.').substringBefore('<') == "ResourceLocation"
                }.takeIf { it >= 0 }
            }
            val addsConditions = conditionsIndex?.let { index ->
                val name = components[index].name
                Regex("""\b${Regex.escape(name)}\b[\s\S]*\b(?:add|writeConditions)\s*\(""").containsMatchIn(body)
            } == true
            if (outputOverrideIndex == null && !addsConditions) continue
            val declarationLineStart = source.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val declarationPrefix = masked.substring(declarationLineStart, match.range.first)
            if (!Regex("""^[ \t]*(?:(?:public|protected|private|static|final)\s+)*$""")
                    .matches(declarationPrefix) ||
                previousNonBlankLine(source, declarationLineStart)?.trimStart()?.startsWith("@") == true) continue
            val declaration = source.substring(match.range.first, bodyClose + 1)
            if (!isExactRecipeOutputDecorator(
                    declaration,
                    match.groupValues[1],
                    components,
                    wrappedIndex,
                    conditionsIndex,
                    outputOverrideIndex
                )) continue

            val owner = enclosingOutermostType(masked, match.range.first) ?: continue
            if (outputOverrideIndex != null) {
                if (!isRecipeProviderType(owner, supertypes, emptySet())) continue
                if (source.contains("modporter\$resultOverrides")) continue
                if (Regex("""\bprotected\s+(?:java\.util\.concurrent\.)?CompletableFuture\s*<\s*\?\s*>\s+run\s*\(""")
                        .containsMatchIn(masked)) continue
            }

            records += RecipeOutputDecorator(
                name = match.groupValues[1],
                declarationStart = declarationLineStart,
                declarationEnd = bodyClose + 1,
                components = components,
                wrappedIndex = wrappedIndex,
                conditionsIndex = conditionsIndex,
                outputOverrideIndex = outputOverrideIndex,
                ownerName = owner,
            )
        }
        return records
    }

    private fun isExactRecipeOutputDecorator(
        declaration: String,
        recordName: String,
        components: List<RecordComponent>,
        wrappedIndex: Int,
        conditionsIndex: Int?,
        outputOverrideIndex: Int?,
    ): Boolean {
        val conditions = conditionsIndex ?: return false
        val modeledComponents = listOfNotNull(wrappedIndex, conditionsIndex, outputOverrideIndex)
        if (modeledComponents.distinct().size != modeledComponents.size ||
            modeledComponents.toSet() != components.indices.toSet()) return false

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val record = parser.parseBodyDeclaration<RecordDeclaration>(declaration).result.orElse(null)
            ?: return false
        if (record.nameAsString != recordName ||
            record.parameters.size != components.size ||
            record.parameters.map { it.nameAsString } != components.map { it.name } ||
            record.typeParameters.isNotEmpty() ||
            record.implementedTypes.map { it.nameAsString }.singleOrNull() !in
            setOf("FinishedRecipe", "RecipeOutput")) return false

        val methods = record.members.map { it as? MethodDeclaration ?: return false }
        val expectedMethods = setOf(
            "getId",
            "getType",
            "serializeAdvancement",
            "getAdvancementId",
            "serializeRecipeData",
        )
        if (methods.map { it.nameAsString }.toSet() != expectedMethods ||
            methods.size != expectedMethods.size ||
            methods.any { method ->
                !method.isPublic ||
                    method.modifiers.any { it.keyword.asString() != "public" } ||
                    method.annotations.any { it.nameAsString.substringAfterLast('.') != "Override" } ||
                    method.typeParameters.isNotEmpty() ||
                    method.thrownExceptions.isNotEmpty()
            }) return false

        val wrapped = components[wrappedIndex].name
        for (name in expectedMethods - "serializeRecipeData") {
            val method = methods.singleOrNull { it.nameAsString == name } ?: return false
            if (method.parameters.isNotEmpty()) return false
            val statements = method.body.orElse(null)?.statements ?: return false
            if (statements.size != 1 ||
                compactJava(statements.single().toString()) !=
                "return${wrapped}.${name}();") return false
        }

        val serialize = methods.single { it.nameAsString == "serializeRecipeData" }
        if (serialize.parameters.size != 1 ||
            serialize.parameters.single().type.asString().substringAfterLast('.') != "JsonObject" ||
            serialize.parameters.single().modifiers.isNotEmpty() ||
            serialize.parameters.single().annotations.any {
                it.nameAsString.substringAfterLast('.') != "NotNull"
            }) return false
        val json = serialize.parameters.single().nameAsString
        val statements = serialize.body.orElse(null)?.statements?.map { compactJava(it.toString()) }
            ?: return false
        val expectedCount = if (outputOverrideIndex == null) 4 else 5
        if (statements.size != expectedCount ||
            statements.first() != "${wrapped}.serializeRecipeData(${json});") return false

        var statementIndex = 1
        if (outputOverrideIndex != null) {
            val outputOverride = components[outputOverrideIndex].name
            if (statements[statementIndex++] !=
                "${json}.addProperty(\"result\",${outputOverride}.toString());") return false
        }

        val array = Regex(
            """^(?:com\.google\.gson\.)?JsonArray($ID)=new(?:com\.google\.gson\.)?JsonArray\(\);$"""
        ).matchEntire(statements[statementIndex++])?.groupValues?.get(1) ?: return false
        val conditionName = components[conditions].name
        val forEach = Regex(
            """^${Regex.escape(conditionName)}\.forEach\(\(?($ID)\)?->${Regex.escape(array)}\.add\((.*)\)\);$"""
        ).matchEntire(statements[statementIndex++]) ?: return false
        val conditionVariable = forEach.groupValues[1]
        if (!isExactConditionSerialization(forEach.groupValues[2], conditionVariable) ||
            statements[statementIndex] != "${json}.add(\"conditions\",${array});") return false
        return true
    }

    private fun isExactConditionSerialization(expression: String, conditionVariable: String): Boolean {
        val canonical = expression
            .replace("net.neoforged.neoforge.common.conditions.ICondition", "ICondition")
            .replace("com.mojang.serialization.JsonOps", "JsonOps")
        return canonical in setOf(
            "CraftingHelper.serialize($conditionVariable)",
            "$conditionVariable.toString()",
            "ICondition.CODEC.encodeStart(JsonOps.INSTANCE,$conditionVariable)" +
                ".getOrThrow().getAsJsonObject()",
        )
    }

    private fun compactJava(source: String): String = source.replace(Regex("""\s+"""), "")

    private fun previousNonBlankLine(source: String, before: Int): String? {
        var end = before
        while (end > 0) {
            end = source.lastIndexOf('\n', end - 2).let { if (it < 0) 0 else it + 1 }
            val lineEnd = source.indexOf('\n', end).let { if (it < 0 || it > before) before else it }
            val line = source.substring(end, lineEnd).trim()
            if (line.isNotEmpty()) return line
            if (end == 0) break
        }
        return null
    }

    private fun parseRecordComponent(text: String): RecordComponent? {
        val match = Regex("""(?s)^(.*?)\b($ID)\s*$""").matchEntire(text.trim()) ?: return null
        val type = match.groupValues[1].trim()
        if (type.isEmpty()) return null
        return RecordComponent(type, match.groupValues[2])
    }

    private fun delegatesContractMethods(bodyMasked: String, wrapped: String): Boolean {
        for (method in listOf("getId", "getType", "serializeAdvancement", "getAdvancementId")) {
            if (!Regex("""\b$method\s*\(""").containsMatchIn(bodyMasked)) continue
            if (!Regex("""\breturn\s+$wrapped\s*\.\s*$method\s*\(""").containsMatchIn(bodyMasked)) return false
        }
        return true
    }

    private fun rewriteDecoratorSaveCalls(source: String, decorator: RecipeOutputDecorator): String? {
        var text = source
        var cursor = 0
        var replacements = 0
        val saveCall = Regex("""\.\s*save\s*\(""")
        while (true) {
            val masked = JavaSourceText.maskCommentsAndLiterals(text)
            val match = saveCall.find(masked, cursor) ?: break
            val open = match.range.last
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) break
            val args = JavaSourceText.splitTopLevelArgs(text.substring(open + 1, close))
            if (args.size < 2) {
                cursor = close + 1
                continue
            }
            val rewrittenOutput = rewriteSaveLambda(args.first(), decorator)
            if (rewrittenOutput == null) {
                cursor = close + 1
                continue
            }
            val replacementArgs = (listOf(rewrittenOutput) + args.drop(1)).joinToString(", ")
            text = text.substring(0, open + 1) + replacementArgs + text.substring(close)
            cursor = open + replacementArgs.length + 2
            replacements++
        }
        if (replacements == 0) return null
        if (Regex("""\b${Regex.escape(decorator.name)}\b""")
                .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(text))) return null
        return text
    }

    private fun rewriteSaveLambda(lambdaText: String, decorator: RecipeOutputDecorator): String? {
        val lambda = Regex("""(?s)^\s*\(?\s*($ID)\s*\)?\s*->\s*(.*)\s*$""").matchEntire(lambdaText) ?: return null
        val parameter = lambda.groupValues[1]
        var body = lambda.groupValues[2].trim()
        if (body.startsWith("{") && body.endsWith("}")) body = body.substring(1, body.length - 1).trim()
        val accept = Regex("""(?s)^((?:$ID)(?:\s*\.\s*$ID)*)\s*\.\s*accept\s*\((.*)\)\s*;\s*$""")
            .matchEntire(body) ?: return null
        val output = accept.groupValues[1].replace(Regex("""\s+"""), "")
        val expression = accept.groupValues[2].trim()
        val constructor = Regex("""\bnew\s+(?:(?:$ID)\s*\.\s*)*${Regex.escape(decorator.name)}\s*\(""")
            .find(JavaSourceText.maskCommentsAndLiterals(expression)) ?: return null
        val constructorOpen = constructor.range.last
        val constructorClose = JavaSourceText.matchingParen(
            JavaSourceText.maskCommentsAndLiterals(expression),
            constructorOpen
        )
        if (constructorClose < 0) return null
        val constructorArgs = JavaSourceText.splitTopLevelArgs(expression.substring(constructorOpen + 1, constructorClose))
        if (constructorArgs.size != decorator.components.size ||
            constructorArgs[decorator.wrappedIndex].trim() != parameter) return null
        val decorated = decoratorOutputExpression(output, constructorArgs, decorator) ?: return null
        val constructorText = expression.substring(constructor.range.first, constructorClose + 1)

        if (expression == constructorText) return decorated
        val ternary = Regex(
            """(?s)^(.*?)\?\s*${Regex.escape(constructorText)}\s*:\s*${Regex.escape(parameter)}\s*$"""
        ).matchEntire(expression) ?: return null
        return "${ternary.groupValues[1].trim()} ? $decorated : $output"
    }

    private fun decoratorOutputExpression(
        output: String,
        args: List<String>,
        decorator: RecipeOutputDecorator,
    ): String? {
        val conditions = decorator.conditionsIndex?.let { args[it] }
        val outputOverride = decorator.outputOverrideIndex?.let { args[it] }
        return if (outputOverride != null) {
            val conditionList = conditions ?: "java.util.List.of()"
            "modporter\$recipeOutput($output, $outputOverride, $conditionList)"
        } else {
            val conditionList = conditions ?: return null
            "$output.withConditions($conditionList.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0]))"
        }
    }

    private fun injectRawRecipeOutputBridge(source: String, ownerName: String): String? {
        if (source.contains("modporter\$resultOverrides")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val owner = typeRange(masked, ownerName) ?: return null
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val lineStart = source.lastIndexOf('\n', owner.first).let { if (it < 0) 0 else it + 1 }
        val ownerIndent = source.substring(lineStart, owner.first).takeWhile { it == ' ' || it == '\t' }
        val indent = ownerIndent + if (source.substring(lineStart, owner.first).contains('\t')) "\t" else "    "
        val member = rawRecipeOutputBridge(indent, newline)
        return source.substring(0, owner.last) + newline + member + newline + ownerIndent + source.substring(owner.last)
    }

    private fun rawRecipeOutputBridge(indent: String, newline: String): String {
        val i2 = indent + if (indent.contains('\t')) "\t" else "    "
        val i3 = i2 + if (indent.contains('\t')) "\t" else "    "
        val i4 = i3 + if (indent.contains('\t')) "\t" else "    "
        val i5 = i4 + if (indent.contains('\t')) "\t" else "    "
        val i6 = i5 + if (indent.contains('\t')) "\t" else "    "
        return """
${indent}private java.util.IdentityHashMap<net.minecraft.world.item.crafting.Recipe<?>, net.minecraft.resources.ResourceLocation> modporter${'$'}resultOverrides;

${indent}private net.minecraft.data.recipes.RecipeOutput modporter${'$'}recipeOutput(
${i2}net.minecraft.data.recipes.RecipeOutput output,
${i2}net.minecraft.resources.ResourceLocation resultOverride,
${i2}java.util.List<net.neoforged.neoforge.common.conditions.ICondition> conditions
${indent}) {
${i2}return new net.minecraft.data.recipes.RecipeOutput() {
${i3}@Override
${i3}public net.minecraft.advancements.Advancement.Builder advancement() {
${i4}return output.advancement();
${i3}}

${i3}@Override
${i3}public void accept(
${i4}net.minecraft.resources.ResourceLocation id,
${i4}net.minecraft.world.item.crafting.Recipe<?> recipe,
${i4}net.minecraft.advancements.AdvancementHolder advancement,
${i4}net.neoforged.neoforge.common.conditions.ICondition... additionalConditions
${i3}) {
${i4}if (modporter${'$'}resultOverrides == null) {
${i5}throw new IllegalStateException("Recipe result override used outside the provider run");
${i4}}
${i4}modporter${'$'}resultOverrides.put(recipe, resultOverride);
${i4}net.minecraft.data.recipes.RecipeOutput target = conditions.isEmpty()
${i5}? output
${i5}: output.withConditions(conditions.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0]));
${i4}target.accept(id, recipe, advancement, additionalConditions);
${i3}}
${i2}};
${indent}}

${indent}@Override
${indent}protected java.util.concurrent.CompletableFuture<?> run(
${i2}net.minecraft.data.CachedOutput output,
${i2}net.minecraft.core.HolderLookup.Provider registries
${indent}) {
${i2}if (modporter${'$'}resultOverrides != null) {
${i3}throw new IllegalStateException("Concurrent recipe provider runs are not supported");
${i2}}
${i2}modporter${'$'}resultOverrides = new java.util.IdentityHashMap<>();
${i2}try {
${i3}java.util.Set<net.minecraft.resources.ResourceLocation> ids = new java.util.HashSet<>();
${i3}java.util.List<java.util.concurrent.CompletableFuture<?>> futures = new java.util.ArrayList<>();
${i3}net.minecraft.resources.RegistryOps<com.google.gson.JsonElement> jsonOps =
${i4}registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
${i3}net.minecraft.data.recipes.RecipeOutput recipeOutput = new net.minecraft.data.recipes.RecipeOutput() {
${i4}@Override
${i4}public net.minecraft.advancements.Advancement.Builder advancement() {
${i5}return net.minecraft.advancements.Advancement.Builder.recipeAdvancement()
${i6}.parent(net.minecraft.data.recipes.RecipeBuilder.ROOT_RECIPE_ADVANCEMENT);
${i4}}

${i4}@Override
${i4}public void accept(
${i5}net.minecraft.resources.ResourceLocation id,
${i5}net.minecraft.world.item.crafting.Recipe<?> recipe,
${i5}net.minecraft.advancements.AdvancementHolder advancement,
${i5}net.neoforged.neoforge.common.conditions.ICondition... conditions
${i4}) {
${i5}if (!ids.add(id)) {
${i6}throw new IllegalStateException("Duplicate recipe " + id);
${i5}}
${i5}com.google.gson.JsonElement recipeJson =
${i6}net.minecraft.world.item.crafting.Recipe.CONDITIONAL_CODEC.encodeStart(
${i6}jsonOps,
${i6}java.util.Optional.of(new net.neoforged.neoforge.common.conditions.WithConditions<>(recipe, conditions))
${i6}).getOrThrow();
${i5}net.minecraft.resources.ResourceLocation resultOverride = modporter${'$'}resultOverrides.remove(recipe);
${i5}if (resultOverride != null) {
${i6}com.google.gson.JsonObject recipeObject = recipeJson.getAsJsonObject();
${i6}com.google.gson.JsonElement result = recipeObject.get("result");
${i6}if (result == null) {
${i6}    throw new IllegalStateException("Cannot override a recipe without a result field: " + id);
${i6}}
${i6}if (result.isJsonObject()) {
${i6}    if (!result.getAsJsonObject().has("id")) {
${i6}        throw new IllegalStateException("Cannot identify the encoded recipe result: " + id);
${i6}    }
${i6}    result.getAsJsonObject().addProperty("id", resultOverride.toString());
${i6}} else if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
${i6}    recipeObject.addProperty("result", resultOverride.toString());
${i6}} else {
${i6}    throw new IllegalStateException("Unsupported encoded recipe result shape: " + id);
${i6}}
${i5}}
${i5}futures.add(net.minecraft.data.DataProvider.saveStable(
${i6}output, recipeJson, recipePathProvider.json(id)));
${i5}if (advancement != null) {
${i6}com.google.gson.JsonElement advancementJson =
${i6}    net.minecraft.advancements.Advancement.CONDITIONAL_CODEC.encodeStart(
${i6}        jsonOps,
${i6}        java.util.Optional.of(new net.neoforged.neoforge.common.conditions.WithConditions<>(
${i6}            advancement.value(), conditions))
${i6}    ).getOrThrow();
${i6}futures.add(net.minecraft.data.DataProvider.saveStable(
${i6}    output, advancementJson, advancementPathProvider.json(advancement.id())));
${i5}}
${i4}}
${i3}};
${i3}this.buildRecipes(recipeOutput, registries);
${i3}if (!modporter${'$'}resultOverrides.isEmpty()) {
${i4}throw new IllegalStateException("A recipe result override was not consumed");
${i3}}
${i3}return java.util.concurrent.CompletableFuture.allOf(
${i4}futures.toArray(java.util.concurrent.CompletableFuture[]::new));
${i2}} finally {
${i3}modporter${'$'}resultOverrides.clear();
${i3}modporter${'$'}resultOverrides = null;
${i2}}
${indent}}""".trim('\r', '\n').replace("\n", newline)
    }

    private fun enclosingOutermostType(masked: String, position: Int): String? {
        val candidates = mutableListOf<Triple<String, Int, Int>>()
        val type = Regex("""\b(?:class|interface|record|enum)\s+($ID)\b[^{;]*\{""")
        for (match in type.findAll(masked)) {
            val open = match.range.last
            val close = JavaSourceText.matchingBrace(masked, open)
            if (close >= position && match.range.first < position) {
                candidates += Triple(match.groupValues[1], match.range.first, close)
            }
        }
        return candidates.maxByOrNull { it.third - it.second }?.first
    }

    private fun typeRange(masked: String, name: String): IntRange? {
        val header = Regex("""\b(?:class|interface|record|enum)\s+${Regex.escape(name)}\b[^{;]*\{""")
            .find(masked) ?: return null
        val close = JavaSourceText.matchingBrace(masked, header.range.last)
        return if (close < 0) null else header.range.first..close
    }

    private fun projectSupertypes(sources: Collection<String>): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        for (source in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            Regex("""\b(?:class|interface|record)\s+($ID)\s*(?:<[^{]*?>)?([^{;]*)\{""").findAll(masked).forEach { match ->
                result[match.groupValues[1]] = Regex("""\b(?:extends|implements)\s+([^{]+)""")
                    .findAll(match.groupValues[2])
                    .flatMap { it.groupValues[1].split(',').asSequence() }
                    .map { it.trim().substringBefore('<').substringAfterLast('.').trim() }
                    .filter { it.isNotEmpty() && it !in setOf("extends", "implements") }
                    .toList()
            }
        }
        return result
    }

    private fun isRecipeProviderType(
        type: String,
        supertypes: Map<String, List<String>>,
        seen: Set<String>,
    ): Boolean {
        if (type == "RecipeProvider") return true
        if (type in seen) return false
        val parents = supertypes[type]
            ?: return TargetMembers.classesNamed(type)
                .any { TargetMembers.inheritsFrom(it, "net.minecraft.data.recipes.RecipeProvider") }
        return parents.any { isRecipeProviderType(it, supertypes, seen + type) }
    }

    private fun rewriteResultClass(source: String, masked: String, recipeTypes: Set<String>): Pair<String, Result>? {
        for (header in resultHeader.findAll(masked)) {
            val open = header.range.last
            val close = JavaSourceText.matchingBrace(masked, open)
            if (close < 0) continue
            val body = masked.substring(open, close)
            if (!Regex("""\bvoid\s+serializeRecipeData\s*\(""").containsMatchIn(body)) continue
            if (!Regex("""\bResourceLocation\s+getId\s*\(\s*\)""").containsMatchIn(body)) continue
            // no advancement: serializeAdvancement() returned null
            val advancement = Regex("""\bserializeAdvancement\s*\(\s*\)\s*\{\s*return\s+null\s*;\s*\}""").containsMatchIn(body)
            if (!advancement) continue
            val typeBounds = header.groupValues[2].removePrefix("<").removeSuffix(">").split(',')
                .associate { it.substringBefore("extends").trim() to it.substringAfter("extends", "").trim().substringBefore('<') }
            val fields = Regex("""(?m)^[ \t]*(?:(?:private|protected|public|final)\s+)*($ID)(?:\s*<[^;=]*>)?\s+($ID)\s*;""").findAll(body).toList()
            val recipeField = fields.firstOrNull { field ->
                val type = typeBounds[field.groupValues[1]]?.ifEmpty { null } ?: field.groupValues[1]
                type in recipeTypes || TargetMembers.classesNamed(type).any { TargetMembers.inheritsFrom(it, "net.minecraft.world.item.crafting.Recipe") }
            }?.groupValues?.get(2) ?: continue
            val conditionsField = Regex("""\bList\s*<\s*ICondition\s*>\s+($ID)\s*;""").find(body)?.groupValues?.get(1)

            var text = source.substring(open + 1, close)
            for (method in contractMethods) {
                text = removeMethod(text, method)
            }
            text = text.replace(Regex("""@Override(\s+public\s+(?:ResourceLocation\s+getId|RecipeSerializer\s*<[^>]*>\s+getType)\s*\()"""), "$1")
            val indent = Regex("""(?m)^([ \t]+)\S""").find(text)?.groupValues?.get(1) ?: "    "
            val conditions = conditionsField?.let { ", $it.toArray(new net.neoforged.neoforge.common.conditions.ICondition[0])" } ?: ""
            val saveTo = "\n${indent}public void saveTo(net.minecraft.data.recipes.RecipeOutput output) {\n" +
                "$indent${indent}output.accept(getId(), $recipeField, null$conditions);\n$indent}\n"
            val interfaces = header.groupValues[3].removePrefix("implements").split(',').map { it.trim() }
                .filter { it.isNotEmpty() && it != "RecipeOutput" && it != "FinishedRecipe" }
            val newHeader = source.substring(header.range.first, header.groups[3]!!.range.first) +
                (if (interfaces.isEmpty()) "" else "implements ${interfaces.joinToString(", ")} ") + "{"
            val rewritten = source.substring(0, header.range.first) + newHeader + saveTo + text + source.substring(close)
            return rewritten to Result(header.groupValues[1], recipeField, conditionsField)
        }
        return null
    }

    /** Removes a method declaration named [name] (with its annotations) from a class body. */
    private fun removeMethod(body: String, name: String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(body)
        val declaration = Regex("""(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|final)\s+)*[\w.<>?\[\], ]+\s+${Regex.escape(name)}\s*\([^)]*\)\s*\{""").find(masked) ?: return body
        val close = JavaSourceText.matchingBrace(masked, declaration.range.last)
        if (close < 0) return body
        val end = body.indexOf('\n', close).let { if (it < 0) close + 1 else it + 1 }
        return body.substring(0, declaration.range.first) + body.substring(end)
    }

    /** Project classes that implement Recipe, directly or through other project classes or platform ones. */
    private fun projectRecipeTypes(sources: Collection<String>): Set<String> {
        val supertypes = HashMap<String, List<String>>()
        for (source in sources) {
            val masked = JavaSourceText.maskCommentsAndLiterals(source)
            Regex("""\b(?:class|interface|record)\s+($ID)\s*(?:<[^{]*?>)?([^{;]*)\{""").findAll(masked).forEach { match ->
                supertypes[match.groupValues[1]] = Regex("""\b(?:extends|implements)\s+([^{]+)""").findAll(match.groupValues[2])
                    .flatMap { it.groupValues[1].split(',').asSequence() }
                    .map { it.trim().substringBefore('<').substringAfterLast('.').trim() }
                    .filter { it.isNotEmpty() && it != "extends" && it != "implements" }
                    .toList()
            }
        }
        val recipes = HashSet<String>()
        fun isRecipe(type: String, seen: Set<String>): Boolean {
            if (type == "Recipe" || type in recipes) return true
            if (type in seen) return false
            val parents = supertypes[type]
                ?: return TargetMembers.classesNamed(type).any { TargetMembers.inheritsFrom(it, "net.minecraft.world.item.crafting.Recipe") }
            return parents.any { isRecipe(it, seen + type) }
        }
        supertypes.keys.filterTo(recipes) { isRecipe(it, emptySet()) }
        return recipes
    }
}
