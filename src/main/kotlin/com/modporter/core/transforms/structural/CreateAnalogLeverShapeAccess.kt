package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.ReturnStmt
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
 * Restores Create's source-owned AnalogLever delegation to the vanilla LeverBlock shape.
 *
 * Calling `state.getShape(...)` from AnalogLeverBlock.getShape would dispatch back to the
 * AnalogLever override and recurse. The admitted source instead invokes BlockBehaviour's
 * protected implementation on Blocks.LEVER through the exact common mixin accessor shipped by
 * Create 1.21.1.
 */
internal object CreateAnalogLeverShapeAccess {
    const val RULE_ID = "struct-create-analog-lever-shape-access"

    private const val ANALOG_PACKAGE =
        "com.simibubi.create.content.redstone.analogLever"
    private const val ANALOG_CLASS = "AnalogLeverBlock"
    private const val ANALOG_RELATIVE =
        "src/main/java/com/simibubi/create/content/redstone/analogLever/AnalogLeverBlock.java"
    private const val MIXIN_PACKAGE = "com.simibubi.create.foundation.mixin"
    private const val ACCESSOR_PACKAGE = "$MIXIN_PACKAGE.accessor"
    private const val ACCESSOR_NAME = "BlockBehaviourAccessor"
    private const val ACCESSOR_FQN = "$ACCESSOR_PACKAGE.$ACCESSOR_NAME"
    private const val MIXIN_ENTRY = "accessor.BlockBehaviourAccessor"
    private const val BLOCK_BEHAVIOUR =
        "net.minecraft.world.level.block.state.BlockBehaviour"
    private const val BLOCKS = "net.minecraft.world.level.block.Blocks"
    private const val GET_SHAPE_DESCRIPTOR =
        "(Lnet/minecraft/world/level/block/state/BlockState;" +
            "Lnet/minecraft/world/level/BlockGetter;" +
            "Lnet/minecraft/core/BlockPos;" +
            "Lnet/minecraft/world/phys/shapes/CollisionContext;)" +
            "Lnet/minecraft/world/phys/shapes/VoxelShape;"

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
        Result(emptyList(), listOf(abort.message ?: "AnalogLever accessor admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "AnalogLever accessor migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean
    ): Result {
        val analogFile = projectDir.resolve(ANALOG_RELATIVE)
        if (!analogFile.isRegularFile()) return Result.EMPTY
        if (!targetContractIsVerified()) {
            abort(
                "NeoForge 1.21.1 BlockBehaviour.getShape is not the verified protected " +
                    "four-argument target"
            )
        }

        val analogSource = analogFile.readText()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parse(parser, analogSource, analogFile)
        val admitted = admitAnalogLever(parsed, analogSource)

        val configs = readMixinConfigs(projectDir)
        val owners = configs.filter { it.packageName == MIXIN_PACKAGE }
        if (owners.size != 1) {
            abort(
                "AnalogLever accessor requires exactly one $MIXIN_PACKAGE mixin config; " +
                    "found ${owners.size}"
            )
        }
        val owner = owners.single()
        if (owner.root["mixins"] !is JsonArray) {
            abort("Owning mixin config ${owner.path} has no common mixins array")
        }
        requireMixinConfigRegistration(projectDir, owner.path)
        requireUniqueCommonOwnership(configs, owner)

        val sourceRoot = projectDir.resolve("src/main/java")
        val accessorPath = sourceRoot.resolve(
            ACCESSOR_PACKAGE.replace('.', '/') + "/$ACCESSOR_NAME.java"
        )
        val accessorSource = renderAccessor()
        if (accessorPath.exists() && !accessorHasExactContract(accessorPath.readText())) {
            abort("Generated accessor path is owned by incompatible source: $accessorPath")
        }
        Files.walk(sourceRoot).use { paths ->
            paths.filter {
                it.isRegularFile() &&
                    it.name == "$ACCESSOR_NAME.java" &&
                    it != accessorPath
            }.forEach { path ->
                val source = path.readText()
                if (Regex(
                        """(?m)^\s*package\s+${Regex.escape(ACCESSOR_PACKAGE)}\s*;"""
                    ).containsMatchIn(source)
                ) {
                    abort("Duplicate $ACCESSOR_FQN source exists at $path")
                }
            }
        }

        val updatedAnalog = when (admitted.mode) {
            ShapeMode.LEGACY -> migrateAnalogSource(pass, parser, parsed, admitted)
            ShapeMode.MIGRATED -> analogSource
        }
        val updatedConfig = registerCommonMixin(owner)
        val outputs = mutableListOf<PreparedOutput>()
        if (updatedAnalog != analogSource) {
            outputs += PreparedOutput(
                analogFile,
                analogSource,
                updatedAnalog,
                "Delegate AnalogLever shape to Blocks.LEVER through BlockBehaviourAccessor"
            )
        }
        if (!accessorPath.exists()) {
            outputs += PreparedOutput(
                accessorPath,
                null,
                accessorSource,
                "Generate the verified BlockBehaviour getShape invoker"
            )
        }
        if (updatedConfig != owner.source) {
            outputs += PreparedOutput(
                owner.path,
                owner.source,
                updatedConfig,
                "Register BlockBehaviourAccessor in the common Create mixin array"
            )
        }

        if (!dryRun) writeFamily(outputs)
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

    private fun admitAnalogLever(
        unit: CompilationUnit,
        source: String
    ): AdmittedShape {
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        if (packageName != ANALOG_PACKAGE) {
            abort("AnalogLever source path has unexpected package $packageName")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("AnalogLever source must contain one top-level class")
        if (owner.nameAsString != ANALOG_CLASS || owner.isInterface ||
            owner.extendedTypes.singleOrNull()?.nameAsString !=
            "FaceAttachedHorizontalDirectionalBlock"
        ) {
            abort("AnalogLever source does not own the expected concrete block class")
        }
        val methods = owner.methods.filter { it.nameAsString == "getShape" }
        if (methods.size != 1) {
            abort("AnalogLever must own exactly one getShape override")
        }
        val method = methods.single()
        val expectedTypes = listOf(
            "BlockState",
            "BlockGetter",
            "BlockPos",
            "CollisionContext"
        )
        if (method.parameters.map { simpleTypeName(it.type.toString()) } != expectedTypes ||
            simpleTypeName(method.type.toString()) != "VoxelShape"
        ) {
            abort("AnalogLever getShape signature is outside the verified four-argument contract")
        }
        val body = method.body.orElse(null)
            ?: abort("AnalogLever getShape must have a body")
        val returned = body.statements.singleOrNull() as? ReturnStmt
            ?: abort("AnalogLever getShape must contain one direct return")
        val expression = returned.expression.orElse(null)
            ?: abort("AnalogLever getShape return is missing")
        val arguments = method.parameters.map { it.nameAsString }

        val mode = when {
            isLegacyLeverShapeCall(expression, arguments) -> {
                if (!hasExactImport(unit, BLOCKS)) {
                    abort("Legacy AnalogLever delegation does not import the canonical Blocks owner")
                }
                ShapeMode.LEGACY
            }
            isMigratedLeverShapeCall(expression, arguments) -> {
                if (!hasExactImport(unit, ACCESSOR_FQN)) {
                    abort("Migrated AnalogLever call is missing the exact accessor import")
                }
                ShapeMode.MIGRATED
            }
            Regex("""\b${Regex.escape(arguments[0])}\s*\.getShape\s*\(""")
                .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source)) -> {
                abort(
                    "AnalogLever getShape delegates through its BlockState and would recurse; " +
                        "the verified Blocks.LEVER BlockBehaviour invoker is required"
                )
            }
            else -> abort(
                "AnalogLever getShape is not the verified direct Blocks.LEVER delegation"
            )
        }
        val conflictingImports = unit.imports.filter {
            !it.isAsterisk &&
                it.name.identifier == ACCESSOR_NAME &&
                it.nameAsString != ACCESSOR_FQN
        }
        if (conflictingImports.isNotEmpty()) {
            abort("AnalogLever imports a conflicting $ACCESSOR_NAME type")
        }
        return AdmittedShape(method, returned, arguments, mode)
    }

    private fun isLegacyLeverShapeCall(
        expression: Expression,
        arguments: List<String>
    ): Boolean {
        val call = expression.unwrap() as? MethodCallExpr ?: return false
        if (call.nameAsString != "getShape" ||
            call.arguments.map { normalizeJava(it.toString()) } != arguments
        ) {
            return false
        }
        val lever = call.scope.orElse(null)?.unwrap() as? FieldAccessExpr ?: return false
        return lever.nameAsString == "LEVER" &&
            (lever.scope.unwrap() as? NameExpr)?.nameAsString == "Blocks"
    }

    private fun isMigratedLeverShapeCall(
        expression: Expression,
        arguments: List<String>
    ): Boolean {
        val expected =
            "((BlockBehaviourAccessor)Blocks.LEVER).create\$getShape(" +
                arguments.joinToString(",") + ")"
        return normalizeJava(expression.toString()) == expected
    }

    private fun migrateAnalogSource(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        unit: CompilationUnit,
        admitted: AdmittedShape
    ): String {
        val invocation = parser.parseExpression<Expression>(
            "((BlockBehaviourAccessor) Blocks.LEVER).create\$getShape(" +
                admitted.arguments.joinToString(", ") + ")"
        ).result.orElseThrow()
        LexicalPreservingPrinter.setup(unit)
        admitted.returned.setExpression(invocation)
        admitted.method.annotations.filter {
            normalizeJava(it.toString()) == """@SuppressWarnings("deprecation")"""
        }.forEach { it.remove() }
        val migrated = LexicalPreservingPrinter.print(unit)
        return pass.addImportIfMissing(migrated, ACCESSOR_FQN)
    }

    private fun readMixinConfigs(projectDir: Path): List<MixinConfig> {
        val resources = projectDir.resolve("src/main/resources")
        if (!Files.isDirectory(resources)) {
            abort("AnalogLever accessor requires an existing main mixin config")
        }
        return Files.walk(resources).use { paths ->
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
                MixinConfig(path, source, root, packageName)
            }.toList()
        }
    }

    private fun requireUniqueCommonOwnership(
        configs: List<MixinConfig>,
        owner: MixinConfig
    ) {
        var commonOccurrences = 0
        for (config in configs) {
            for (section in listOf("mixins", "client", "server")) {
                val entries = config.root[section] as? JsonArray ?: continue
                for (element in entries) {
                    val entry = (element as? JsonPrimitive)?.content ?: continue
                    val canonical = "${config.packageName}.$entry"
                    if (canonical != ACCESSOR_FQN) continue
                    if (config.path != owner.path || section != "mixins") {
                        abort(
                            "$ACCESSOR_FQN is already owned by ${config.path} in " +
                                "the $section mixin section"
                        )
                    }
                    commonOccurrences++
                }
            }
        }
        if (commonOccurrences > 1) {
            abort("$ACCESSOR_FQN is registered more than once in the common mixin array")
        }
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

        val buildScripts = listOf(
            projectDir.resolve("build.gradle"),
            projectDir.resolve("build.gradle.kts")
        ).filter { it.exists() }
        val owningBlocks = buildScripts.flatMap { script ->
            legacyMixinBlocks(script.readText()).filter { block ->
                legacyMixinConfigEntries(block) == listOf(relative)
            }
        }
        if (owningBlocks.size != 1) {
            abort(
                "Owning mixin config $relative is not registered once in mod metadata " +
                    "or one exact legacy Gradle mixin block"
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

    private fun registerCommonMixin(config: MixinConfig): String {
        val mixins = config.root["mixins"] as? JsonArray
            ?: abort("Owning mixin config ${config.path} has no common mixins array")
        val occurrences = mixins.count {
            (it as? JsonPrimitive)?.content == MIXIN_ENTRY
        }
        if (occurrences > 1) {
            abort("$MIXIN_ENTRY appears more than once in ${config.path}")
        }
        if (occurrences == 1) return config.source
        val updated = JsonObject(
            config.root.mapValues { (key, value) ->
                if (key != "mixins") value
                else JsonArray(mixins + JsonPrimitive(MIXIN_ENTRY))
            }
        )
        val newline = if (config.source.contains("\r\n")) "\r\n" else "\n"
        return metadataJson.encodeToString(JsonObject.serializer(), updated)
            .replace("\n", newline)
            .trimEnd() + newline
    }

    private fun renderAccessor(): String = """
package $ACCESSOR_PACKAGE;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockBehaviour.class)
public interface $ACCESSOR_NAME {
    @Invoker("getShape")
    VoxelShape create${'$'}getShape(
        BlockState blockState,
        BlockGetter blockGetter,
        BlockPos blockPos,
        CollisionContext context
    );
}
""".trimIndent() + "\n"

    private fun accessorHasExactContract(source: String): Boolean = runCatching {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val unit = parser.parse(source).result.orElse(null) ?: return@runCatching false
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != ACCESSOR_PACKAGE) {
            return@runCatching false
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: return@runCatching false
        if (!owner.isInterface || owner.nameAsString != ACCESSOR_NAME ||
            owner.members.size != 1 ||
            owner.annotations.none {
                normalizeJava(it.toString()) == "@Mixin(BlockBehaviour.class)"
            }
        ) {
            return@runCatching false
        }
        val method = owner.methods.singleOrNull() ?: return@runCatching false
        method.nameAsString == "create\$getShape" &&
            !method.body.isPresent &&
            simpleTypeName(method.type.toString()) == "VoxelShape" &&
            method.parameters.map { simpleTypeName(it.type.toString()) } == listOf(
                "BlockState",
                "BlockGetter",
                "BlockPos",
                "CollisionContext"
            ) &&
            method.annotations.any {
                normalizeJava(it.toString()) == """@Invoker("getShape")"""
            } &&
            hasExactImport(unit, BLOCK_BEHAVIOUR) &&
            hasExactImport(unit, "org.spongepowered.asm.mixin.Mixin") &&
            hasExactImport(unit, "org.spongepowered.asm.mixin.gen.Invoker")
    }.getOrDefault(false)

    private fun targetContractIsVerified(): Boolean =
        TargetMembers.inheritedMethod(
            BLOCK_BEHAVIOUR,
            "getShape",
            GET_SHAPE_DESCRIPTOR
        )?.let { method ->
            method.owner == BLOCK_BEHAVIOUR && method.access == "protected"
        } == true

    private fun hasExactImport(unit: CompilationUnit, type: String): Boolean =
        unit.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == type }

    private fun simpleTypeName(type: String): String =
        type.replace(Regex("""\s+"""), "").substringAfterLast('.')

    private fun normalizeJava(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private fun Expression.unwrap(): Expression {
        var current = this
        while (current is EnclosedExpr) current = current.inner
        return current
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
                    ".modporter-analog-lever-",
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
                            ".modporter-analog-lever-rollback-",
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

    private enum class ShapeMode {
        LEGACY,
        MIGRATED
    }

    private data class AdmittedShape(
        val method: MethodDeclaration,
        val returned: ReturnStmt,
        val arguments: List<String>,
        val mode: ShapeMode
    )

    private data class MixinConfig(
        val path: Path,
        val source: String,
        val root: JsonObject,
        val packageName: String
    )

    private data class PreparedOutput(
        val path: Path,
        val before: String?,
        val after: String,
        val description: String
    )
}
