package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * Migrates a source-proven ItemAttribute family away from the removed built-in
 * enchantment registry.
 *
 * The durable value is a ResourceKey<Enchantment>. A Holder is retained only
 * when a stack supplies an unkeyed direct holder. Loading and legacy decoding
 * therefore remain provider-free, unknown keys survive serialization, and
 * display resolution is deferred to a HolderLookup.Provider from a proven UI
 * root.
 *
 * This is a project transaction. It emits no changes unless it proves:
 *  - the raw Enchantment field's save/load/match/display family,
 *  - every source constructor call,
 *  - the attribute interface's formatting defaults,
 *  - every project call to the one-argument attribute format method, and
 *  - every string-backed enchantment display lookup in the same interface.
 */
internal object KeyBackedDynamicAttributes {
    const val RULE_ID = "struct-key-backed-dynamic-attributes"

    private const val ATTRIBUTE_KEY = "enchantId"
    private const val BUILT_IN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val ENCHANTMENT = "net.minecraft.world.item.enchantment.Enchantment"
    private const val ENCHANTMENT_HELPER =
        "net.minecraft.world.item.enchantment.EnchantmentHelper"
    private const val HOLDER = "net.minecraft.core.Holder"
    private const val HOLDER_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private data class ParsedSource(
        val file: Path,
        val source: String,
        val unit: CompilationUnit,
        val typing: SourceValueTypes,
        val lineStarts: List<Int>
    ) {
        fun offset(node: Node): Int = node.range
            .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
            .orElse(-1)
    }

    private data class RawAttributePlan(
        val source: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val canonicalName: String,
        val interfaceName: String,
        val rawField: FieldDeclaration,
        val rawVariable: VariableDeclarator,
        val constructor: ConstructorDeclaration,
        val appliesTo: MethodDeclaration,
        val translationParameters: MethodDeclaration,
        val save: MethodDeclaration,
        val load: MethodDeclaration,
        val stackParameter: String,
        val keyField: String,
        val directField: String
    )

    private enum class ConstructionKind {
        BLANK,
        HOLDER,
        LEGACY_ID
    }

    private data class ConstructionPlan(
        val source: ParsedSource,
        val creation: ObjectCreationExpr,
        val kind: ConstructionKind,
        val replacement: Expression? = null,
        val removableDeclaration: ExpressionStmt? = null
    )

    private data class InterfacePlan(
        val source: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val format: MethodDeclaration,
        val translationParameters: MethodDeclaration
    )

    private data class StringDisplayPlan(
        val source: ParsedSource,
        val owner: ClassOrInterfaceDeclaration,
        val method: MethodDeclaration,
        val enchantmentVariable: String,
        val displayVariable: String,
        val idExpression: Expression
    )

    private data class FormatCallPlan(
        val source: ParsedSource,
        val call: MethodCallExpr,
        val providerExpression: String
    )

    private data class TypeShape(val raw: String, val arguments: List<TypeShape> = emptyList())

    internal interface ProjectFileIo {
        fun readText(path: Path): String
        fun createTempSibling(target: Path, suffix: String): Path
        fun writeText(path: Path, source: String)
        fun copy(source: Path, target: Path)
        fun moveReplace(source: Path, target: Path)
        fun deleteIfExists(path: Path)
    }

    internal object NioProjectFileIo : ProjectFileIo {
        override fun readText(path: Path): String = Files.readString(path)

        override fun createTempSibling(target: Path, suffix: String): Path =
            Files.createTempFile(target.parent, ".${target.fileName}.modporter-", suffix)

        override fun writeText(path: Path, source: String) {
            Files.writeString(path, source)
        }

        override fun copy(source: Path, target: Path) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
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
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean,
        fileIo: ProjectFileIo = NioProjectFileIo
    ): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return emptyList()
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths ->
            paths.filter { it.extension == "java" }
                .filter {
                    !it.toString().replace('\\', '/')
                        .contains("/com/modporter/generated/")
                }
                .toList()
        }
        if (files.isEmpty()) return emptyList()

        val originals = files.associateWith { file ->
            runCatching { fileIo.readText(file) }.getOrNull() ?: return emptyList()
        }
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)

        val parsed = originals.mapNotNull { (file, source) ->
            parser.parse(source).result.orElse(null)?.let { unit ->
                ParsedSource(
                    file = file,
                    source = source,
                    unit = unit,
                    typing = SourceValueTypes(pass, source),
                    lineStarts = buildList {
                        add(0)
                        source.forEachIndexed { index, character ->
                            if (character == '\n') add(index + 1)
                        }
                    }
                )
            }
        }
        if (parsed.isEmpty()) return emptyList()

        val candidates = parsed.flatMap { source ->
            source.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .mapNotNull { owner -> rawAttributePlan(source, owner) }
        }
        val raw = candidates.singleOrNull() ?: return emptyList()

        val unparsed = originals.keys - parsed.map { it.file }.toSet()
        if (unparsed.any { file ->
                val code = JavaSourceText.maskCommentsAndLiterals(originals.getValue(file))
                Regex("""\b${Regex.escape(raw.owner.nameAsString)}\b""").containsMatchIn(code) ||
                    (
                        Regex("""\b${Regex.escape(raw.interfaceName.substringAfterLast('.'))}\b""")
                            .containsMatchIn(code) &&
                            code.contains(".format(")
                        )
            }
        ) {
            return emptyList()
        }

        if (!rawFieldUsesAreClosed(parsed, raw)) return emptyList()
        val interfacePlan = findInterfacePlan(parsed, raw.interfaceName) ?: return emptyList()
        val constructions = findConstructionPlans(parsed, raw) ?: return emptyList()
        if (constructions.none { it.kind == ConstructionKind.BLANK } ||
            constructions.none { it.kind == ConstructionKind.HOLDER } ||
            constructions.none { it.kind == ConstructionKind.LEGACY_ID }
        ) {
            return emptyList()
        }

        val displays = findStringDisplayPlans(parsed, raw.interfaceName) ?: return emptyList()
        val formatCalls = findFormatCalls(pass, parsed, raw.interfaceName) ?: return emptyList()
        if (formatCalls.isEmpty()) return emptyList()

        parsed.filter { it.file != interfacePlan.source.file }
            .forEach { LexicalPreservingPrinter.setup(it.unit) }

        migrateInterface(interfacePlan)
        migrateRawAttribute(raw)
        migrateConstructions(constructions)
        displays.forEach(::migrateStringDisplay)
        formatCalls.forEach { plan ->
            plan.call.addArgument(parseExpression(plan.providerExpression))
        }

        val imports = mutableMapOf<Path, MutableSet<String>>()
        fun requireImport(source: ParsedSource, name: String) {
            imports.getOrPut(source.file) { linkedSetOf() } += name
        }

        requireImport(interfacePlan.source, "net.minecraft.core.HolderLookup")
        requireImport(interfacePlan.source, "org.jetbrains.annotations.Nullable")
        listOf(
            "java.util.Objects",
            "net.minecraft.core.Holder",
            "net.minecraft.core.HolderLookup",
            "net.minecraft.core.registries.Registries",
            "net.minecraft.resources.ResourceKey",
            "org.jetbrains.annotations.Nullable"
        ).forEach { requireImport(raw.source, it) }
        displays.forEach { display ->
            listOf(
                "net.minecraft.core.HolderLookup",
                "net.minecraft.core.registries.Registries",
                "net.minecraft.resources.ResourceKey",
                "org.jetbrains.annotations.Nullable"
            ).forEach { requireImport(display.source, it) }
        }

        val migrated = linkedMapOf<Path, String>()
        for (source in parsed) {
            var result = if (source.file == interfacePlan.source.file) {
                source.unit.toString()
            } else {
                LexicalPreservingPrinter.print(source.unit)
            }
            imports[source.file].orEmpty().forEach { result = JavaSourceText.addImport(result, it) }
            if (result == source.source) continue
            if (!parser.parse(result).isSuccessful) return emptyList()
            migrated[source.file] = result
        }
        if (migrated.isEmpty()) return emptyList()

        if (!dryRun) {
            commitProjectTransaction(migrated, originals, fileIo)
        }
        return migrated.map { (file, after) ->
            val before = originals.getValue(file)
            Change(
                file = file,
                line = firstChangedLine(before, after),
                description =
                    "Preserve dynamic enchantment attributes by key and resolve display names from a proven registry provider",
                before = "raw Enchantment / BuiltInRegistries.ENCHANTMENT",
                after = "ResourceKey<Enchantment> / HolderLookup.Provider",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
    }

    private fun commitProjectTransaction(
        migrated: Map<Path, String>,
        originals: Map<Path, String>,
        fileIo: ProjectFileIo
    ) {
        val staged = linkedMapOf<Path, Path>()
        val backups = linkedMapOf<Path, Path>()
        val temporary = linkedSetOf<Path>()
        val attemptedReplacements = mutableListOf<Path>()
        var failure: Throwable? = null
        try {
            for ((target, after) in migrated) {
                val stage = fileIo.createTempSibling(target, ".stage")
                temporary.add(stage)
                val backup = fileIo.createTempSibling(target, ".backup")
                temporary.add(backup)
                fileIo.writeText(stage, after)
                check(fileIo.readText(stage) == after) {
                    "Staged dynamic-attribute source changed before commit: $target"
                }
                staged[target] = stage
                fileIo.copy(target, backup)
                check(fileIo.readText(backup) == originals.getValue(target)) {
                    "Dynamic-attribute rollback copy does not match its source: $target"
                }
                backups[target] = backup
            }

            for ((target, stage) in staged) {
                attemptedReplacements.add(target)
                fileIo.moveReplace(stage, target)
            }
        } catch (error: Throwable) {
            failure = error
            for (target in attemptedReplacements.asReversed()) {
                val backup = backups.getValue(target)
                try {
                    fileIo.moveReplace(backup, target)
                } catch (rollbackError: Throwable) {
                    temporary.remove(backup)
                    error.addSuppressed(
                        IOException(
                            "Could not restore $target; recovery copy retained at $backup",
                            rollbackError
                        )
                    )
                }
            }
            throw error
        } finally {
            for (path in temporary) {
                try {
                    fileIo.deleteIfExists(path)
                } catch (cleanupError: Throwable) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupError)
                    }
                }
            }
        }
    }

    private fun rawAttributePlan(
        source: ParsedSource,
        owner: ClassOrInterfaceDeclaration
    ): RawAttributePlan? {
        if (owner.isInterface || owner.implementedTypes.size != 1) return null
        val interfaceName = qualifyRaw(source, owner.implementedTypes.single().nameWithScope)
            ?: return null
        val enchantmentFields = owner.fields.flatMap { field ->
            field.variables.mapNotNull { variable ->
                if (qualifyRaw(source, variable.type.toString()) == ENCHANTMENT) field to variable
                else null
            }
        }
        val (field, variable) = enchantmentFields.singleOrNull() ?: return null
        if (!field.isPrivate || field.variables.size != 1) return null

        val constructor = owner.constructors.singleOrNull { candidate ->
            candidate.parameters.size == 1 &&
                qualifyRaw(source, candidate.parameters.single().type.toString()) == ENCHANTMENT &&
                candidate.body.statements.singleOrNull()
                    ?.let { it as? ExpressionStmt }
                    ?.expression
                    ?.let { it as? AssignExpr }
                    ?.let { assignment ->
                        isFieldReference(assignment.target, variable.nameAsString) &&
                            assignment.value.toString() ==
                            candidate.parameters.single().nameAsString
                    } == true
        } ?: return null
        if (!constructor.isPublic) return null

        val methods = owner.methods
        val save = methods.singleOrNull { method ->
            method.parameters.size == 1 &&
                qualifyRaw(source, method.parameters.single().type.toString()) == COMPOUND_TAG &&
                method.body.orElse(null)?.statements?.size == 4 &&
                method.findAll(StringLiteralExpr::class.java).any { it.asString() == ATTRIBUTE_KEY } &&
                method.findAll(MethodCallExpr::class.java).any { call ->
                    call.nameAsString == "getKey" &&
                        call.arguments.size == 1 &&
                        isFieldReference(call.arguments.single(), variable.nameAsString) &&
                        isBuiltInEnchantment(call.scope.orElse(null), source)
                }
        } ?: return null
        val load = methods.singleOrNull { method ->
            method !== save &&
                method.parameters.size == 1 &&
                qualifyRaw(source, method.parameters.single().type.toString()) == COMPOUND_TAG &&
                method.body.orElse(null)?.statements?.singleOrNull() is IfStmt &&
                method.findAll(StringLiteralExpr::class.java).any { it.asString() == ATTRIBUTE_KEY } &&
                method.findAll(AssignExpr::class.java).any { assignment ->
                    isFieldReference(assignment.target, variable.nameAsString) &&
                        (assignment.value as? MethodCallExpr)?.let { call ->
                            call.nameAsString == "get" &&
                                call.arguments.size == 1 &&
                                isBuiltInEnchantment(call.scope.orElse(null), source)
                        } == true
                }
        } ?: return null
        val translation = methods.singleOrNull { method ->
            method.parameters.isEmpty() &&
                method.nameAsString == "getTranslationParameters" &&
                method.body.orElse(null)?.statements?.size == 3 &&
                method.findAll(MethodCallExpr::class.java).any { call ->
                    call.nameAsString == "getDescriptionId" &&
                        call.arguments.isEmpty() &&
                        call.scope.map {
                            isFieldReference(it, variable.nameAsString)
                        }.orElse(false)
                }
        } ?: return null
        val applies = methods.singleOrNull { method ->
            method.parameters.any {
                qualifyRaw(source, it.type.toString()) == ITEM_STACK
            } &&
                method.body.orElse(null)?.statements?.singleOrNull() is ReturnStmt &&
                method.findAll(MethodCallExpr::class.java).any { call ->
                    call.nameAsString == "getEnchantmentsForCrafting" &&
                        call.arguments.size == 1 &&
                        call.scope.map {
                            source.typing.isTypeName(it.toString(), ENCHANTMENT_HELPER)
                        }.orElse(false)
                } &&
                referencesField(method, variable.nameAsString)
        } ?: return null
        val loadGuard = load.body.get().statements.single() as IfStmt
        if (loadGuard.elseStmt.isPresent || statementCount(loadGuard.thenStmt) != 1) {
            return null
        }
        val translationGuard = translation.body.get().statements
            .filterIsInstance<IfStmt>()
            .singleOrNull() ?: return null
        if (translationGuard.elseStmt.isPresent ||
            statementCount(translationGuard.thenStmt) != 1 ||
            translation.body.get().statements.filterIsInstance<ReturnStmt>().size != 1 ||
            translation.body.get().statements
                .filterIsInstance<ExpressionStmt>()
                .map { it.expression }
                .filterIsInstance<com.github.javaparser.ast.expr.VariableDeclarationExpr>()
                .flatMap { it.variables }
                .count {
                    qualifyRaw(source, it.type.toString()) == "java.lang.String" &&
                        it.initializer.orElse(null)?.toString() == "\"\""
                } != 1
        ) {
            return null
        }
        if (owner.methods.none { method ->
                method.parameters.isEmpty() &&
                    method.findAll(FieldAccessExpr::class.java).any {
                        it.nameAsString == "HAS_ENCHANT"
                    }
            }
        ) {
            return null
        }

        val holderLoops = owner.findAll(ForEachStmt::class.java).filter { loop ->
            loop.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) !== owner &&
                isEnchantmentHolderLoop(loop, source)
        }
        if (holderLoops.isEmpty()) return null

        val usedNames = owner.findAll(VariableDeclarator::class.java)
            .map { it.nameAsString }
            .toMutableSet()
        val keyField = uniqueName(variable.nameAsString + "Key", usedNames)
        usedNames += keyField
        val directStem = "direct" + variable.nameAsString.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val directField = uniqueName(directStem, usedNames)
        val stackParameter = applies.parameters.singleOrNull {
            qualifyRaw(source, it.type.toString()) == ITEM_STACK
        }?.nameAsString ?: return null

        return RawAttributePlan(
            source = source,
            owner = owner,
            canonicalName = canonicalType(source, owner),
            interfaceName = interfaceName,
            rawField = field,
            rawVariable = variable,
            constructor = constructor,
            appliesTo = applies,
            translationParameters = translation,
            save = save,
            load = load,
            stackParameter = stackParameter,
            keyField = keyField,
            directField = directField
        )
    }

    private fun rawFieldUsesAreClosed(
        sources: List<ParsedSource>,
        raw: RawAttributePlan
    ): Boolean {
        val fieldName = raw.rawVariable.nameAsString
        val allowed = setOf<Node>(
            raw.constructor,
            raw.appliesTo,
            raw.translationParameters,
            raw.save,
            raw.load
        )
        for (name in raw.owner.findAll(NameExpr::class.java)) {
            if (name.nameAsString != fieldName ||
                name.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) !== raw.owner
            ) {
                continue
            }
            val callable = nearestCallable(name)
            if (callable !in allowed) return false
        }
        for (access in raw.owner.findAll(FieldAccessExpr::class.java)) {
            if (access.nameAsString != fieldName ||
                access.scope !is ThisExpr ||
                access.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) !== raw.owner
            ) {
                continue
            }
            if (nearestCallable(access) !in allowed) return false
        }
        return sources.none { source ->
            source.unit.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == "getDeclaredField" &&
                    call.arguments.singleOrNull()
                        ?.let { it as? StringLiteralExpr }
                        ?.asString() == fieldName
            }
        }
    }

    private fun findInterfacePlan(
        sources: List<ParsedSource>,
        interfaceName: String
    ): InterfacePlan? {
        val declarations = sources.flatMap { source ->
            source.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { it.isInterface && canonicalType(source, it) == interfaceName }
                .map { source to it }
        }
        val (source, owner) = declarations.singleOrNull() ?: return null
        val format = owner.methods.singleOrNull { method ->
            method.nameAsString == "format" &&
                method.parameters.size == 1 &&
                qualifyRaw(source, method.parameters.single().type.toString()) == "boolean" &&
                method.findAll(MethodCallExpr::class.java).count {
                    it.nameAsString == "getTranslationParameters" && it.arguments.isEmpty()
                } == 1
        } ?: return null
        val parameters = owner.methods.singleOrNull {
            it.nameAsString == "getTranslationParameters" && it.parameters.isEmpty()
        } ?: return null
        if (owner.methods.any { method ->
                method.nameAsString in setOf("format", "getTranslationParameters") &&
                    method.parameters.any {
                        qualifyRaw(source, it.type.toString()) == HOLDER_PROVIDER
                    }
            }
        ) {
            return null
        }
        return InterfacePlan(source, owner, format, parameters)
    }

    private fun findConstructionPlans(
        sources: List<ParsedSource>,
        raw: RawAttributePlan
    ): List<ConstructionPlan>? {
        val result = mutableListOf<ConstructionPlan>()
        for (source in sources) {
            for (creation in source.unit.findAll(ObjectCreationExpr::class.java)) {
                if (qualifyRaw(source, creation.type.toString()) != raw.canonicalName) continue
                val blank = blankConstruction(source, creation, raw)
                if (blank != null) {
                    result += blank
                    continue
                }
                val holder = holderConstruction(source, creation, raw)
                if (holder != null) {
                    result += holder
                    continue
                }
                val legacy = legacyConstruction(source, creation, raw)
                if (legacy != null) {
                    result += legacy
                    continue
                }
                return null
            }
        }
        return result
    }

    private fun blankConstruction(
        source: ParsedSource,
        creation: ObjectCreationExpr,
        raw: RawAttributePlan
    ): ConstructionPlan? {
        if (creation.arguments.singleOrNull() !is NullLiteralExpr) return null
        val owner = creation.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return null
        if (!isNestedInside(owner, raw.owner)) return null
        val method = creation.findAncestor(MethodDeclaration::class.java).orElse(null)
            ?: return null
        if (method.parameters.isNotEmpty() ||
            qualifyRaw(source, method.type.toString()) != raw.interfaceName
        ) {
            return null
        }
        return ConstructionPlan(source, creation, ConstructionKind.BLANK)
    }

    private fun holderConstruction(
        source: ParsedSource,
        creation: ObjectCreationExpr,
        raw: RawAttributePlan
    ): ConstructionPlan? {
        val argument = creation.arguments.singleOrNull() ?: return null
        val loop = creation.findAncestor(ForEachStmt::class.java).orElse(null) ?: return null
        if (!isNestedInside(
                loop.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null),
                raw.owner
            ) ||
            !isEnchantmentHolderLoop(loop, source)
        ) {
            return null
        }
        val holderName = loop.variable.variables.single().nameAsString
        if (argument.toString() == holderName) {
            return ConstructionPlan(
                source,
                creation,
                ConstructionKind.HOLDER,
                NameExpr(holderName)
            )
        }
        val argumentName = (argument as? NameExpr)?.nameAsString ?: return null
        val rawValue = loop.body.findAll(VariableDeclarator::class.java)
            .singleOrNull { variable ->
                variable.nameAsString == argumentName &&
                    qualifyRaw(source, variable.type.toString()) == ENCHANTMENT &&
                    (variable.initializer.orElse(null) as? MethodCallExpr)?.let { call ->
                        call.nameAsString == "value" &&
                            call.arguments.isEmpty() &&
                            call.scope.map { it.toString() == holderName }.orElse(false)
                    } == true
            } ?: return null
        val uses = loop.body.findAll(NameExpr::class.java)
            .filter { it.nameAsString == argumentName }
        if (uses.size != 1 || uses.single() !== argument) return null
        val declaration = rawValue.parentNode.orElse(null)
            ?.parentNode?.orElse(null) as? ExpressionStmt ?: return null
        return ConstructionPlan(
            source,
            creation,
            ConstructionKind.HOLDER,
            NameExpr(holderName),
            declaration
        )
    }

    private fun legacyConstruction(
        source: ParsedSource,
        creation: ObjectCreationExpr,
        raw: RawAttributePlan
    ): ConstructionPlan? {
        val registryGet = creation.arguments.singleOrNull() as? MethodCallExpr ?: return null
        if (registryGet.nameAsString != "get" ||
            registryGet.arguments.size != 1 ||
            !isBuiltInEnchantment(registryGet.scope.orElse(null), source)
        ) {
            return null
        }
        val parse = registryGet.arguments.single() as? MethodCallExpr ?: return null
        if (parse.nameAsString != "tryParse" ||
            parse.arguments.size != 1 ||
            !parse.scope.map {
                source.typing.isTypeName(it.toString(), RESOURCE_LOCATION)
            }.orElse(false)
        ) {
            return null
        }
        val lambda = creation.findAncestor(LambdaExpr::class.java).orElse(null) ?: return null
        val registration = lambda.findAncestor(MethodCallExpr::class.java).orElse(null)
            ?: return null
        if (registration.arguments.none { it === lambda } ||
            registration.arguments.none {
                (it as? StringLiteralExpr)?.asString() == "has_enchant"
            }
        ) {
            return null
        }
        return ConstructionPlan(
            source,
            creation,
            ConstructionKind.LEGACY_ID,
            parse.clone()
        )
    }

    private fun findStringDisplayPlans(
        sources: List<ParsedSource>,
        interfaceName: String
    ): List<StringDisplayPlan>? {
        val plans = mutableListOf<StringDisplayPlan>()
        for (source in sources) {
            for (owner in source.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                if (owner.isInterface || owner.implementedTypes.none {
                        qualifyRaw(source, it.nameWithScope) == interfaceName
                    }
                ) {
                    continue
                }
                for (method in owner.methods.filter {
                    it.nameAsString == "getTranslationParameters" && it.parameters.isEmpty()
                }) {
                    val registryVariable = method.findAll(VariableDeclarator::class.java)
                        .singleOrNull { variable ->
                            qualifyRaw(source, variable.type.toString()) == ENCHANTMENT &&
                                (variable.initializer.orElse(null) as? MethodCallExpr)?.let { call ->
                                    call.nameAsString == "get" &&
                                        call.arguments.size == 1 &&
                                        isBuiltInEnchantment(call.scope.orElse(null), source)
                                } == true
                        } ?: continue
                    val registryGet = registryVariable.initializer.get() as MethodCallExpr
                    val idExpression = registryGet.arguments.single() as? MethodCallExpr
                        ?: return null
                    if (idExpression.nameAsString != "tryParse" ||
                        idExpression.arguments.size != 1 ||
                        !idExpression.scope.map {
                            source.typing.isTypeName(it.toString(), RESOURCE_LOCATION)
                        }.orElse(false)
                    ) {
                        return null
                    }
                    val ifStatement = method.findAll(IfStmt::class.java)
                        .singleOrNull { statement ->
                            !statement.elseStmt.isPresent &&
                                statement.condition.toString().replace(" ", "") in setOf(
                                    "${registryVariable.nameAsString}!=null",
                                    "null!=${registryVariable.nameAsString}"
                                )
                        } ?: return null
                    if (ifStatement.elseStmt.isPresent ||
                        statementCount(ifStatement.thenStmt) != 1 ||
                        ifStatement.parentNode.orElse(null) !== method.body.orElse(null)
                    ) {
                        return null
                    }
                    val assignment = ifStatement.thenStmt.findAll(AssignExpr::class.java)
                        .singleOrNull { assign ->
                            assign.value.findAll(MethodCallExpr::class.java).any { call ->
                                call.nameAsString == "getDescriptionId" &&
                                    call.arguments.isEmpty() &&
                                    call.scope.map {
                                        it.toString() == registryVariable.nameAsString
                                    }.orElse(false)
                            }
                        } ?: return null
                    val displayVariable = (assignment.target as? NameExpr)?.nameAsString
                        ?: return null
                    val displayDeclaration = method.findAll(VariableDeclarator::class.java)
                        .singleOrNull {
                            it.nameAsString == displayVariable &&
                                qualifyRaw(source, it.type.toString()) == "java.lang.String"
                        } ?: return null
                    if (displayDeclaration.initializer.orElse(null)?.toString() != "\"\"") {
                        return null
                    }
                    if (registryVariable.parentNode.orElse(null)
                            ?.parentNode?.orElse(null)
                            ?.parentNode?.orElse(null) !== method.body.orElse(null)
                    ) {
                        return null
                    }
                    val enchantmentUses = method.findAll(NameExpr::class.java)
                        .filter { it.nameAsString == registryVariable.nameAsString }
                    if (enchantmentUses.any { !isDescendantOf(it, ifStatement) }) return null
                    plans += StringDisplayPlan(
                        source,
                        owner,
                        method,
                        registryVariable.nameAsString,
                        displayVariable,
                        idExpression.clone()
                    )
                }
            }
        }
        return plans
    }

    private fun findFormatCalls(
        pass: StructuralRefactorPass,
        sources: List<ParsedSource>,
        interfaceName: String
    ): List<FormatCallPlan>? {
        val result = mutableListOf<FormatCallPlan>()
        for (source in sources) {
            val mentionsInterface = source.unit.findAll(
                com.github.javaparser.ast.type.ClassOrInterfaceType::class.java
            ).any { qualifyRaw(source, it.nameWithScope) == interfaceName }
            if (!mentionsInterface) continue
            for (reference in source.unit.findAll(
                com.github.javaparser.ast.expr.MethodReferenceExpr::class.java
            )) {
                if (reference.identifier != "format") continue
                val receiver = reference.scope
                val rawType = source.typing.typeOf(
                    receiver.toString(),
                    source.offset(receiver)
                )
                val structuralType = fullType(pass, source, receiver, 0)?.raw
                if (rawType == interfaceName || structuralType == interfaceName ||
                    rawType == null
                ) {
                    return null
                }
            }
            for (call in source.unit.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString != "format" || call.arguments.size != 1) {
                    continue
                }
                if (call.scope.isEmpty) {
                    val enclosing = call.findAncestor(
                        ClassOrInterfaceDeclaration::class.java
                    ).orElse(null)
                    if (enclosing != null && enclosing.implementedTypes.any {
                            qualifyRaw(source, it.nameWithScope) == interfaceName
                        }
                    ) {
                        return null
                    }
                    continue
                }
                val receiver = call.scope.get()
                val rawType = source.typing.typeOf(receiver.toString(), source.offset(receiver))
                val structuralType = fullType(pass, source, receiver, 0)?.raw
                val isAttribute = rawType == interfaceName ||
                    structuralType == interfaceName ||
                    pairFirstReturnsInterface(pass, source, receiver, interfaceName)
                if (!isAttribute) {
                    if (rawType == null) return null
                    continue
                }
                val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
                    ?: return null
                val provider = method.parameters.singleOrNull {
                    qualifyRaw(source, it.type.toString()) == HOLDER_PROVIDER
                }?.nameAsString
                if (provider != null) {
                    result += FormatCallPlan(source, call, provider)
                    continue
                }
                val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null) ?: return null
                if (!isActiveScreenRoot(source, owner)) return null
                result += FormatCallPlan(
                    source,
                    call,
                    "minecraft.level.registryAccess()"
                )
            }
        }
        return result
    }

    private fun pairFirstReturnsInterface(
        pass: StructuralRefactorPass,
        source: ParsedSource,
        expression: Expression,
        interfaceName: String
    ): Boolean {
        val call = expression as? MethodCallExpr ?: return false
        if (call.nameAsString != "getFirst" ||
            call.arguments.isNotEmpty() ||
            call.scope.isEmpty
        ) {
            return false
        }
        val pair = fullType(pass, source, call.scope.get(), 0) ?: return false
        return pair.raw.endsWith(".Pair") &&
            pair.arguments.firstOrNull()?.raw == interfaceName
    }

    private fun fullType(
        pass: StructuralRefactorPass,
        source: ParsedSource,
        expression: Expression,
        depth: Int
    ): TypeShape? {
        if (depth > 8) return null
        return when (expression) {
            is NameExpr -> {
                val lambda = expression.findAncestor(LambdaExpr::class.java).orElse(null)
                val parameterIndex = lambda?.parameters
                    ?.indexOfFirst { it.nameAsString == expression.nameAsString } ?: -1
                if (lambda != null && parameterIndex >= 0) {
                    lambdaParameterType(pass, source, lambda, parameterIndex, depth + 1)
                } else {
                    val callable = nearestCallable(expression)
                    val explicitParameter = when (callable) {
                        is MethodDeclaration -> callable.parameters.firstOrNull {
                            it.nameAsString == expression.nameAsString
                        }
                        is ConstructorDeclaration -> callable.parameters.firstOrNull {
                            it.nameAsString == expression.nameAsString
                        }
                        else -> null
                    }
                    explicitParameter?.let {
                        parseTypeShape(source, it.type.toString())
                    } ?: run {
                        val local = callable?.findAll(VariableDeclarator::class.java)
                            ?.filter {
                                it.nameAsString == expression.nameAsString &&
                                    source.offset(it) <= source.offset(expression)
                            }
                            ?.maxByOrNull { source.offset(it) }
                        local?.let { parseTypeShape(source, it.type.toString()) }
                    } ?: projectFieldType(
                        pass,
                        source,
                        expression.nameAsString,
                        expression
                    ) ?: constructorForwardedFieldType(
                        source,
                        expression.nameAsString,
                        expression
                    )
                }
            }
            is FieldAccessExpr -> {
                val owner = source.typing.typeOf(
                    expression.scope.toString(),
                    source.offset(expression.scope)
                ) ?: fullType(pass, source, expression.scope, depth + 1)?.raw
                owner?.let {
                    pass.projectValueFacts.fields[it]?.get(expression.nameAsString)
                }?.let(::parseCanonicalTypeShape)
                    ?: pass.projectValueFacts.fields.values
                        .mapNotNull { it[expression.nameAsString] }
                        .distinct()
                        .singleOrNull()
                        ?.let(::parseCanonicalTypeShape)
            }
            is MethodCallExpr -> {
                val scope = expression.scope.orElse(null) ?: return null
                val scopeType = fullType(pass, source, scope, depth + 1) ?: return null
                when {
                    expression.nameAsString == "stream" &&
                        expression.arguments.isEmpty() &&
                        scopeType.arguments.size == 1 ->
                        TypeShape("java.util.stream.Stream", scopeType.arguments)
                    expression.nameAsString == "getFirst" &&
                        expression.arguments.isEmpty() &&
                        scopeType.raw.endsWith(".Pair") ->
                        scopeType.arguments.firstOrNull()
                    else -> null
                }
            }
            is com.github.javaparser.ast.expr.EnclosedExpr ->
                fullType(pass, source, expression.inner, depth + 1)
            else -> null
        }
    }

    private fun lambdaParameterType(
        pass: StructuralRefactorPass,
        source: ParsedSource,
        lambda: LambdaExpr,
        parameterIndex: Int,
        depth: Int
    ): TypeShape? {
        val parameter = lambda.parameters[parameterIndex]
        if (!parameter.type.isUnknownType) return parseTypeShape(source, parameter.type.toString())
        var argument: Node = lambda
        while (argument.parentNode.orElse(null) is com.github.javaparser.ast.expr.EnclosedExpr) {
            argument = argument.parentNode.get()
        }
        val call = argument.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (call.arguments.indexOf(argument) < 0 || parameterIndex != 0 ||
            call.nameAsString !in setOf("forEach", "map", "filter", "anyMatch")
        ) {
            return null
        }
        val receiver = call.scope.orElse(null) ?: return null
        return fullType(pass, source, receiver, depth + 1)
            ?.arguments?.singleOrNull()
    }

    private fun projectFieldType(
        pass: StructuralRefactorPass,
        source: ParsedSource,
        name: String,
        at: Node
    ): TypeShape? {
        val owner = at.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return null
        var canonical = canonicalType(source, owner)
        val seen = mutableSetOf<String>()
        while (seen.add(canonical)) {
            pass.projectValueFacts.fields[canonical]?.get(name)
                ?.let { return parseCanonicalTypeShape(it) }
            canonical = pass.projectValueFacts.parents[canonical] ?: break
        }
        return null
    }

    private fun constructorForwardedFieldType(
        source: ParsedSource,
        name: String,
        at: Node
    ): TypeShape? {
        val owner = at.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return null
        val types = owner.constructors.mapNotNull { constructor ->
            val parameter = constructor.parameters.singleOrNull {
                it.nameAsString == name
            } ?: return@mapNotNull null
            val forwards = constructor.body.findAll(
                com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt::class.java
            ).any { invocation ->
                !invocation.isThis &&
                    invocation.arguments.any { it.toString() == name }
            }
            if (forwards) parseTypeShape(source, parameter.type.toString()) else null
        }.distinct()
        return types.singleOrNull()
    }

    private fun isActiveScreenRoot(
        source: ParsedSource,
        owner: ClassOrInterfaceDeclaration
    ): Boolean {
        if (owner.extendedTypes.none { it.nameWithScope.contains("Screen") }) return false
        val hasLevel = owner.findAll(FieldAccessExpr::class.java).any {
            it.nameAsString == "level" && it.scope.toString() == "minecraft"
        }
        val passesLevelToAttributes = owner.findAll(MethodCallExpr::class.java).any { call ->
            call.nameAsString == "getAllAttributes" &&
                call.arguments.any { it.toString() == "minecraft.level" }
        }
        return hasLevel && passesLevelToAttributes
    }

    private fun migrateInterface(plan: InterfacePlan) {
        val provider = uniqueName(
            "registries",
            (
                plan.format.findAll(Parameter::class.java).map { it.nameAsString } +
                    plan.format.findAll(VariableDeclarator::class.java).map { it.nameAsString } +
                    plan.translationParameters.findAll(Parameter::class.java)
                        .map { it.nameAsString } +
                    plan.translationParameters.findAll(VariableDeclarator::class.java)
                        .map { it.nameAsString }
                ).toSet()
        )
        val providerFormat = detachedMethod(plan.format)
        providerFormat.addParameter("HolderLookup.Provider", provider)
        providerFormat.parameters.last().addAnnotation("Nullable")
        providerFormat.findAll(MethodCallExpr::class.java)
            .single {
                it.nameAsString == "getTranslationParameters" &&
                    it.arguments.isEmpty()
            }
            .addArgument(provider)
        val inverted = plan.format.parameters.single().nameAsString
        providerFormat.body.orElseThrow().addStatement(
            0,
            parseBlock(
                """
                {
                    if ($provider == null)
                        return format($inverted);
                }
                """.trimIndent()
            ).statements.single().clone()
        )

        val providerParameters = detachedMethod(plan.translationParameters)
        providerParameters.addParameter("HolderLookup.Provider", provider)
        providerParameters.parameters.last().addAnnotation("Nullable")
        providerParameters.setBody(
            parseBlock(
                """
                {
                    return getTranslationParameters();
                }
                """.trimIndent()
            )
        )

        val formatIndex = plan.owner.members.indexOf(plan.format)
        plan.owner.members.add(formatIndex + 1, providerFormat)
        val parametersIndex = plan.owner.members.indexOf(plan.translationParameters)
        plan.owner.members.add(parametersIndex + 1, providerParameters)
    }

    private fun migrateRawAttribute(plan: RawAttributePlan) {
        val keyDeclaration = parseMember(
            """
            private @Nullable ResourceKey<Enchantment> ${plan.keyField};
            """.trimIndent()
        ) as FieldDeclaration
        val directDeclaration = parseMember(
            """
            private @Nullable Holder<Enchantment> ${plan.directField};
            """.trimIndent()
        ) as FieldDeclaration
        val fieldIndex = plan.owner.members.indexOf(plan.rawField)
        plan.owner.members[fieldIndex] = keyDeclaration
        plan.owner.members.add(fieldIndex + 1, directDeclaration)

        val holderParameter = plan.constructor.parameters.single()
        holderParameter.setType("Holder<Enchantment>")
        val holderName = holderParameter.nameAsString
        plan.constructor.setBody(
            parseBlock(
                """
                {
                    this.${plan.keyField} = $holderName == null
                        ? null
                        : $holderName.unwrapKey().orElse(null);
                    this.${plan.directField} =
                        $holderName != null && this.${plan.keyField} == null
                            ? $holderName
                            : null;
                }
                """.trimIndent()
            )
        )

        val className = plan.owner.nameAsString
        val idName = uniqueName(
            "modporterEnchantmentId",
            plan.owner.findAll(VariableDeclarator::class.java)
                .map { it.nameAsString }
                .toSet()
        )
        val extraConstructors = parseMembers(
            """
            public $className() {
                this((ResourceLocation) null);
            }

            public $className(@Nullable ResourceLocation $idName) {
                this.${plan.keyField} = $idName == null
                    ? null
                    : ResourceKey.create(Registries.ENCHANTMENT, $idName);
                this.${plan.directField} = null;
            }
            """.trimIndent(),
            className
        )
        var constructorIndex = plan.owner.members.indexOf(plan.constructor)
        extraConstructors.forEach { member ->
            plan.owner.members.add(++constructorIndex, member)
        }

        plan.appliesTo.setBody(
            parseBlock(
                """
                {
                    return EnchantmentHelper.getEnchantmentsForCrafting(${plan.stackParameter})
                        .keySet()
                        .stream()
                        .anyMatch(candidate -> this.${plan.keyField} != null
                            ? candidate.is(this.${plan.keyField})
                            : this.${plan.directField} != null
                                && Objects.equals(
                                    candidate.value(),
                                    this.${plan.directField}.value()));
                }
                """.trimIndent()
            )
        )

        plan.translationParameters.setBody(
            parseBlock(
                """
                {
                    String parameter = "";
                    if (this.${plan.directField} != null)
                        parameter = this.${plan.directField}.value().description().getString();
                    return new Object[]{parameter};
                }
                """.trimIndent()
            )
        )
        val providerMethod = detachedMethod(plan.translationParameters)
        val providerName = uniqueName(
            "registries",
            (
                providerMethod.parameters.map { it.nameAsString } +
                    providerMethod.findAll(VariableDeclarator::class.java)
                        .map { it.nameAsString }
                ).toSet()
        )
        providerMethod.addParameter("HolderLookup.Provider", providerName)
        providerMethod.parameters.last().addAnnotation("Nullable")
        providerMethod.setBody(
            parseBlock(
                """
                {
                    if ($providerName == null)
                        return getTranslationParameters();
                    String parameter = "";
                    if (this.${plan.keyField} != null) {
                        parameter = $providerName.lookup(Registries.ENCHANTMENT)
                            .flatMap(lookup -> lookup.get(this.${plan.keyField}))
                            .map(holder -> holder.value().description().getString())
                            .orElse("");
                    } else if (this.${plan.directField} != null) {
                        parameter = this.${plan.directField}.value().description().getString();
                    }
                    return new Object[]{parameter};
                }
                """.trimIndent()
            )
        )
        val translationIndex = plan.owner.members.indexOf(plan.translationParameters)
        plan.owner.members.add(translationIndex + 1, providerMethod)

        val saveTag = plan.save.parameters.single().nameAsString
        plan.save.setBody(
            parseBlock(
                """
                {
                    if (this.${plan.keyField} == null)
                        return;
                    net.createmod.catnip.nbt.NBTHelper.writeResourceLocation(
                        $saveTag,
                        "$ATTRIBUTE_KEY",
                        this.${plan.keyField}.location());
                }
                """.trimIndent()
            )
        )
        val loadTag = plan.load.parameters.single().nameAsString
        val loadId = uniqueName(
            idName,
            (
                plan.load.parameters.map { it.nameAsString } +
                    plan.load.findAll(VariableDeclarator::class.java)
                        .map { it.nameAsString }
                ).toSet()
        )
        plan.load.setBody(
            parseBlock(
                """
                {
                    if ($loadTag.contains("$ATTRIBUTE_KEY")) {
                        ResourceLocation $loadId =
                            net.createmod.catnip.nbt.NBTHelper.readResourceLocation(
                                $loadTag,
                                "$ATTRIBUTE_KEY");
                        this.${plan.keyField} = $loadId == null
                            ? null
                            : ResourceKey.create(Registries.ENCHANTMENT, $loadId);
                        this.${plan.directField} = null;
                    }
                }
                """.trimIndent()
            )
        )
    }

    private fun migrateConstructions(plans: List<ConstructionPlan>) {
        plans.forEach { plan ->
            when (plan.kind) {
                ConstructionKind.BLANK -> plan.creation.arguments.clear()
                ConstructionKind.HOLDER, ConstructionKind.LEGACY_ID -> {
                    plan.creation.setArgument(0, plan.replacement!!.clone())
                }
            }
        }
        plans.mapNotNull { it.removableDeclaration }
            .distinct()
            .forEach(Node::remove)
    }

    private fun migrateStringDisplay(plan: StringDisplayPlan) {
        val provider = uniqueName(
            "registries",
            (
                plan.method.parameters.map { it.nameAsString } +
                    plan.method.findAll(VariableDeclarator::class.java)
                        .map { it.nameAsString }
                ).toSet()
        )
        val providerMethod = detachedMethod(plan.method)
        providerMethod.addParameter("HolderLookup.Provider", provider)
        providerMethod.parameters.last().addAnnotation("Nullable")
        rewriteStringDisplayMethod(plan, providerMethod, provider)
        providerMethod.body.orElseThrow().addStatement(
            0,
            parseBlock(
                """
                {
                    if ($provider == null)
                        return getTranslationParameters();
                }
                """.trimIndent()
            ).statements.single().clone()
        )
        rewriteStringDisplayMethod(plan, plan.method, null)
        val methodIndex = plan.owner.members.indexOf(plan.method)
        plan.owner.members.add(methodIndex + 1, providerMethod)
    }

    private fun rewriteStringDisplayMethod(
        plan: StringDisplayPlan,
        method: MethodDeclaration,
        provider: String?
    ) {
        val declaration = method.findAll(VariableDeclarator::class.java)
            .single {
                it.nameAsString == plan.enchantmentVariable &&
                    qualifyRaw(plan.source, it.type.toString()) == ENCHANTMENT
            }
        val declarationStatement = declaration.parentNode.get()
            .parentNode.get() as ExpressionStmt
        val guard = method.findAll(IfStmt::class.java)
            .single {
                it.condition.toString().replace(" ", "") in setOf(
                    "${plan.enchantmentVariable}!=null",
                    "null!=${plan.enchantmentVariable}"
                )
            }
        declarationStatement.remove()
        if (provider == null) {
            guard.remove()
            return
        }
        val names = method.findAll(VariableDeclarator::class.java)
            .map { it.nameAsString }
            .toMutableSet()
        names += method.parameters.map { it.nameAsString }
        val idName = uniqueName("modporterEnchantmentId", names)
        names += idName
        val lookupName = uniqueName("modporterEnchantmentLookup", names)
        names += lookupName
        val holderName = uniqueName("modporterEnchantmentHolder", names)
        guard.replace(
            parseBlock(
                """
                {
                    ResourceLocation $idName = ${plan.idExpression};
                    if ($idName != null) {
                        ${plan.displayVariable} = $provider.lookup(Registries.ENCHANTMENT)
                            .flatMap($lookupName -> $lookupName.get(
                                ResourceKey.create(Registries.ENCHANTMENT, $idName)))
                            .map($holderName ->
                                $holderName.value().description().getString())
                            .orElse("");
                    }
                }
                """.trimIndent()
            )
        )
    }

    private fun isEnchantmentHolderLoop(
        loop: ForEachStmt,
        source: ParsedSource
    ): Boolean {
        val variable = loop.variable.variables.singleOrNull() ?: return false
        val type = variable.type
        if (!type.isClassOrInterfaceType) return false
        val holderType = type.asClassOrInterfaceType()
        if (qualifyRaw(source, holderType.nameWithScope) != HOLDER) return false
        val argument = holderType.typeArguments.orElse(null)?.singleOrNull() ?: return false
        if (qualifyRaw(source, argument.toString()) != ENCHANTMENT) return false
        val keySet = loop.iterable as? MethodCallExpr ?: return false
        if (keySet.nameAsString != "keySet" ||
            keySet.arguments.isNotEmpty() ||
            keySet.scope.isEmpty
        ) {
            return false
        }
        val enchantments = keySet.scope.get() as? MethodCallExpr ?: return false
        return enchantments.nameAsString == "getEnchantmentsForCrafting" &&
            enchantments.arguments.size == 1 &&
            enchantments.scope.map {
                source.typing.isTypeName(it.toString(), ENCHANTMENT_HELPER)
            }.orElse(false)
    }

    private fun isBuiltInEnchantment(
        expression: Expression?,
        source: ParsedSource
    ): Boolean {
        val field = expression as? FieldAccessExpr ?: return false
        return field.nameAsString == "ENCHANTMENT" &&
            source.typing.isTypeName(field.scope.toString(), BUILT_IN_REGISTRIES)
    }

    private fun isFieldReference(expression: Expression, name: String): Boolean =
        expression is NameExpr && expression.nameAsString == name ||
            expression is FieldAccessExpr &&
            expression.nameAsString == name &&
            expression.scope is ThisExpr

    private fun referencesField(node: Node, name: String): Boolean =
        node.findAll(NameExpr::class.java).any { it.nameAsString == name } ||
            node.findAll(FieldAccessExpr::class.java).any {
                it.nameAsString == name && it.scope is ThisExpr
            }

    private fun statementCount(statement: com.github.javaparser.ast.stmt.Statement): Int =
        if (statement is BlockStmt) statement.statements.size else 1

    private fun nearestCallable(node: Node): Node? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is MethodDeclaration || current is ConstructorDeclaration) return current
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun isNestedInside(
        candidate: ClassOrInterfaceDeclaration?,
        owner: ClassOrInterfaceDeclaration
    ): Boolean {
        var current: Node? = candidate
        while (current != null) {
            if (current === owner) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun isDescendantOf(node: Node, ancestor: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun canonicalType(
        source: ParsedSource,
        owner: ClassOrInterfaceDeclaration
    ): String {
        val owners = mutableListOf<ClassOrInterfaceDeclaration>()
        var current: ClassOrInterfaceDeclaration? = owner
        while (current != null) {
            owners += current
            val parent = current.parentNode.orElse(null)
            current = when (parent) {
                is ClassOrInterfaceDeclaration -> parent
                null -> null
                else -> parent.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .orElse(null)
            }
        }
        val names = owners.asReversed().joinToString(".") { it.nameAsString }
        val pkg = source.unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (pkg.isBlank()) names else "$pkg.$names"
    }

    private fun qualifyRaw(source: ParsedSource, text: String): String? {
        var name = text.trim()
            .replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "")
            .substringBefore('<')
            .removeSuffix("[]")
            .trim()
        if (name in setOf(
                "boolean", "byte", "char", "double", "float",
                "int", "long", "short", "void"
            )
        ) {
            return name
        }
        if (name in setOf(
                "String", "Object", "Integer", "Boolean", "Long",
                "Double", "Float", "Void"
            )
        ) {
            return "java.lang.$name"
        }
        val first = name.substringBefore('.')
        source.unit.imports.firstOrNull {
            !it.isStatic && !it.isAsterisk && it.name.identifier == first
        }?.let {
            return it.nameAsString + name.removePrefix(first)
        }
        if (first.firstOrNull()?.isLowerCase() == true) return name
        val pkg = source.unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (pkg.isBlank()) name else "$pkg.$name"
    }

    private fun parseTypeShape(source: ParsedSource, text: String): TypeShape? {
        val value = text.trim()
        val open = value.indexOf('<')
        val raw = qualifyRaw(source, if (open < 0) value else value.substring(0, open))
            ?: return null
        if (open < 0) return TypeShape(raw)
        val close = value.lastIndexOf('>')
        if (close < open) return null
        val arguments = JavaSourceText.splitTopLevelArgs(value.substring(open + 1, close))
            .map { argument ->
                val clean = argument.removePrefix("? extends ")
                    .removePrefix("? super ")
                    .trim()
                parseTypeShape(source, clean) ?: return null
            }
        return TypeShape(raw, arguments)
    }

    private fun parseCanonicalTypeShape(text: String): TypeShape? {
        val value = text.trim()
        val open = value.indexOf('<')
        val raw = if (open < 0) value else value.substring(0, open)
        if (open < 0) return TypeShape(raw)
        val close = value.lastIndexOf('>')
        if (close < open) return null
        val arguments = JavaSourceText.splitTopLevelArgs(value.substring(open + 1, close))
            .map { argument ->
                parseCanonicalTypeShape(
                    argument.removePrefix("? extends ")
                        .removePrefix("? super ")
                        .trim()
                ) ?: return null
            }
        return TypeShape(raw, arguments)
    }

    private fun uniqueName(stem: String, occupied: Set<String>): String {
        var candidate = stem
        while (candidate in occupied) candidate += "_"
        return candidate
    }

    private fun parseExpression(text: String): Expression =
        parser.parseExpression<Expression>(text).result.orElseThrow()

    private fun parseBlock(text: String): BlockStmt =
        parser.parseBlock(text).result.orElseThrow()

    private fun parseMember(text: String): BodyDeclaration<*> =
        parseMembers(text).single()

    private fun detachedMethod(method: MethodDeclaration): MethodDeclaration {
        val containerName = "ModporterDetachedMethod"
        val containerKind = if (method.isDefault) "interface" else "class"
        val unit = parser.parse(
            "$containerKind $containerName {\n${method}\n}"
        ).result.orElseThrow()
        val container = unit.types.single() as ClassOrInterfaceDeclaration
        check(container.nameAsString == containerName)
        return container.members.single().clone() as MethodDeclaration
    }

    private fun parseMembers(
        text: String,
        containerName: String = "ModporterMembers"
    ): List<BodyDeclaration<*>> {
        val unit = parser.parse("class $containerName {\n$text\n}")
            .result.orElseThrow()
        return unit.getClassByName(containerName).orElseThrow()
            .members.map { it.clone() }
    }

    private fun firstChangedLine(before: String, after: String): Int {
        val beforeLines = before.lineSequence().toList()
        val afterLines = after.lineSequence().toList()
        val common = minOf(beforeLines.size, afterLines.size)
        for (index in 0 until common) {
            if (beforeLines[index] != afterLines[index]) return index + 1
        }
        return common + 1
    }
}

internal fun StructuralRefactorPass.migrateProjectKeyBackedDynamicAttributes(
    projectDir: Path,
    dryRun: Boolean
): List<Change> = KeyBackedDynamicAttributes.migrate(this, projectDir, dryRun)
