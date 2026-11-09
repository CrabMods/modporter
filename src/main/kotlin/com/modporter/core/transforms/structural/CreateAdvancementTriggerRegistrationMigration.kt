package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Moves Create's custom criterion-trigger registration to the TRIGGER_TYPES RegisterEvent.
 *
 * Forge Create registers these trigger instances from common-setup enqueueWork. NeoForge datagen
 * serializes advancements before that work binds the instances, so Advancement.CODEC observes
 * direct, unregistered trigger holders. Native Create 1.21 binds the same instances and IDs while
 * the built-in trigger registry is firing.
 */
internal object CreateAdvancementTriggerRegistrationMigration {
    const val RULE_ID = "struct-create-advancement-trigger-register-event"

    private const val CREATE_RELATIVE =
        "src/main/java/com/simibubi/create/Create.java"
    private const val TRIGGERS_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/advancement/AllTriggers.java"
    private const val BUILT_IN_REGISTRIES =
        "net.minecraft.core.registries.BuiltInRegistries"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result = try {
        migrateChecked(projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create trigger registration admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Create trigger registration migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(projectDir: Path, dryRun: Boolean): Result {
        val triggersFile = projectDir.resolve(TRIGGERS_RELATIVE)
        if (!triggersFile.isRegularFile()) return Result.EMPTY
        val createFile = projectDir.resolve(CREATE_RELATIVE)
        if (!createFile.isRegularFile()) {
            abort("Create trigger registration source graph is missing $CREATE_RELATIVE")
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val triggersUnit = parse(parser, triggersFile.readText(), triggersFile)
        requireNativeTriggerRegistration(triggersUnit)

        val before = createFile.readText()
        val unit = parse(parser, before, createFile)
        val owner = requireOwner(unit, "com.simibubi.create", "Create")
        val mode = classify(owner, unit)
        if (mode == Mode.TARGET) return Result.EMPTY
        if (mode != Mode.LEGACY) {
            abort("Create advancement trigger registration is partial or incompatible: $mode")
        }

        LexicalPreservingPrinter.setup(unit)
        val init = method(owner, "init") ?: abort("Create lacks init")
        val initBlock = enqueueWorkBlock(init)
            ?: abort("Create init lacks one event.enqueueWork block")
        val advancement = registrationStatement(initBlock, "AllAdvancements")
            ?: abort("Create common setup lost AllAdvancements.register()")
        val triggers = registrationStatement(initBlock, "AllTriggers")
            ?: abort("Create common setup lost AllTriggers.register()")
        if (initBlock.statements.indexOf(triggers) != initBlock.statements.indexOf(advancement) + 1) {
            abort("Create common setup registration calls are no longer consecutive")
        }
        advancement.remove()
        triggers.remove()

        unit.addImport(BUILT_IN_REGISTRIES)
        val onRegister = method(owner, "onRegister")
            ?: abort("Create lacks onRegister")
        onRegister.body.orElseThrow().addStatement(
            StaticJavaParser.parseStatement(
                """
                if (event.getRegistry() == BuiltInRegistries.TRIGGER_TYPES) {
                    AllAdvancements.register();
                    AllTriggers.register();
                }
                """.trimIndent()
            )
        )

        val after = LexicalPreservingPrinter.print(unit)
        val verified = parse(parser, after, createFile)
        if (classify(requireOwner(verified, "com.simibubi.create", "Create"), verified) != Mode.TARGET) {
            abort("Create did not reach native trigger RegisterEvent timing")
        }

        if (!dryRun) atomicWrite(createFile, after)
        return Result(
            listOf(
                Change(
                    file = createFile,
                    line = 1,
                    description =
                        "Bind Create advancement triggers during the TRIGGER_TYPES RegisterEvent",
                    before = before.take(160),
                    after = after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            ),
            emptyList()
        )
    }

    private fun classify(
        owner: ClassOrInterfaceDeclaration,
        unit: CompilationUnit
    ): Mode {
        val init = method(owner, "init") ?: return Mode.OTHER
        val onRegister = method(owner, "onRegister") ?: return Mode.OTHER
        if (!isEventMethod(init, "FMLCommonSetupEvent") ||
            !isEventMethod(onRegister, "RegisterEvent")
        ) {
            return Mode.OTHER
        }
        val initBlock = enqueueWorkBlock(init) ?: return Mode.OTHER
        val initAdvancements = registrationStatements(initBlock, "AllAdvancements")
        val initTriggers = registrationStatements(initBlock, "AllTriggers")
        val targetBlocks = onRegister.findAll(IfStmt::class.java).filter(::isTargetRegistration)
        val importCount = unit.imports.count {
            !it.isStatic && !it.isAsterisk && it.nameAsString == BUILT_IN_REGISTRIES
        }

        if (
            initAdvancements.size == 1 &&
            initTriggers.size == 1 &&
            targetBlocks.isEmpty() &&
            importCount == 0 &&
            ownerRegistrationCount(owner, "AllAdvancements") == 1 &&
            ownerRegistrationCount(owner, "AllTriggers") == 1
        ) {
            val advancementIndex = initBlock.statements.indexOf(initAdvancements.single())
            val triggerIndex = initBlock.statements.indexOf(initTriggers.single())
            return if (triggerIndex == advancementIndex + 1) Mode.LEGACY else Mode.OTHER
        }

        if (
            initAdvancements.isEmpty() &&
            initTriggers.isEmpty() &&
            targetBlocks.size == 1 &&
            importCount == 1 &&
            ownerRegistrationCount(owner, "AllAdvancements") == 1 &&
            ownerRegistrationCount(owner, "AllTriggers") == 1
        ) {
            return Mode.TARGET
        }
        return Mode.OTHER
    }

    private fun method(
        owner: ClassOrInterfaceDeclaration,
        name: String
    ): MethodDeclaration? = owner.methods.singleOrNull { it.nameAsString == name }

    private fun isEventMethod(method: MethodDeclaration, parameterType: String): Boolean =
        method.isPublic &&
            method.isStatic &&
            method.typeAsString == "void" &&
            method.typeParameters.isEmpty() &&
            method.parameters.size == 1 &&
            method.parameters.single().typeAsString == parameterType &&
            method.parameters.single().nameAsString == "event" &&
            method.body.isPresent

    private fun enqueueWorkBlock(method: MethodDeclaration): BlockStmt? {
        val calls = method.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "enqueueWork" &&
                call.scope.map { it.toString() }.orElse("") == "event" &&
                call.arguments.size == 1 &&
                call.arguments.single() is LambdaExpr
        }
        if (calls.size != 1) return null
        val lambda = calls.single().arguments.single() as LambdaExpr
        return lambda.body as? BlockStmt
    }

    private fun registrationStatements(
        block: BlockStmt,
        owner: String
    ): List<ExpressionStmt> =
        block.statements.mapNotNull { statement ->
            val expression = statement as? ExpressionStmt ?: return@mapNotNull null
            val call = expression.expression as? MethodCallExpr ?: return@mapNotNull null
            if (
                call.nameAsString == "register" &&
                call.scope.map { it.toString() }.orElse("") == owner &&
                call.arguments.isEmpty()
            ) {
                expression
            } else {
                null
            }
        }

    private fun registrationStatement(
        block: BlockStmt,
        owner: String
    ): ExpressionStmt? = registrationStatements(block, owner).singleOrNull()

    private fun ownerRegistrationCount(
        owner: ClassOrInterfaceDeclaration,
        scope: String
    ): Int = owner.findAll(MethodCallExpr::class.java).count { call ->
        call.nameAsString == "register" &&
            call.scope.map { it.toString() }.orElse("") == scope &&
            call.arguments.isEmpty()
    }

    private fun isTargetRegistration(statement: IfStmt): Boolean {
        if (
            normalize(statement.condition.toString()) !=
            "event.getRegistry()==BuiltInRegistries.TRIGGER_TYPES" ||
            statement.elseStmt.isPresent
        ) {
            return false
        }
        val block = statement.thenStmt as? BlockStmt ?: return false
        if (block.statements.size != 2) return false
        return registrationStatement(block, "AllAdvancements") === block.statements[0] &&
            registrationStatement(block, "AllTriggers") === block.statements[1]
    }

    private fun requireNativeTriggerRegistration(unit: CompilationUnit) {
        val owner = requireOwner(
            unit,
            "com.simibubi.create.foundation.advancement",
            "AllTriggers"
        )
        val register = method(owner, "register")
            ?: abort("AllTriggers lacks register()")
        if (
            !register.isPublic ||
            !register.isStatic ||
            register.typeAsString != "void" ||
            register.parameters.isNotEmpty()
        ) {
            abort("AllTriggers.register() has an incompatible declaration")
        }
        val registrations = register.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "register" &&
                call.scope.map { it.toString() }.orElse("") == "Registry" &&
                call.arguments.size == 3 &&
                normalize(call.arguments[0].toString()) == "BuiltInRegistries.TRIGGER_TYPES" &&
                normalize(call.arguments[1].toString()) == "trigger.getId()" &&
                normalize(call.arguments[2].toString()) == "trigger"
        }
        if (registrations.size != 1) {
            abort(
                "AllTriggers must bind the existing trigger instance and ID to " +
                    "BuiltInRegistries.TRIGGER_TYPES"
            )
        }
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

    private fun atomicWrite(path: Path, source: String) {
        val temporary = Files.createTempFile(
            path.parent,
            ".modporter-create-trigger-register-event-",
            ".tmp"
        )
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private enum class Mode {
        LEGACY,
        TARGET,
        OTHER
    }
}
