package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val RAW_RECIPE_OUTPUT =
    "net.minecraft.data.recipes.RecipeOutput"
private const val RAW_FINISHED_RECIPE =
    "net.minecraft.data.recipes.FinishedRecipe"
private const val RAW_RECIPE_OUTPUT_EXTENSION =
    "net.neoforged.neoforge.common.extensions.IRecipeOutputExtension"
private const val RAW_RESOURCE_LOCATION =
    "net.minecraft.resources.ResourceLocation"
private const val RAW_ITEM =
    "net.minecraft.world.item.Item"
private const val RAW_ITEM_LIKE =
    "net.minecraft.world.level.ItemLike"
private const val RAW_ITEM_STACK =
    "net.minecraft.world.item.ItemStack"
private const val RAW_INGREDIENT =
    "net.minecraft.world.item.crafting.Ingredient"
private const val RAW_SHAPED_RECIPE =
    "net.minecraft.world.item.crafting.ShapedRecipe"
private const val RAW_SHAPED_PATTERN =
    "net.minecraft.world.item.crafting.ShapedRecipePattern"
private const val RAW_CRAFTING_CATEGORY =
    "net.minecraft.world.item.crafting.CraftingBookCategory"
private const val RAW_RECIPE_SERIALIZER =
    "net.minecraft.world.item.crafting.RecipeSerializer"
private const val RAW_I_CONDITION =
    "net.neoforged.neoforge.common.conditions.ICondition"
private const val RAW_JSON_OBJECT =
    "com.google.gson.JsonObject"
private const val RAW_JSON_ARRAY =
    "com.google.gson.JsonArray"
private const val RAW_LIST = "java.util.List"
private const val RAW_MAP = "java.util.Map"
private const val RAW_STRING = "java.lang.String"
private const val RAW_CHARACTER = "java.lang.Character"
private const val RAW_MAP_ENTRY = "java.util.Map.Entry"
private const val RAW_MAP_CODEC = "com.mojang.serialization.MapCodec"
private const val RAW_CODEC = "com.mojang.serialization.Codec"
private const val RAW_RECORD_CODEC_BUILDER =
    "com.mojang.serialization.codecs.RecordCodecBuilder"
private const val RAW_JSON_OPS = "com.mojang.serialization.JsonOps"
private const val RAW_CRAFTING_HELPER =
    "net.neoforged.neoforge.common.crafting.CraftingHelper"

private const val RAW_ACCEPT_DESCRIPTOR =
    "(Lnet/minecraft/resources/ResourceLocation;" +
        "Lnet/minecraft/world/item/crafting/Recipe;" +
        "Lnet/minecraft/advancements/AdvancementHolder;" +
        "[Lnet/neoforged/neoforge/common/conditions/ICondition;)V"
private const val RAW_PATTERN_OF_DESCRIPTOR =
    "(Ljava/util/Map;Ljava/util/List;)" +
        "Lnet/minecraft/world/item/crafting/ShapedRecipePattern;"
private const val RAW_ITEM_STACK_DESCRIPTOR =
    "(Lnet/minecraft/world/level/ItemLike;I)V"
private const val RAW_SHAPED_RECIPE_4_DESCRIPTOR =
    "(Ljava/lang/String;" +
        "Lnet/minecraft/world/item/crafting/CraftingBookCategory;" +
        "Lnet/minecraft/world/item/crafting/ShapedRecipePattern;" +
        "Lnet/minecraft/world/item/ItemStack;)V"
private const val RAW_SHAPED_RECIPE_5_DESCRIPTOR =
    "(Ljava/lang/String;" +
        "Lnet/minecraft/world/item/crafting/CraftingBookCategory;" +
        "Lnet/minecraft/world/item/crafting/ShapedRecipePattern;" +
        "Lnet/minecraft/world/item/ItemStack;Z)V"

/**
 * Migrates the closed 1.20 raw-JSON shaped recipe result into the target
 * RecipeOutput contract.
 *
 * Admission proves the old result's complete pattern/key/result/mirror/
 * conditions JSON shape and associates it with one project ShapedRecipe
 * subclass through the same serializer expression. The target subclass must
 * expose the exact 1.21 constructor and a codec composed from the vanilla
 * shaped recipe codec plus its mirrored boolean.
 */
internal object RawShapedRecipeOutputResults {
    const val RULE_ID = "struct-raw-shaped-recipe-output-result"

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        if (!targetContractVerified()) return emptyList()
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()
        val paths = JavaSourceText.walkJavaSourceRoots(sourceRoot).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        if (paths.none {
                val text = it.readText()
                text.contains("serializeRecipeData") &&
                    text.contains("RecipeOutput")
            }) return emptyList()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val files = paths.map { path ->
            val source = path.readText()
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful || parsed.result.isEmpty) {
                val problems = parsed.problems.joinToString("; ") {
                    it.verboseMessage
                }
                failRawRecipe(
                    "Raw shaped recipe adapter requires every Java source to parse; " +
                        "$path failed: ${problems.ifBlank { "unknown parse failure" }}"
                )
            }
            RawRecipeFile(path, source, parsed.result.orElseThrow())
        }
        val index = RawRecipeTypeIndex(files)
        val candidates = files.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .mapNotNull { result ->
                    rawResultCandidate(result, file, index)
                }
        }
        if (candidates.isEmpty()) return emptyList()

        val canonicalCandidates = candidates.associateBy { it.canonical }
        if (canonicalCandidates.size != candidates.size) {
            failRawRecipe("Raw shaped recipe result canonical names are not unique")
        }
        val plans = candidates.map { candidate ->
            planRawResult(candidate, files, index)
        }
        auditRawResultReferences(files, index, plans)

        files.forEach { LexicalPreservingPrinter.setup(it.unit) }
        plans.forEach(::applyRawResultPlan)
        val changed = files.mapNotNull { file ->
            val migrated = LexicalPreservingPrinter.print(file.unit)
            if (migrated == file.source) null
            else RawRecipePrepared(file, migrated)
        }
        if (!dryRun) {
            changed.forEach { it.file.path.writeText(it.migrated) }
        }
        return changed.map { prepared ->
            Change(
                file = prepared.file.path,
                line = 1,
                description =
                    "Emit a source-proven shaped recipe through RecipeOutput",
                before =
                    "Raw FinishedRecipe-style JSON result passed to RecipeOutput.accept",
                after =
                    "Native recipe instance with null advancement and preserved conditions",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
    }

    private fun targetContractVerified(): Boolean =
        TargetMembers.inheritsFrom(
            RAW_RECIPE_OUTPUT,
            RAW_RECIPE_OUTPUT_EXTENSION
        ) &&
            RAW_ACCEPT_DESCRIPTOR in TargetMembers.methodDescriptors(
                RAW_RECIPE_OUTPUT_EXTENSION,
                "accept"
            ) &&
            RAW_PATTERN_OF_DESCRIPTOR in TargetMembers.methodDescriptors(
                RAW_SHAPED_PATTERN,
                "of"
            ) &&
            RAW_ITEM_STACK_DESCRIPTOR in TargetMembers.methodDescriptors(
                RAW_ITEM_STACK,
                "<init>"
            ) &&
            RAW_SHAPED_RECIPE_4_DESCRIPTOR in TargetMembers.methodDescriptors(
                RAW_SHAPED_RECIPE,
                "<init>"
            ) &&
            RAW_SHAPED_RECIPE_5_DESCRIPTOR in TargetMembers.methodDescriptors(
                RAW_SHAPED_RECIPE,
                "<init>"
            ) &&
            TargetMembers.hasField(RAW_CRAFTING_CATEGORY, "MISC") &&
            TargetMembers.hasField(RAW_RECIPE_SERIALIZER, "SHAPED_RECIPE")

    private fun rawResultCandidate(
        result: ClassOrInterfaceDeclaration,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): RawResultCandidate? {
        if (result.isInterface) return null
        if (
            result.implementedTypes.size != 1 ||
            index.resolve(result.implementedTypes.single(), file) !in
            setOf(RAW_RECIPE_OUTPUT, RAW_FINISHED_RECIPE)
        ) return null
        if (result.methods.none { it.nameAsString == "serializeRecipeData" }) {
            return null
        }
        if (!looksLikeRawShapedResult(result, file, index)) return null
        val outer = result.parentNode.orElse(null)
            as? ClassOrInterfaceDeclaration
            ?: failRawRecipe(
                "Raw shaped recipe result ${result.nameAsString} must be a direct member class"
            )
        val canonical = rawCanonicalName(result, file.unit)
            ?: failRawRecipe(
                "Local raw shaped recipe result ${result.nameAsString} is unsupported"
            )
        return RawResultCandidate(canonical, result, outer, file)
    }

    private fun planRawResult(
        candidate: RawResultCandidate,
        files: List<RawRecipeFile>,
        index: RawRecipeTypeIndex
    ): RawResultPlan {
        val result = candidate.result
        if (
            result.isAbstract ||
            !result.isPublic ||
            result.isStatic ||
            result.typeParameters.isNotEmpty() ||
            result.extendedTypes.isNotEmpty()
        ) {
            failRawRecipe(
                "${candidate.canonical} must be a public concrete direct member result"
            )
        }
        val fields = exactRawResultFields(result, candidate.file, index)
        exactRawResultConstructor(result, candidate.file, index, fields)
        val methods = result.methods
        val expected = setOf(
            "serializeRecipeData",
            "getType",
            "getId",
            "serializeAdvancement",
            "getAdvancementId"
        )
        if (
            methods.size != expected.size ||
            methods.map { it.nameAsString }.toSet() != expected
        ) {
            failRawRecipe(
                "${candidate.canonical} must contain only the five legacy result methods"
            )
        }
        requireRawMethod(
            methods.single { it.nameAsString == "serializeRecipeData" },
            candidate,
            index,
            "void",
            listOf(RAW_JSON_OBJECT)
        )
        requireRawMethod(
            methods.single { it.nameAsString == "getType" },
            candidate,
            index,
            RAW_RECIPE_SERIALIZER
        )
        requireRawMethod(
            methods.single { it.nameAsString == "getId" },
            candidate,
            index,
            RAW_RESOURCE_LOCATION
        )
        requireRawMethod(
            methods.single { it.nameAsString == "serializeAdvancement" },
            candidate,
            index,
            RAW_JSON_OBJECT
        )
        requireRawMethod(
            methods.single { it.nameAsString == "getAdvancementId" },
            candidate,
            index,
            RAW_RESOURCE_LOCATION
        )
        val build = exactRawBuildMethod(candidate, index, fields)
        val serialize = methods.single { it.nameAsString == "serializeRecipeData" }
        validateRawRecipeJson(
            serialize,
            candidate,
            fields,
            index,
            build.itemKeyOwner
        )
        val serializerExpression = singleReturnedExpression(
            methods.single { it.nameAsString == "getType" },
            "${candidate.canonical}.getType"
        )
        val idReturn = singleReturnedExpression(
            methods.single { it.nameAsString == "getId" },
            "${candidate.canonical}.getId"
        )
        if (
            rawFieldName(idReturn) !=
            fields.getValue(RawRecipeRole.ID).nameAsString
        ) {
            failRawRecipe(
                "${candidate.canonical}.getId must return its ResourceLocation field"
            )
        }
        listOf("serializeAdvancement", "getAdvancementId").forEach { name ->
            val returned = singleReturnedExpression(
                methods.single { it.nameAsString == name },
                "${candidate.canonical}.$name"
            )
            if (returned !is NullLiteralExpr) {
                failRawRecipe(
                    "${candidate.canonical}.$name must return null to preserve no advancement"
                )
            }
        }

        val recipe = targetRecipeFor(
            serializerExpression,
            candidate.file,
            files,
            index,
            candidate.canonical
        )
        return RawResultPlan(
            candidate = candidate,
            build = build,
            recipeCanonical = recipe
        )
    }

    private fun looksLikeRawShapedResult(
        result: ClassOrInterfaceDeclaration,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val fields = result.fields
            .filterNot(FieldDeclaration::isStatic)
            .flatMap { it.variables }
        if (fields.size != RawRecipeRole.entries.size) return false
        val roles = fields.mapNotNull { rawRecipeRole(it.type, file, index) }
        if (
            roles.size != fields.size ||
            roles.toSet() != RawRecipeRole.entries.toSet()
        ) return false
        val serialize = result.methods.singleOrNull {
            it.nameAsString == "serializeRecipeData"
        } ?: return false
        return serialize.body.orElse(null)?.statements?.size == 15
    }

    private fun requireRawMethod(
        method: MethodDeclaration,
        candidate: RawResultCandidate,
        index: RawRecipeTypeIndex,
        returnType: String,
        parameterTypes: List<String> = emptyList()
    ) {
        if (
            !method.isPublic ||
            method.isStatic ||
            method.modifiers.size != 1 ||
            method.typeParameters.isNotEmpty() ||
            method.thrownExceptions.isNotEmpty() ||
            index.resolve(method.type, candidate.file) != returnType ||
            method.parameters.map {
                index.resolve(it.type, candidate.file)
            } != parameterTypes
        ) {
            failRawRecipe(
                "${candidate.canonical}.${method.nameAsString} has an unsupported legacy signature"
            )
        }
    }

    private fun exactRawResultFields(
        result: ClassOrInterfaceDeclaration,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Map<RawRecipeRole, VariableDeclarator> {
        if (
            result.members.any {
                it !is FieldDeclaration &&
                    it !is ConstructorDeclaration &&
                    it !is MethodDeclaration
            } ||
            result.fields.any { field ->
                field.isStatic ||
                    !field.isPrivate ||
                    field.variables.size != 1 ||
                    field.variables.single().initializer.isPresent
            }
        ) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} contains unsupported result state"
            )
        }
        val instanceFields = result.fields
            .filterNot(FieldDeclaration::isStatic)
            .flatMap { it.variables }
        if (instanceFields.size != RawRecipeRole.entries.size) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} must have exactly seven result fields"
            )
        }
        val roles = instanceFields.map { field ->
            rawRecipeRole(field.type, file, index) to field
        }
        if (
            roles.any { it.first == null } ||
            roles.mapNotNull { it.first }.toSet() != RawRecipeRole.entries.toSet()
        ) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} fields do not match the complete " +
                    "id/result/count/pattern/key/mirror/conditions shape"
            )
        }
        return roles.associate { it.first!! to it.second }
    }

    private fun exactRawResultConstructor(
        result: ClassOrInterfaceDeclaration,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex,
        fields: Map<RawRecipeRole, VariableDeclarator>
    ) {
        val constructor = result.constructors.singleOrNull()
            ?: failRawRecipe(
                "${rawCanonicalName(result, file.unit)} must have one constructor"
            )
        if (!constructor.isPublic || constructor.parameters.size != fields.size) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} constructor must be public and complete"
            )
        }
        if (
            constructor.modifiers.size != 1 ||
            constructor.typeParameters.isNotEmpty() ||
            constructor.thrownExceptions.isNotEmpty() ||
            constructor.body.statements.size != fields.size
        ) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} constructor must only assign its fields"
            )
        }
        val parameterRoles = constructor.parameters.associate { parameter ->
            val role = rawRecipeRole(parameter.type, file, index)
                ?: failRawRecipe(
                    "${rawCanonicalName(result, file.unit)} has an unsupported constructor parameter"
                )
            role to parameter.nameAsString
        }
        if (parameterRoles.keys != RawRecipeRole.entries.toSet()) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} constructor roles are incomplete"
            )
        }
        val assignments = constructor.body.statements.mapNotNull { statement ->
            val assign = statement.asExpressionStmtOrNull()?.expression
                as? AssignExpr ?: return@mapNotNull null
            if (assign.operator != AssignExpr.Operator.ASSIGN) {
                return@mapNotNull null
            }
            val target = assign.target as? FieldAccessExpr
                ?: return@mapNotNull null
            if (target.scope !is ThisExpr) return@mapNotNull null
            val field = target.nameAsString
            val parameter = (assign.value as? NameExpr)?.nameAsString
                ?: return@mapNotNull null
            field to parameter
        }
        if (
            assignments.size != fields.size ||
            assignments.map { it.first }.toSet().size != fields.size ||
            assignments.map { it.second }.toSet().size != fields.size
        ) {
            failRawRecipe(
                "${rawCanonicalName(result, file.unit)} constructor must only assign its fields"
            )
        }
        RawRecipeRole.entries.forEach { role ->
            val expected = fields.getValue(role).nameAsString to
                parameterRoles.getValue(role)
            if (expected !in assignments) {
                failRawRecipe(
                    "${rawCanonicalName(result, file.unit)} constructor does not preserve $role"
                )
            }
        }
    }

    private fun exactRawBuildMethod(
        candidate: RawResultCandidate,
        index: RawRecipeTypeIndex,
        resultFields: Map<RawRecipeRole, VariableDeclarator>
    ): RawBuildPlan {
        val builds = candidate.outer.methods.filter { method ->
            method.nameAsString == "build" &&
                method.isPublic &&
                !method.isStatic &&
                index.resolve(method.type, candidate.file) == "void" &&
                method.parameters.size == 2 &&
                index.resolve(method.parameters[0].type, candidate.file) ==
                RAW_RECIPE_OUTPUT &&
                index.resolve(method.parameters[1].type, candidate.file) ==
                RAW_RESOURCE_LOCATION
        }
        val matches = builds.mapNotNull { method ->
            val statements = method.body.orElse(null)?.statements
                ?: return@mapNotNull null
            if (statements.size != 2) return@mapNotNull null
            val id = method.parameters[1].nameAsString
            val validate = statements[0].asExpressionStmtOrNull()
                ?.expression as? MethodCallExpr ?: return@mapNotNull null
            if (
                validate.nameAsString != "validate" ||
                validate.arguments.singleOrNull()?.toString() != id
            ) return@mapNotNull null
            val statement = statements[1] as? ExpressionStmt
                ?: return@mapNotNull null
            val accept = statement.expression as? MethodCallExpr
                ?: return@mapNotNull null
            val output = method.parameters[0].nameAsString
            if (
                accept.nameAsString != "accept" ||
                accept.scope.orElse(null)?.toString() != output ||
                accept.arguments.size != 1
            ) return@mapNotNull null
            val creation = accept.arguments.single() as? ObjectCreationExpr
                ?: return@mapNotNull null
            if (
                creation.anonymousClassBody.isPresent ||
                index.resolve(creation.type, candidate.file) != candidate.canonical ||
                creation.arguments.size != RawRecipeRole.entries.size
            ) return@mapNotNull null
            val constructor = candidate.result.constructors.single()
            val roleByParameter = constructor.parameters.map {
                rawRecipeRole(it.type, candidate.file, index)
                    ?: return@mapNotNull null
            }
            val arguments = roleByParameter.zip(creation.arguments)
                .associate { it.first to it.second }
            if (arguments.getValue(RawRecipeRole.ID).toString() != id) {
                return@mapNotNull null
            }
            val outerFields = candidate.outer.fields
                .filterNot(FieldDeclaration::isStatic)
                .flatMap { it.variables }
            for (role in RawRecipeRole.entries - RawRecipeRole.ID) {
                val expression = arguments.getValue(role)
                val fieldName = rawFieldName(expression) ?: return@mapNotNull null
                val field = outerFields.singleOrNull {
                    it.nameAsString == fieldName &&
                        rawRecipeRole(it.type, candidate.file, index) == role
                } ?: return@mapNotNull null
                if (
                    rawRecipeRole(
                        resultFields.getValue(role).type,
                        candidate.file,
                        index
                    ) != role
                ) return@mapNotNull null
                if (field.nameAsString != fieldName) return@mapNotNull null
            }
            RawBuildPlan(
                acceptStatement = statement,
                output = output,
                id = id,
                arguments = arguments,
                itemKeyOwner = defaultItemKeyOwner(
                    candidate,
                    index,
                    arguments.getValue(RawRecipeRole.RESULT)
                )
            )
        }
        return matches.singleOrNull()
            ?: failRawRecipe(
                "${candidate.canonical} must be constructed once in a two-statement " +
                    "RecipeOutput build method"
            )
    }

    private fun defaultItemKeyOwner(
        candidate: RawResultCandidate,
        index: RawRecipeTypeIndex,
        resultExpression: Expression
    ): String {
        val resultField = rawFieldName(resultExpression)
            ?: failRawRecipe(
                "${candidate.canonical} build result must come from its owning builder"
            )
        val owners = candidate.outer.methods.mapNotNull { method ->
            if (
                method.nameAsString != "build" ||
                !method.isPublic ||
                method.isStatic ||
                index.resolve(method.type, candidate.file) != "void" ||
                method.parameters.size != 1 ||
                index.resolve(method.parameters.single().type, candidate.file) !=
                RAW_RECIPE_OUTPUT
            ) return@mapNotNull null
            val call = method.body.orElse(null)?.statements?.singleOrNull()
                ?.asExpressionStmtOrNull()?.expression as? MethodCallExpr
                ?: return@mapNotNull null
            val output = method.parameters.single().nameAsString
            if (
                call.nameAsString != "build" ||
                call.arguments.size != 2 ||
                call.arguments[0].toString() != output ||
                call.scope.orElse(null)?.let {
                    it !is ThisExpr
                } == true
            ) return@mapNotNull null
            val key = call.arguments[1] as? MethodCallExpr
                ?: return@mapNotNull null
            if (
                key.nameAsString != "getKeyOrThrow" ||
                key.arguments.singleOrNull()?.let(::rawFieldName) != resultField
            ) return@mapNotNull null
            key.scope.orElse(null)?.let {
                canonicalRawExpression(it, candidate.file, index)
            }
        }.distinct()
        return owners.singleOrNull()
            ?: failRawRecipe(
                "${candidate.canonical} must share one item-key helper with its default build"
            )
    }

    private fun targetRecipeFor(
        serializerExpression: Expression,
        serializerFile: RawRecipeFile,
        files: List<RawRecipeFile>,
        index: RawRecipeTypeIndex,
        resultCanonical: String
    ): String {
        val expectedSerializer = canonicalRawExpression(
            serializerExpression,
            serializerFile,
            index
        )
        val matches = files.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .mapNotNull { owner ->
                    if (
                        owner.isInterface ||
                        owner.isAbstract ||
                        !owner.isPublic ||
                        owner.typeParameters.isNotEmpty() ||
                        owner.extendedTypes.size != 1 ||
                        index.resolve(owner.extendedTypes.single(), file) !=
                        RAW_SHAPED_RECIPE
                    ) return@mapNotNull null
                    val canonical = rawCanonicalName(owner, file.unit)
                        ?: return@mapNotNull null
                    val constructor = owner.constructors.singleOrNull {
                        it.isPublic &&
                            rawConstructorTypes(it, file, index) == listOf(
                            RAW_STRING,
                            RAW_CRAFTING_CATEGORY,
                            RAW_SHAPED_PATTERN,
                            RAW_ITEM_STACK,
                            "boolean"
                        )
                    } ?: return@mapNotNull null
                    val mirror = targetMirror(
                        owner,
                        constructor,
                        file,
                        index
                    ) ?: return@mapNotNull null
                    val getSerializer = owner.methods.singleOrNull {
                        it.nameAsString == "getSerializer" &&
                            it.isPublic &&
                            !it.isStatic &&
                            it.modifiers.size == 1 &&
                            it.parameters.isEmpty() &&
                            it.typeParameters.isEmpty() &&
                            it.thrownExceptions.isEmpty() &&
                            index.resolve(it.type, file) == RAW_RECIPE_SERIALIZER
                    } ?: return@mapNotNull null
                    val returned = runCatching {
                        singleReturnedExpression(
                            getSerializer,
                            "$canonical.getSerializer"
                        )
                    }.getOrNull() ?: return@mapNotNull null
                    if (
                        canonicalRawExpression(returned, file, index) !=
                            expectedSerializer
                    ) return@mapNotNull null
                    if (
                        !hasShapedMirrorCodec(
                            owner,
                            canonical,
                            mirror,
                            file,
                            index
                        )
                    ) {
                        return@mapNotNull null
                    }
                    canonical
                }
        }.distinct()
        return matches.singleOrNull()
            ?: failRawRecipe(
                "$resultCanonical serializer association must resolve to one project " +
                    "ShapedRecipe subclass with the exact target constructor and mirror codec; " +
                    "found ${matches.size}"
            )
    }

    private fun targetMirror(
        owner: ClassOrInterfaceDeclaration,
        constructor: ConstructorDeclaration,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): RawTargetMirror? {
        if (
            constructor.modifiers.size != 1 ||
            constructor.typeParameters.isNotEmpty() ||
            constructor.thrownExceptions.isNotEmpty() ||
            constructor.body.statements.size != 2
        ) return null
        val parameters = constructor.parameters.map { it.nameAsString }
        val invocation = constructor.body.statements[0]
            as? ExplicitConstructorInvocationStmt ?: return null
        if (
            invocation.isThis ||
            invocation.expression.isPresent ||
            invocation.arguments.size !in setOf(4, 5) ||
            invocation.arguments.take(4).map { it.toString() } !=
            parameters.take(4) ||
            (
                invocation.arguments.size == 5 &&
                    invocation.arguments[4].toString() != parameters[4]
                )
        ) return null
        val assignment = constructor.body.statements[1]
            .asExpressionStmtOrNull()?.expression as? AssignExpr
            ?: return null
        if (
            assignment.operator != AssignExpr.Operator.ASSIGN ||
            (assignment.value as? NameExpr)?.nameAsString != parameters[4]
        ) return null
        val target = assignment.target as? FieldAccessExpr ?: return null
        if (target.scope !is ThisExpr) return null
        val field = target.nameAsString
        val declared = owner.fields.flatMap { it.variables }.singleOrNull {
            it.nameAsString == field &&
                index.resolve(it.type, file) == "boolean"
        } ?: return null
        val declaration = declared.parentNode.orElse(null)
            as? FieldDeclaration ?: return null
        if (
            declared.nameAsString != field ||
            declaration.isStatic ||
            declared.initializer.isPresent
        ) return null
        val accessor = owner.methods.singleOrNull { method ->
            method.isPublic &&
                !method.isStatic &&
                method.modifiers.size == 1 &&
                method.parameters.isEmpty() &&
                method.typeParameters.isEmpty() &&
                method.thrownExceptions.isEmpty() &&
                index.resolve(method.type, file) == "boolean" &&
                runCatching {
                    rawFieldName(
                        singleReturnedExpression(
                            method,
                            "${owner.nameAsString}.${method.nameAsString}"
                        )
                    )
                }.getOrNull() == field
        }?.nameAsString ?: return null
        return RawTargetMirror(accessor)
    }

    private fun hasShapedMirrorCodec(
        owner: ClassOrInterfaceDeclaration,
        ownerCanonical: String,
        mirror: RawTargetMirror,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val nested = owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
        return nested.any { serializer ->
            serializer.implementedTypes.singleOrNull()?.let { implemented ->
                index.resolve(implemented, file) == RAW_RECIPE_SERIALIZER &&
                    rawTypeArguments(implemented, file, index) ==
                    listOf(ownerCanonical)
            } == true &&
                serializer.fields.flatMap { it.variables }.any { codec ->
                    codec.parentNode.orElse(null)
                        ?.let { it as? FieldDeclaration }
                        ?.let { it.isStatic && it.isFinal } == true &&
                        index.resolve(codec.type, file) == RAW_MAP_CODEC &&
                        rawTypeArguments(codec.type, file, index) ==
                        listOf(ownerCanonical) &&
                        codec.initializer.map {
                            exactShapedMirrorCodec(
                                it,
                                owner,
                                ownerCanonical,
                                mirror,
                                file,
                                index
                            )
                        }.orElse(false)
                }
        }
    }

    private fun exactShapedMirrorCodec(
        initializer: Expression,
        owner: ClassOrInterfaceDeclaration,
        ownerCanonical: String,
        mirror: RawTargetMirror,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val mapCodec = initializer as? MethodCallExpr ?: return false
        if (
            mapCodec.nameAsString != "mapCodec" ||
            mapCodec.arguments.size != 1 ||
            canonicalTypeScope(mapCodec.scope.orElse(null), file, index) !=
            RAW_RECORD_CODEC_BUILDER
        ) return false
        val lambda = mapCodec.arguments.single() as? LambdaExpr ?: return false
        val instance = lambda.parameters.singleOrNull()?.nameAsString
            ?: return false
        val apply = lambdaExpression(lambda) as? MethodCallExpr ?: return false
        if (
            apply.nameAsString != "apply" ||
            apply.arguments.size != 2 ||
            (apply.arguments[0] as? NameExpr)?.nameAsString != instance
        ) return false
        val factory = apply.arguments[1] as? MethodReferenceExpr ?: return false
        if (
            factory.identifier != "fromShaped" ||
            canonicalTypeScope(factory.scope, file, index) != ownerCanonical
        ) return false
        val group = apply.scope.orElse(null) as? MethodCallExpr ?: return false
        if (
            group.nameAsString != "group" ||
            group.arguments.size != 2 ||
            (group.scope.orElse(null) as? NameExpr)?.nameAsString != instance
        ) return false
        return exactShapedBaseCodec(group.arguments[0], file, index) &&
            exactMirrorCodec(
                group.arguments[1],
                ownerCanonical,
                mirror.accessor,
                file,
                index
            ) &&
            exactFromShapedFactory(
                owner,
                ownerCanonical,
                factory.identifier,
                file,
                index
            )
    }

    private fun exactShapedBaseCodec(
        expression: Expression,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val getter = expression as? MethodCallExpr ?: return false
        if (
            getter.nameAsString != "forGetter" ||
            getter.arguments.size != 1
        ) return false
        val identity = getter.arguments.single() as? LambdaExpr ?: return false
        val parameter = identity.parameters.singleOrNull()?.nameAsString
            ?: return false
        if (
            (lambdaExpression(identity) as? NameExpr)?.nameAsString != parameter
        ) return false
        val codec = getter.scope.orElse(null) as? MethodCallExpr ?: return false
        if (codec.nameAsString != "codec" || codec.arguments.isNotEmpty()) {
            return false
        }
        val shaped = codec.scope.orElse(null) as? FieldAccessExpr ?: return false
        return shaped.nameAsString == "SHAPED_RECIPE" &&
            canonicalTypeScope(shaped.scope, file, index) ==
            RAW_RECIPE_SERIALIZER
    }

    private fun exactMirrorCodec(
        expression: Expression,
        ownerCanonical: String,
        mirrorAccessor: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val getter = expression as? MethodCallExpr ?: return false
        if (
            getter.nameAsString != "forGetter" ||
            getter.arguments.size != 1
        ) return false
        val reference = getter.arguments.single() as? MethodReferenceExpr
            ?: return false
        if (
            reference.identifier != mirrorAccessor ||
            canonicalTypeScope(reference.scope, file, index) != ownerCanonical
        ) return false
        val field = getter.scope.orElse(null) as? MethodCallExpr ?: return false
        if (
            field.nameAsString !in setOf("fieldOf", "optionalFieldOf") ||
            (field.arguments.firstOrNull() as? StringLiteralExpr)?.value !=
            "acceptMirrored"
        ) return false
        if (
            field.nameAsString == "fieldOf" &&
            field.arguments.size != 1
        ) return false
        if (
            field.nameAsString == "optionalFieldOf" &&
            (
                field.arguments.size != 2 ||
                    (field.arguments[1] as? BooleanLiteralExpr)?.value != true
                )
        ) return false
        val bool = field.scope.orElse(null) as? FieldAccessExpr ?: return false
        return bool.nameAsString == "BOOL" &&
            canonicalTypeScope(bool.scope, file, index) == RAW_CODEC
    }

    private fun exactFromShapedFactory(
        owner: ClassOrInterfaceDeclaration,
        ownerCanonical: String,
        methodName: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val factory = owner.methods.singleOrNull { method ->
            method.nameAsString == methodName &&
                method.isStatic &&
                index.resolve(method.type, file) == ownerCanonical &&
                method.parameters.map {
                    index.resolve(it.type, file)
                } == listOf(RAW_SHAPED_RECIPE, "boolean")
        } ?: return false
        val returned = factory.body.orElse(null)?.statements?.singleOrNull()
            as? ReturnStmt ?: return false
        val creation = returned.expression.orElse(null)
            as? ObjectCreationExpr ?: return false
        if (
            creation.anonymousClassBody.isPresent ||
            index.resolve(creation.type, file) != ownerCanonical ||
            creation.arguments.size != 5
        ) return false
        val shaped = factory.parameters[0].nameAsString
        val mirrored = factory.parameters[1].nameAsString
        return exactNoArgCall(creation.arguments[0], shaped, "getGroup") &&
            exactNoArgCall(creation.arguments[1], shaped, "category") &&
            exactPatternExtraction(creation.arguments[2], shaped) &&
            exactResultExtraction(creation.arguments[3], shaped) &&
            (creation.arguments[4] as? NameExpr)?.nameAsString == mirrored
    }

    private fun exactNoArgCall(
        expression: Expression,
        scope: String,
        name: String
    ): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        return call.nameAsString == name &&
            call.arguments.isEmpty() &&
            (call.scope.orElse(null) as? NameExpr)?.nameAsString == scope
    }

    private fun exactPatternExtraction(
        expression: Expression,
        shaped: String
    ): Boolean = when (val value = unwrapRaw(expression)) {
        is FieldAccessExpr ->
            value.nameAsString == "pattern" &&
                (value.scope as? NameExpr)?.nameAsString == shaped
        is MethodCallExpr -> {
            if (value.arguments.isNotEmpty()) {
                false
            } else {
                when (value.nameAsString) {
                    "pattern" ->
                        (value.scope.orElse(null) as? NameExpr)
                            ?.nameAsString == shaped
                    "create\$getPattern" -> {
                        val cast = value.scope.orElse(null)?.let(::unwrapRaw)
                            as? com.github.javaparser.ast.expr.CastExpr
                        cast != null &&
                            (unwrapRaw(cast.expression) as? NameExpr)
                            ?.nameAsString == shaped
                    }
                    else -> false
                }
            }
        }
        else -> false
    }

    private fun exactResultExtraction(
        expression: Expression,
        shaped: String
    ): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        return call.nameAsString == "getResultItem" &&
            (call.scope.orElse(null) as? NameExpr)?.nameAsString == shaped &&
            call.arguments.singleOrNull() is NullLiteralExpr
    }

    private fun lambdaExpression(lambda: LambdaExpr): Expression? =
        lambda.body.asExpressionStmtOrNull()?.expression

    private fun unwrapRaw(expression: Expression): Expression =
        if (expression is EnclosedExpr) unwrapRaw(expression.inner)
        else expression

    private fun validateRawRecipeJson(
        method: MethodDeclaration,
        candidate: RawResultCandidate,
        fields: Map<RawRecipeRole, VariableDeclarator>,
        index: RawRecipeTypeIndex,
        itemKeyOwner: String
    ) {
        if (
            !method.isPublic ||
            method.parameters.size != 1 ||
            index.resolve(method.parameters.single().type, candidate.file) !=
            RAW_JSON_OBJECT
        ) {
            failRawRecipe(
                "${candidate.canonical}.serializeRecipeData must accept one JsonObject"
            )
        }
        val json = method.parameters.single().nameAsString
        val statements = method.body.orElse(null)?.statements
            ?: failRawRecipe(
                "${candidate.canonical}.serializeRecipeData has no body"
            )
        if (statements.size != 15) {
            failRawRecipe(
                "${candidate.canonical}.serializeRecipeData must retain all 15 raw JSON steps"
            )
        }
        val patternArray = rawLocalNew(
            statements[0],
            RAW_JSON_ARRAY,
            candidate.file,
            index
        ) ?: failRawRecipe("${candidate.canonical} is missing its pattern array")
        if (!rawPatternLoop(
                statements[1],
                fields.getValue(RawRecipeRole.PATTERN).nameAsString,
                patternArray,
                candidate.file,
                index
            ) ||
            !rawAdd(
                statements[2],
                json,
                "pattern",
                patternArray
            )
        ) failRawRecipe("${candidate.canonical} does not preserve pattern JSON")

        val keyObject = rawLocalNew(
            statements[3],
            RAW_JSON_OBJECT,
            candidate.file,
            index
        ) ?: failRawRecipe("${candidate.canonical} is missing its key object")
        if (!rawKeyLoop(
                statements[4],
                fields.getValue(RawRecipeRole.KEY).nameAsString,
                keyObject,
                candidate.file,
                index
            ) ||
            !rawAdd(statements[5], json, "key", keyObject)
        ) failRawRecipe("${candidate.canonical} does not preserve key JSON")

        val resultObject = rawLocalNew(
            statements[6],
            RAW_JSON_OBJECT,
            candidate.file,
            index
        ) ?: failRawRecipe("${candidate.canonical} is missing its result object")
        if (!rawItemProperty(
                statements[7],
                resultObject,
                fields.getValue(RawRecipeRole.RESULT).nameAsString,
                itemKeyOwner,
                candidate.file,
                index
            ) ||
            !rawCountProperty(
                statements[8],
                resultObject,
                fields.getValue(RawRecipeRole.COUNT).nameAsString
            ) ||
            !rawAdd(statements[9], json, "result", resultObject) ||
            !rawMirrorProperty(
                statements[10],
                json,
                fields.getValue(RawRecipeRole.MIRRORED).nameAsString
            )
        ) failRawRecipe("${candidate.canonical} does not preserve result or mirror JSON")

        val conditions = fields.getValue(RawRecipeRole.CONDITIONS).nameAsString
        if (!rawEmptyReturn(statements[11], conditions)) {
            failRawRecipe("${candidate.canonical} does not preserve empty conditions")
        }
        val conditionArray = rawLocalNew(
            statements[12],
            RAW_JSON_ARRAY,
            candidate.file,
            index
        ) ?: failRawRecipe("${candidate.canonical} is missing its conditions array")
        if (
            !rawConditionLoop(
                statements[13],
                conditions,
                conditionArray,
                candidate.file,
                index
            ) ||
            !rawAdd(statements[14], json, "conditions", conditionArray)
        ) failRawRecipe("${candidate.canonical} does not preserve conditions JSON")
    }

    private fun rawPatternLoop(
        statement: Statement,
        patternField: String,
        array: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val loop = statement as? ForEachStmt ?: return false
        if (
            loop.variable.variables.size != 1 ||
            index.resolve(loop.variable.variables.single().type, file) !=
                RAW_STRING ||
            rawFieldName(loop.iterable) != patternField
        ) return false
        val element = loop.variable.variables.single().nameAsString
        return compactRawRecipe(loop.body.toString()) ==
            "$array.add($element);"
    }

    private fun rawKeyLoop(
        statement: Statement,
        keyField: String,
        jsonObject: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val loop = statement as? ForEachStmt ?: return false
        if (loop.variable.variables.size != 1) return false
        val variable = loop.variable.variables.single()
        if (
            index.resolve(variable.type, file) != RAW_MAP_ENTRY ||
            rawTypeArguments(variable.type, file, index) !=
            listOf(RAW_CHARACTER, RAW_INGREDIENT)
        ) return false
        val entry = variable.nameAsString
        val iterable = loop.iterable as? MethodCallExpr ?: return false
        if (
            iterable.nameAsString != "entrySet" ||
            rawFieldName(iterable.scope.orElse(null)) != keyField
        ) return false
        val add = rawCall(loop.body, jsonObject, "add") ?: return false
        if (
            add.arguments.size != 2 ||
            !rawEntryKeyJson(add.arguments[0], entry, file, index)
        ) return false
        return rawLegacyIngredientJson(add.arguments[1], entry) ||
            rawIngredientCodecJson(add.arguments[1], entry, file, index)
    }

    private fun rawEntryKeyJson(
        expression: Expression,
        entry: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val valueOf = expression as? MethodCallExpr ?: return false
        return valueOf.nameAsString == "valueOf" &&
            valueOf.arguments.singleOrNull()?.let {
                rawMapEntryAccessor(it, entry, "getKey")
            } == true &&
            canonicalTypeScope(valueOf.scope.orElse(null), file, index) ==
            RAW_STRING
    }

    private fun rawLegacyIngredientJson(
        expression: Expression,
        entry: String
    ): Boolean {
        val toJson = expression as? MethodCallExpr ?: return false
        return toJson.nameAsString == "toJson" &&
            toJson.arguments.isEmpty() &&
            toJson.scope.orElse(null)?.let {
                rawMapEntryAccessor(it, entry, "getValue")
            } == true
    }

    private fun rawIngredientCodecJson(
        expression: Expression,
        entry: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val getOrThrow = expression as? MethodCallExpr ?: return false
        if (
            getOrThrow.nameAsString != "getOrThrow" ||
            getOrThrow.arguments.isNotEmpty()
        ) return false
        val encode = getOrThrow.scope.orElse(null)
            as? MethodCallExpr ?: return false
        if (
            encode.nameAsString != "encodeStart" ||
            encode.arguments.size != 2 ||
            !rawMapEntryAccessor(
                encode.arguments[1],
                entry,
                "getValue"
            )
        ) return false
        val codec = encode.scope.orElse(null) as? FieldAccessExpr
            ?: return false
        if (
            codec.nameAsString != "CODEC" ||
            canonicalTypeScope(codec.scope, file, index) != RAW_INGREDIENT
        ) return false
        val ops = encode.arguments[0] as? FieldAccessExpr ?: return false
        return ops.nameAsString == "INSTANCE" &&
            canonicalTypeScope(ops.scope, file, index) == RAW_JSON_OPS
    }

    private fun rawMapEntryAccessor(
        expression: Expression,
        entry: String,
        accessor: String
    ): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        return call.nameAsString == accessor &&
            call.arguments.isEmpty() &&
            (call.scope.orElse(null) as? NameExpr)?.nameAsString == entry
    }

    private fun rawItemProperty(
        statement: Statement,
        resultObject: String,
        resultField: String,
        expectedOwner: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val call = rawCall(statement, resultObject, "addProperty")
            ?: return false
        if (
            call.arguments.size != 2 ||
            (call.arguments[0] as? StringLiteralExpr)?.value != "item"
        ) return false
        val toString = call.arguments[1] as? MethodCallExpr ?: return false
        if (toString.nameAsString != "toString" || toString.arguments.isNotEmpty()) {
            return false
        }
        val key = toString.scope.orElse(null) as? MethodCallExpr ?: return false
        return key.nameAsString == "getKeyOrThrow" &&
            key.arguments.singleOrNull()?.let(::rawFieldName) == resultField &&
            key.scope.orElse(null)?.let {
                canonicalRawExpression(it, file, index)
            } == expectedOwner
    }

    private fun rawCountProperty(
        statement: Statement,
        resultObject: String,
        countField: String
    ): Boolean {
        val branch = statement as? IfStmt ?: return false
        if (branch.elseStmt.isPresent) return false
        val condition = branch.condition as? BinaryExpr ?: return false
        if (
            condition.operator != BinaryExpr.Operator.GREATER ||
            rawFieldName(condition.left) != countField ||
            condition.right.toString() != "1"
        ) return false
        val call = rawCall(branch.thenStmt, resultObject, "addProperty")
            ?: return false
        return call.arguments.size == 2 &&
            (call.arguments[0] as? StringLiteralExpr)?.value == "count" &&
            rawFieldName(call.arguments[1]) == countField
    }

    private fun rawMirrorProperty(
        statement: Statement,
        json: String,
        mirrorField: String
    ): Boolean {
        val call = rawCall(statement, json, "addProperty") ?: return false
        return call.arguments.size == 2 &&
            (call.arguments[0] as? StringLiteralExpr)?.value ==
            "acceptMirrored" &&
            rawFieldName(call.arguments[1]) == mirrorField
    }

    private fun rawEmptyReturn(
        statement: Statement,
        conditionsField: String
    ): Boolean {
        val branch = statement as? IfStmt ?: return false
        if (branch.elseStmt.isPresent) return false
        val condition = branch.condition as? MethodCallExpr ?: return false
        return condition.nameAsString == "isEmpty" &&
            rawFieldName(condition.scope.orElse(null)) == conditionsField &&
            branch.thenStmt is ReturnStmt &&
            !(branch.thenStmt as ReturnStmt).expression.isPresent
    }

    private fun rawConditionLoop(
        statement: Statement,
        conditionsField: String,
        array: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val call = statement.asExpressionStmtOrNull()?.expression
            as? MethodCallExpr ?: return false
        if (
            call.nameAsString != "forEach" ||
            rawFieldName(call.scope.orElse(null)) != conditionsField
        ) return false
        val lambda = call.arguments.singleOrNull() as? LambdaExpr ?: return false
        val parameter = lambda.parameters.singleOrNull()?.nameAsString
            ?: return false
        val add = lambda.body.asExpressionStmtOrNull()?.expression
            as? MethodCallExpr ?: return false
        if (
            add.nameAsString != "add" ||
            add.scope.orElse(null)?.toString() != array ||
            add.arguments.size != 1
        ) return false
        return exactConditionJson(
            add.arguments.single(),
            parameter,
            file,
            index
        )
    }

    private fun exactConditionJson(
        expression: Expression,
        condition: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): Boolean {
        val helper = expression as? MethodCallExpr
        if (
            helper?.nameAsString == "serialize" &&
            helper.arguments.singleOrNull()?.toString() == condition &&
            canonicalTypeScope(helper.scope.orElse(null), file, index) ==
            RAW_CRAFTING_HELPER
        ) return true

        val asJson = expression as? MethodCallExpr ?: return false
        if (
            asJson.nameAsString != "getAsJsonObject" ||
            asJson.arguments.isNotEmpty()
        ) return false
        val getOrThrow = asJson.scope.orElse(null)
            as? MethodCallExpr ?: return false
        if (
            getOrThrow.nameAsString != "getOrThrow" ||
            getOrThrow.arguments.isNotEmpty()
        ) return false
        val encode = getOrThrow.scope.orElse(null)
            as? MethodCallExpr ?: return false
        if (
            encode.nameAsString != "encodeStart" ||
            encode.arguments.size != 2 ||
            encode.arguments[1].toString() != condition
        ) return false
        val codec = encode.scope.orElse(null) as? FieldAccessExpr ?: return false
        if (
            codec.nameAsString != "CODEC" ||
            canonicalTypeScope(codec.scope, file, index) != RAW_I_CONDITION
        ) return false
        val ops = encode.arguments[0] as? FieldAccessExpr ?: return false
        return ops.nameAsString == "INSTANCE" &&
            canonicalTypeScope(ops.scope, file, index) == RAW_JSON_OPS
    }

    private fun rawAdd(
        statement: Statement,
        json: String,
        property: String,
        value: String
    ): Boolean {
        val call = rawCall(statement, json, "add") ?: return false
        return call.arguments.size == 2 &&
            (call.arguments[0] as? StringLiteralExpr)?.value == property &&
            call.arguments[1].toString() == value
    }

    private fun rawCall(
        statement: Statement,
        scope: String,
        name: String
    ): MethodCallExpr? {
        val expression = statement.asExpressionStmtOrNull()?.expression
            as? MethodCallExpr ?: return null
        return expression.takeIf {
            it.nameAsString == name &&
                it.scope.orElse(null)?.toString() == scope
        }
    }

    private fun rawLocalNew(
        statement: Statement,
        expectedType: String,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): String? {
        val declaration = statement.asExpressionStmtOrNull()?.expression
            ?.takeIf {
                it is com.github.javaparser.ast.expr.VariableDeclarationExpr
            } as? com.github.javaparser.ast.expr.VariableDeclarationExpr
            ?: return null
        val variable = declaration.variables.singleOrNull() ?: return null
        if (index.resolve(variable.type, file) != expectedType) return null
        val creation = variable.initializer.orElse(null) as? ObjectCreationExpr
            ?: return null
        if (
            index.resolve(creation.type, file) != expectedType ||
            creation.arguments.isNotEmpty() ||
            creation.anonymousClassBody.isPresent
        ) return null
        return variable.nameAsString
    }

    private fun applyRawResultPlan(plan: RawResultPlan) {
        val args = plan.build.arguments
        val output = plan.build.output
        val id = plan.build.id
        val replacement = StaticJavaParser.parseStatement(
            """
            $output.accept(
                $id,
                new ${plan.recipeCanonical}(
                    "",
                    net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                    net.minecraft.world.item.crafting.ShapedRecipePattern.of(
                        ${args.getValue(RawRecipeRole.KEY)},
                        ${args.getValue(RawRecipeRole.PATTERN)}),
                    new net.minecraft.world.item.ItemStack(
                        ${args.getValue(RawRecipeRole.RESULT)},
                        ${args.getValue(RawRecipeRole.COUNT)}),
                    ${args.getValue(RawRecipeRole.MIRRORED)}),
                null,
                ${args.getValue(RawRecipeRole.CONDITIONS)}.toArray(
                    new net.neoforged.neoforge.common.conditions.ICondition[0]));
            """.trimIndent()
        )
        plan.build.acceptStatement.replace(replacement)
        if (!plan.candidate.result.remove()) {
            failRawRecipe(
                "Could not remove admitted raw result ${plan.candidate.canonical}"
            )
        }
    }

    private fun auditRawResultReferences(
        files: List<RawRecipeFile>,
        index: RawRecipeTypeIndex,
        plans: List<RawResultPlan>
    ) {
        plans.forEach { plan ->
            val creations = files.flatMap { file ->
                file.unit.findAll(ObjectCreationExpr::class.java)
                    .filter {
                        index.resolve(it.type, file) ==
                            plan.candidate.canonical
                    }
            }
            val expected = plan.build.acceptStatement.expression
                .asMethodCallExpr().arguments.single()
            if (creations.size != 1 || creations.single() !== expected) {
                failRawRecipe(
                    "${plan.candidate.canonical} must have exactly one admitted construction"
                )
            }
            files.forEach { file ->
                file.unit.findAll(ClassOrInterfaceType::class.java).forEach {
                    if (index.resolve(it, file) != plan.candidate.canonical) {
                        return@forEach
                    }
                    val parent = it.parentNode.orElse(null)
                    if (
                        parent !is ObjectCreationExpr ||
                        parent !== expected
                    ) {
                        failRawRecipe(
                            "${plan.candidate.canonical} escapes its closed build boundary " +
                                "in ${file.path}"
                        )
                    }
                }
            }
        }
    }

    private fun rawRecipeRole(
        type: Type,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): RawRecipeRole? {
        val raw = index.resolve(type, file)
        return when {
            raw == RAW_RESOURCE_LOCATION -> RawRecipeRole.ID
            raw == RAW_ITEM -> RawRecipeRole.RESULT
            raw == "int" -> RawRecipeRole.COUNT
            raw == "boolean" -> RawRecipeRole.MIRRORED
            raw == RAW_LIST &&
                rawTypeArguments(type, file, index) ==
                listOf(RAW_STRING) -> RawRecipeRole.PATTERN
            raw == RAW_LIST &&
                rawTypeArguments(type, file, index) ==
                listOf(RAW_I_CONDITION) -> RawRecipeRole.CONDITIONS
            raw == RAW_MAP &&
                rawTypeArguments(type, file, index) ==
                listOf(RAW_CHARACTER, RAW_INGREDIENT) -> RawRecipeRole.KEY
            else -> null
        }
    }

    private fun rawTypeArguments(
        type: Type,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): List<String> {
        val declared = type as? ClassOrInterfaceType ?: return emptyList()
        return declared.typeArguments.orElse(null)?.mapNotNull {
            index.resolve(it, file)
        }.orEmpty()
    }

    private fun rawConstructorTypes(
        constructor: ConstructorDeclaration,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): List<String> = constructor.parameters.mapNotNull {
        index.resolve(it.type, file)
    }.takeIf { it.size == constructor.parameters.size }.orEmpty()

    private fun singleReturnedExpression(
        method: MethodDeclaration,
        label: String
    ): Expression {
        if (method.parameters.isNotEmpty()) {
            failRawRecipe("$label must have no parameters")
        }
        val statement = method.body.orElse(null)?.statements?.singleOrNull()
            as? ReturnStmt
            ?: failRawRecipe("$label must contain one return statement")
        return statement.expression.orElse(null)
            ?: failRawRecipe("$label must return a value")
    }

    private fun rawFieldName(expression: Expression?): String? =
        when (expression) {
            is NameExpr -> expression.nameAsString
            is FieldAccessExpr ->
                expression.nameAsString.takeIf { expression.scope is ThisExpr }
            else -> null
        }

    private fun canonicalTypeScope(
        expression: Expression?,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): String? = expression?.let {
        index.resolveSpelling(it.toString(), file)
    }

    private fun canonicalRawExpression(
        expression: Expression,
        file: RawRecipeFile,
        index: RawRecipeTypeIndex
    ): String = when (expression) {
        is NameExpr ->
            index.resolveSpelling(expression.nameAsString, file)
                ?.let { "type:$it" }
                ?: "name:${expression.nameAsString}"
        is FieldAccessExpr ->
            "${canonicalRawExpression(expression.scope, file, index)}" +
                ".field:${expression.nameAsString}"
        is MethodCallExpr -> {
            val scope = expression.scope.map {
                canonicalRawExpression(it, file, index)
            }.orElse("<implicit>")
            val arguments = expression.arguments.joinToString(",") {
                canonicalRawExpression(it, file, index)
            }
            "$scope.call:${expression.nameAsString}($arguments)"
        }
        is ThisExpr -> "this"
        else -> compactRawRecipe(expression.toString())
    }

    private fun Statement.asExpressionStmtOrNull(): ExpressionStmt? =
        this as? ExpressionStmt

    private fun compactRawRecipe(text: String): String =
        text.replace(Regex("""\s+"""), "")
}

private enum class RawRecipeRole {
    ID,
    RESULT,
    COUNT,
    PATTERN,
    KEY,
    MIRRORED,
    CONDITIONS
}

private data class RawRecipeFile(
    val path: Path,
    val source: String,
    val unit: CompilationUnit
)

private data class RawRecipePrepared(
    val file: RawRecipeFile,
    val migrated: String
)

private data class RawResultCandidate(
    val canonical: String,
    val result: ClassOrInterfaceDeclaration,
    val outer: ClassOrInterfaceDeclaration,
    val file: RawRecipeFile
)

private data class RawBuildPlan(
    val acceptStatement: ExpressionStmt,
    val output: String,
    val id: String,
    val arguments: Map<RawRecipeRole, Expression>,
    val itemKeyOwner: String
)

private data class RawResultPlan(
    val candidate: RawResultCandidate,
    val build: RawBuildPlan,
    val recipeCanonical: String
)

private data class RawTargetMirror(
    val accessor: String
)

private class RawRecipeTypeIndex(files: List<RawRecipeFile>) {
    private val types = files.flatMap { file ->
        file.unit.findAll(TypeDeclaration::class.java).mapNotNull {
            rawCanonicalName(it, file.unit)
        }
    }.toSet()

    fun resolve(type: Type, file: RawRecipeFile): String? {
        if (type.isPrimitiveType) return type.asString()
        if (type.isVoidType) return "void"
        if (type.isArrayType) {
            return resolve(type.asArrayType().componentType, file)?.plus("[]")
        }
        return resolve(type as? ClassOrInterfaceType ?: return null, file)
    }

    fun resolve(type: ClassOrInterfaceType, file: RawRecipeFile): String? {
        val spelling = type.nameWithScope
        if (spelling.firstOrNull()?.isLowerCase() == true) return spelling
        rawJavaLangType(spelling)?.let { return it }

        val first = spelling.substringBefore('.')
        val suffix = spelling.removePrefix(first)
        val local = file.unit.findAll(TypeDeclaration::class.java)
            .mapNotNull {
                rawCanonicalName(it, file.unit)?.takeIf { canonical ->
                    canonical.substringAfterLast('.')
                        .substringAfterLast('$') == first
                }
            }.distinct()
        if (local.isNotEmpty()) return local.singleOrNull()?.plus(suffix)

        val explicit = file.unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == first
        }.map { it.nameAsString }.distinct()
        if (explicit.isNotEmpty()) {
            return explicit.singleOrNull()?.plus(suffix)
        }

        val packageName = file.unit.packageDeclaration
            .map { it.nameAsString }.orElse("")
        val samePackage = types.filter {
            it.substringAfterLast('.').substringAfterLast('$') == first &&
                it.substringBeforeLast('.', "") == packageName
        }
        if (samePackage.isNotEmpty()) {
            return samePackage.singleOrNull()?.plus(suffix)
        }

        val wildcard = file.unit.imports.filter {
            !it.isStatic && it.isAsterisk
        }.map { "${it.nameAsString}.$spelling" }
            .filter { it in types || it in rawKnownTypes }
            .distinct()
        return wildcard.singleOrNull()
    }

    fun resolveSpelling(spelling: String, file: RawRecipeFile): String? {
        val parsed = runCatching {
            StaticJavaParser.parseClassOrInterfaceType(spelling)
        }.getOrNull() ?: return null
        return resolve(parsed, file)
    }
}

private val rawKnownTypes = setOf(
    RAW_RECIPE_OUTPUT,
    RAW_FINISHED_RECIPE,
    RAW_RESOURCE_LOCATION,
    RAW_ITEM,
    RAW_ITEM_LIKE,
    RAW_ITEM_STACK,
    RAW_INGREDIENT,
    RAW_SHAPED_RECIPE,
    RAW_SHAPED_PATTERN,
    RAW_CRAFTING_CATEGORY,
    RAW_RECIPE_SERIALIZER,
    RAW_I_CONDITION,
    RAW_JSON_OBJECT,
    RAW_JSON_ARRAY,
    RAW_LIST,
    RAW_MAP,
    RAW_MAP_ENTRY,
    RAW_MAP_CODEC,
    RAW_CODEC,
    RAW_RECORD_CODEC_BUILDER,
    RAW_JSON_OPS,
    RAW_CRAFTING_HELPER
)

private fun rawJavaLangType(spelling: String): String? = when (spelling) {
    "String" -> RAW_STRING
    "Character" -> RAW_CHARACTER
    else -> null
}

private fun rawCanonicalName(
    declaration: TypeDeclaration<*>,
    unit: CompilationUnit
): String? {
    val names = mutableListOf<String>()
    var current: Node = declaration
    while (current is TypeDeclaration<*>) {
        names += current.nameAsString
        current = current.parentNode.orElse(null) ?: return null
    }
    if (current !== unit) return null
    val packageName = unit.packageDeclaration
        .map { it.nameAsString }.orElse("")
    val nested = names.asReversed().joinToString(".")
    return if (packageName.isBlank()) nested else "$packageName.$nested"
}

private fun failRawRecipe(message: String): Nothing =
    throw IllegalStateException(message)
