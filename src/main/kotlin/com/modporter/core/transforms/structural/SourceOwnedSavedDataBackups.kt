package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
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
 * Preserves a source-owned `.dat_old` SavedData backup family while moving it onto the 1.21.1
 * provider, factory and Path contracts.
 *
 * Admission is structural, never by class, package or file name. A family is a source-owned
 * static utility `<T extends SavedData> m(T data, File file)` that saves `data.save(tag)` through
 * the File overloads of `NbtIo.writeCompressed` and `Util.safeReplaceFile` (both take Path in
 * 1.21.1), plus every direct SavedData subclass whose `save(File)` override calls it with
 * `(this, file)`. Every call and method reference of the utility must be one of those overrides.
 * The target still dispatches `SavedData.save(File, HolderLookup.Provider)` virtually, so the
 * overrides regain `@Override` with the provider parameter and thread it into the utility.
 *
 * A server loader of a family class that the common provider pass widened with a trailing
 * provider parameter is restored to its original arity only when its body never reads that
 * parameter and every project call already passes the original arguments; DimensionDataStorage
 * hands its provider to the SavedData.Factory deserializer instead. Any other shape of an admitted
 * family leaves every file unchanged and reports a gate.
 */
internal object SourceOwnedSavedDataBackups {
    const val RULE_ID = "struct-source-owned-saved-data-backups"

    private const val HOLDER_LOOKUP = "net.minecraft.core.HolderLookup"
    private const val PROVIDER = "net.minecraft.core.HolderLookup.Provider"
    private const val SAVED_DATA = "net.minecraft.world.level.saveddata.SavedData"
    private const val DATA_STORAGE = "net.minecraft.world.level.storage.DimensionDataStorage"
    private const val FILE = "java.io.File"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val NBT_IO = "net.minecraft.nbt.NbtIo"
    private const val UTIL = "net.minecraft.Util"
    private const val MINECRAFT_SERVER = "net.minecraft.server.MinecraftServer"

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
        val files = javaFiles(projectDir)
        val originalBytes = files.associateWith { fileIo.readBytes(it) }
        val texts = originalBytes.mapValues { (_, bytes) -> String(bytes, StandardCharsets.UTF_8) }
        if (texts.values.none {
                it.contains("SavedData") && it.contains("writeCompressed") &&
                    it.contains("safeReplaceFile")
            }
        ) return Result.EMPTY

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val parsed = files.associateWith { path ->
            parse(path, texts.getValue(path), parser, originalBytes.getValue(path))
        }
        val candidates = parsed.values.flatMap(::utilityCandidates)
        if (candidates.isEmpty()) return Result.EMPTY
        if (!targetContractIsVerified()) {
            abort("SavedData target provider, factory, and Path contracts are not verified")
        }

        val families = candidates.map { utility -> admitFamily(utility, parsed.values) }
        val familyClasses = families.flatMap { it.data }.map { it.owner }
        if (familyClasses.size != familyClasses.distinctBy { System.identityHashCode(it) }.size) {
            abort("A SavedData class belongs to more than one backup utility family")
        }

        if (families.all { family ->
                family.utility.state == State.LEGACY &&
                    family.data.isNotEmpty() &&
                    family.data.all { it.preCommon }
            }
        ) {
            // analyze() sees the source before migrateCommonNeoForge121Apis is materialized.
            // That existing pass owns the NBT callback/provider migration; apply() reaches the
            // post-common shape below and this rule repairs only the residual source family.
            return Result.EMPTY
        }
        families.flatMap { it.data }.firstOrNull { it.preCommon }?.let {
            abort("${it.ownerName} NBT callbacks are outside the target provider contract")
        }

        val states = families.flatMap { family ->
            listOf(family.utility.state) + family.data.map(DataPlan::state)
        }
        if (states.all { it == State.TARGET }) return Result.EMPTY
        if (states.any { it != State.LEGACY }) {
            abort("SavedData backup family mixes legacy and target shapes")
        }

        val affected = families.flatMap { family ->
            listOf(family.utility.source) + family.data.map(DataPlan::source)
        }.distinctBy { it.path }
        affected.forEach { LexicalPreservingPrinter.setup(it.unit) }

        val utilityPaths = families.map { it.utility.source.path }.toSet()
        families.forEach { family ->
            val utility = family.utility
            val registries = freshName(utility.method, "registries")
            utility.method.addParameter("HolderLookup.Provider", registries)
            utility.savedDataSave.addArgument(NameExpr(registries))
            utility.writeCompressed.setArgument(
                1,
                StaticJavaParser.parseExpression("${utility.writeCompressed.arguments[1]}.toPath()")
            )
            utility.safeReplace.arguments.toList().forEachIndexed { index, argument ->
                utility.safeReplace.setArgument(
                    index,
                    StaticJavaParser.parseExpression("$argument.toPath()")
                )
            }
            addImport(utility.source.unit, HOLDER_LOOKUP)

            family.data.forEach { plan ->
                val fileRegistries = freshName(plan.fileSave, "registries")
                plan.fileSave.addParameter("HolderLookup.Provider", fileRegistries)
                if (plan.fileSave.annotations.none { it.nameAsString == "Override" }) {
                    plan.fileSave.addAnnotation("Override")
                }
                plan.utilityCall.addArgument(NameExpr(fileRegistries))
                plan.loader?.let { loader ->
                    loader.method.parameters.removeAt(loader.method.parameters.size - 1)
                    loader.computeIfAbsent.setArgument(
                        0,
                        StaticJavaParser.parseExpression("factory()")
                    )
                    val factoryMethod = StaticJavaParser.parseBodyDeclaration(
                        """
                        public static SavedData.Factory<${plan.className}> factory() {
                            return new SavedData.Factory<>(
                                ${plan.className}::new, ${plan.className}::${loader.deserializer});
                        }
                        """.trimIndent()
                    )
                    val insertion = plan.owner.members.indexOf(plan.nbtSave)
                    if (insertion < 0) abort("${plan.ownerName} lost its NBT save member")
                    plan.owner.members.add(insertion, factoryMethod)
                }
                addImport(plan.source.unit, HOLDER_LOOKUP)
            }
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
        val originals = affected.associate { source ->
            source.path to source.originalBytes.copyOf()
        }
        val changes = affected.map { source ->
            sourceChange(source.path, source.path in utilityPaths)
        }
        if (!dryRun) {
            val failure = commitProjectTransaction(stagedBytes, originals, fileIo)
            if (failure != null) {
                return Result(
                    changes.filter { it.file in failure.changedPaths },
                    listOf(failure.gate)
                )
            }
        }
        return Result(changes, emptyList())
    }

    private fun sourceChange(path: Path, utility: Boolean): Change =
        Change(
            file = path,
            line = 0,
            description = if (utility) {
                "Preserve source-owned SavedData .dat_old writes with target provider and Path APIs"
            } else {
                "Thread the target provider into the SavedData file save and restore its server loader"
            },
            before = if (utility) {
                "<T extends SavedData> backup utility with File-based NBT and replacement calls"
            } else {
                "provider-less save(File) and provider-widened server loader with an inline factory"
            },
            after = if (utility) {
                "provider parameter with Path-based NBT and replacement calls"
            } else {
                "save(File, Provider) override, original server loader arity, and factory()"
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

    /**
     * A recognized backup utility: a static `<T extends SavedData>` method whose first two
     * parameters are `(T, File)` and which both saves its first parameter and writes NBT through
     * NbtIo. Anything else is not this family and is left to other rules.
     */
    private fun utilityCandidates(source: ParsedSource): List<UtilityCandidate> =
        source.unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
            val owner = method.parentNode.orElse(null) as? ClassOrInterfaceDeclaration
                ?: return@mapNotNull null
            if (owner.isInterface || !method.isStatic) return@mapNotNull null
            val typeParameter = method.typeParameters.singleOrNull() ?: return@mapNotNull null
            val bound = typeParameter.typeBound.singleOrNull() ?: return@mapNotNull null
            if (!source.unit.typeIs(bound.nameWithScope, SAVED_DATA, owner)) return@mapNotNull null
            if (method.parameters.size !in 2..3 ||
                method.parameters[0].typeAsString != typeParameter.nameAsString ||
                !source.unit.typeIs(method.parameters[1].typeAsString, FILE, owner)
            ) return@mapNotNull null
            val body = method.body.orElse(null) ?: return@mapNotNull null
            val dataName = method.parameters[0].nameAsString
            val calls = body.findAll(MethodCallExpr::class.java)
            if (calls.none { it.nameAsString == "save" && scope(it) == dataName } ||
                calls.none {
                    it.nameAsString == "writeCompressed" &&
                        source.unit.typeIs(scope(it), NBT_IO, owner)
                }
            ) return@mapNotNull null
            val ownerName = canonicalName(owner, source.unit) ?: return@mapNotNull null
            UtilityCandidate(source, owner, ownerName, method)
        }

    private fun admitUtility(candidate: UtilityCandidate): UtilityPlan {
        val method = candidate.method
        val unit = candidate.source.unit
        val label = "${candidate.ownerName}.${method.nameAsString}"
        val dataName = method.parameters[0].nameAsString
        val body = method.body.orElseThrow()
        val calls = body.findAll(MethodCallExpr::class.java)
        val savedDataSave = calls.singleOrNull {
            it.nameAsString == "save" && scope(it) == dataName
        } ?: abort("$label must save its SavedData parameter exactly once")
        val writeCompressed = calls.singleOrNull {
            it.nameAsString == "writeCompressed" && unit.typeIs(scope(it), NBT_IO, method)
        } ?: abort("$label must write its NBT through exactly one NbtIo.writeCompressed call")
        val safeReplace = calls.singleOrNull {
            it.nameAsString == "safeReplaceFile" && unit.typeIs(scope(it), UTIL, method)
        } ?: abort("$label must replace its file through exactly one Util.safeReplaceFile call")
        if (writeCompressed.arguments.size != 2 || safeReplace.arguments.size != 3 ||
            !compoundValue(savedDataSave.arguments.firstOrNull(), method, unit)
        ) {
            abort("$label no longer has the exact NBT save and file replacement flow")
        }

        val legacy = method.parameters.size == 2 &&
            savedDataSave.arguments.size == 1 &&
            fileValue(writeCompressed.arguments[1], method, unit) &&
            safeReplace.arguments.all { fileValue(it, method, unit) }
        val target = method.parameters.size == 3 &&
            providerParameter(method, 2, unit) &&
            savedDataSave.arguments.size == 2 &&
            savedDataSave.arguments[1].toString() == method.parameters[2].nameAsString &&
            pathOfFile(writeCompressed.arguments[1], method, unit) &&
            safeReplace.arguments.all { pathOfFile(it, method, unit) }
        val state = when {
            legacy -> State.LEGACY
            target -> State.TARGET
            else -> abort("$label mixes legacy and target provider or Path calls")
        }
        return UtilityPlan(
            candidate.source,
            candidate.owner,
            candidate.ownerName,
            state,
            method,
            savedDataSave,
            writeCompressed,
            safeReplace
        )
    }

    private fun admitFamily(
        candidate: UtilityCandidate,
        sources: Collection<ParsedSource>
    ): FamilyPlan {
        val utility = admitUtility(candidate)
        val name = utility.method.nameAsString
        val label = "${utility.ownerName}.$name"

        sources.forEach { source ->
            source.unit.findAll(MethodReferenceExpr::class.java).forEach { reference ->
                if (reference.identifier == name &&
                    source.unit.refersTo(reference.scope.toString(), utility.ownerName, reference)
                ) {
                    abort("$label is also used as a method reference")
                }
            }
        }
        val calls = sources.flatMap { source ->
            source.unit.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == name && callsOwner(it, source, utility.ownerName) }
                .map { source to it }
        }

        val plans = mutableListOf<DataPlan>()
        calls.forEach { (source, call) ->
            val fileSave = call.findAncestor(MethodDeclaration::class.java).orElse(null)
                ?: abort("$label has a call outside a SavedData file save override")
            val owner = fileSave.parentNode.orElse(null) as? ClassOrInterfaceDeclaration
                ?: abort("$label has a call outside a SavedData file save override")
            if (plans.any { it.owner === owner }) {
                abort("$label has more than one call in ${canonicalName(owner, source.unit)}")
            }
            plans += admitData(source, owner, fileSave, call, utility, sources)
        }
        return FamilyPlan(utility, plans)
    }

    private fun admitData(
        source: ParsedSource,
        owner: ClassOrInterfaceDeclaration,
        fileSave: MethodDeclaration,
        utilityCall: MethodCallExpr,
        utility: UtilityPlan,
        sources: Collection<ParsedSource>
    ): DataPlan {
        val unit = source.unit
        val ownerName = canonicalName(owner, unit)
            ?: abort("${utility.ownerName}.${utility.method.nameAsString} has a call in an anonymous or local class")
        val label = "${utility.ownerName}.${utility.method.nameAsString}"
        if (owner.isInterface ||
            owner.extendedTypes.singleOrNull()?.let { unit.typeIs(it.nameWithScope, SAVED_DATA, owner) } != true
        ) {
            abort("$label has calls outside its owned SavedData file save overrides ($ownerName)")
        }
        val fileSaves = owner.methods.filter {
            it.nameAsString == "save" &&
                it.parameters.firstOrNull()?.let { parameter ->
                    unit.typeIs(parameter.typeAsString, FILE, owner)
                } == true
        }
        if (fileSaves.singleOrNull() !== fileSave || fileSave.isStatic || !fileSave.type.isVoidType ||
            fileSave.parameters.size !in 1..2
        ) {
            abort("$label has calls outside its owned SavedData file save overrides ($ownerName)")
        }
        if (fileSave.annotations.any { it.nameAsString != "Override" }) {
            abort("$ownerName file save has an unknown method annotation")
        }
        val fileName = fileSave.parameters[0].nameAsString
        val utilityArguments = utilityCall.arguments.map(Node::toString)

        val nbtSaves = owner.methods.filter {
            it.nameAsString == "save" &&
                it.parameters.firstOrNull()?.let { parameter ->
                    unit.typeIs(parameter.typeAsString, COMPOUND_TAG, owner)
                } == true
        }
        val nbtSave = nbtSaves.singleOrNull()
            ?: abort("$ownerName has no exact NBT save method")
        if (nbtSave.isStatic || !unit.typeIs(nbtSave.typeAsString, COMPOUND_TAG, owner)) {
            abort("$ownerName NBT save method is outside the SavedData contract")
        }
        val preCommon = nbtSave.parameters.size == 1
        if (!preCommon &&
            (nbtSave.parameters.size != 2 || !providerParameter(nbtSave, 1, unit))
        ) {
            abort("$ownerName NBT callbacks are outside the target provider contract")
        }

        val loader = admitLoader(source, owner, ownerName, sources)
        val legacyFileSave = fileSave.parameters.size == 1 &&
            utilityArguments == listOf("this", fileName)
        val targetFileSave = fileSave.parameters.size == 2 &&
            providerParameter(fileSave, 1, unit) &&
            fileSave.annotations.singleOrNull()?.nameAsString == "Override" &&
            utilityArguments == listOf("this", fileName, fileSave.parameters[1].nameAsString)
        if (utilityCall.arguments.firstOrNull() !is ThisExpr) {
            abort("$ownerName file save does not hand itself to $label")
        }
        val state = when {
            legacyFileSave && loader?.state != State.TARGET -> State.LEGACY
            targetFileSave && loader?.state != State.LEGACY -> State.TARGET
            else -> abort("$ownerName mixes legacy and target save/load factory shapes")
        }
        return DataPlan(
            source,
            owner,
            ownerName,
            owner.nameAsString,
            state,
            preCommon,
            nbtSave,
            fileSave,
            utilityCall,
            loader?.takeIf { it.state == State.LEGACY }
        )
    }

    /**
     * The static method of [owner] that obtains it from DimensionDataStorage through a
     * SavedData.Factory of [owner] (null when the class has none).
     */
    private fun admitLoader(
        source: ParsedSource,
        owner: ClassOrInterfaceDeclaration,
        ownerName: String,
        sources: Collection<ParsedSource>
    ): LoaderPlan? {
        val unit = source.unit
        val className = owner.nameAsString
        val loaders = owner.methods.filter { method ->
            method.isStatic && method.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == "computeIfAbsent" &&
                    call.arguments.size == 2 &&
                    (
                        factoryCreation(call.arguments[0], className) != null ||
                            call.arguments[0].toString() == "factory()"
                        )
            }
        }
        if (loaders.isEmpty()) return null
        val loader = loaders.singleOrNull()
            ?: abort("$ownerName has more than one SavedData.Factory server loader")
        val compute = loader.findAll(MethodCallExpr::class.java).singleOrNull {
            it.nameAsString == "computeIfAbsent"
        } ?: abort("$ownerName server loader has no exact computeIfAbsent call")
        val storage = compute.scope.orElse(null) as? MethodCallExpr
        if (storage?.nameAsString != "getDataStorage" || storage.arguments.isNotEmpty() ||
            compute.arguments.size != 2 ||
            !unit.typeIs(loader.typeAsString, ownerName, owner)
        ) {
            abort("$ownerName server loader is outside the DimensionDataStorage factory contract")
        }
        val factoryMethods = owner.methods.filter { it.nameAsString == "factory" }
        val inline = factoryCreation(compute.arguments[0], className)
        val parameters = loader.parameters
        val lastProvider = parameters.isNotEmpty() &&
            providerParameter(loader, parameters.size - 1, unit)

        if (inline != null) {
            val deserializer = (inline.arguments[1] as MethodReferenceExpr).identifier
            val provider = parameters.lastOrNull()?.nameAsString
            val providerUnused = provider != null && loader.findAll(NameExpr::class.java).none {
                it.nameAsString == provider
            }
            if (!lastProvider || !providerUnused || factoryMethods.isNotEmpty()) {
                abort("$ownerName server loader is not a provider-widened inline factory loader")
            }
            if (parameters.size < 2 ||
                !unit.typeIs(parameters[0].typeAsString, MINECRAFT_SERVER, owner)
            ) {
                abort("$ownerName server loader does not take the MinecraftServer first")
            }
            admitDeserializer(owner, ownerName, deserializer, unit)
            proveLoaderCallers(loader, ownerName, inline, sources)
            return LoaderPlan(State.LEGACY, loader, compute, deserializer)
        }

        val factory = factoryMethods.singleOrNull()
            ?: abort("$ownerName server loader calls factory() without one exact factory method")
        if (!factory.isPublic || !factory.isStatic || factory.parameters.isNotEmpty() ||
            factory.typeAsString.replace(" ", "") != "SavedData.Factory<$className>" ||
            lastProvider
        ) {
            abort("$ownerName factory method or server loader is outside the target shape")
        }
        val returned = factory.findAll(ReturnStmt::class.java).singleOrNull()
            ?.expression?.orElse(null)
            ?.let { factoryCreation(it, className) }
            ?: abort("$ownerName factory method does not return one exact SavedData.Factory")
        val deserializer = (returned.arguments[1] as MethodReferenceExpr).identifier
        admitDeserializer(owner, ownerName, deserializer, unit)
        return LoaderPlan(State.TARGET, loader, compute, deserializer)
    }

    private fun admitDeserializer(
        owner: ClassOrInterfaceDeclaration,
        ownerName: String,
        deserializer: String,
        unit: CompilationUnit
    ) {
        val methods = owner.methods.filter { it.nameAsString == deserializer && it.isStatic }
        val nbtLoad = methods.singleOrNull {
            it.parameters.size == 2 &&
                unit.typeIs(it.parameters[0].typeAsString, COMPOUND_TAG, owner) &&
                providerParameter(it, 1, unit)
        } ?: abort("$ownerName has no exact (CompoundTag, Provider) factory deserializer")
        if (!unit.typeIs(nbtLoad.typeAsString, ownerName, owner)) {
            abort("$ownerName factory deserializer does not return its own type")
        }
        if (owner.constructors.isNotEmpty() && owner.constructors.none { it.parameters.isEmpty() }) {
            abort("$ownerName has no no-argument constructor for its factory")
        }
    }

    /**
     * Every project call of a widened server loader must already pass the original arguments,
     * and its only method references are the deserializer references of the owner's own factory.
     */
    private fun proveLoaderCallers(
        loader: MethodDeclaration,
        ownerName: String,
        factory: ObjectCreationExpr,
        sources: Collection<ParsedSource>
    ) {
        val original = loader.parameters.size - 1
        sources.forEach { source ->
            source.unit.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == loader.nameAsString && callsOwner(it, source, ownerName) }
                .forEach { call ->
                    // A call with the widened arity either supplies the provider or is an
                    // overload the restored loader could not be told apart from.
                    if (call.arguments.size == loader.parameters.size) {
                        abort(
                            "$ownerName.${loader.nameAsString} has a caller that does not pass " +
                                "only its original $original argument(s)"
                        )
                    }
                }
            source.unit.findAll(MethodReferenceExpr::class.java)
                .filter {
                    it.identifier == loader.nameAsString &&
                        source.unit.refersTo(it.scope.toString(), ownerName, it)
                }
                .forEach { reference ->
                    if (reference !== factory.arguments[1]) {
                        abort("$ownerName::${loader.nameAsString} has references outside its owned factory")
                    }
                }
        }
    }

    private fun factoryCreation(expression: Expression, className: String): ObjectCreationExpr? {
        val creation = expression as? ObjectCreationExpr ?: return null
        if (creation.type.nameAsString != "Factory" || creation.arguments.size != 2) return null
        val supplier = creation.arguments[0] as? MethodReferenceExpr ?: return null
        val deserializer = creation.arguments[1] as? MethodReferenceExpr ?: return null
        return creation.takeIf {
            supplier.toString() == "$className::new" &&
                deserializer.scope.toString() == className &&
                deserializer.identifier != "new"
        }
    }

    /** Whether [call] statically targets a method of [ownerName]. */
    private fun callsOwner(call: MethodCallExpr, source: ParsedSource, ownerName: String): Boolean {
        val scope = call.scope.orElse(null)
        if (scope == null) {
            var current: Node? = call
            while (current != null) {
                if (current is ClassOrInterfaceDeclaration &&
                    canonicalName(current, source.unit) == ownerName
                ) return true
                current = current.parentNode.orElse(null)
            }
            return false
        }
        return source.unit.refersTo(scope.toString(), ownerName, call)
    }

    private fun compoundValue(
        expression: Expression?,
        method: MethodDeclaration,
        unit: CompilationUnit
    ): Boolean = when (expression) {
        is ObjectCreationExpr -> expression.arguments.isEmpty() &&
            unit.typeIs(expression.type.nameWithScope, COMPOUND_TAG, method)
        is NameExpr -> declaredType(expression.nameAsString, method)
            ?.let { unit.typeIs(it, COMPOUND_TAG, method) } == true
        else -> false
    }

    private fun fileValue(
        expression: Expression,
        method: MethodDeclaration,
        unit: CompilationUnit
    ): Boolean = expression is NameExpr &&
        declaredType(expression.nameAsString, method)?.let { unit.typeIs(it, FILE, method) } == true

    private fun pathOfFile(
        expression: Expression,
        method: MethodDeclaration,
        unit: CompilationUnit
    ): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        return call.nameAsString == "toPath" && call.arguments.isEmpty() &&
            call.scope.orElse(null)?.let { fileValue(it, method, unit) } == true
    }

    /** The single declared type of a parameter or local named [name] inside [method]. */
    private fun declaredType(name: String, method: MethodDeclaration): String? {
        val types = method.parameters.filter { it.nameAsString == name }.map { it.typeAsString } +
            method.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == name }
                .map { it.typeAsString }
        return types.singleOrNull()?.takeIf { it != "var" }
    }

    private fun freshName(method: MethodDeclaration, preferred: String): String {
        val used = method.parameters.map { it.nameAsString }.toSet() +
            method.findAll(VariableDeclarator::class.java).map { it.nameAsString } +
            method.findAll(NameExpr::class.java).map { it.nameAsString }
        return generateSequence(0) { it + 1 }
            .map { if (it == 0) preferred else "$preferred$it" }
            .first { it !in used }
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
            TargetMembers.methodDescriptors(NBT_IO, "writeCompressed") &&
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V" in
            TargetMembers.methodDescriptors(UTIL, "safeReplaceFile")

    private fun providerParameter(
        method: MethodDeclaration,
        index: Int,
        unit: CompilationUnit
    ): Boolean =
        method.parameters.getOrNull(index)?.let {
            unit.typeIs(it.typeAsString, PROVIDER, method)
        } == true

    /**
     * Whether the source type [raw] written in [unit] denotes [fqn]: the qualified name, an
     * explicit or on-demand import, or a type of the unit's own package. A type declared in the
     * unit with the same simple name shadows imports.
     */
    private fun CompilationUnit.typeIs(raw: String, fqn: String, context: Node): Boolean {
        val cleaned = raw.replace(Regex("""@[\w.]+(?:\([^)]*\))?\s*"""), "")
            .substringBefore('<').replace(" ", "").trim()
        if (cleaned.isEmpty()) return false
        if (cleaned == fqn) return true
        val first = cleaned.substringBefore('.')
        val rest = cleaned.removePrefix(first)
        if (first.firstOrNull()?.isLowerCase() == true) return false
        val declared = findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { it.nameAsString == first }
            .mapNotNull { canonicalName(it, this) }
        if (declared.isNotEmpty()) return declared.any { it + rest == fqn }
        if (generateSequence(context) { it.parentNode.orElse(null) }.any { node ->
                (node as? MethodDeclaration)?.typeParameters?.any { it.nameAsString == first } == true ||
                    (node as? ClassOrInterfaceDeclaration)?.typeParameters?.any { it.nameAsString == first } == true
            }
        ) return false
        val explicit = imports.filter {
            !it.isStatic && !it.isAsterisk && it.name.identifier == first
        }
        if (explicit.isNotEmpty()) return explicit.any { it.nameAsString + rest == fqn }
        val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
        if ((if (packageName.isBlank()) cleaned else "$packageName.$cleaned") == fqn) return true
        return imports.any { !it.isStatic && it.isAsterisk && "${it.nameAsString}.$cleaned" == fqn }
    }

    private fun CompilationUnit.refersTo(raw: String, fqn: String, context: Node): Boolean =
        typeIs(raw, fqn, context)

    private fun canonicalName(
        declaration: ClassOrInterfaceDeclaration,
        unit: CompilationUnit
    ): String? {
        val names = mutableListOf<String>()
        var current: Node = declaration
        while (current is ClassOrInterfaceDeclaration) {
            if (current.isLocalClassDeclaration) return null
            names += current.nameAsString
            current = current.parentNode.orElse(null) ?: return null
        }
        if (current !== unit) return null
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val nested = names.asReversed().joinToString(".")
        return if (packageName.isBlank()) nested else "$packageName.$nested"
    }

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

    private fun abort(message: String): Nothing =
        throw AdmissionAbort(message)

    private data class ParsedSource(
        val path: Path,
        val originalBytes: ByteArray,
        val unit: CompilationUnit
    )

    private data class TransactionFailure(
        val changedPaths: Set<Path>,
        val gate: String
    )

    private class UtilityCandidate(
        val source: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val ownerName: String,
        val method: MethodDeclaration
    )

    private class UtilityPlan(
        val source: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val ownerName: String,
        val state: State,
        val method: MethodDeclaration,
        val savedDataSave: MethodCallExpr,
        val writeCompressed: MethodCallExpr,
        val safeReplace: MethodCallExpr
    )

    private class LoaderPlan(
        val state: State,
        val method: MethodDeclaration,
        val computeIfAbsent: MethodCallExpr,
        val deserializer: String
    )

    private class DataPlan(
        val source: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val ownerName: String,
        val className: String,
        val state: State,
        val preCommon: Boolean,
        val nbtSave: MethodDeclaration,
        val fileSave: MethodDeclaration,
        val utilityCall: MethodCallExpr,
        val loader: LoaderPlan?
    )

    private class FamilyPlan(
        val utility: UtilityPlan,
        val data: List<DataPlan>
    )

    private enum class State {
        LEGACY,
        TARGET
    }

    private class AdmissionAbort(message: String) : RuntimeException(message)
}
