package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.ReturnStmt
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
 * Moves Create's common-metal tag factories from Forge's legacy namespace to the 1.21 common
 * convention namespace used by native Create.
 *
 * The factories feed recipe and tag datagen. Leaving them on `forge` regenerates legacy recipe
 * inputs and produces NeoForge's missing/legacy-tag warnings even after existing resources have
 * been migrated to `c`.
 */
internal object CreateCommonMetalTagNamespaceMigration {
    const val RULE_ID = "struct-create-common-metal-c-tags"

    private const val COMMON_METAL_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/data/recipe/CommonMetal.java"
    private const val PACKAGE_NAME =
        "com.simibubi.create.foundation.data.recipe"
    private const val OBSOLETE_TODO =
        "TODO: change forge to c in 1.21"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result = try {
        migrateChecked(projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create common-metal tag admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Create common-metal tag migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(projectDir: Path, dryRun: Boolean): Result {
        val sourceFile = projectDir.resolve(COMMON_METAL_RELATIVE)
        if (!sourceFile.isRegularFile()) return Result.EMPTY

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val before = sourceFile.readText()
        val unit = parse(parser, before, sourceFile)
        val owner = requireOwner(unit)
        val itemTag = requireFactory(owner, "itemTag", "Item")
        val blockTag = requireFactory(owner, "blockTag", "Block")
        val itemMode = factoryMode(itemTag, "ITEM")
        val blockMode = factoryMode(blockTag, "BLOCK")

        if (itemMode == Mode.TARGET && blockMode == Mode.TARGET) return Result.EMPTY
        if (itemMode != Mode.LEGACY || blockMode != Mode.LEGACY) {
            abort(
                "Create CommonMetal tag factories are partial or incompatible: " +
                    "itemTag=$itemMode, blockTag=$blockMode"
            )
        }

        LexicalPreservingPrinter.setup(unit)
        migrateFactory(itemTag, "ITEM")
        migrateFactory(blockTag, "BLOCK")
        val after = LexicalPreservingPrinter.print(unit)

        val verified = parse(parser, after, sourceFile)
        val verifiedOwner = requireOwner(verified)
        val verifiedItemMode =
            factoryMode(requireFactory(verifiedOwner, "itemTag", "Item"), "ITEM")
        val verifiedBlockMode =
            factoryMode(requireFactory(verifiedOwner, "blockTag", "Block"), "BLOCK")
        if (
            verifiedItemMode != Mode.TARGET ||
            verifiedBlockMode != Mode.TARGET
        ) {
            abort(
                "Create CommonMetal did not reach native common-tag namespace semantics: " +
                    "itemTag=$verifiedItemMode, blockTag=$verifiedBlockMode"
            )
        }

        if (!dryRun) atomicWrite(sourceFile, after)
        return Result(
            listOf(
                Change(
                    file = sourceFile,
                    line = 1,
                    description =
                        "Generate Create common-metal item and block tags in the 1.21 c namespace",
                    before = before.take(160),
                    after = after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            ),
            emptyList()
        )
    }

    private fun requireOwner(unit: CompilationUnit): EnumDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != PACKAGE_NAME) {
            abort("CommonMetal source has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? EnumDeclaration
            ?: abort("CommonMetal source must contain one top-level enum")
        if (owner.nameAsString != "CommonMetal" || !owner.isPublic) {
            abort("CommonMetal source owns an unexpected top-level type")
        }
        return owner
    }

    private fun requireFactory(
        owner: EnumDeclaration,
        name: String,
        elementType: String
    ): MethodDeclaration {
        val methods = owner.methods.filter { it.nameAsString == name }
        if (methods.size != 1) abort("CommonMetal must own one exact $name factory")
        val method = methods.single()
        val modifiers = method.modifiers.map { it.keyword.asString() }.toSet()
        if (
            modifiers != setOf("private", "static") ||
            method.typeAsString != "TagKey<$elementType>" ||
            method.typeParameters.isNotEmpty() ||
            method.thrownExceptions.isNotEmpty() ||
            method.annotations.isNotEmpty() ||
            method.parameters.size != 1 ||
            method.parameters.single().typeAsString != "String" ||
            method.parameters.single().nameAsString != "path" ||
            method.body.isEmpty
        ) {
            abort("CommonMetal.$name has an incompatible declaration")
        }
        return method
    }

    private fun factoryMode(method: MethodDeclaration, registry: String): Mode {
        val body = method.body.orElse(null) ?: return Mode.OTHER
        if (body.statements.size != 1) return Mode.OTHER
        val returned = body.statements.single() as? ReturnStmt ?: return Mode.OTHER
        val create = returned.expression.orElse(null) as? MethodCallExpr ?: return Mode.OTHER
        if (
            create.nameAsString != "create" ||
            create.scope.map { normalize(it.toString()) }.orElse("") != "TagKey" ||
            create.arguments.size != 2 ||
            normalize(create.arguments[0].toString()) != "Registries.$registry"
        ) {
            return Mode.OTHER
        }
        val location = create.arguments[1] as? MethodCallExpr ?: return Mode.OTHER
        if (
            location.nameAsString != "fromNamespaceAndPath" ||
            location.scope.map { normalize(it.toString()) }.orElse("") != "ResourceLocation" ||
            location.arguments.size != 2 ||
            normalize(location.arguments[1].toString()) != "path"
        ) {
            return Mode.OTHER
        }
        val namespace = location.arguments[0] as? StringLiteralExpr ?: return Mode.OTHER
        return when (namespace.value) {
            "forge" -> Mode.LEGACY
            "c" -> Mode.TARGET
            else -> Mode.OTHER
        }
    }

    private fun migrateFactory(method: MethodDeclaration, registry: String) {
        if (factoryMode(method, registry) != Mode.LEGACY) {
            abort("CommonMetal.${method.nameAsString} changed after admission")
        }
        val namespace = method.findAll(StringLiteralExpr::class.java).singleOrNull {
            it.value == "forge" &&
                it.parentNode.orElse(null) is MethodCallExpr &&
                (it.parentNode.orElseThrow() as MethodCallExpr).nameAsString ==
                    "fromNamespaceAndPath"
        } ?: abort("CommonMetal.${method.nameAsString} lost its exact forge namespace literal")
        namespace.setValue("c")
        method.allContainedComments
            .filter { it.content.trim() == OBSOLETE_TODO }
            .forEach { it.remove() }
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
            ".modporter-create-common-metal-tags-",
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
