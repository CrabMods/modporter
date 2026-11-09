package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Replaces Create's Forge-era mutation of vanilla copper suppliers with the native NeoForge
 * block data maps used by the 1.21.1 Create source.
 *
 * NeoForge can evaluate WeatheringCopper.NEXT_BY_BLOCK before Create's constructor runs. At that
 * point Guava has replaced the supplier delegate with its completed sentinel, so retaining the
 * reflection injection makes registry freeze call the sentinel and fail. The target-native graph
 * keeps the exact BlockEntry holder identities, emits oxidizable/waxable data maps during
 * datagen, and removes the obsolete runtime injection.
 */
internal object CreateCopperDataMapMigration {
    const val RULE_ID = "struct-create-copper-native-data-maps"

    private const val COPPER_REGISTRIES_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/block/CopperRegistries.java"
    private const val COPPER_BLOCK_SET_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/block/CopperBlockSet.java"
    private const val CREATE_RELATIVE =
        "src/main/java/com/simibubi/create/Create.java"
    private const val CREATE_DATAGEN_RELATIVE =
        "src/main/java/com/simibubi/create/infrastructure/data/CreateDatagen.java"
    private const val PROVIDER_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/data/CreateDatamapProvider.java"

    private const val COPPER_REGISTRIES_FQN =
        "com.simibubi.create.foundation.block.CopperRegistries"
    private const val PROVIDER_FQN =
        "com.simibubi.create.foundation.data.CreateDatamapProvider"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)? = null
    ): Result = try {
        migrateChecked(projectDir, dryRun, commitProbe)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create copper data-map admission failed"))
    } catch (failure: Exception) {
        val rollbackFailures = failure.suppressed
            .map { it.message ?: it::class.java.simpleName }
        val rollbackDetail = if (rollbackFailures.isEmpty()) {
            ""
        } else {
            "; rollback failures: ${rollbackFailures.joinToString()}"
        }
        Result(
            emptyList(),
            listOf(
                "Create copper data-map migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName) +
                    rollbackDetail
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)?
    ): Result {
        val registriesFile = projectDir.resolve(COPPER_REGISTRIES_RELATIVE)
        if (!registriesFile.isRegularFile()) return Result.EMPTY

        val blockSetFile = requireFile(projectDir, COPPER_BLOCK_SET_RELATIVE)
        val createFile = requireFile(projectDir, CREATE_RELATIVE)
        val datagenFile = requireFile(projectDir, CREATE_DATAGEN_RELATIVE)
        val providerFile = projectDir.resolve(PROVIDER_RELATIVE)

        requireClosedProjectReferences(projectDir)

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val registriesBefore = registriesFile.readText()
        val blockSetBefore = blockSetFile.readText()
        val createBefore = createFile.readText()
        val datagenBefore = datagenFile.readText()
        val providerBefore = if (providerFile.isRegularFile()) providerFile.readText() else null

        val registriesAfter = migrateRegistries(parser, registriesBefore, registriesFile)
        val blockSetAfter = migrateBlockSet(parser, blockSetBefore, blockSetFile)
        val createAfter = migrateCreate(parser, createBefore, createFile)
        val datagenAfter = migrateDatagen(parser, datagenBefore, datagenFile)
        val providerAfter = renderProvider()
        if (providerBefore != null && !sameJava(providerBefore, providerAfter)) {
            abort("Generated copper data-map provider path is owned by incompatible source: $providerFile")
        }

        val outputs = mutableListOf<PreparedOutput>()
        addOutput(
            outputs,
            registriesFile,
            registriesBefore,
            registriesAfter,
            "Replace reflected vanilla copper supplier mutation with holder-backed registry views"
        )
        addOutput(
            outputs,
            blockSetFile,
            blockSetBefore,
            blockSetAfter,
            "Preserve exact copper BlockEntry holder identities for NeoForge data maps"
        )
        addOutput(
            outputs,
            createFile,
            createBefore,
            createAfter,
            "Remove the obsolete CopperRegistries runtime injection"
        )
        addOutput(
            outputs,
            datagenFile,
            datagenBefore,
            datagenAfter,
            "Register Create's native oxidizable and waxable data-map provider"
        )
        if (providerBefore == null) {
            outputs += PreparedOutput(
                providerFile,
                null,
                providerAfter,
                "Generate the source-bound NeoForge copper data-map provider"
            )
        }

        if (!dryRun) writeFamily(outputs, commitProbe)
        return Result(
            outputs.map { output ->
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

    private fun requireFile(projectDir: Path, relative: String): Path {
        val path = projectDir.resolve(relative)
        if (!path.isRegularFile()) {
            abort("Create copper data-map source graph is missing $relative")
        }
        return path
    }

    private fun requireClosedProjectReferences(projectDir: Path) {
        val allowed = mapOf(
            CREATE_RELATIVE to setOf("CopperRegistries.inject()"),
            COPPER_BLOCK_SET_RELATIVE to setOf(
                "CopperRegistries.addWaxable(",
                "CopperRegistries.addWeathering("
            ),
            CREATE_DATAGEN_RELATIVE to emptySet(),
            COPPER_REGISTRIES_RELATIVE to emptySet(),
            PROVIDER_RELATIVE to setOf(
                "CopperRegistries.getWeatheringView()",
                "CopperRegistries.getWaxableView()"
            )
        )
        val sourceRoot = projectDir.resolve("src/main/java")
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".java") }
                .forEach { path ->
                    val relative = projectDir.relativize(path).toString().replace('\\', '/')
                    val source = path.readText()
                    if (!source.contains("CopperRegistries")) return@forEach
                    val expectedMarkers = allowed[relative]
                        ?: abort("Unexpected CopperRegistries source reference at $relative")
                    if (relative == COPPER_REGISTRIES_RELATIVE) return@forEach
                    val compact = normalizeJava(source)
                    expectedMarkers.forEach { marker ->
                        if (!compact.contains(normalizeJava(marker))) {
                            abort("$relative is missing the proved copper registry reference $marker")
                        }
                    }
                    val occurrences = Regex("""CopperRegistries\s*\.""").findAll(source).count()
                    if (occurrences != expectedMarkers.size) {
                        abort(
                            "$relative owns $occurrences CopperRegistries calls; " +
                                "expected ${expectedMarkers.size}"
                        )
                    }
                }
        }
    }

    private fun migrateRegistries(
        parser: JavaParser,
        source: String,
        path: Path
    ): String {
        val expected = renderRegistries()
        if (sameJava(source, expected)) return source
        val unit = parse(parser, source, path)
        val owner = requireOwner(
            unit,
            "com.simibubi.create.foundation.block",
            "CopperRegistries"
        )
        requireExactImports(
            unit,
            setOf(
                "java.lang.reflect.Field",
                "java.util.function.BiConsumer",
                "java.util.function.Supplier",
                "org.jetbrains.annotations.Nullable",
                "org.slf4j.Logger",
                "com.google.common.base.Suppliers",
                "com.google.common.collect.BiMap",
                "com.google.common.collect.HashBiMap",
                "com.google.common.collect.ImmutableBiMap",
                "com.simibubi.create.Create",
                "net.minecraft.world.item.HoneycombItem",
                "net.minecraft.world.level.block.Block",
                "net.minecraft.world.level.block.WeatheringCopper"
            ),
            "CopperRegistries"
        )
        if (owner.extendedTypes.isNotEmpty() || owner.implementedTypes.isNotEmpty() ||
            owner.annotations.isNotEmpty() ||
            owner.modifiers.map { it.keyword.asString() }.toSet() != setOf("public") ||
            owner.constructors.isNotEmpty() ||
            owner.fields.flatMap { it.variables }.map { it.nameAsString }.toSet() !=
            setOf(
                "WEATHERING",
                "WAXABLE",
                "injected",
                "weatheringMemoized",
                "waxableMemoized"
            )
        ) {
            abort("CopperRegistries fields or inheritance changed from the proved Forge graph")
        }
        requireLegacyRegistryFields(owner)
        val methodsByName = owner.methods.groupBy { it.nameAsString }
        if (methodsByName.values.any { it.size != 1 }) {
            abort("CopperRegistries contains overloaded or duplicate proved methods")
        }
        val methods = methodsByName.mapValues { it.value.single() }
        val errorConsumer = owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull()
        if (methods.keys != setOf("addWeathering", "addWaxable", "inject") ||
            errorConsumer?.nameAsString != "ErrorHandlingBiConsumer" ||
            owner.members.filterIsInstance<ClassOrInterfaceDeclaration>().size != 1
        ) {
            abort("CopperRegistries methods or error consumer changed from the proved Forge graph")
        }
        requireMethodSignature(
            methods.getValue("addWeathering"),
            modifiers = setOf("public", "static", "synchronized"),
            returnType = "void",
            parameters = listOf(
                "Supplier<Block>" to "original",
                "Supplier<Block>" to "weathered"
            )
        )
        requireMethodBody(
            methods.getValue("addWeathering"),
            "{if(weatheringMemoized){thrownewIllegalStateException(" +
                "\"CannotaddweatheringentrytoCopperRegistriesaftermemoization!\");}" +
                "WEATHERING.put(original,weathered);}"
        )
        requireMethodSignature(
            methods.getValue("addWaxable"),
            modifiers = setOf("public", "static", "synchronized"),
            returnType = "void",
            parameters = listOf(
                "Supplier<Block>" to "original",
                "Supplier<Block>" to "waxed"
            )
        )
        requireMethodBody(
            methods.getValue("addWaxable"),
            "{if(waxableMemoized){thrownewIllegalStateException(" +
                "\"CannotaddwaxableentrytoCopperRegistriesaftermemoization!\");}" +
                "WAXABLE.put(original,waxed);}"
        )
        requireMethodSignature(
            methods.getValue("inject"),
            modifiers = setOf("public", "static"),
            returnType = "void",
            parameters = emptyList()
        )
        requireMethodBody(
            methods.getValue("inject"),
            LEGACY_INJECT_BODY
        )
        requireLegacyErrorConsumer(errorConsumer)
        return expected
    }

    private fun migrateBlockSet(
        parser: JavaParser,
        source: String,
        path: Path
    ): String {
        val unit = parse(parser, source, path)
        val owner = requireOwner(
            unit,
            "com.simibubi.create.foundation.block",
            "CopperBlockSet"
        )
        LexicalPreservingPrinter.setup(unit)
        val calls = owner.findAll(MethodCallExpr::class.java)
            .filter { it.scope.map { scope -> normalizeJava(scope.toString()) == "CopperRegistries" }.orElse(false) }
        if (calls.size != 2 || calls.map { it.nameAsString }.toSet() !=
            setOf("addWaxable", "addWeathering")
        ) {
            abort("CopperBlockSet must own one exact waxable and one exact weathering registration")
        }

        val waxable = calls.single { it.nameAsString == "addWaxable" }
        val weathering = calls.single { it.nameAsString == "addWeathering" }
        val waxedFirst = "entries[getIndex(state,false)]"
        val weatheredFirst =
            "entries[getIndex(WEATHER_STATES[state.ordinal()-1],false)]"
        val direct = argumentsMatch(waxable, waxedFirst, "entry") &&
            argumentsMatch(weathering, weatheredFirst, "entry")
        if (direct) return source

        requireSupplierArguments(waxable, "$waxedFirst.get()", "entry.get()")
        requireSupplierArguments(weathering, "$weatheredFirst.get()", "entry.get()")
        waxable.setArgument(0, StaticJavaParser.parseExpression(waxedFirst))
        waxable.setArgument(1, StaticJavaParser.parseExpression("entry"))
        weathering.setArgument(0, StaticJavaParser.parseExpression(weatheredFirst))
        weathering.setArgument(1, StaticJavaParser.parseExpression("entry"))
        return LexicalPreservingPrinter.print(unit)
    }

    private fun migrateCreate(
        parser: JavaParser,
        source: String,
        path: Path
    ): String {
        val unit = parse(parser, source, path)
        val owner = requireOwner(unit, "com.simibubi.create", "Create")
        LexicalPreservingPrinter.setup(unit)
        val imports = unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.nameAsString == COPPER_REGISTRIES_FQN
        }
        val calls = unit.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "inject" &&
                it.scope.map { scope -> normalizeJava(scope.toString()) == "CopperRegistries" }
                    .orElse(false)
        }
        if (imports.isEmpty() && calls.isEmpty()) return source
        if (imports.size != 1 || calls.size != 1 || calls.single().arguments.isNotEmpty()) {
            abort("Create constructor does not own one exact CopperRegistries.inject call")
        }
        val statement = calls.single().findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: abort("CopperRegistries.inject is not a standalone constructor statement")
        if (normalizeJava(statement.expression.toString()) != "CopperRegistries.inject()") {
            abort("CopperRegistries.inject statement has changed from the proved call")
        }
        requireCreateInitializationOwner(owner, statement)
        imports.single().remove()
        statement.remove()
        return LexicalPreservingPrinter.print(unit)
    }

    private fun migrateDatagen(
        parser: JavaParser,
        source: String,
        path: Path
    ): String {
        val unit = parse(parser, source, path)
        val owner = requireOwner(
            unit,
            "com.simibubi.create.infrastructure.data",
            "CreateDatagen"
        )
        LexicalPreservingPrinter.setup(unit)
        val providerImports = unit.imports.filter {
            !it.isStatic && !it.isAsterisk && it.nameAsString == PROVIDER_FQN
        }
        val providerCalls = owner.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "addProvider" &&
                it.arguments.any { argument ->
                    normalizeJava(argument.toString()) ==
                        "newCreateDatamapProvider(output,lookupProvider)"
                }
        }
        if (providerImports.size == 1 && providerCalls.size == 1) {
            requireDatamapRegistration(owner, providerCalls.single())
            return source
        }
        if (providerImports.isNotEmpty() || providerCalls.isNotEmpty()) {
            abort("CreateDatagen contains a partial or ambiguous copper data-map registration")
        }
        val gather = owner.methods.singleOrNull { it.nameAsString == "gatherData" }
            ?: abort("CreateDatagen must own one gatherData method")
        val anchors = gather.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "addProvider" &&
                it.arguments.any { argument ->
                    normalizeJava(argument.toString()) ==
                        "newCreateSequencedAssemblyRecipeGen(output,lookupProvider)"
                }
        }
        if (anchors.size != 1) {
            abort("CreateDatagen lacks one exact sequenced-assembly provider anchor")
        }
        val anchorStatement = anchors.single()
            .findAncestor(ExpressionStmt::class.java)
            .orElse(null)
            ?: abort("Sequenced assembly provider is not a standalone statement")
        val block = anchorStatement.parentNode.orElse(null)
            ?.let { parent -> parent as? com.github.javaparser.ast.stmt.BlockStmt }
            ?: abort("Sequenced assembly provider is outside gatherData's block")
        val index = block.statements.indexOf(anchorStatement)
        if (index < 0) abort("Cannot locate sequenced assembly provider statement")
        unit.addImport(PROVIDER_FQN)
        block.addStatement(
            index + 1,
            StaticJavaParser.parseStatement(
                "generator.addProvider(event.includeServer(), " +
                    "new CreateDatamapProvider(output, lookupProvider));"
            )
        )
        val inserted = owner.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "addProvider" &&
                it.arguments.any { argument ->
                    normalizeJava(argument.toString()) ==
                        "newCreateDatamapProvider(output,lookupProvider)"
                }
        } ?: abort("Cannot validate inserted Create data-map provider registration")
        requireDatamapRegistration(owner, inserted)
        return LexicalPreservingPrinter.print(unit)
    }

    private fun requireSupplierArguments(
        call: MethodCallExpr,
        firstBody: String,
        secondBody: String
    ) {
        if (call.arguments.size != 2 ||
            !lambdaBodyMatches(call.arguments[0], firstBody) ||
            !lambdaBodyMatches(call.arguments[1], secondBody)
        ) {
            abort(
                "CopperBlockSet.${call.nameAsString} changed from the proved lazy BlockEntry graph"
            )
        }
    }

    private fun lambdaBodyMatches(expression: Expression, expected: String): Boolean {
        val lambda = expression as? LambdaExpr ?: return false
        if (lambda.parameters.isNotEmpty()) return false
        val body = (lambda.body as? ExpressionStmt)?.expression ?: return false
        return normalizeJava(body.toString()) == normalizeJava(expected)
    }

    private fun argumentsMatch(
        call: MethodCallExpr,
        first: String,
        second: String
    ): Boolean = call.arguments.size == 2 &&
        normalizeJava(call.arguments[0].toString()) == normalizeJava(first) &&
        normalizeJava(call.arguments[1].toString()) == normalizeJava(second)

    private fun requireMethodBody(method: MethodDeclaration, expected: String) {
        val actual = normalizeJavaWithoutComments(
            method.body.map { it.toString() }.orElse("")
        )
        if (actual != normalizeJavaWithoutComments(expected)) {
            abort("CopperRegistries.${method.nameAsString} changed from the proved source")
        }
    }

    private fun requireMethodSignature(
        method: MethodDeclaration,
        modifiers: Set<String>,
        returnType: String,
        parameters: List<Pair<String, String>>
    ) {
        val actualParameters = method.parameters.map {
            normalizeJava(it.type.toString()) to it.nameAsString
        }
        if (method.modifiers.map { it.keyword.asString() }.toSet() != modifiers ||
            method.annotations.isNotEmpty() ||
            method.typeParameters.isNotEmpty() ||
            method.thrownExceptions.isNotEmpty() ||
            normalizeJava(method.type.toString()) != normalizeJava(returnType) ||
            actualParameters != parameters.map { normalizeJava(it.first) to it.second }
        ) {
            abort("CopperRegistries.${method.nameAsString} signature changed from the proved source")
        }
    }

    private fun requireExactImports(
        unit: CompilationUnit,
        expected: Set<String>,
        ownerName: String
    ) {
        if (unit.imports.any { it.isStatic || it.isAsterisk } ||
            unit.imports.map { it.nameAsString }.toSet() != expected ||
            unit.imports.size != expected.size
        ) {
            abort("$ownerName imports changed from the proved source-bound graph")
        }
    }

    private fun requireLegacyRegistryFields(owner: ClassOrInterfaceDeclaration) {
        data class ExpectedField(
            val modifiers: Set<String>,
            val type: String,
            val initializer: String?,
            val annotations: Set<String> = emptySet()
        )

        val expected = mapOf(
            "WEATHERING" to ExpectedField(
                setOf("private", "static", "final"),
                "BiMap<Supplier<Block>,Supplier<Block>>",
                "HashBiMap.create()"
            ),
            "WAXABLE" to ExpectedField(
                setOf("private", "static", "final"),
                "BiMap<Supplier<Block>,Supplier<Block>>",
                "HashBiMap.create()"
            ),
            "injected" to ExpectedField(setOf("private", "static"), "boolean", null),
            "weatheringMemoized" to ExpectedField(
                setOf("private", "static"),
                "boolean",
                null
            ),
            "waxableMemoized" to ExpectedField(
                setOf("private", "static"),
                "boolean",
                null
            )
        )
        val actual = owner.fields.flatMap { field ->
            if (field.variables.size != 1) {
                abort("CopperRegistries field declarations changed from the proved source")
            }
            field.variables.map { variable -> variable.nameAsString to (field to variable) }
        }.toMap()
        if (actual.keys != expected.keys) {
            abort("CopperRegistries fields changed from the proved source")
        }
        expected.forEach { (name, descriptor) ->
            val (field, variable) = actual.getValue(name)
            if (field.modifiers.map { it.keyword.asString() }.toSet() != descriptor.modifiers ||
                field.annotations.map { it.nameAsString }.toSet() != descriptor.annotations ||
                normalizeJava(variable.type.toString()) != normalizeJava(descriptor.type) ||
                variable.initializer.map { normalizeJava(it.toString()) }.orElse(null) !=
                descriptor.initializer?.let(::normalizeJava)
            ) {
                abort("CopperRegistries.$name field changed from the proved source")
            }
        }
    }

    private fun requireLegacyErrorConsumer(owner: ClassOrInterfaceDeclaration) {
        if (owner.modifiers.map { it.keyword.asString() }.toSet() != setOf("private", "static") ||
            owner.annotations.isNotEmpty() ||
            owner.extendedTypes.isNotEmpty() ||
            owner.implementedTypes.map { normalizeJava(it.toString()) } !=
            listOf("BiConsumer<T,U>") ||
            owner.typeParameters.map { it.nameAsString } != listOf("T", "U") ||
            owner.constructors.size != 1
        ) {
            abort("CopperRegistries error consumer declaration changed from the proved source")
        }
        val fields = owner.fields.flatMap { field ->
            if (field.variables.size != 1) {
                abort("CopperRegistries error consumer fields changed from the proved source")
            }
            field.variables.map { variable -> variable.nameAsString to (field to variable) }
        }.toMap()
        if (fields.keys != setOf("delegate", "exceptionCount", "firstException")) {
            abort("CopperRegistries error consumer fields changed from the proved source")
        }
        fun requireField(
            name: String,
            modifiers: Set<String>,
            type: String,
            initializer: String?,
            annotations: Set<String> = emptySet()
        ) {
            val (field, variable) = fields.getValue(name)
            if (field.modifiers.map { it.keyword.asString() }.toSet() != modifiers ||
                field.annotations.map { it.nameAsString }.toSet() != annotations ||
                normalizeJava(variable.type.toString()) != normalizeJava(type) ||
                variable.initializer.map { normalizeJava(it.toString()) }.orElse(null) !=
                initializer?.let(::normalizeJava)
            ) {
                abort("CopperRegistries error consumer $name field changed from the proved source")
            }
        }
        requireField("delegate", setOf("private", "final"), "BiConsumer<T,U>", null)
        requireField("exceptionCount", setOf("private"), "int", "0")
        requireField(
            "firstException",
            setOf("private"),
            "Throwable",
            null,
            setOf("Nullable")
        )

        val constructor = owner.constructors.single()
        if (constructor.modifiers.map { it.keyword.asString() }.toSet() != setOf("public") ||
            constructor.parameters.map {
                normalizeJava(it.type.toString()) to it.nameAsString
            } != listOf("BiConsumer<T,U>" to "delegate") ||
            normalizeJavaWithoutComments(constructor.body.toString()) !=
            normalizeJava("{this.delegate=delegate;}")
        ) {
            abort("CopperRegistries error consumer constructor changed from the proved source")
        }

        val methodsByName = owner.methods.groupBy { it.nameAsString }
        if (methodsByName.keys != setOf("accept", "reportExceptions") ||
            methodsByName.values.any { it.size != 1 }
        ) {
            abort("CopperRegistries error consumer methods changed from the proved source")
        }
        val accept = methodsByName.getValue("accept").single()
        if (accept.modifiers.map { it.keyword.asString() }.toSet() != setOf("public") ||
            accept.annotations.map { it.nameAsString }.toSet() != setOf("Override") ||
            normalizeJava(accept.type.toString()) != "void" ||
            accept.parameters.map {
                normalizeJava(it.type.toString()) to it.nameAsString
            } != listOf("T" to "t", "U" to "u") ||
            normalizeJavaWithoutComments(accept.body.orElseThrow().toString()) !=
            normalizeJavaWithoutComments(LEGACY_ERROR_ACCEPT_BODY)
        ) {
            abort("CopperRegistries error consumer accept method changed from the proved source")
        }
        val report = methodsByName.getValue("reportExceptions").single()
        if (report.modifiers.map { it.keyword.asString() }.toSet() != setOf("public") ||
            report.annotations.isNotEmpty() ||
            normalizeJava(report.type.toString()) != "void" ||
            report.parameters.map {
                normalizeJava(it.type.toString()) to it.nameAsString
            } != listOf("Logger" to "logger", "String" to "type") ||
            normalizeJavaWithoutComments(report.body.orElseThrow().toString()) !=
            normalizeJavaWithoutComments(LEGACY_ERROR_REPORT_BODY)
        ) {
            abort("CopperRegistries error consumer report method changed from the proved source")
        }
    }

    private fun requireDatamapRegistration(
        owner: ClassOrInterfaceDeclaration,
        call: MethodCallExpr
    ) {
        if (call.scope.map { normalizeJava(it.toString()) }.orElse("") != "generator" ||
            call.arguments.size != 2 ||
            normalizeJava(call.arguments[0].toString()) != "event.includeServer()" ||
            normalizeJava(call.arguments[1].toString()) !=
            "newCreateDatamapProvider(output,lookupProvider)"
        ) {
            abort("CreateDatagen copper data-map registration changed from the target graph")
        }
        val statement = call.findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: abort("Create data-map provider is not a standalone statement")
        if (normalizeJava(statement.expression.toString()) != normalizeJava(call.toString())) {
            abort("Create data-map provider registration is nested in another expression")
        }
        val gather = owner.methods.singleOrNull { it.nameAsString == "gatherData" }
            ?: abort("CreateDatagen must own one gatherData method")
        val block = statement.parentNode.orElse(null)
            ?.let { it as? com.github.javaparser.ast.stmt.BlockStmt }
            ?: abort("Create data-map provider is outside gatherData's block")
        if (statement.findAncestor(MethodDeclaration::class.java).orElse(null) !== gather) {
            abort("Create data-map provider is outside gatherData")
        }
        val index = block.statements.indexOf(statement)
        if (index <= 0) {
            abort("Create data-map provider lacks its sequenced-assembly predecessor")
        }
        val previous = block.statements[index - 1] as? ExpressionStmt
            ?: abort("Create data-map provider predecessor is not a provider statement")
        val previousCall = previous.expression as? MethodCallExpr
            ?: abort("Create data-map provider predecessor is not a call")
        if (previousCall.nameAsString != "addProvider" ||
            previousCall.scope.map { normalizeJava(it.toString()) }.orElse("") != "generator" ||
            previousCall.arguments.size != 2 ||
            normalizeJava(previousCall.arguments[0].toString()) != "event.includeServer()" ||
            normalizeJava(previousCall.arguments[1].toString()) !=
            "newCreateSequencedAssemblyRecipeGen(output,lookupProvider)"
        ) {
            abort("Create data-map provider is not immediately after sequenced assembly")
        }
    }

    private fun requireCreateInitializationOwner(
        owner: ClassOrInterfaceDeclaration,
        statement: ExpressionStmt
    ) {
        val constructor = statement.findAncestor(ConstructorDeclaration::class.java).orElse(null)
        if (constructor != null) {
            if (constructor.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null) !== owner ||
                statement.parentNode.orElse(null) !== constructor.body
            ) {
                abort("CopperRegistries.inject is outside Create's direct constructor body")
            }
            return
        }

        val method = statement.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: abort("CopperRegistries.inject is outside Create's initialization graph")
        if (method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) !== owner ||
            method.nameAsString != "onCtor" ||
            !method.isStatic ||
            normalizeJava(method.type.toString()) != "void" ||
            method.parameters.isNotEmpty() ||
            statement.parentNode.orElse(null) !== method.body.orElse(null)
        ) {
            abort("CopperRegistries.inject is outside Create's proved onCtor body")
        }
        val constructorCalls = owner.constructors.flatMap { createConstructor ->
            createConstructor.findAll(MethodCallExpr::class.java).filter { call ->
                call.nameAsString == "onCtor" &&
                    call.arguments.isEmpty() &&
                    call.scope.map { normalizeJava(it.toString()) == "Create" }.orElse(true)
            }.filter { call ->
                call.findAncestor(ConstructorDeclaration::class.java).orElse(null) ===
                    createConstructor &&
                    call.findAncestor(ExpressionStmt::class.java).map { callStatement ->
                        callStatement.parentNode.orElse(null) === createConstructor.body &&
                            normalizeJava(callStatement.expression.toString()) ==
                            normalizeJava(call.toString())
                    }.orElse(false)
            }
        }
        if (constructorCalls.size != 1) {
            abort("Create's proved onCtor body is not called once by a direct constructor statement")
        }
    }

    private fun requireOwner(
        unit: CompilationUnit,
        packageName: String,
        className: String
    ): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
            abort("$className source path has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("$className source must contain one top-level class")
        if (owner.nameAsString != className || owner.isInterface) {
            abort("$className source owns an unexpected top-level type")
        }
        return owner
    }

    private fun parse(
        parser: JavaParser,
        source: String,
        path: Path
    ): CompilationUnit {
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "Cannot parse $path: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
                        .ifBlank { "unknown parse failure" }
            )
        }
        return result.result.orElseThrow()
    }

    private fun addOutput(
        outputs: MutableList<PreparedOutput>,
        path: Path,
        before: String,
        after: String,
        description: String
    ) {
        if (before != after) {
            outputs += PreparedOutput(path, before, after, description)
        }
    }

    private fun renderRegistries(): String = """
        package com.simibubi.create.foundation.block;

        import java.util.Collections;
        import java.util.Map;

        import org.jetbrains.annotations.ApiStatus;

        import com.google.common.collect.HashBiMap;

        import net.minecraft.core.Holder;
        import net.minecraft.world.level.block.Block;

        @ApiStatus.Internal
        public class CopperRegistries {
        	private static final Map<Holder<Block>, Holder<Block>> WEATHERING = HashBiMap.create();
        	private static final Map<Holder<Block>, Holder<Block>> WAXABLE = HashBiMap.create();

        	public static Map<Holder<Block>, Holder<Block>> getWeatheringView() {
        		return Collections.unmodifiableMap(WEATHERING);
        	}

        	public static Map<Holder<Block>, Holder<Block>> getWaxableView() {
        		return Collections.unmodifiableMap(WAXABLE);
        	}

        	public static synchronized void addWeathering(Holder<Block> original, Holder<Block> weathered) {
        		WEATHERING.put(original, weathered);
        	}

        	public static synchronized void addWaxable(Holder<Block> original, Holder<Block> waxed) {
        		WAXABLE.put(original, waxed);
        	}
        }
    """.trimIndent() + "\n"

    private fun renderProvider(): String = """
        package com.simibubi.create.foundation.data;

        import java.util.concurrent.CompletableFuture;

        import com.simibubi.create.foundation.block.CopperRegistries;

        import net.minecraft.core.Holder;
        import net.minecraft.core.HolderLookup;
        import net.minecraft.core.HolderLookup.Provider;
        import net.minecraft.data.PackOutput;
        import net.minecraft.world.level.block.Block;

        import net.neoforged.neoforge.common.data.DataMapProvider;
        import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
        import net.neoforged.neoforge.registries.datamaps.builtin.Oxidizable;
        import net.neoforged.neoforge.registries.datamaps.builtin.Waxable;

        public class CreateDatamapProvider extends DataMapProvider {
        	public CreateDatamapProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        		super(packOutput, lookupProvider);
        	}

        	@Override
        	protected void gather(Provider provider) {
        		final Builder<Oxidizable, Block> oxidizables = builder(NeoForgeDataMaps.OXIDIZABLES);
        		CopperRegistries.getWeatheringView().forEach((now, after) -> add(oxidizables, now, new Oxidizable(after.value())));

        		final Builder<Waxable, Block> waxables = builder(NeoForgeDataMaps.WAXABLES);
        		CopperRegistries.getWaxableView().forEach((now, after) -> add(waxables, now, new Waxable(after.value())));
        	}

        	public static <T> void add(Builder<T, Block> b, Holder<Block> now, T after) {
        		b.add(now, after, false);
        	}
        }
    """.trimIndent() + "\n"

    private fun normalizeJava(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private fun normalizeJavaWithoutComments(source: String): String =
        normalizeJava(
            source.replace(Regex("""(?s)/\*.*?\*/|//[^\r\n]*"""), "")
        )

    private fun sameJava(first: String, second: String): Boolean =
        normalizeJava(first) == normalizeJava(second)

    private fun writeFamily(
        outputs: List<PreparedOutput>,
        commitProbe: ((Int, Path) -> Unit)?
    ) {
        if (outputs.isEmpty()) return
        val staged = linkedMapOf<PreparedOutput, Path>()
        val committed = mutableListOf<PreparedOutput>()
        try {
            outputs.forEach { output ->
                output.path.parent.createDirectories()
                val temporary = Files.createTempFile(
                    output.path.parent,
                    ".modporter-copper-data-map-",
                    ".tmp"
                )
                Files.writeString(temporary, output.after, StandardCharsets.UTF_8)
                staged[output] = temporary
            }
            staged.entries.forEachIndexed { index, (output, temporary) ->
                commitProbe?.invoke(index, output.path)
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
                            ".modporter-copper-data-map-rollback-",
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

    private const val LEGACY_INJECT_BODY = """
        {
            if (injected) {
                throw new IllegalStateException("Cannot inject CopperRegistries twice!");
            }
            injected = true;
            try {
                Field delegateField =
                    WeatheringCopper.NEXT_BY_BLOCK.getClass().getDeclaredField("delegate");
                delegateField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Supplier<BiMap<Block, Block>> originalWeatheringMapDelegate =
                    (Supplier<BiMap<Block, Block>>) delegateField.get(
                        WeatheringCopper.NEXT_BY_BLOCK
                    );
                com.google.common.base.Supplier<BiMap<Block, Block>>
                    weatheringMapDelegate = () -> {
                        weatheringMemoized = true;
                        ImmutableBiMap.Builder<Block, Block> builder =
                            ImmutableBiMap.builder();
                        builder.putAll(originalWeatheringMapDelegate.get());
                        ErrorHandlingBiConsumer<Supplier<Block>, Supplier<Block>>
                            consumer = new ErrorHandlingBiConsumer<>(
                                (original, weathered) -> {
                                    builder.put(original.get(), weathered.get());
                                }
                            );
                        WEATHERING.forEach(consumer);
                        consumer.reportExceptions(Create.LOGGER, "weathering");
                        return builder.build();
                    };
                delegateField.set(
                    WeatheringCopper.NEXT_BY_BLOCK,
                    weatheringMapDelegate
                );
            } catch (Exception e) {
                Create.LOGGER.error(
                    "Failed to inject weathering copper from CopperRegistries",
                    e
                );
            }
            Supplier<BiMap<Block, Block>> originalWaxableMapSupplier =
                HoneycombItem.WAXABLES;
            Supplier<BiMap<Block, Block>> waxableMapSupplier =
                Suppliers.memoize(() -> {
                    waxableMemoized = true;
                    ImmutableBiMap.Builder<Block, Block> builder =
                        ImmutableBiMap.builder();
                    builder.putAll(originalWaxableMapSupplier.get());
                    ErrorHandlingBiConsumer<Supplier<Block>, Supplier<Block>>
                        consumer = new ErrorHandlingBiConsumer<>(
                            (original, waxed) -> {
                                builder.put(original.get(), waxed.get());
                            }
                        );
                    WAXABLE.forEach(consumer);
                    consumer.reportExceptions(Create.LOGGER, "waxable");
                    return builder.build();
                });
            HoneycombItem.WAXABLES = waxableMapSupplier;
        }
    """

    private const val LEGACY_ERROR_ACCEPT_BODY = """
        {
            try {
                delegate.accept(t, u);
            } catch (Throwable throwable) {
                exceptionCount++;
                if (firstException == null) {
                    firstException = throwable;
                }
            }
        }
    """

    private const val LEGACY_ERROR_REPORT_BODY = """
        {
            if (exceptionCount != 0) {
                logger.error(
                    "Adding " + type + " copper entries from CopperRegistries encountered " +
                        exceptionCount + " exception(s)!"
                );
                if (firstException != null) {
                    logger.error(
                        "The first exception that was thrown is logged below.",
                        firstException
                    );
                }
            }
        }
    """

    private data class PreparedOutput(
        val path: Path,
        val before: String?,
        val after: String,
        val description: String
    )
}
