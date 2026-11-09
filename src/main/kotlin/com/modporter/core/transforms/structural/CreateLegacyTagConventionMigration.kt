package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
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
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Moves Create's remaining Forge tag helpers and enum entries to the 1.21 tag conventions.
 *
 * This is a two-source transaction because MillingRecipeGen embeds both the old namespace and a
 * call to AllTags.forgeItemTag. The migration admits only the complete known legacy or target
 * family, rewrites both sources in memory, verifies the target semantics, then publishes both
 * files with aggregate rollback.
 */
internal object CreateLegacyTagConventionMigration {
    const val RULE_ID = "struct-create-legacy-tag-conventions"

    private const val ALL_TAGS_RELATIVE =
        "src/main/java/com/simibubi/create/AllTags.java"
    private const val MILLING_RELATIVE =
        "src/main/java/com/simibubi/create/api/data/recipe/MillingRecipeGen.java"
    private const val ALL_TAGS_PACKAGE = "com.simibubi.create"
    private const val MILLING_PACKAGE = "com.simibubi.create.api.data.recipe"
    private const val NAMESPACE_IMPORT =
        "com.simibubi.create.AllTags.NameSpace"

    private val entries = listOf(
        TagEntry("AllBlockTags", "CARDBOARD_STORAGE_BLOCKS", "storage_blocks/cardboard"),
        TagEntry("AllBlockTags", "ANDESITE_ALLOY_STORAGE_BLOCKS", "storage_blocks/andesite_alloy"),
        TagEntry("AllBlockTags", "STONE_ORES_IN_GROUND", "ores_in_ground/stone"),
        TagEntry("AllBlockTags", "DEEPSLATE_ORES_IN_GROUND", "ores_in_ground/deepslate"),
        TagEntry("AllBlockTags", "RELOCATION_NOT_SUPPORTED"),
        TagEntry("AllItemTags", "STRIPPED_LOGS"),
        TagEntry(
            "AllItemTags",
            "STRIPPED_WOOD",
            targetPath = "stripped_woods"
        ),
        TagEntry("AllItemTags", "OBSIDIAN_DUST", "dusts/obsidian"),
        TagEntry("AllItemTags", "PLATES"),
        TagEntry("AllItemTags", "OBSIDIAN_PLATES", "plates/obsidian"),
        TagEntry("AllItemTags", "CARDBOARD_PLATES", "plates/cardboard"),
        TagEntry("AllItemTags", "WRENCH", "tools/wrench"),
        TagEntry("AllItemTags", "CERTUS_QUARTZ", "gems/certus_quartz"),
        TagEntry("AllItemTags", "AMETRINE_ORES", "ores/ametrine"),
        TagEntry("AllItemTags", "ANTHRACITE_ORES", "ores/anthracite"),
        TagEntry("AllItemTags", "EMERALDITE_ORES", "ores/emeraldite"),
        TagEntry("AllItemTags", "LIGNITE_ORES", "ores/lignite"),
        TagEntry("AllItemTags", "RAW_MATERIALS"),
        TagEntry("AllItemTags", "CARDBOARD_STORAGE_BLOCKS", "storage_blocks/cardboard"),
        TagEntry("AllItemTags", "ANDESITE_ALLOY_STORAGE_BLOCKS", "storage_blocks/andesite_alloy"),
        TagEntry("AllItemTags", "STONE_ORES_IN_GROUND", "ores_in_ground/stone"),
        TagEntry("AllItemTags", "DEEPSLATE_ORES_IN_GROUND", "ores_in_ground/deepslate"),
        TagEntry("AllItemTags", "HONEY_BUCKETS", "buckets/honey"),
        TagEntry("AllItemTags", "FLOUR", targetPath = "flours"),
        TagEntry(
            "AllItemTags",
            "WHEAT_FLOUR",
            legacyPath = "flour/wheat",
            targetPath = "flours/wheat"
        ),
        TagEntry("AllItemTags", "DOUGH", targetPath = "foods/dough"),
        TagEntry(
            "AllItemTags",
            "WHEAT_DOUGH",
            legacyPath = "dough/wheat",
            targetPath = "foods/dough/wheat"
        ),
        TagEntry(
            "AllItemTags",
            "HELMET_ARMORS",
            legacyPath = "armors/helmets",
            targetNamespace = "MINECRAFT",
            targetPath = "head_armor"
        ),
        TagEntry(
            "AllItemTags",
            "CHESTPLATE_ARMORS",
            legacyPath = "armors/chestplates",
            targetNamespace = "MINECRAFT",
            targetPath = "chest_armor"
        ),
        TagEntry(
            "AllItemTags",
            "LEGGING_ARMORS",
            legacyPath = "armors/leggings",
            targetNamespace = "MINECRAFT",
            targetPath = "leg_armor"
        ),
        TagEntry(
            "AllItemTags",
            "BOOT_ARMORS",
            legacyPath = "armors/boots",
            targetNamespace = "MINECRAFT",
            targetPath = "foot_armor"
        ),
        TagEntry("AllFluidTags", "TEA"),
        TagEntry("AllFluidTags", "HONEY"),
        TagEntry("AllFluidTags", "CHOCOLATE"),
        TagEntry("AllFluidTags", "CREOSOTE")
    )

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result =
        migrateWithMove(projectDir, dryRun, ::atomicMove)

    internal fun migrateForTest(
        projectDir: Path,
        dryRun: Boolean,
        move: (Path, Path) -> Unit
    ): Result = migrateWithMove(projectDir, dryRun, move)

    private fun migrateWithMove(
        projectDir: Path,
        dryRun: Boolean,
        move: (Path, Path) -> Unit
    ): Result = try {
        migrateChecked(projectDir, dryRun, move)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create legacy tag admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Create legacy tag migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        move: (Path, Path) -> Unit
    ): Result {
        val allTagsFile = projectDir.resolve(ALL_TAGS_RELATIVE)
        if (!allTagsFile.isRegularFile()) return Result.EMPTY
        val millingFile = projectDir.resolve(MILLING_RELATIVE)
        if (!millingFile.isRegularFile()) {
            abort("Create legacy tag source graph is missing $MILLING_RELATIVE")
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val allTagsBefore = allTagsFile.readText()
        val millingBefore = millingFile.readText()
        val allTagsUnit = parse(parser, allTagsBefore, allTagsFile)
        val millingUnit = parse(parser, millingBefore, millingFile)
        val allTagsMode = classifyAllTags(allTagsUnit)
        val millingMode = classifyMilling(millingUnit)

        if (allTagsMode == Mode.TARGET && millingMode == Mode.TARGET) return Result.EMPTY
        if (allTagsMode != Mode.LEGACY || millingMode != Mode.LEGACY) {
            abort(
                "Create legacy tag family is partial or incompatible: " +
                    "AllTags=$allTagsMode, MillingRecipeGen=$millingMode"
            )
        }

        LexicalPreservingPrinter.setup(allTagsUnit)
        LexicalPreservingPrinter.setup(millingUnit)
        migrateAllTags(allTagsUnit)
        migrateMilling(millingUnit)
        val allTagsAfter = LexicalPreservingPrinter.print(allTagsUnit)
        val millingAfter = LexicalPreservingPrinter.print(millingUnit)

        if (classifyAllTags(parse(parser, allTagsAfter, allTagsFile)) != Mode.TARGET) {
            abort("AllTags did not reach the complete 1.21 common-tag convention family")
        }
        if (classifyMilling(parse(parser, millingAfter, millingFile)) != Mode.TARGET) {
            abort("MillingRecipeGen did not reach the paired common-tag condition semantics")
        }

        val outputs = linkedMapOf(
            allTagsFile to allTagsAfter,
            millingFile to millingAfter
        )
        if (!dryRun) {
            writeAtomicFamily(
                outputs,
                mapOf(
                    allTagsFile to allTagsBefore.toByteArray(StandardCharsets.UTF_8),
                    millingFile to millingBefore.toByteArray(StandardCharsets.UTF_8)
                ),
                move
            )
        }
        return Result(
            outputs.map { (file, after) ->
                val before = if (file == allTagsFile) allTagsBefore else millingBefore
                Change(
                    file = file,
                    line = 1,
                    description =
                        "Migrate Create legacy Forge tags to 1.21 common and vanilla conventions",
                    before = before.take(160),
                    after = after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun classifyAllTags(unit: CompilationUnit): Mode {
        val owner = requireClass(unit, ALL_TAGS_PACKAGE, "AllTags")
        val namespace = owner.members.filterIsInstance<EnumDeclaration>()
            .singleOrNull { it.nameAsString == "NameSpace" }
            ?: return Mode.OTHER
        val legacyNamespace = namespaceEntry(namespace, "FORGE", "forge")
        val targetNamespace = namespaceEntry(namespace, "FORGE", "c")
        val minecraftNamespace = namespaceEntry(namespace, "MINECRAFT", "minecraft")
        val hasForgeImport = hasStaticImport(unit, "FORGE")
        val hasMinecraftImport = hasStaticImport(unit, "MINECRAFT")

        val helperMethods =
            helperMode(owner, "forgeTag", "FORGE") &&
                delegateMode(owner, "forgeBlockTag", "forgeTag", "Block", "BLOCK") &&
                delegateMode(owner, "forgeItemTag", "forgeTag", "Item", "ITEM") &&
                delegateMode(owner, "forgeFluidTag", "forgeTag", "Fluid", "FLUID")

        val legacyEntries = entries.all { entryMode(owner, it, Mode.LEGACY) }
        val targetEntries = entries.all { entryMode(owner, it, Mode.TARGET) }
        val forgeNameCount = owner.findAll(NameExpr::class.java).count { it.nameAsString == "FORGE" }
        val minecraftNameCount =
            owner.findAll(NameExpr::class.java).count { it.nameAsString == "MINECRAFT" }
        val expectedLegacyNames = entries.size + 1
        val expectedTargetForgeNames = entries.count { it.targetNamespace == "FORGE" } + 1
        val expectedMinecraftNames = entries.count { it.targetNamespace == "MINECRAFT" }

        if (
            legacyNamespace &&
            !targetNamespace &&
            !minecraftNamespace &&
            hasForgeImport &&
            !hasMinecraftImport &&
            helperMethods &&
            legacyEntries &&
            forgeNameCount == expectedLegacyNames &&
            minecraftNameCount == 0
        ) {
            return Mode.LEGACY
        }
        if (
            !legacyNamespace &&
            targetNamespace &&
            minecraftNamespace &&
            hasForgeImport &&
            hasMinecraftImport &&
            helperMethods &&
            targetEntries &&
            forgeNameCount == expectedTargetForgeNames &&
            minecraftNameCount == expectedMinecraftNames
        ) {
            return Mode.TARGET
        }
        return Mode.OTHER
    }

    private fun classifyMilling(unit: CompilationUnit): Mode {
        val owner = requireClass(unit, MILLING_PACKAGE, "MillingRecipeGen")
        val methods = owner.methods.filter { it.nameAsString == "metalOre" }
        if (methods.size != 1) return Mode.OTHER
        val method = methods.single()
        if (
            !method.isProtected ||
            method.typeAsString != "GeneratedRecipe" ||
            method.parameters.map { it.typeAsString to it.nameAsString } !=
            listOf(
                "String" to "name",
                "ItemEntry<? extends Item>" to "crushed",
                "int" to "duration"
            ) ||
            method.body.isEmpty
        ) {
            return Mode.OTHER
        }
        val conditions = method.findAll(ObjectCreationExpr::class.java).filter {
            it.typeAsString == "TagEmptyCondition" &&
                it.arguments.size == 2 &&
                normalize(it.arguments[1].toString()) == "\"ores/\"+name"
        }
        val helpers = method.findAll(MethodCallExpr::class.java).filter {
            it.scope.map { scope -> normalize(scope.toString()) }.orElse("") == "AllTags" &&
                it.arguments.size == 1 &&
                normalize(it.arguments[0].toString()) == "\"ores/\"+name" &&
                it.nameAsString in setOf("forgeItemTag", "commonItemTag")
        }
        if (conditions.size != 1 || helpers.size != 1) return Mode.OTHER
        val namespace = conditions.single().arguments[0] as? StringLiteralExpr ?: return Mode.OTHER
        return when {
            namespace.value == "forge" && helpers.single().nameAsString == "forgeItemTag" ->
                Mode.LEGACY
            namespace.value == "c" && helpers.single().nameAsString == "forgeItemTag" ->
                Mode.TARGET
            else -> Mode.OTHER
        }
    }

    private fun migrateAllTags(unit: CompilationUnit) {
        val owner = requireClass(unit, ALL_TAGS_PACKAGE, "AllTags")
        val namespace = owner.members.filterIsInstance<EnumDeclaration>()
            .single { it.nameAsString == "NameSpace" }
        val forgeEntry = namespace.entries.single { it.nameAsString == "FORGE" }
        (forgeEntry.arguments.single() as StringLiteralExpr).setValue("c")
        namespace.entries.add(
            EnumConstantDeclaration("MINECRAFT")
                .addArgument(StringLiteralExpr("minecraft"))
        )

        unit.addImport("$NAMESPACE_IMPORT.MINECRAFT", true, false)

        for (spec in entries) {
            val enumOwner = owner.members.filterIsInstance<EnumDeclaration>()
                .single { it.nameAsString == spec.enumName }
            val entry = enumOwner.entries.single { it.nameAsString == spec.constantName }
            setEntry(entry, spec.targetNamespace, spec.targetPath)
        }
    }

    private fun migrateMilling(unit: CompilationUnit) {
        val owner = requireClass(unit, MILLING_PACKAGE, "MillingRecipeGen")
        val method = owner.methods.single { it.nameAsString == "metalOre" }
        val condition = method.findAll(ObjectCreationExpr::class.java).single {
            it.typeAsString == "TagEmptyCondition" &&
                it.arguments.size == 2 &&
                normalize(it.arguments[1].toString()) == "\"ores/\"+name"
        }
        (condition.arguments[0] as StringLiteralExpr).setValue("c")
    }

    private fun helperMode(
        owner: ClassOrInterfaceDeclaration,
        name: String,
        namespace: String
    ): Boolean {
        val methods = owner.methods.filter { it.nameAsString == name }
        if (methods.size != 1) return false
        val method = methods.single()
        if (
            !method.isPublic ||
            !method.isStatic ||
            method.typeAsString != "TagKey<T>" ||
            method.typeParameters.map { it.nameAsString } != listOf("T") ||
            method.parameters.map { it.typeAsString to it.nameAsString } !=
            listOf("Registry<T>" to "registry", "String" to "path") ||
            method.body.isEmpty
        ) {
            return false
        }
        val body = method.body.orElseThrow()
        if (body.statements.size != 1) return false
        val returned = body.statements.single() as? ReturnStmt ?: return false
        return normalize(returned.expression.map { it.toString() }.orElse("")) ==
            "optionalTag(registry,$namespace.id(path))"
    }

    private fun delegateMode(
        owner: ClassOrInterfaceDeclaration,
        name: String,
        delegate: String,
        elementType: String,
        registry: String
    ): Boolean {
        val methods = owner.methods.filter { it.nameAsString == name }
        if (methods.size != 1) return false
        val method = methods.single()
        if (
            !method.isPublic ||
            !method.isStatic ||
            method.typeAsString != "TagKey<$elementType>" ||
            method.typeParameters.isNotEmpty() ||
            method.parameters.map { it.typeAsString to it.nameAsString } !=
            listOf("String" to "path") ||
            method.body.isEmpty
        ) {
            return false
        }
        val body = method.body.orElseThrow()
        if (body.statements.size != 1) return false
        val returned = body.statements.single() as? ReturnStmt ?: return false
        return normalize(returned.expression.map { it.toString() }.orElse("")) ==
            "$delegate(BuiltInRegistries.$registry,path)"
    }

    private fun entryMode(
        owner: ClassOrInterfaceDeclaration,
        spec: TagEntry,
        mode: Mode
    ): Boolean {
        val enumOwner = owner.members.filterIsInstance<EnumDeclaration>()
            .singleOrNull { it.nameAsString == spec.enumName }
            ?: return false
        val constants = enumOwner.entries.filter { it.nameAsString == spec.constantName }
        if (constants.size != 1) return false
        val entry = constants.single()
        val expectedNamespace = if (mode == Mode.LEGACY) "FORGE" else spec.targetNamespace
        val expectedPath = if (mode == Mode.LEGACY) spec.legacyPath else spec.targetPath
        if (entry.arguments.size !in 1..2) return false
        val namespace = entry.arguments[0] as? NameExpr ?: return false
        if (namespace.nameAsString != expectedNamespace) return false
        if (expectedPath == null) return entry.arguments.size == 1
        return entry.arguments.size == 2 &&
            (entry.arguments[1] as? StringLiteralExpr)?.value == expectedPath
    }

    private fun setEntry(
        entry: EnumConstantDeclaration,
        namespace: String,
        path: String?
    ) {
        (entry.arguments[0] as NameExpr).setName(namespace)
        when {
            path == null && entry.arguments.size == 2 -> entry.arguments.removeAt(1)
            path != null && entry.arguments.size == 1 ->
                entry.addArgument(StringLiteralExpr(path))
            path != null ->
                (entry.arguments[1] as StringLiteralExpr).setValue(path)
        }
    }

    private fun namespaceEntry(
        owner: EnumDeclaration,
        name: String,
        id: String
    ): Boolean {
        val entries = owner.entries.filter { it.nameAsString == name }
        if (entries.size != 1) return false
        val arguments = entries.single().arguments
        return arguments.size == 1 && (arguments.single() as? StringLiteralExpr)?.value == id
    }

    private fun hasStaticImport(unit: CompilationUnit, name: String): Boolean =
        unit.imports.count {
            it.isStatic &&
                !it.isAsterisk &&
                it.nameAsString == "$NAMESPACE_IMPORT.$name"
        } == 1

    private fun requireClass(
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

    private fun writeAtomicFamily(
        outputs: Map<Path, String>,
        originalBytes: Map<Path, ByteArray>,
        move: (Path, Path) -> Unit
    ) {
        val stagedOutputs = linkedMapOf<Path, Path>()
        val stagedOriginals = linkedMapOf<Path, Path>()
        val committed = mutableListOf<Path>()
        val preservedRollbackStages = mutableSetOf<Path>()
        try {
            for ((path, source) in outputs.entries.sortedBy { it.key.toString() }) {
                val outputStage =
                    Files.createTempFile(path.parent, ".modporter-create-tags-stage-", ".java")
                val originalStage =
                    Files.createTempFile(path.parent, ".modporter-create-tags-original-", ".java")
                stagedOutputs[path] = outputStage
                stagedOriginals[path] = originalStage
                Files.writeString(outputStage, source, StandardCharsets.UTF_8)
                Files.write(originalStage, originalBytes.getValue(path))
            }
            for ((path, temporary) in stagedOutputs) {
                move(temporary, path)
                committed.add(path)
            }
        } catch (failure: Exception) {
            for (path in committed.asReversed()) {
                try {
                    move(stagedOriginals.getValue(path), path)
                } catch (rollbackFailure: Exception) {
                    preservedRollbackStages.add(stagedOriginals.getValue(path))
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            (stagedOutputs.values + stagedOriginals.values).forEach {
                if (it !in preservedRollbackStages) runCatching { Files.deleteIfExists(it) }
            }
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun normalize(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private data class TagEntry(
        val enumName: String,
        val constantName: String,
        val legacyPath: String? = null,
        val targetNamespace: String = "FORGE",
        val targetPath: String? = legacyPath
    )

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private enum class Mode {
        LEGACY,
        TARGET,
        OTHER
    }
}
