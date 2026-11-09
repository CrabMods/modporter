package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MarkerAnnotationExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Replaces one closed legacy datagen-only Ingredient subclass family with an
 * Ingredient.Value codec that exists only in the datagen serialization path.
 *
 * The admitted legacy class has exactly two stored constructor arguments,
 * invokes `super(Stream.empty())`, and writes only
 * `{"item": first.asResource(second).toString()}`. Every constructor consumer
 * must add the value to a source-proven Collection<Ingredient>. Runtime
 * Ingredient decoding is untouched: the generated mixin extends Value.MAP_CODEC
 * only while NeoForge reports that datagen is active.
 */
internal object DatagenIngredientValues {
    const val RULE_ID = "struct-datagen-ingredient-value"

    private const val INGREDIENT = "net.minecraft.world.item.crafting.Ingredient"
    private const val INGREDIENT_VALUE =
        "net.minecraft.world.item.crafting.Ingredient.Value"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val STRING = "java.lang.String"
    private const val STREAM = "java.util.stream.Stream"
    private const val JSON_ELEMENT = "com.google.gson.JsonElement"
    private const val JSON_OBJECT = "com.google.gson.JsonObject"
    private const val JAVA_LANG_OVERRIDE = "java.lang.Override"
    private const val DATAGEN_LOADER =
        "net.neoforged.neoforge.data.loading.DatagenModLoader"
    private const val MIXIN_EXTRAS =
        "com.llamalad7.mixinextras.injector.ModifyExpressionValue"
    private const val MIXIN_NAME = "ModporterDatagenIngredientValueMixin"
    private const val MIXIN_TEMPLATE =
        "/compat/datagen-ingredient-value-mixin.java.template"
    private const val INGREDIENT_VALUE_CONSTRUCTOR =
        "([Lnet/minecraft/world/item/crafting/Ingredient\$Value;)V"
    private const val ACCESS_TRANSFORMER_ENTRY =
        "public net.minecraft.world.item.crafting.Ingredient " +
            "<init>([Lnet/minecraft/world/item/crafting/Ingredient\$Value;)V"

    private val metadataJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): Result = try {
        migrateChecked(pass, projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Datagen Ingredient admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Datagen Ingredient migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): Result {
        val javaFiles = javaFiles(projectDir)
        if (javaFiles.isEmpty()) return Result.EMPTY
        val captured = javaFiles.associateWith { it.readText() }
        if (captured.values.none {
                it.contains("Ingredient") &&
                    Regex("""\bextends\s+(?:[\w.]+\.)?Ingredient\b""").containsMatchIn(
                        JavaSourceText.maskCommentsAndLiterals(it)
                    )
            }
        ) return Result.EMPTY

        val facts = ProjectValueFacts.collect(pass, javaFiles)
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val parsed = mutableMapOf<Path, ParsedSource>()
        fun parse(path: Path): ParsedSource = parsed.getOrPut(path) {
            val source = captured.getValue(path)
            val result = parser.parse(source)
            if (!result.isSuccessful || result.result.isEmpty) {
                abort(
                    "Datagen Ingredient preflight could not parse $path: " +
                        result.problems.joinToString("; ") { it.verboseMessage }
                            .ifBlank { "unknown parse failure" }
                )
            }
            ParsedSource(path, source, result.result.orElseThrow())
        }

        val candidates = captured.entries.flatMap { (path, source) ->
            val code = JavaSourceText.maskCommentsAndLiterals(source)
            if (!code.contains("Ingredient") || !code.contains("extends")) {
                emptyList()
            } else {
                val file = parse(path)
                file.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                    .filter { owner ->
                        !owner.isInterface &&
                            owner.extendedTypes.size == 1 &&
                            resolveType(owner.extendedTypes.single(), file, facts) == INGREDIENT
                    }
                    .map { owner -> CandidateSource(file, owner) }
            }
        }
        if (candidates.isEmpty()) return Result.EMPTY
        if (candidates.size != 1) {
            abort(
                "Datagen Ingredient migration requires exactly one legacy Ingredient " +
                    "subclass; found ${candidates.size}"
            )
        }
        if (!targetContractIsVerified()) {
            abort(
                "NeoForge 1.21.1 Ingredient.Value, private Value[] constructor, " +
                    "ResourceLocation codec, or datagen loader contract is not verified"
            )
        }

        val candidate = admitCandidate(candidates.single(), facts)
        val relevant = captured.filterValues { source ->
            Regex("""\b${Regex.escape(candidate.simpleName)}\b""").containsMatchIn(
                JavaSourceText.maskCommentsAndLiterals(source)
            )
        }.keys.map(::parse)
        rejectSourceChildrenAndNonConstructorTypes(candidate, relevant, facts)
        val consumers = collectConsumers(candidate, relevant, facts)
        if (consumers.isEmpty()) {
            abort("${candidate.canonicalName} has no admitted constructor consumers")
        }

        if (captured.values.none {
                it.contains("import $MIXIN_EXTRAS;")
            }
        ) {
            abort(
                "${candidate.canonicalName} requires an existing MixinExtras " +
                    "ModifyExpressionValue dependency before codec registration"
            )
        }

        val mixinConfig = selectMixinConfig(projectDir, candidate.packageName)
        requireMixinConfigRegistration(projectDir, mixinConfig.path)
        val sourceRoot = sourceRoot(candidate.file.path, candidate.packageName)
            ?: abort("Cannot resolve the Java source root for ${candidate.file.path}")
        val mixinPackage = mixinConfig.packageName
        val mixinPath = sourceRoot.resolve(
            mixinPackage.replace('.', '/') + "/$MIXIN_NAME.java"
        )
        captured.entries.firstOrNull { (path, source) ->
            path != mixinPath &&
                JavaSourceText.maskCommentsAndLiterals(source)
                    .contains("@Mixin(Ingredient.Value.class)")
        }?.let {
            abort(
                "An existing Ingredient.Value mixin in ${it.key} makes codec " +
                    "composition ownership ambiguous"
            )
        }

        val mixinSource = renderMixin(mixinPackage, candidate)
        if (mixinPath.exists() && mixinPath.readText() != mixinSource) {
            abort("Generated mixin path is already owned by different source: $mixinPath")
        }
        val updatedConfig = registerMixin(mixinConfig.source, MIXIN_NAME)
        val accessTransformer = projectDir.resolve(
            "src/main/resources/META-INF/accesstransformer.cfg"
        )
        val accessTransformerSource =
            if (accessTransformer.exists()) accessTransformer.readText() else ""
        val updatedAccessTransformer = addAccessTransformer(accessTransformerSource)

        val outputs = mutableListOf<PreparedOutput>()
        val candidateSource = renderCandidate(candidate)
        outputs += PreparedOutput(
            candidate.file.path,
            candidate.file.source,
            candidateSource,
            "Replace final-target Ingredient inheritance with a datagen-only Ingredient.Value"
        )

        consumers.groupBy { it.file }.forEach { (file, fileConsumers) ->
            LexicalPreservingPrinter.setup(file.unit)
            fileConsumers.forEach { consumer ->
                val replacement = StaticJavaParser.parseExpression<Expression>(
                    "${consumer.creation.type}.of(" +
                        consumer.creation.arguments.joinToString(", ") +
                        ")"
                )
                consumer.creation.replace(replacement)
            }
            outputs += PreparedOutput(
                file.path,
                file.source,
                LexicalPreservingPrinter.print(file.unit),
                "Route datagen Ingredient construction through the generated Value factory"
            )
        }

        if (!mixinPath.exists()) {
            outputs += PreparedOutput(
                mixinPath,
                null,
                mixinSource,
                "Generate the datagen-only Ingredient.Value codec mixin"
            )
        }
        if (updatedConfig != mixinConfig.source) {
            outputs += PreparedOutput(
                mixinConfig.path,
                mixinConfig.source,
                updatedConfig,
                "Register the datagen-only Ingredient.Value codec mixin"
            )
        }
        if (updatedAccessTransformer != accessTransformerSource) {
            outputs += PreparedOutput(
                accessTransformer,
                accessTransformerSource.takeIf { accessTransformer.exists() },
                updatedAccessTransformer,
                "Expose the verified private Ingredient(Value[]) constructor"
            )
        }

        val changed = outputs.filter { it.before != it.after }
        if (!dryRun) writeFamily(changed)
        return Result(
            changed.map { output ->
                Change(
                    file = output.path,
                    line = 1,
                    description = output.description,
                    before = output.before?.take(160) ?: "(missing file)",
                    after = output.after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun admitCandidate(
        source: CandidateSource,
        facts: ProjectValueFacts
    ): Candidate {
        val file = source.file
        val owner = source.owner
        val canonical = canonicalName(owner, file.unit)
            ?: abort("Datagen Ingredient candidate must be a named top-level class")
        if (owner.parentNode.orElse(null) !== file.unit || file.unit.types.size != 1) {
            abort("$canonical must be the sole top-level type in its source file")
        }
        if (!owner.isPublic || owner.isAbstract || owner.isStatic ||
            owner.typeParameters.isNotEmpty() ||
            owner.implementedTypes.isNotEmpty() ||
            owner.annotations.isNotEmpty()
        ) {
            abort("$canonical is not the closed public concrete legacy class shape")
        }
        if (owner.extendedTypes.size != 1 ||
            resolveType(owner.extendedTypes.single(), file, facts) != INGREDIENT
        ) {
            abort("$canonical does not canonically extend Ingredient")
        }
        val fields = owner.fields
        if (fields.size != 2 || fields.any {
                !it.isPrivate || it.isStatic || it.variables.size != 1 ||
                    it.variables.single().initializer.isPresent
            }
        ) {
            abort("$canonical must contain exactly two private uninitialized instance fields")
        }
        val constructors = owner.constructors
        val methods = owner.methods
        if (constructors.size != 1 || methods.size != 1 ||
            owner.members.size != fields.size + constructors.size + methods.size
        ) {
            abort("$canonical must contain only two fields, one constructor, and toJson")
        }
        val constructor = constructors.single()
        if (!constructor.isPublic || constructor.parameters.size != 2 ||
            constructor.typeParameters.isNotEmpty() ||
            constructor.thrownExceptions.isNotEmpty() ||
            constructor.annotations.isNotEmpty() ||
            constructor.body.statements.size != 3
        ) {
            abort("$canonical constructor is outside the admitted two-argument shape")
        }
        admitEmptyIngredientSuper(canonical, constructor, file, facts)

        val fieldNames = fields.map { it.variables.single().nameAsString }.toSet()
        val parameterNames = constructor.parameters.map { it.nameAsString }.toSet()
        val parameterByField = linkedMapOf<String, Parameter>()
        constructor.body.statements.drop(1).forEach { statement ->
            val assignment = (statement as? ExpressionStmt)?.expression as? AssignExpr
                ?: abort("$canonical constructor must only assign its two stored arguments")
            if (assignment.operator != AssignExpr.Operator.ASSIGN) {
                abort("$canonical constructor contains a non-assignment mutation")
            }
            val field = assignment.target.thisFieldName()
                ?: abort("$canonical constructor assignments must target this.field")
            val parameter = assignment.value as? NameExpr
                ?: abort("$canonical constructor assignments must store direct parameters")
            val declaration = constructor.parameters.singleOrNull {
                it.nameAsString == parameter.nameAsString
            } ?: abort("$canonical constructor assignment has an unknown parameter")
            if (field !in fieldNames || parameter.nameAsString !in parameterNames ||
                parameterByField.put(field, declaration) != null
            ) {
                abort("$canonical constructor does not bijectively initialize both fields")
            }
        }
        if (parameterByField.keys != fieldNames ||
            parameterByField.values.map { it.nameAsString }.toSet() != parameterNames
        ) {
            abort("$canonical constructor does not initialize both fields exactly once")
        }

        val json = admitJsonWriter(canonical, methods.single(), file, facts, fieldNames)
        val modParameter = parameterByField.getValue(json.modField)
        val idParameter = parameterByField.getValue(json.idField)
        if (constructor.parameters[0] !== modParameter ||
            constructor.parameters[1] !== idParameter
        ) {
            abort("$canonical constructor must receive the mod before its String id")
        }
        val fieldTypes = facts.fields[canonical].orEmpty()
        val modType = fieldTypes[json.modField]
            ?: abort("$canonical mod field type is not source-resolved")
        val idType = fieldTypes[json.idField]
            ?: abort("$canonical id field type is not source-resolved")
        if (idType != STRING ||
            resolveTypeText(idParameter.typeAsString, file, facts) != STRING ||
            resolveTypeText(modParameter.typeAsString, file, facts) != modType
        ) {
            abort("$canonical constructor fields do not preserve a mod plus String id")
        }
        if (!Regex("""[A-Za-z_$][\w$]*""").matches(modParameter.typeAsString) ||
            idParameter.typeAsString !in setOf("String", "java.lang.String")
        ) {
            abort("$canonical factory parameter spellings are not safely reproducible")
        }
        val resourceMethods = facts.methods[modType]
            ?.get("asResource/1")
            .orEmpty()
            .filter {
                it.returnType == RESOURCE_LOCATION &&
                    it.parameterTypes == listOf(STRING)
            }
        if (resourceMethods.size != 1) {
            abort(
                "$canonical does not have one source-proven " +
                    "$modType.asResource(String): ResourceLocation contract"
            )
        }
        return Candidate(
            file = file,
            owner = owner,
            canonicalName = canonical,
            packageName = file.unit.packageDeclaration
                .map { it.nameAsString }.orElse(""),
            simpleName = owner.nameAsString,
            modType = modType,
            modTypeSpelling = modParameter.typeAsString,
            modParameter = modParameter.nameAsString,
            idParameter = idParameter.nameAsString
        )
    }

    private fun admitEmptyIngredientSuper(
        canonical: String,
        constructor: ConstructorDeclaration,
        file: ParsedSource,
        facts: ProjectValueFacts
    ) {
        val invocation =
            constructor.body.statements.firstOrNull() as? ExplicitConstructorInvocationStmt
                ?: abort("$canonical constructor must begin with super(Stream.empty())")
        if (invocation.isThis || invocation.expression.isPresent ||
            invocation.arguments.size != 1
        ) {
            abort("$canonical constructor must directly invoke Ingredient")
        }
        val empty = invocation.arguments.single() as? MethodCallExpr
            ?: abort("$canonical super argument must be Stream.empty()")
        val scope = empty.scope.orElse(null)
        val scopeType = when (scope) {
            is NameExpr -> resolveTypeText(scope.nameAsString, file, facts)
            is FieldAccessExpr -> resolveTypeText(scope.toString(), file, facts)
            else -> null
        }
        if (empty.nameAsString != "empty" || empty.arguments.isNotEmpty() ||
            scopeType != STREAM
        ) {
            abort("$canonical super argument is not canonical Stream.empty()")
        }
    }

    private fun admitJsonWriter(
        canonical: String,
        method: MethodDeclaration,
        file: ParsedSource,
        facts: ProjectValueFacts,
        fieldNames: Set<String>
    ): JsonWriterShape {
        if (method.nameAsString != "toJson" || !method.isPublic || method.isStatic ||
            method.parameters.isNotEmpty() || method.typeParameters.isNotEmpty() ||
            method.thrownExceptions.isNotEmpty() ||
            !hasOnlyCanonicalOverrideMarker(method, file, facts) ||
            resolveTypeText(method.typeAsString, file, facts) != JSON_ELEMENT
        ) {
            abort("$canonical must have one exact public JsonElement toJson() method")
        }
        val statements = method.body.orElse(null)?.statements
            ?: abort("$canonical.toJson has no body")
        if (statements.size != 3) {
            abort("$canonical.toJson must construct, populate, and return one JsonObject")
        }
        val variable = ((statements[0] as? ExpressionStmt)?.expression
            as? VariableDeclarationExpr)?.variables?.singleOrNull()
            ?: abort("$canonical.toJson must begin with one JsonObject local")
        if (resolveTypeText(variable.typeAsString, file, facts) != JSON_OBJECT) {
            abort("$canonical.toJson local is not canonical JsonObject")
        }
        val created = variable.initializer.orElse(null) as? ObjectCreationExpr
            ?: abort("$canonical.toJson local is not directly constructed")
        if (created.arguments.isNotEmpty() ||
            resolveType(created.type, file, facts) != JSON_OBJECT
        ) {
            abort("$canonical.toJson does not construct an empty JsonObject")
        }
        val add = (statements[1] as? ExpressionStmt)?.expression as? MethodCallExpr
            ?: abort("$canonical.toJson must add exactly one property")
        if (add.nameAsString != "addProperty" || add.arguments.size != 2 ||
            (add.scope.orElse(null) as? NameExpr)?.nameAsString != variable.nameAsString ||
            (add.arguments[0] as? StringLiteralExpr)?.value != "item"
        ) {
            abort("$canonical.toJson does not write the literal item property")
        }
        val toStringCall = add.arguments[1].unwrap() as? MethodCallExpr
            ?: abort("$canonical.toJson item value is not a ResourceLocation string")
        val resourceCall = toStringCall.scope.orElse(null)?.unwrap() as? MethodCallExpr
            ?: abort("$canonical.toJson item value is not mod.asResource(id).toString()")
        val modField = resourceCall.scope.orElse(null)?.fieldName()
            ?: abort("$canonical.toJson resource receiver is not a stored field")
        val idField = resourceCall.arguments.singleOrNull()?.fieldName()
            ?: abort("$canonical.toJson resource id is not a stored field")
        if (toStringCall.nameAsString != "toString" ||
            toStringCall.arguments.isNotEmpty() ||
            resourceCall.nameAsString != "asResource" ||
            resourceCall.arguments.size != 1 ||
            modField !in fieldNames || idField !in fieldNames ||
            modField == idField
        ) {
            abort("$canonical.toJson is outside the external item-id writer shape")
        }
        val returned = statements[2] as? ReturnStmt
            ?: abort("$canonical.toJson must return its JsonObject")
        if ((returned.expression.orElse(null) as? NameExpr)?.nameAsString !=
            variable.nameAsString
        ) {
            abort("$canonical.toJson returns a different value")
        }
        return JsonWriterShape(modField, idField)
    }

    private fun hasOnlyCanonicalOverrideMarker(
        method: MethodDeclaration,
        file: ParsedSource,
        facts: ProjectValueFacts
    ): Boolean {
        if (method.annotations.isEmpty()) return true
        val annotation = method.annotations.singleOrNull() as? MarkerAnnotationExpr
            ?: return false
        val name = annotation.nameAsString
        if (name == JAVA_LANG_OVERRIDE) return true
        if (name != "Override") return false

        val explicit = file.unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == name
        }.map { it.nameAsString }.distinct()
        if (explicit.isNotEmpty()) {
            return explicit.singleOrNull() == JAVA_LANG_OVERRIDE
        }

        val packageName = file.unit.packageDeclaration.map {
            it.nameAsString
        }.orElse("")
        if (facts.types.any {
                it.substringAfterLast('.') == name &&
                    it.substringBeforeLast('.', "") == packageName
            }
        ) {
            return false
        }

        // Any explicit on-demand import could make the simple marker ambiguous.
        // java.lang is always implicitly visible and is the only admitted owner.
        return file.unit.imports.none {
            !it.isStatic && it.isAsterisk && it.nameAsString != "java.lang"
        }
    }

    private fun rejectSourceChildrenAndNonConstructorTypes(
        candidate: Candidate,
        files: List<ParsedSource>,
        facts: ProjectValueFacts
    ) {
        files.forEach { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { owner ->
                owner.extendedTypes.forEach { parent ->
                    if (resolveType(parent, file, facts) == candidate.canonicalName) {
                        abort(
                            "${candidate.canonicalName} has a source-owned subclass in ${file.path}"
                        )
                    }
                }
            }
            file.unit.findAll(ClassOrInterfaceType::class.java)
                .filter { resolveType(it, file, facts) == candidate.canonicalName }
                .forEach { reference ->
                    val parent = reference.parentNode.orElse(null)
                    if (parent !is ObjectCreationExpr || parent.type !== reference) {
                        abort(
                            "${candidate.canonicalName} is used as a source type outside " +
                                "its constructor in ${file.path}"
                        )
                    }
                }
        }
    }

    private fun collectConsumers(
        candidate: Candidate,
        files: List<ParsedSource>,
        facts: ProjectValueFacts
    ): List<ConstructorConsumer> {
        val consumers = mutableListOf<ConstructorConsumer>()
        files.filter { it.path != candidate.file.path }.forEach { file ->
            file.unit.findAll(ObjectCreationExpr::class.java)
                .filter {
                    resolveType(it.type, file, facts) == candidate.canonicalName
                }
                .forEach { creation ->
                    if (creation.anonymousClassBody.isPresent ||
                        creation.arguments.size != 2
                    ) {
                        abort(
                            "Unsupported ${candidate.simpleName} constructor shape in ${file.path}"
                        )
                    }
                    val call = creation.parentNode.orElse(null) as? MethodCallExpr
                        ?: abort(
                            "${candidate.simpleName} construction is not a direct " +
                                "Collection<Ingredient>.add argument in ${file.path}"
                        )
                    if (call.nameAsString != "add" ||
                        call.arguments.size != 1 ||
                        call.arguments.single() !== creation
                    ) {
                        abort(
                            "${candidate.simpleName} construction has an unsupported consumer " +
                                "in ${file.path}"
                        )
                    }
                    val receiver = call.scope.orElse(null)
                        ?: abort("${candidate.simpleName}.add receiver is implicit in ${file.path}")
                    val receiverType = expressionType(receiver, call, file, facts)
                        ?: abort(
                            "${candidate.simpleName} constructor consumer type is unresolved " +
                                "in ${file.path}"
                        )
                    if (!isIngredientCollection(receiverType)) {
                        abort(
                            "${candidate.simpleName} constructor consumer is $receiverType, " +
                                "not a source-proven Collection<Ingredient>"
                        )
                    }
                    consumers += ConstructorConsumer(file, creation)
                }
        }
        return consumers
    }

    private fun expressionType(
        expression: Expression,
        use: Node,
        file: ParsedSource,
        facts: ProjectValueFacts
    ): String? {
        return when (val direct = expression.unwrap()) {
            is ThisExpr -> direct.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .map { canonicalName(it, file.unit) }.orElse(null)
            is NameExpr -> {
                val executable = use.findAncestor(MethodDeclaration::class.java).orElse(null)
                    ?: use.findAncestor(ConstructorDeclaration::class.java).orElse(null)
                val parameters = when (executable) {
                    is MethodDeclaration -> executable.parameters
                    is ConstructorDeclaration -> executable.parameters
                    else -> emptyList()
                }
                parameters.singleOrNull { it.nameAsString == direct.nameAsString }
                    ?.let { resolveTypeText(it.typeAsString, file, facts) }
                    ?: run {
                        val locals = executable?.findAll(VariableDeclarator::class.java).orEmpty()
                            .filter {
                                if (it.nameAsString != direct.nameAsString ||
                                    !it.range.isPresent || !use.range.isPresent
                                ) {
                                    false
                                } else {
                                    val declared = it.range.get().begin
                                    val used = use.range.get().begin
                                    declared.line < used.line ||
                                        declared.line == used.line &&
                                        declared.column < used.column
                                }
                            }
                        if (locals.isNotEmpty()) {
                            locals.singleOrNull()?.let {
                                resolveTypeText(it.typeAsString, file, facts)
                            }
                        } else {
                            val owner = use.findAncestor(
                                ClassOrInterfaceDeclaration::class.java
                            ).orElse(null)
                            canonicalName(owner ?: return null, file.unit)
                                ?.let { fieldType(it, direct.nameAsString, facts) }
                        }
                    }
            }
            is FieldAccessExpr -> {
                val ownerType = if (direct.scope is ThisExpr) {
                    use.findAncestor(ClassOrInterfaceDeclaration::class.java)
                        .map { canonicalName(it, file.unit) }.orElse(null)
                } else {
                    expressionType(direct.scope, use, file, facts)
                }
                ownerType?.substringBefore('<')
                    ?.let { fieldType(it, direct.nameAsString, facts) }
            }
            else -> null
        }
    }

    private fun fieldType(
        owner: String,
        name: String,
        facts: ProjectValueFacts
    ): String? {
        var current: String? = owner
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            facts.fields[current]?.get(name)?.let { return it }
            current = facts.parents[current]
        }
        return null
    }

    private fun isIngredientCollection(type: String): Boolean {
        val open = type.indexOf('<')
        val close = type.lastIndexOf('>')
        if (open <= 0 || close <= open) return false
        val raw = type.substring(0, open)
        if (raw !in setOf(
                "java.util.Collection",
                "java.util.List",
                "java.util.ArrayList",
                "java.util.LinkedList",
                "java.util.Set",
                "java.util.HashSet",
                "net.minecraft.core.NonNullList"
            )
        ) return false
        return JavaSourceText.splitTopLevelArgs(type.substring(open + 1, close))
            .singleOrNull() == INGREDIENT
    }

    private fun renderCandidate(candidate: Candidate): String {
        val packageLine = if (candidate.packageName.isBlank()) {
            ""
        } else {
            "package ${candidate.packageName};\n\n"
        }
        val imports = linkedSetOf(
            "com.mojang.serialization.DataResult",
            "com.mojang.serialization.DynamicOps",
            "com.mojang.serialization.MapCodec",
            "com.mojang.serialization.MapLike",
            "com.mojang.serialization.RecordBuilder",
            "java.util.Collection",
            "java.util.List",
            "java.util.stream.Stream",
            RESOURCE_LOCATION,
            ITEM_STACK,
            INGREDIENT
        )
        if (!candidate.modType.startsWith("java.lang.") &&
            candidate.modType.substringBeforeLast('.', "") != candidate.packageName
        ) {
            imports += candidate.modType
        }
        val importBlock = imports.sorted().joinToString("\n") { "import $it;" }
        val prefix = leadingFileComment(candidate.file.source)
        val generated =
            prefix + packageLine + importBlock + "\n\n" + """
public final class ${candidate.simpleName} implements Ingredient.Value {
    public static final MapCodec<${candidate.simpleName}> MAP_CODEC =
        new MapCodec<>() {
            @Override
            public <T> DataResult<${candidate.simpleName}> decode(
                DynamicOps<T> ops,
                MapLike<T> input
            ) {
                return DataResult.error(
                    () -> "${candidate.simpleName} is output-only"
                );
            }

            @Override
            public <T> RecordBuilder<T> encode(
                ${candidate.simpleName} input,
                DynamicOps<T> ops,
                RecordBuilder<T> prefix
            ) {
                return prefix.add(
                    "item",
                    ResourceLocation.CODEC.encodeStart(ops, input.item)
                );
            }

            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.of(ops.createString("item"));
            }
        };

    private final ResourceLocation item;

    private ${candidate.simpleName}(ResourceLocation item) {
        this.item = item;
    }

    @Override
    public Collection<ItemStack> getItems() {
        return List.of();
    }

    public static Ingredient of(
        ${candidate.modTypeSpelling} ${candidate.modParameter},
        String ${candidate.idParameter}
    ) {
        Ingredient.Value[] values = new Ingredient.Value[] {
            new ${candidate.simpleName}(
                ${candidate.modParameter}.asResource(${candidate.idParameter})
            )
        };
        return new Ingredient(values);
    }
}
""".trimIndent() + "\n"
        return generated.replace("\r\n", "\n").replace("\n", candidate.file.newline)
    }

    private fun renderMixin(packageName: String, candidate: Candidate): String {
        val stream = DatagenIngredientValues::class.java.getResourceAsStream(MIXIN_TEMPLATE)
            ?: abort("Missing datagen Ingredient mixin template $MIXIN_TEMPLATE")
        val template = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return template
            .replace("@@PACKAGE@@", packageName)
            .replace("@@VALUE_TYPE@@", candidate.canonicalName)
            .replace("@@VALUE_SIMPLE@@", candidate.simpleName)
            .replace("\r\n", "\n")
            .trimEnd() + "\n"
    }

    private fun selectMixinConfig(
        projectDir: Path,
        candidatePackage: String
    ): MixinConfig {
        val resources = projectDir.resolve("src/main/resources")
        if (!Files.isDirectory(resources)) {
            abort("Datagen Ingredient codec requires an existing main mixin config")
        }
        val configs = Files.walk(resources).use { paths ->
            paths.filter {
                it.isRegularFile() && it.name.endsWith(".mixins.json")
            }.map { path ->
                val source = path.readText()
                val root = runCatching {
                    metadataJson.parseToJsonElement(source).jsonObject
                }.getOrElse {
                    abort("Cannot parse mixin config $path: ${it.message}")
                }
                val packageName = (root["package"] as? JsonPrimitive)?.content
                    ?: abort("Mixin config $path has no package")
                MixinConfig(
                    path,
                    source,
                    packageName,
                    commonPackagePrefix(candidatePackage, packageName),
                    root["mixins"] is JsonArray
                )
            }.toList()
        }
        if (configs.isEmpty()) {
            abort("Datagen Ingredient codec requires an existing main mixin config")
        }
        val bestScore = configs.maxOf { it.packageScore }
        val best = configs.filter { it.packageScore == bestScore }
        if (bestScore < 2 || best.size != 1) {
            abort(
                "Cannot choose one owning mixin config for package $candidatePackage"
            )
        }
        val owner = best.single()
        if (!owner.hasCommonMixins) {
            abort("Owning mixin config ${owner.path} has no common mixins array")
        }
        return owner
    }

    private fun requireMixinConfigRegistration(projectDir: Path, config: Path) {
        val resources = projectDir.resolve("src/main/resources")
        val relative = resources.relativize(config).toString().replace('\\', '/')
        val registration = Regex(
            """(?m)^\s*config\s*=\s*"${Regex.escape(relative)}"\s*$"""
        )
        val registered = Files.walk(resources).use { paths ->
            paths.filter {
                it.isRegularFile() && it.extension == "toml"
            }.anyMatch { registration.containsMatchIn(it.readText()) }
        }
        if (registered) return
        requireLegacyMixinConfigRegistration(projectDir, resources, config, relative)
    }

    /**
     * Structural migration precedes BuildSystemPass. An original Forge project can therefore
     * still own its active config through MixinGradle while META-INF/mods.toml has no [[mixins]]
     * entry. Admit that intermediate state only when one exact root MixinGradle block registers
     * the selected resource and the later build migration can discover and register that JSON.
     */
    private fun requireLegacyMixinConfigRegistration(
        projectDir: Path,
        resources: Path,
        config: Path,
        relative: String
    ) {
        val normalizedResources = resources.toAbsolutePath().normalize()
        val normalizedConfig = config.toAbsolutePath().normalize()
        val root = metadataJson.parseToJsonElement(config.readText()).jsonObject
        val discoverableByBuildMigration =
            normalizedConfig.startsWith(normalizedResources) &&
                config.name.endsWith(".json") &&
                config.name.contains("mixin", ignoreCase = true) &&
                root["package"] is JsonPrimitive &&
                root["mixins"] is JsonArray
        if (!discoverableByBuildMigration) {
            abort(
                "Mixin config $relative is not registered in NeoForge mod metadata " +
                    "and cannot be surfaced by the build migration"
            )
        }

        val metadataTargets = listOf(
            projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
            projectDir.resolve("src/main/resources/META-INF/mods.toml")
        ).filter { it.exists() }
        if (metadataTargets.size != 1) {
            abort(
                "Mixin config $relative has no unique mod-metadata target for later registration"
            )
        }

        val buildScripts = listOf(
            projectDir.resolve("build.gradle"),
            projectDir.resolve("build.gradle.kts")
        ).filter { it.exists() }
        val blocks = buildScripts.flatMap { script ->
            legacyMixinBlocks(script.readText()).map { script to it }
        }
        if (blocks.size != 1) {
            abort(
                "Mixin config $relative is not uniquely owned by one legacy Gradle mixin block"
            )
        }
        val registrations = legacyMixinConfigEntries(blocks.single().second)
        if (registrations != listOf(relative)) {
            abort(
                "Legacy Gradle mixin ownership for $relative is missing or ambiguous: " +
                    registrations.joinToString().ifBlank { "(none)" }
            )
        }
    }

    private fun legacyMixinBlocks(source: String): List<String> {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        return Regex("""\bmixin\s*\{""").findAll(masked).map { match ->
            val open = masked.indexOf('{', match.range.first)
            val close = JavaSourceText.matchingBrace(masked, open)
            if (close < 0) abort("Legacy Gradle mixin block has unbalanced braces")
            source.substring(open + 1, close)
        }.toList()
    }

    private fun legacyMixinConfigEntries(block: String): List<String> {
        val maskedLines = JavaSourceText.maskCommentsAndLiterals(block).lines()
        val sourceLines = block.lines()
        val entry = Regex(
            """^\s*config\s*(?:\(\s*)?["']([^"']+)["']\s*\)?\s*;?\s*$"""
        )
        return sourceLines.zip(maskedLines).mapNotNull { (source, masked) ->
            if (!Regex("""^\s*config\b""").containsMatchIn(masked)) {
                null
            } else {
                entry.matchEntire(source)?.groupValues?.get(1)
                    ?: abort("Legacy Gradle mixin config call is not an exact literal")
            }
        }
    }

    private fun registerMixin(source: String, entry: String): String {
        val root = metadataJson.parseToJsonElement(source).jsonObject
        val mixins = root["mixins"] as? JsonArray
            ?: abort("Owning mixin config has no common mixins array")
        if (mixins.any { (it as? JsonPrimitive)?.content == entry }) return source
        val updated = JsonObject(
            root.mapValues { (key, value) ->
                if (key != "mixins") value
                else JsonArray(mixins + JsonPrimitive(entry))
            }
        )
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        return metadataJson.encodeToString(JsonObject.serializer(), updated)
            .replace("\n", newline)
            .trimEnd() + newline
    }

    private fun addAccessTransformer(source: String): String {
        val matching = source.lineSequence().filter { line ->
            val code = line.substringBefore('#').trim().replace(Regex("""\s+"""), " ")
            code.contains("net.minecraft.world.item.crafting.Ingredient") &&
                code.contains("<init>$INGREDIENT_VALUE_CONSTRUCTOR")
        }.toList()
        if (matching.size > 1) {
            abort("Ingredient(Value[]) has duplicate access transformer entries")
        }
        if (matching.singleOrNull() != null) {
            val normalized = matching.single().substringBefore('#').trim()
                .replace(Regex("""\s+"""), " ")
            if (normalized != ACCESS_TRANSFORMER_ENTRY) {
                abort("Ingredient(Value[]) has a conflicting access transformer entry")
            }
            return source
        }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val prefix = source.trimEnd()
        return if (prefix.isEmpty()) {
            ACCESS_TRANSFORMER_ENTRY + newline
        } else {
            prefix + newline + ACCESS_TRANSFORMER_ENTRY + newline
        }
    }

    private fun targetContractIsVerified(): Boolean =
        TargetMembers.inheritedMethod(
            INGREDIENT,
            "<init>",
            INGREDIENT_VALUE_CONSTRUCTOR
        )?.access == "private" &&
            TargetMembers.fieldDescriptor(
                "net.minecraft.world.item.crafting.Ingredient\$Value",
                "MAP_CODEC"
            ) == "Lcom/mojang/serialization/MapCodec;" &&
            TargetMembers.inheritedMethod(
                "net.minecraft.world.item.crafting.Ingredient\$Value",
                "getItems",
                "()Ljava/util/Collection;"
            )?.access == "public" &&
            TargetMembers.fieldDescriptor(RESOURCE_LOCATION, "CODEC") ==
            "Lcom/mojang/serialization/Codec;" &&
            TargetMembers.inheritedMethod(
                DATAGEN_LOADER,
                "isRunningDataGen",
                "()Z"
            )?.access == "public"

    private fun resolveType(
        type: ClassOrInterfaceType,
        file: ParsedSource,
        facts: ProjectValueFacts
    ): String? = resolveTypeText(type.nameWithScope, file, facts)

    private fun resolveTypeText(
        sourceType: String,
        file: ParsedSource,
        facts: ProjectValueFacts
    ): String? {
        val plain = sourceType.substringBefore('<').removeSuffix("[]").replace(" ", "")
        if (plain.isBlank()) return null
        if (plain in facts.types || TargetMembers.hasClass(plain) ||
            plain in setOf(
                INGREDIENT,
                INGREDIENT_VALUE,
                RESOURCE_LOCATION,
                ITEM_STACK,
                DATAGEN_LOADER,
                STREAM,
                JSON_ELEMENT,
                JSON_OBJECT,
                STRING
            )
        ) return plain
        val first = plain.substringBefore('.')
        val explicit = file.unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == first
        }.map { it.nameAsString }.distinct()
        if (explicit.size == 1) {
            return explicit.single() + plain.removePrefix(first)
        }
        if (first in setOf(
                "String",
                "Object",
                "Boolean",
                "Integer",
                "Long",
                "Double",
                "Float"
            ) && plain == first
        ) return "java.lang.$first"
        if (first.firstOrNull()?.isLowerCase() == true) return plain
        val packageName = file.unit.packageDeclaration.map {
            it.nameAsString
        }.orElse("")
        val samePackage = facts.types.filter {
            it.substringAfterLast('.') == plain &&
                it.substringBeforeLast('.', "") == packageName
        }.distinct()
        if (samePackage.size == 1) return samePackage.single()
        val lexical = file.unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { it.nameAsString == plain }
            .mapNotNull { canonicalName(it, file.unit) }
            .distinct()
        if (lexical.size == 1) return lexical.single()
        val project = facts.types.filter {
            it.substringAfterLast('.') == plain
        }.distinct()
        if (project.size == 1) return project.single()
        val wildcardNamespaces = file.unit.imports.filter {
            !it.isStatic && it.isAsterisk
        }.map { it.nameAsString }.toSet()
        val wildcard = (facts.types + setOf(
            INGREDIENT,
            RESOURCE_LOCATION,
            ITEM_STACK,
            STREAM,
            JSON_ELEMENT,
            JSON_OBJECT
        )).filter {
            it.substringAfterLast('.') == plain &&
                it.substringBeforeLast('.', "") in wildcardNamespaces
        }.distinct()
        return wildcard.singleOrNull()
    }

    private fun canonicalName(
        declaration: ClassOrInterfaceDeclaration,
        unit: CompilationUnit
    ): String? {
        val names = mutableListOf<String>()
        var current: Node = declaration
        while (current is TypeDeclaration<*>) {
            names += current.nameAsString
            current = current.parentNode.orElse(null) ?: return null
        }
        if (current !== unit) return null
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val nested = names.asReversed().joinToString(".")
        return if (packageName.isBlank()) nested else "$packageName.$nested"
    }

    private fun sourceRoot(file: Path, packageName: String): Path? {
        var root: Path? = file.parent
        if (packageName.isNotBlank()) {
            repeat(packageName.split('.').size) {
                root = root?.parent ?: return null
            }
        }
        return root
    }

    private fun commonPackagePrefix(first: String, second: String): Int =
        first.split('.').zip(second.split('.')).takeWhile {
            it.first == it.second
        }.size

    private fun leadingFileComment(source: String): String {
        val packageOffset = Regex("""(?m)^\s*package\s+""").find(source)?.range?.first
            ?: return ""
        val prefix = source.substring(0, packageOffset)
        return prefix.takeIf { it.contains("//") || it.contains("/*") }
            ?.trimEnd()
            ?.plus("\n")
            ?: ""
    }

    private fun Expression.unwrap(): Expression {
        var current = this
        while (current is EnclosedExpr) current = current.inner
        return current
    }

    private fun Expression.fieldName(): String? = when (val direct = unwrap()) {
        is NameExpr -> direct.nameAsString
        is FieldAccessExpr -> direct.nameAsString.takeIf {
            direct.scope.unwrap() is ThisExpr
        }
        else -> null
    }

    private fun Expression.thisFieldName(): String? =
        (unwrap() as? FieldAccessExpr)?.let {
            it.nameAsString.takeIf { _ -> it.scope.unwrap() is ThisExpr }
        }

    private fun javaFiles(projectDir: Path): List<Path> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return JavaSourceText.walkJavaSourceRoots(root).use { paths ->
            paths.filter {
                it.isRegularFile() && it.extension == "java"
            }.filter { path ->
                val relative = projectDir.relativize(path)
                    .toString().replace('\\', '/')
                !relative.startsWith("build/") &&
                    !relative.contains("/build/") &&
                    !relative.startsWith("src/references/") &&
                    !relative.contains("/src/references/")
            }.sorted().toList()
        }
    }

    private fun writeFamily(outputs: List<PreparedOutput>) {
        if (outputs.isEmpty()) return
        val staged = linkedMapOf<PreparedOutput, Path>()
        val committed = mutableListOf<PreparedOutput>()
        try {
            outputs.forEach { output ->
                output.path.parent.createDirectories()
                val temporary = Files.createTempFile(
                    output.path.parent,
                    ".modporter-datagen-ingredient-",
                    ".tmp"
                )
                Files.writeString(temporary, output.after, StandardCharsets.UTF_8)
                staged[output] = temporary
            }
            staged.forEach { (output, temporary) ->
                Files.move(temporary, output.path, ATOMIC_MOVE, REPLACE_EXISTING)
                committed += output
            }
        } catch (failure: Exception) {
            committed.asReversed().forEach { output ->
                runCatching {
                    if (output.before == null) {
                        Files.deleteIfExists(output.path)
                    } else {
                        val rollback = Files.createTempFile(
                            output.path.parent,
                            ".modporter-datagen-ingredient-rollback-",
                            ".tmp"
                        )
                        Files.writeString(rollback, output.before, StandardCharsets.UTF_8)
                        Files.move(rollback, output.path, ATOMIC_MOVE, REPLACE_EXISTING)
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            staged.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private data class ParsedSource(
        val path: Path,
        val source: String,
        val unit: CompilationUnit
    ) {
        val newline: String = if (source.contains("\r\n")) "\r\n" else "\n"
    }

    private data class CandidateSource(
        val file: ParsedSource,
        val owner: ClassOrInterfaceDeclaration
    )

    private data class Candidate(
        val file: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val canonicalName: String,
        val packageName: String,
        val simpleName: String,
        val modType: String,
        val modTypeSpelling: String,
        val modParameter: String,
        val idParameter: String
    )

    private data class JsonWriterShape(
        val modField: String,
        val idField: String
    )

    private data class ConstructorConsumer(
        val file: ParsedSource,
        val creation: ObjectCreationExpr
    )

    private data class MixinConfig(
        val path: Path,
        val source: String,
        val packageName: String,
        val packageScore: Int,
        val hasCommonMixins: Boolean
    )

    private data class PreparedOutput(
        val path: Path,
        val before: String?,
        val after: String,
        val description: String
    )
}
