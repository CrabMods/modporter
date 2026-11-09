package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension

/**
 * Preserves Create's source-owned `.dat_old` SavedData writes while moving its exact railway and
 * logistics family onto the 1.21.1 provider and factory contracts.
 *
 * Official Create 1.21 removed these file overrides and its SavedDataUtil. This migration keeps the
 * older source's stronger backup behavior instead: the target still dispatches
 * `save(File, HolderLookup.Provider)` virtually, while NbtIo and Util now take Path. The server
 * loaders retain their original one-argument API because DimensionDataStorage passes its provider
 * through the SavedData.Factory deserializer.
 */
internal object SourceOwnedSavedDataBackups {
    const val RULE_ID = "struct-source-owned-saved-data-backups"

    private const val HOLDER_LOOKUP = "net.minecraft.core.HolderLookup"
    private const val SAVED_DATA = "net.minecraft.world.level.saveddata.SavedData"
    private const val DATA_STORAGE = "net.minecraft.world.level.storage.DimensionDataStorage"

    private val utilityRelative = Path.of(
        "src/main/java/com/simibubi/create/foundation/utility/SavedDataUtil.java"
    )
    private val families = listOf(
        Family(
            packageName = "com.simibubi.create.content.trains",
            className = "RailwaySavedData",
            dataName = "create_tracks",
            relative = Path.of(
                "src/main/java/com/simibubi/create/content/trains/RailwaySavedData.java"
            ),
            managerRelative = Path.of(
                "src/main/java/com/simibubi/create/content/trains/GlobalRailwayManager.java"
            )
        ),
        Family(
            packageName = "com.simibubi.create.content.logistics.packagerLink",
            className = "LogisticsNetworkSavedData",
            dataName = "create_logistics",
            relative = Path.of(
                "src/main/java/com/simibubi/create/content/logistics/packagerLink/" +
                    "LogisticsNetworkSavedData.java"
            ),
            managerRelative = Path.of(
                "src/main/java/com/simibubi/create/content/logistics/packagerLink/" +
                    "GlobalLogisticsManager.java"
            )
        )
    )

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    internal interface ProjectFileIo {
        fun readBytes(path: Path): ByteArray
        fun createTempSibling(target: Path, suffix: String): Path
        fun writeBytes(path: Path, bytes: ByteArray)
        fun moveReplace(source: Path, target: Path)
        fun deleteIfExists(path: Path)
    }

    internal object NioProjectFileIo : ProjectFileIo {
        override fun readBytes(path: Path): ByteArray = Files.readAllBytes(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            Files.createTempFile(target.parent, ".${target.fileName}.modporter-", suffix)

        override fun writeBytes(path: Path, bytes: ByteArray) {
            Files.write(path, bytes)
        }

        override fun moveReplace(source: Path, target: Path) {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        override fun deleteIfExists(path: Path) {
            Files.deleteIfExists(path)
        }
    }

    fun migrate(
        projectDir: Path,
        dryRun: Boolean,
        fileIo: ProjectFileIo = NioProjectFileIo
    ): Result = try {
        migrateChecked(projectDir, dryRun, fileIo)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "SavedData backup admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "SavedData backup migration failed before its source transaction: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        fileIo: ProjectFileIo
    ): Result {
        val utilityPath = projectDir.resolve(utilityRelative)
        if (!Files.isRegularFile(utilityPath)) return Result.EMPTY

        val requiredPaths = buildList {
            add(utilityPath)
            families.forEach {
                add(projectDir.resolve(it.relative))
                add(projectDir.resolve(it.managerRelative))
            }
        }
        val missing = requiredPaths.filterNot { Files.isRegularFile(it) }
        if (missing.isNotEmpty()) {
            abort("Create SavedData backup family is incomplete: ${missing.joinToString()}")
        }
        if (!targetContractIsVerified()) {
            abort("Create SavedData target provider, factory, and Path contracts are not verified")
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val parsed = javaFiles(projectDir).associateWith { path ->
            val originalBytes = fileIo.readBytes(path)
            parse(
                path,
                String(originalBytes, StandardCharsets.UTF_8),
                parser,
                originalBytes
            )
        }
        val utilitySource = parsed.getValue(utilityPath)
        val utility = admitUtility(utilitySource)
        if (utility.state == State.LEGACY &&
            families.all { family ->
                isPreCommonFamily(
                    family,
                    parsed.getValue(projectDir.resolve(family.relative))
                )
            }
        ) {
            // analyze() sees the source before migrateCommonNeoForge121Apis is materialized.
            // That existing pass owns the NBT callback/provider migration; apply() reaches the
            // post-common shape below and this rule repairs only the residual source family.
            return Result.EMPTY
        }
        val dataPlans = families.map { family ->
            admitData(
                family,
                parsed.getValue(projectDir.resolve(family.relative))
            )
        }
        proveClosedUsage(projectDir, parsed, utility, dataPlans)

        val states = listOf(utility.state) + dataPlans.map(DataPlan::state)
        if (states.all { it == State.TARGET }) return Result.EMPTY
        if (states.any { it != State.LEGACY }) {
            abort("Create SavedData backup family mixes legacy and target shapes")
        }

        val affected = listOf(utility.source) + dataPlans.map(DataPlan::source)
        affected.forEach { LexicalPreservingPrinter.setup(it.unit) }

        utility.method.addParameter("HolderLookup.Provider", "registries")
        utility.savedDataSave.addArgument(NameExpr("registries"))
        utility.writeCompressed.setArgument(
            1,
            StaticJavaParser.parseExpression("temp.toPath()")
        )
        listOf("file", "temp", "oldFile").forEachIndexed { index, name ->
            utility.safeReplace.setArgument(
                index,
                StaticJavaParser.parseExpression("$name.toPath()")
            )
        }
        addImport(utility.source.unit, HOLDER_LOOKUP)

        dataPlans.forEach { plan ->
            plan.fileSave.addParameter("HolderLookup.Provider", "registries")
            if (plan.fileSave.annotations.none { it.nameAsString == "Override" }) {
                plan.fileSave.addAnnotation("Override")
            }
            plan.utilityCall.addArgument(NameExpr("registries"))
            plan.serverLoad.parameters.removeAt(1)
            plan.computeIfAbsent.setArgument(
                0,
                StaticJavaParser.parseExpression("factory()")
            )
            val factoryMethod = StaticJavaParser.parseBodyDeclaration(
                """
                public static SavedData.Factory<${plan.family.className}> factory() {
                    return new SavedData.Factory<>(
                        ${plan.family.className}::new, ${plan.family.className}::load);
                }
                """.trimIndent()
            )
            val insertion = plan.owner.members.indexOf(plan.nbtSave)
            if (insertion < 0) abort("${plan.family.owner} lost its NBT save member")
            plan.owner.members.add(insertion, factoryMethod)
            addImport(plan.source.unit, HOLDER_LOOKUP)
        }

        val stagedText = affected.associate { source ->
            source.path to LexicalPreservingPrinter.print(source.unit)
        }
        stagedText.forEach { (path, text) ->
            parse(path, text, parser)
        }
        val stagedBytes = stagedText.mapValues { (_, text) ->
            text.toByteArray(StandardCharsets.UTF_8)
        }
        val originalBytes = affected.associate { source ->
            source.path to source.originalBytes.copyOf()
        }
        val changes = affected.map { source ->
            sourceChange(source.path, utilityPath)
        }
        if (!dryRun) {
            val failure = commitProjectTransaction(stagedBytes, originalBytes, fileIo)
            if (failure != null) {
                return Result(
                    changes.filter { it.file in failure.changedPaths },
                    listOf(failure.gate)
                )
            }
        }
        return Result(changes, emptyList())
    }

    private fun sourceChange(path: Path, utilityPath: Path): Change =
        Change(
            file = path,
            line = 0,
            description = if (path == utilityPath) {
                "Preserve Create SavedData .dat_old writes with target provider and Path APIs"
            } else {
                "Restore Create's one-argument SavedData loader and source-owned factory"
            },
            before = if (path == utilityPath) {
                "saveWithDatOld(T, File) with File-based NBT and replacement calls"
            } else {
                "provider-widened server loader with an inline SavedData.Factory"
            },
            after = if (path == utilityPath) {
                "saveWithDatOld(T, File, Provider) with Path-based NBT and replacement calls"
            } else {
                "load(MinecraftServer), factory(), and provider-aware .dat_old override"
            },
            confidence = Confidence.HIGH,
            ruleId = RULE_ID
        )

    private fun commitProjectTransaction(
        migrated: Map<Path, ByteArray>,
        originals: Map<Path, ByteArray>,
        fileIo: ProjectFileIo
    ): TransactionFailure? {
        val stages = linkedMapOf<Path, Path>()
        val backups = linkedMapOf<Path, Path>()
        val temporary = linkedSetOf<Path>()
        val retainedRecovery = linkedSetOf<Path>()
        val attemptedReplacements = mutableListOf<Path>()
        var failure: TransactionFailure? = null

        try {
            verifyOriginalBytes(originals, fileIo, "before staging")
            for ((target, after) in migrated) {
                val stage = fileIo.createTempSibling(target, ".stage")
                temporary.add(stage)
                fileIo.writeBytes(stage, after.copyOf())
                check(fileIo.readBytes(stage).contentEquals(after)) {
                    "Staged SavedData source changed before commit: $target"
                }
                stages[target] = stage

                val backup = fileIo.createTempSibling(target, ".backup")
                temporary.add(backup)
                val original = originals.getValue(target)
                fileIo.writeBytes(backup, original.copyOf())
                check(fileIo.readBytes(backup).contentEquals(original)) {
                    "SavedData rollback copy does not match its admitted source: $target"
                }
                backups[target] = backup
            }

            verifyOriginalBytes(originals, fileIo, "after staging")
            for ((target, stage) in stages) {
                verifyOriginalBytes(mapOf(target to originals.getValue(target)), fileIo, "before publish")
                attemptedReplacements.add(target)
                fileIo.moveReplace(stage, target)
                check(fileIo.readBytes(target).contentEquals(migrated.getValue(target))) {
                    "Published SavedData source does not match its staged bytes: $target"
                }
            }
        } catch (commitFailure: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            val unresolved = linkedSetOf<Path>()
            val recoveryPaths = linkedMapOf<Path, Path>()

            for (target in attemptedReplacements.asReversed().distinct()) {
                val original = originals.getValue(target)
                val alreadyRestored = runCatching {
                    fileIo.readBytes(target).contentEquals(original)
                }.getOrDefault(false)
                if (alreadyRestored) continue

                val backup = backups[target]
                if (backup == null) {
                    rollbackFailures += IOException(
                        "No verified SavedData recovery copy exists for $target"
                    )
                    continue
                }
                try {
                    check(fileIo.readBytes(backup).contentEquals(original)) {
                        "SavedData recovery copy changed before rollback: $backup"
                    }
                    val rollbackStage = fileIo.createTempSibling(target, ".rollback")
                    temporary.add(rollbackStage)
                    fileIo.writeBytes(rollbackStage, original.copyOf())
                    check(fileIo.readBytes(rollbackStage).contentEquals(original)) {
                        "SavedData rollback stage changed before restore: $target"
                    }
                    fileIo.moveReplace(rollbackStage, target)
                    check(fileIo.readBytes(target).contentEquals(original)) {
                        "SavedData source did not match its original bytes after rollback: $target"
                    }
                } catch (rollbackFailure: Throwable) {
                    rollbackFailures += rollbackFailure
                }
            }

            val stillChanged = attemptedReplacements.distinct().filterTo(linkedSetOf()) { target ->
                runCatching {
                    !fileIo.readBytes(target).contentEquals(originals.getValue(target))
                }.getOrDefault(true)
            }
            unresolved += stillChanged
            unresolved.forEach { target ->
                backups[target]?.let { backup ->
                    retainedRecovery.add(backup)
                    recoveryPaths[target] = backup
                }
            }
            val cause = commitFailure.message ?: commitFailure::class.java.simpleName
            failure = if (unresolved.isEmpty()) {
                TransactionFailure(
                    emptySet(),
                    if (attemptedReplacements.isEmpty()) {
                        "SavedData backup transaction failed before publish; no source file was replaced: $cause"
                    } else {
                        "SavedData backup transaction failed during publish; all attempted source replacements were restored: $cause"
                    }
                )
            } else {
                val recovery = recoveryPaths.entries.joinToString().ifBlank { "none available" }
                val rollback = rollbackFailures.joinToString { error ->
                    error.message ?: error::class.java.simpleName
                }
                TransactionFailure(
                    unresolved,
                    "SavedData backup transaction failed during publish and rollback is incomplete. " +
                        "Changed or unverifiable source files: ${unresolved.joinToString()}. " +
                        "Recovery copies retained at: $recovery. " +
                        "Commit failure: $cause. Rollback failure: $rollback"
                )
            }
        }

        val cleanupFailures = mutableListOf<String>()
        for (path in temporary) {
            if (path in retainedRecovery) continue
            try {
                fileIo.deleteIfExists(path)
            } catch (cleanupFailure: Throwable) {
                cleanupFailures += "$path (${cleanupFailure.message ?: cleanupFailure::class.java.simpleName})"
            }
        }
        if (cleanupFailures.isNotEmpty()) {
            val detail = "Temporary SavedData transaction files could not be removed: " +
                cleanupFailures.joinToString()
            val existingFailure = failure
            failure = if (existingFailure != null) {
                existingFailure.copy(gate = existingFailure.gate + ". $detail")
            } else {
                TransactionFailure(migrated.keys, "SavedData sources were committed. $detail")
            }
        }
        return failure
    }

    private fun verifyOriginalBytes(
        originals: Map<Path, ByteArray>,
        fileIo: ProjectFileIo,
        phase: String
    ) {
        originals.forEach { (target, original) ->
            check(fileIo.readBytes(target).contentEquals(original)) {
                "SavedData source changed $phase: $target"
            }
        }
    }

    private fun admitUtility(source: ParsedSource): UtilityPlan {
        val owner = topLevelClass(
            source,
            "com.simibubi.create.foundation.utility",
            "SavedDataUtil"
        )
        val method = owner.methods.singleOrNull { it.nameAsString == "saveWithDatOld" }
            ?: abort("SavedDataUtil must declare one saveWithDatOld method")
        if (!method.isPublic || !method.isStatic || !method.type.isVoidType ||
            method.typeParameters.size != 1 ||
            method.typeParameters.single().nameAsString != "T" ||
            method.typeParameters.single().typeBound.singleOrNull()?.nameAsString != "SavedData" ||
            method.parameters.size !in 2..3 ||
            method.parameters[0].nameAsString != "savedData" ||
            method.parameters[0].typeAsString != "T" ||
            method.parameters[1].nameAsString != "file" ||
            simpleType(method.parameters[1].typeAsString) != "File"
        ) {
            abort("SavedDataUtil.saveWithDatOld is outside the exact source-owned generic method")
        }
        val body = method.body.orElse(null)
            ?: abort("SavedDataUtil.saveWithDatOld has no body")
        val savedDataSave = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "save" && scope(it) == "savedData"
        } ?: abort("SavedDataUtil has no exact savedData.save call")
        val writeCompressed = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "writeCompressed" &&
                scope(it).substringAfterLast('.') == "NbtIo"
        } ?: abort("SavedDataUtil has no exact NbtIo.writeCompressed call")
        val safeReplace = body.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "safeReplaceFile" &&
                scope(it).substringAfterLast('.') == "Util"
        } ?: abort("SavedDataUtil has no exact Util.safeReplaceFile call")

        val commonShape =
            body.findAll(MethodCallExpr::class.java).any {
                it.nameAsString == "isDirty" && scope(it) == "savedData"
            } &&
                body.findAll(MethodCallExpr::class.java).any {
                    it.nameAsString == "addCurrentDataVersion" &&
                        scope(it).substringAfterLast('.') == "NbtUtils" &&
                        it.arguments.singleOrNull()?.toString() == "compoundtag"
                } &&
                body.findAll(MethodCallExpr::class.java).any {
                    it.nameAsString == "setDirty" &&
                        scope(it) == "savedData" &&
                        it.arguments.singleOrNull()?.toString() == "false"
                }
        if (!commonShape ||
            writeCompressed.arguments.size != 2 ||
            writeCompressed.arguments[0].toString() != "compoundtag" ||
            safeReplace.arguments.size != 3
        ) {
            abort("SavedDataUtil no longer has the exact dirty-save and replacement flow")
        }

        val legacy = method.parameters.size == 2 &&
            savedDataSave.arguments.size == 1 &&
            savedDataSave.arguments[0].toString() == "new CompoundTag()" &&
            writeCompressed.arguments[1].toString() == "temp" &&
            safeReplace.arguments.map(Node::toString) == listOf("file", "temp", "oldFile")
        val target = method.parameters.size == 3 &&
            providerParameter(method, 2, "registries") &&
            savedDataSave.arguments.map(Node::toString) ==
                listOf("new CompoundTag()", "registries") &&
            writeCompressed.arguments[1].toString() == "temp.toPath()" &&
            safeReplace.arguments.map(Node::toString) ==
                listOf("file.toPath()", "temp.toPath()", "oldFile.toPath()")
        val state = when {
            legacy -> State.LEGACY
            target -> State.TARGET
            else -> abort("SavedDataUtil mixes legacy and target provider or Path calls")
        }
        return UtilityPlan(
            source,
            state,
            method,
            savedDataSave,
            writeCompressed,
            safeReplace
        )
    }

    private fun isPreCommonFamily(
        family: Family,
        source: ParsedSource
    ): Boolean {
        val owner = topLevelClass(source, family.packageName, family.className)
        if (owner.extendedTypes.singleOrNull()?.nameAsString != "SavedData" ||
            owner.methods.any { it.nameAsString == "factory" }
        ) return false
        val nbtSave = owner.methods.singleOrNull {
            it.nameAsString == "save" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "CompoundTag"
        } ?: return false
        val nbtLoad = owner.methods.singleOrNull {
            it.nameAsString == "load" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "CompoundTag"
        } ?: return false
        val fileSave = owner.methods.singleOrNull {
            it.nameAsString == "save" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "File"
        } ?: return false
        val serverLoad = owner.methods.singleOrNull {
            it.nameAsString == "load" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "MinecraftServer"
        } ?: return false
        val utilityCall = fileSave.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "saveWithDatOld" &&
                scope(it).substringAfterLast('.') == "SavedDataUtil"
        } ?: return false
        val compute = serverLoad.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "computeIfAbsent"
        } ?: return false
        return nbtSave.isPublic && !nbtSave.isStatic &&
            simpleType(nbtSave.typeAsString) == "CompoundTag" &&
            nbtSave.parameters.size == 1 &&
            !nbtLoad.isPublic && nbtLoad.isPrivate && nbtLoad.isStatic &&
            simpleType(nbtLoad.typeAsString) == family.className &&
            nbtLoad.parameters.size == 1 &&
            fileSave.isPublic && fileSave.type.isVoidType &&
            fileSave.parameters.size == 1 &&
            fileSave.parameters[0].nameAsString == "file" &&
            utilityCall.arguments.map(Node::toString) == listOf("this", "file") &&
            serverLoad.isPublic && serverLoad.isStatic &&
            simpleType(serverLoad.typeAsString) == family.className &&
            serverLoad.parameters.size == 1 &&
            serverLoad.parameters[0].nameAsString == "server" &&
            scope(compute) == "server.overworld().getDataStorage()" &&
            compute.arguments.map(Node::toString) == listOf(
                "${family.className}::load",
                "${family.className}::new",
                "\"${family.dataName}\""
            )
    }

    private fun admitData(family: Family, source: ParsedSource): DataPlan {
        val owner = topLevelClass(source, family.packageName, family.className)
        if (owner.extendedTypes.singleOrNull()?.nameAsString != "SavedData") {
            abort("${family.owner} must directly extend SavedData")
        }
        val nbtSave = owner.methods.singleOrNull {
            it.nameAsString == "save" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "CompoundTag"
        } ?: abort("${family.owner} has no exact NBT save method")
        val nbtLoad = owner.methods.singleOrNull {
            it.nameAsString == "load" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "CompoundTag"
        } ?: abort("${family.owner} has no exact NBT load method")
        if (!nbtSave.isPublic || nbtSave.isStatic ||
            simpleType(nbtSave.typeAsString) != "CompoundTag" ||
            nbtSave.parameters.size != 2 ||
            !providerParameter(nbtSave, 1, "registries") ||
            !nbtLoad.isPrivate || !nbtLoad.isStatic ||
            simpleType(nbtLoad.typeAsString) != family.className ||
            nbtLoad.parameters.size != 2 ||
            !providerParameter(nbtLoad, 1, "registries")
        ) {
            abort("${family.owner} NBT callbacks are outside the target provider contract")
        }
        val constructor = owner.constructors.singleOrNull {
            it.isPrivate && it.parameters.isEmpty()
        } ?: abort("${family.owner} must have one private no-argument constructor")
        if (constructor.body.statements.isNotEmpty()) {
            abort("${family.owner} private constructor is not empty")
        }

        val fileSave = owner.methods.singleOrNull {
            it.nameAsString == "save" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "File"
        } ?: abort("${family.owner} has no source-owned file save override")
        val serverLoad = owner.methods.singleOrNull {
            it.nameAsString == "load" &&
                it.parameters.firstOrNull()?.typeAsString?.let(::simpleType) == "MinecraftServer"
        } ?: abort("${family.owner} has no server loader")
        if (!fileSave.isPublic || !fileSave.type.isVoidType ||
            fileSave.parameters.size !in 1..2 ||
            fileSave.parameters[0].nameAsString != "file" ||
            !serverLoad.isPublic || !serverLoad.isStatic ||
            simpleType(serverLoad.typeAsString) != family.className ||
            serverLoad.parameters.size !in 1..2 ||
            serverLoad.parameters[0].nameAsString != "server"
        ) {
            abort("${family.owner} file save or server load signature is outside the family")
        }
        if (fileSave.annotations.any { it.nameAsString != "Override" }) {
            abort("${family.owner} file save has an unknown method annotation")
        }

        val utilityCall = fileSave.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "saveWithDatOld" &&
                scope(it).substringAfterLast('.') == "SavedDataUtil"
        } ?: abort("${family.owner} file save has no exact SavedDataUtil call")
        val compute = serverLoad.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "computeIfAbsent"
        } ?: abort("${family.owner} server loader has no exact computeIfAbsent call")
        if (scope(compute) != "server.overworld().getDataStorage()" ||
            compute.arguments.size != 2 ||
            compute.arguments[1].toString() != "\"${family.dataName}\""
        ) {
            abort("${family.owner} server loader no longer owns the expected data key")
        }

        val factoryMethods = owner.methods.filter { it.nameAsString == "factory" }
        val legacy = fileSave.parameters.size == 1 &&
            utilityCall.arguments.map(Node::toString) == listOf("this", "file") &&
            serverLoad.parameters.size == 2 &&
            providerParameter(serverLoad, 1, "registries") &&
            factoryMethods.isEmpty() &&
            inlineFactoryIsExact(compute.arguments[0], family.className)
        val target = fileSave.parameters.size == 2 &&
            providerParameter(fileSave, 1, "registries") &&
            fileSave.annotations.singleOrNull()?.nameAsString == "Override" &&
            utilityCall.arguments.map(Node::toString) ==
                listOf("this", "file", "registries") &&
            serverLoad.parameters.size == 1 &&
            compute.arguments[0].toString() == "factory()" &&
            factoryMethods.singleOrNull()?.let {
                factoryMethodIsExact(it, family.className)
            } == true
        val state = when {
            legacy -> State.LEGACY
            target -> State.TARGET
            else -> abort("${family.owner} mixes legacy and target save/load factory shapes")
        }
        return DataPlan(
            family,
            source,
            state,
            owner,
            nbtSave,
            fileSave,
            serverLoad,
            utilityCall,
            compute
        )
    }

    private fun proveClosedUsage(
        projectDir: Path,
        parsed: Map<Path, ParsedSource>,
        utility: UtilityPlan,
        plans: List<DataPlan>
    ) {
        val utilityCalls = parsed.values.flatMap { source ->
            source.unit.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == "saveWithDatOld" }
                .map { source.path to it }
        }
        val allowedUtility = plans.map { it.source.path to it.utilityCall }.toSet()
        if (utilityCalls.toSet() != allowedUtility) {
            abort("SavedDataUtil.saveWithDatOld has calls outside the two owned overrides")
        }
        val utilityReferences = parsed.values.flatMap { source ->
            source.unit.findAll(MethodReferenceExpr::class.java)
                .filter { it.identifier == "saveWithDatOld" }
                .map { source.path to it }
        }
        if (utilityReferences.isNotEmpty()) {
            abort("SavedDataUtil.saveWithDatOld is also used as a method reference")
        }

        plans.forEach { plan ->
            val acceptedScopes = setOf(plan.family.className, plan.family.owner)
            val calls = parsed.values.flatMap { source ->
                source.unit.findAll(MethodCallExpr::class.java)
                    .filter {
                        it.nameAsString == "load" &&
                            scope(it) in acceptedScopes
                    }
                    .map { source.path to it }
            }
            val managerPath = projectDir.resolve(plan.family.managerRelative)
            if (calls.size != 1 ||
                calls.single().first != managerPath ||
                calls.single().second.arguments.map(Node::toString) != listOf("server")
            ) {
                abort("${plan.family.owner}.load must have one manager-owned server caller")
            }

            val references = parsed.values.flatMap { source ->
                source.unit.findAll(MethodReferenceExpr::class.java)
                    .filter {
                        it.identifier == "load" &&
                            it.scope.toString() in acceptedScopes
                    }
                    .map { source.path to it }
            }
            if (references.size != 1 || references.single().first != plan.source.path) {
                abort("${plan.family.owner}::load has references outside its owned factory")
            }
        }
        if (utility.method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .orElse(null) == null
        ) {
            abort("SavedDataUtil.saveWithDatOld lost its declaring class")
        }
    }

    private fun factoryMethodIsExact(
        method: MethodDeclaration,
        className: String
    ): Boolean {
        if (!method.isPublic || !method.isStatic || method.parameters.isNotEmpty() ||
            method.typeAsString.replace(" ", "") != "SavedData.Factory<$className>"
        ) return false
        val returned = method.findAll(ReturnStmt::class.java).singleOrNull()
            ?.expression?.orElse(null) as? ObjectCreationExpr
            ?: return false
        return factoryCreationIsExact(returned, className)
    }

    private fun inlineFactoryIsExact(expression: Node, className: String): Boolean {
        val creation = expression as? ObjectCreationExpr ?: return false
        return factoryCreationIsExact(creation, className)
    }

    private fun factoryCreationIsExact(
        creation: ObjectCreationExpr,
        className: String
    ): Boolean {
        if (creation.type.nameAsString != "Factory" ||
            creation.arguments.size != 2
        ) return false
        return creation.arguments[0].toString() == "$className::new" &&
            creation.arguments[1].toString() == "$className::load"
    }

    private fun topLevelClass(
        source: ParsedSource,
        packageName: String,
        className: String
    ): ClassOrInterfaceDeclaration {
        if (source.unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
            abort("$className is not in its canonical package")
        }
        val owner = source.unit.types.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull { it.nameAsString == className && !it.isInterface }
            ?: abort("$className must be one top-level source-owned class")
        if (!owner.isPublic) abort("$className must remain public")
        return owner
    }

    private fun targetContractIsVerified(): Boolean =
        "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup\$Provider;)" +
            "Lnet/minecraft/nbt/CompoundTag;" in
            TargetMembers.methodDescriptors(SAVED_DATA, "save") &&
            "(Ljava/io/File;Lnet/minecraft/core/HolderLookup\$Provider;)V" in
            TargetMembers.methodDescriptors(SAVED_DATA, "save") &&
            "(Ljava/util/function/Supplier;Ljava/util/function/BiFunction;)V" in
            TargetMembers.methodDescriptors("$SAVED_DATA\$Factory", "<init>") &&
            "(Lnet/minecraft/world/level/saveddata/SavedData\$Factory;" +
            "Ljava/lang/String;)Lnet/minecraft/world/level/saveddata/SavedData;" in
            TargetMembers.methodDescriptors(DATA_STORAGE, "computeIfAbsent") &&
            "(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V" in
            TargetMembers.methodDescriptors("net.minecraft.nbt.NbtIo", "writeCompressed") &&
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V" in
            TargetMembers.methodDescriptors("net.minecraft.Util", "safeReplaceFile")

    private fun providerParameter(
        method: MethodDeclaration,
        index: Int,
        name: String
    ): Boolean =
        method.parameters.getOrNull(index)?.let {
            it.nameAsString == name &&
                it.typeAsString.replace(" ", "") in setOf(
                    "HolderLookup.Provider",
                    "net.minecraft.core.HolderLookup.Provider"
                )
        } == true

    private fun addImport(unit: CompilationUnit, name: String) {
        if (unit.imports.none { !it.isStatic && !it.isAsterisk && it.nameAsString == name }) {
            unit.addImport(name)
        }
    }

    private fun parse(
        path: Path,
        text: String,
        parser: JavaParser,
        originalBytes: ByteArray = text.toByteArray(StandardCharsets.UTF_8)
    ): ParsedSource {
        val result = parser.parse(text)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "SavedData backup usage proof could not parse $path: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
            )
        }
        return ParsedSource(path, originalBytes.copyOf(), result.result.orElseThrow())
    }

    private fun javaFiles(projectDir: Path): List<Path> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "java" }
                .sorted()
                .toList()
        }
    }

    private fun scope(call: MethodCallExpr): String =
        call.scope.map(Node::toString).orElse("")

    private fun simpleType(type: String): String =
        type.substringAfterLast('.')

    private fun abort(message: String): Nothing =
        throw AdmissionAbort(message)

    private data class Family(
        val packageName: String,
        val className: String,
        val dataName: String,
        val relative: Path,
        val managerRelative: Path
    ) {
        val owner: String get() = "$packageName.$className"
    }

    private data class ParsedSource(
        val path: Path,
        val originalBytes: ByteArray,
        val unit: CompilationUnit
    )

    private data class TransactionFailure(
        val changedPaths: Set<Path>,
        val gate: String
    )

    private data class UtilityPlan(
        val source: ParsedSource,
        val state: State,
        val method: MethodDeclaration,
        val savedDataSave: MethodCallExpr,
        val writeCompressed: MethodCallExpr,
        val safeReplace: MethodCallExpr
    )

    private data class DataPlan(
        val family: Family,
        val source: ParsedSource,
        val state: State,
        val owner: ClassOrInterfaceDeclaration,
        val nbtSave: MethodDeclaration,
        val fileSave: MethodDeclaration,
        val serverLoad: MethodDeclaration,
        val utilityCall: MethodCallExpr,
        val computeIfAbsent: MethodCallExpr
    )

    private enum class State {
        LEGACY,
        TARGET
    }

    private class AdmissionAbort(message: String) : RuntimeException(message)
}
