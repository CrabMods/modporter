package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.stmt.BlockStmt
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
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Moves Create's extra Registrate data-generator registration ahead of Registrate's root
 * generator construction.
 *
 * Registrate 1.21 installs its GatherDataEvent listener at NORMAL priority and rejects
 * addDataGenerator after that listener constructs the root RegistrateDataProvider. Create 0.5
 * invokes addExtraRegistrateData from its LOWEST-priority gatherData listener, which is therefore
 * necessarily late on NeoForge. Native Create 1.21 splits that registration into a HIGHEST
 * listener and leaves ordinary provider construction at LOWEST.
 */
internal object CreateRegistrateDatagenLifecycleMigration {
    const val RULE_ID = "struct-create-registrate-datagen-lifecycle"

    private const val CREATE_RELATIVE =
        "src/main/java/com/simibubi/create/Create.java"
    private const val DATAGEN_RELATIVE =
        "src/main/java/com/simibubi/create/infrastructure/data/CreateDatagen.java"

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
        Result(emptyList(), listOf(abort.message ?: "Create Registrate datagen admission failed"))
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
                "Create Registrate datagen lifecycle migration failed before commit: " +
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
        val datagenFile = projectDir.resolve(DATAGEN_RELATIVE)
        if (!datagenFile.isRegularFile()) return Result.EMPTY
        val createFile = projectDir.resolve(CREATE_RELATIVE)
        if (!createFile.isRegularFile()) {
            abort("Create Registrate datagen source graph is missing $CREATE_RELATIVE")
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val createBefore = createFile.readText()
        val datagenBefore = datagenFile.readText()
        val createUnit = parse(parser, createBefore, createFile)
        val datagenUnit = parse(parser, datagenBefore, datagenFile)
        val createOwner = requireOwner(createUnit, "com.simibubi.create", "Create")
        val datagenOwner = requireOwner(
            datagenUnit,
            "com.simibubi.create.infrastructure.data",
            "CreateDatagen"
        )

        val createMode = classifyCreate(createOwner)
        val datagenMode = classifyDatagen(datagenOwner)
        if (createMode == Mode.TARGET && datagenMode == Mode.TARGET) {
            return Result.EMPTY
        }
        if (createMode != Mode.LEGACY || datagenMode != Mode.LEGACY) {
            abort(
                "Create Registrate datagen lifecycle is partial or incompatible: " +
                    "Create.java=$createMode, CreateDatagen.java=$datagenMode"
            )
        }

        LexicalPreservingPrinter.setup(createUnit)
        LexicalPreservingPrinter.setup(datagenUnit)
        migrateCreate(createOwner)
        migrateDatagen(datagenOwner)
        val createAfter = LexicalPreservingPrinter.print(createUnit)
        val datagenAfter = LexicalPreservingPrinter.print(datagenUnit)

        val verifiedCreate = parse(parser, createAfter, createFile)
        val verifiedDatagen = parse(parser, datagenAfter, datagenFile)
        if (classifyCreate(requireOwner(verifiedCreate, "com.simibubi.create", "Create")) != Mode.TARGET) {
            abort("Create.java did not reach the proved native datagen listener lifecycle")
        }
        if (
            classifyDatagen(
                requireOwner(
                    verifiedDatagen,
                    "com.simibubi.create.infrastructure.data",
                    "CreateDatagen"
                )
            ) != Mode.TARGET
        ) {
            abort("CreateDatagen.java did not reach the proved native datagen lifecycle")
        }

        val outputs = listOf(
            PreparedOutput(
                createFile,
                createBefore,
                createAfter,
                "Register Create's extra Registrate data generators before root construction"
            ),
            PreparedOutput(
                datagenFile,
                datagenBefore,
                datagenAfter,
                "Split extra Registrate registration from ordinary Create data providers"
            )
        ).filter { it.before != it.after }

        if (!dryRun) writeFamily(outputs, commitProbe)
        return Result(
            outputs.map { output ->
                Change(
                    file = output.path,
                    line = 1,
                    description = output.description,
                    before = output.before.take(160),
                    after = output.after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun classifyCreate(owner: ClassOrInterfaceDeclaration): Mode {
        val low = listener(owner, "gatherData", "LOWEST")
        val high = listener(owner, "gatherDataHighPriority", "HIGHEST")
        val wrongGatherListeners = owner.findAll(MethodReferenceExpr::class.java)
            .filter {
                it.scope.toString() == "CreateDatagen" &&
                    it.identifier in setOf("gatherData", "gatherDataHighPriority")
            }
            .filter { reference ->
                val call = reference.findAncestor(MethodCallExpr::class.java).orElse(null)
                call == null || call.nameAsString != "addListener" ||
                    call.scope.map { it.toString() }.orElse("") != "modEventBus"
            }
        if (wrongGatherListeners.isNotEmpty()) return Mode.OTHER

        if (low.size == 1 && high.isEmpty()) return Mode.LEGACY
        if (low.size != 1 || high.size != 1) return Mode.OTHER
        val lowStatement = low.single().findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: return Mode.OTHER
        val highStatement = high.single().findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: return Mode.OTHER
        val block = lowStatement.parentNode.orElse(null) as? BlockStmt ?: return Mode.OTHER
        if (highStatement.parentNode.orElse(null) !== block) return Mode.OTHER
        if (block.statements.indexOf(highStatement) >= block.statements.indexOf(lowStatement)) {
            return Mode.OTHER
        }
        return Mode.TARGET
    }

    private fun listener(
        owner: ClassOrInterfaceDeclaration,
        methodName: String,
        priority: String
    ): List<MethodCallExpr> =
        owner.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "addListener" &&
                call.scope.map { it.toString() }.orElse("") == "modEventBus" &&
                call.arguments.size == 2 &&
                normalize(call.arguments[0].toString()) == "EventPriority.$priority" &&
                call.arguments[1] is MethodReferenceExpr &&
                (call.arguments[1] as MethodReferenceExpr).let { reference ->
                    reference.scope.toString() == "CreateDatagen" &&
                        reference.identifier == methodName
                } &&
                call.findAncestor(MethodDeclaration::class.java)
                    .map { it.nameAsString == "onCtor" }
                    .orElse(false)
        }

    private fun classifyDatagen(owner: ClassOrInterfaceDeclaration): Mode {
        val gather = method(owner, "gatherData") ?: return Mode.OTHER
        val high = method(owner, "gatherDataHighPriority")
        val extra = owner.methods.singleOrNull { it.nameAsString == "addExtraRegistrateData" }
            ?: return Mode.OTHER
        if (!isGatherMethod(gather) || !isExtraMethod(extra)) return Mode.OTHER
        val gatherStatements = gather.body.orElse(null)?.statements ?: return Mode.OTHER
        val gatherExtraCalls = gather.findAll(MethodCallExpr::class.java)
            .filter { isExtraCall(it) }

        if (
            high == null &&
            gatherStatements.isNotEmpty() &&
            normalize(gatherStatements.first().toString()) == "addExtraRegistrateData();" &&
            gatherExtraCalls.size == 1 &&
            owner.findAll(MethodCallExpr::class.java).count(::isExtraCall) == 1 &&
            gather.findAll(MethodCallExpr::class.java).none(::isGetModsCall)
        ) {
            return Mode.LEGACY
        }

        if (high == null || !isGatherMethod(high)) return Mode.OTHER
        if (
            normalize(high.body.orElse(null)?.toString() ?: "") !=
            normalize(
                """
                {
                    if (event.getMods().contains(Create.ID))
                        addExtraRegistrateData();
                }
                """.trimIndent()
            )
        ) {
            return Mode.OTHER
        }
        if (
            gatherStatements.isEmpty() ||
            normalize(gatherStatements.first().toString()) !=
            "if(!event.getMods().contains(Create.ID))return;" ||
            gatherExtraCalls.isNotEmpty() ||
            owner.findAll(MethodCallExpr::class.java).count(::isExtraCall) != 1
        ) {
            return Mode.OTHER
        }
        return Mode.TARGET
    }

    private fun method(
        owner: ClassOrInterfaceDeclaration,
        name: String
    ): MethodDeclaration? = owner.methods.singleOrNull { it.nameAsString == name }

    private fun isGatherMethod(method: MethodDeclaration): Boolean =
        method.isPublic &&
            method.isStatic &&
            method.typeAsString == "void" &&
            method.typeParameters.isEmpty() &&
            method.parameters.size == 1 &&
            method.parameters.single().typeAsString == "GatherDataEvent" &&
            method.parameters.single().nameAsString == "event" &&
            method.body.isPresent

    private fun isExtraMethod(method: MethodDeclaration): Boolean =
        method.isPrivate &&
            method.isStatic &&
            method.typeAsString == "void" &&
            method.typeParameters.isEmpty() &&
            method.parameters.isEmpty() &&
            method.body.isPresent

    private fun isExtraCall(call: MethodCallExpr): Boolean =
        call.nameAsString == "addExtraRegistrateData" &&
            call.scope.isEmpty &&
            call.arguments.isEmpty()

    private fun isGetModsCall(call: MethodCallExpr): Boolean =
        call.nameAsString == "getMods" &&
            call.scope.map { it.toString() }.orElse("") == "event" &&
            call.arguments.isEmpty()

    private fun migrateCreate(owner: ClassOrInterfaceDeclaration) {
        val low = listener(owner, "gatherData", "LOWEST").single()
        val lowStatement = low.findAncestor(ExpressionStmt::class.java).orElse(null)
            ?: abort("Create gatherData listener is not a standalone statement")
        val block = lowStatement.parentNode.orElse(null) as? BlockStmt
            ?: abort("Create gatherData listener is outside a method block")
        val index = block.statements.indexOf(lowStatement)
        if (index < 0) abort("Cannot locate Create's LOWEST gatherData listener")
        block.addStatement(
            index,
            StaticJavaParser.parseStatement(
                "modEventBus.addListener(" +
                    "EventPriority.HIGHEST, CreateDatagen::gatherDataHighPriority);"
            )
        )
    }

    private fun migrateDatagen(owner: ClassOrInterfaceDeclaration) {
        val gather = method(owner, "gatherData")
            ?: abort("CreateDatagen lacks gatherData")
        val body = gather.body.orElseThrow()
        if (
            body.statements.isEmpty() ||
            normalize(body.statements.first().toString()) != "addExtraRegistrateData();"
        ) {
            abort("CreateDatagen gatherData no longer starts with extra Registrate registration")
        }
        body.statements.removeAt(0)
        body.addStatement(
            0,
            StaticJavaParser.parseStatement(
                "if (!event.getMods().contains(Create.ID)) return;"
            )
        )

        val gatherIndex = owner.members.indexOf(gather)
        if (gatherIndex < 0) abort("Cannot locate CreateDatagen gatherData member")
        val high = StaticJavaParser.parseBodyDeclaration(
            """
            public static void gatherDataHighPriority(GatherDataEvent event) {
                if (event.getMods().contains(Create.ID))
                    addExtraRegistrateData();
            }
            """.trimIndent()
        ).asMethodDeclaration()
        owner.members.add(gatherIndex, high)
    }

    private fun requireOwner(
        unit: CompilationUnit,
        packageName: String,
        className: String
    ): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
            abort("$className source has an unexpected package")
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

    private fun normalize(source: String): String =
        source.replace(Regex("""\s+"""), "")

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
                    ".modporter-create-datagen-lifecycle-",
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
                    val rollback = Files.createTempFile(
                        output.path.parent,
                        ".modporter-create-datagen-lifecycle-rollback-",
                        ".tmp"
                    )
                    Files.writeString(rollback, output.before, StandardCharsets.UTF_8)
                    Files.move(rollback, output.path, ATOMIC_MOVE, REPLACE_EXISTING)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            staged.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private enum class Mode {
        LEGACY,
        TARGET,
        OTHER
    }

    private data class PreparedOutput(
        val path: Path,
        val before: String,
        val after: String,
        val description: String
    )
}
