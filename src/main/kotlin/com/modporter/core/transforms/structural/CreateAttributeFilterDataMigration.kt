package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Preserves Create's mutable ItemAttribute loader contract while moving filter data to components.
 *
 * ItemAttribute.loadStatic is runtime-extensible and may mutate the supplied nested CompoundTag.
 * These exact source-owned readers therefore borrow a child from one detached root snapshot and
 * publish that root in a finally block after every loader call. The current source implementations
 * must prove that their CompoundTag parameter is read or mutated without escaping; this is a source
 * proof, not a closed-world claim about future addon registrations.
 */
internal object CreateAttributeFilterDataMigration {
    const val RULE_ID = "struct-create-attribute-filter-data"

    private const val ITEM_RELATIVE =
        "src/main/java/com/simibubi/create/content/logistics/filter/AttributeFilterItem.java"
    private const val MENU_RELATIVE =
        "src/main/java/com/simibubi/create/content/logistics/filter/AttributeFilterMenu.java"
    private const val ATTRIBUTE_RELATIVE =
        "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/ItemAttribute.java"
    private const val LEGACY_RELATIVE =
        "src/main/java/com/simibubi/create/content/logistics/item/filter/attribute/" +
            "legacydeserializers/AllItemAttributeLegacyDeserializers.java"

    private const val ITEM_OWNER =
        "com.simibubi.create.content.logistics.filter.AttributeFilterItem"
    private const val MENU_OWNER =
        "com.simibubi.create.content.logistics.filter.AttributeFilterMenu"
    private const val ATTRIBUTE_OWNER =
        "com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute"
    private const val LEGACY_ADAPTER_OWNER =
        "com.simibubi.create.content.logistics.item.filter.attribute.legacydeserializers." +
            "AllItemAttributeLegacyDeserializers"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    private const val LEGACY_DESERIALIZER_OWNER =
        "com.simibubi.create.content.logistics.item.filter.attribute.ItemAttribute.LegacyDeserializer"
    private const val ITEM_ATTRIBUTE_TYPE =
        "com.simibubi.create.content.logistics.item.filter.attribute.ItemAttributeType"
    private const val FUNCTION = "java.util.function.Function"
    private const val HELPER = "modporterLoadStaticAndCommit"

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
        migrateChecked(projectDir.toAbsolutePath().normalize(), dryRun, commitProbe)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "AttributeFilter data admission failed"))
    } catch (failure: Throwable) {
        val rollback = failure.suppressed.map { it.message ?: it::class.java.simpleName }
        val suffix = if (rollback.isEmpty()) "" else "; rollback failures: ${rollback.joinToString()}"
        Result(
            emptyList(),
            listOf(
                "AttributeFilter data migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName) + suffix
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)?
    ): Result {
        val itemFile = projectDir.resolve(ITEM_RELATIVE)
        if (!itemFile.isRegularFile()) return Result.EMPTY
        val menuFile = requireFile(projectDir, MENU_RELATIVE)
        val attributeFile = requireFile(projectDir, ATTRIBUTE_RELATIVE)
        val legacyFile = requireFile(projectDir, LEGACY_RELATIVE)
        val sourceRoot = projectDir.resolve("src/main/java")

        val index = JavaProjectTypeIndex.build(sourceRoot)
        val itemUnit = index.unit(itemFile)
        val menuUnit = index.unit(menuFile)
        val itemOwner = exactOwner(itemUnit, ITEM_OWNER)
        val menuOwner = exactOwner(menuUnit, MENU_OWNER)
        val makeSummary = exactMethod(itemOwner, "makeSummary", ITEM_STACK, index)
        val getFilterItems = exactMethod(itemOwner, "getFilterItems", ITEM_STACK, index)
        val initAndReadInventory = exactMethod(menuOwner, "initAndReadInventory", ITEM_STACK, index)

        val legacyShape =
            bodyEquals(makeSummary, legacyMakeSummary()) &&
                bodyEquals(getFilterItems, legacyGetFilterItems()) &&
                bodyEquals(initAndReadInventory, legacyInitAndReadInventory()) &&
                itemOwner.methods.none { it.nameAsString == HELPER } &&
                menuOwner.methods.none { it.nameAsString == HELPER }
        val migratedShape =
            bodyEquals(makeSummary, migratedMakeSummary()) &&
                bodyEquals(getFilterItems, migratedGetFilterItems()) &&
                bodyEquals(initAndReadInventory, migratedInitAndReadInventory()) &&
                exactHelper(itemOwner, index) &&
                exactHelper(menuOwner, index)
        if (!legacyShape && !migratedShape) {
            abort("AttributeFilter readers are outside the exact source-owned borrow family")
        }

        proveLoaderGraph(sourceRoot, index, attributeFile, legacyFile)
        if (migratedShape) return Result.EMPTY

        val itemBefore = itemFile.readText()
        val menuBefore = menuFile.readText()
        val itemAfter = rewriteSource(
            itemBefore,
            itemOwner,
            listOf(
                makeSummary to migratedMakeSummary(),
                getFilterItems to migratedGetFilterItems()
            )
        )
        val menuAfter = rewriteSource(
            menuBefore,
            menuOwner,
            listOf(initAndReadInventory to migratedInitAndReadInventory())
        )
        validateMigratedOutput(itemAfter, ITEM_OWNER)
        validateMigratedOutput(menuAfter, MENU_OWNER)
        val outputs = listOf(
            PreparedOutput(
                itemFile,
                itemBefore,
                itemAfter,
                "Borrow AttributeFilterItem nested data and commit every loader effect"
            ),
            PreparedOutput(
                menuFile,
                menuBefore,
                menuAfter,
                "Borrow AttributeFilterMenu nested data and commit every loader effect"
            )
        )
        if (!dryRun) writeFamily(outputs, commitProbe)
        return Result(
            outputs.map { output ->
                Change(
                    file = output.path,
                    line = 1,
                    description = output.description,
                    before = "live ItemStack tag view passed through an extensible attribute loader",
                    after = "root CustomData snapshot committed in finally after every loader call",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun proveLoaderGraph(
        sourceRoot: Path,
        index: JavaProjectTypeIndex,
        attributeFile: Path,
        legacyFile: Path
    ) {
        val effects = ExactProjectTagEffects(sourceRoot, index)
        val attributeUnit = index.unit(attributeFile)
        val attributeOwner = exactOwner(attributeUnit, ATTRIBUTE_OWNER)
        val loadStatic = attributeOwner.methods.singleOrNull { method ->
            method.nameAsString == "loadStatic" && method.isStatic && method.parameters.size == 1 &&
                index.declaredType(method.parameters.single().type, method) == COMPOUND_TAG
        } ?: abort("ItemAttribute has no exact loadStatic(CompoundTag) source body")
        if (!bodyEquals(loadStatic, loadStaticBody())) {
            abort("ItemAttribute.loadStatic is outside the admitted runtime-extensible dispatch shape")
        }

        val loadContract = attributeOwner.methods.singleOrNull { method ->
            method.nameAsString == "load" && !method.isStatic && method.parameters.size == 1 &&
                index.declaredType(method.parameters.single().type, method) == COMPOUND_TAG
        } ?: abort("ItemAttribute has no exact load(CompoundTag) contract")
        proveNamedOverrideFamily("ItemAttribute.load", loadContract, index, effects)

        val legacyOwner = attributeOwner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull { it.nameAsString == "LegacyDeserializer" && it.isInterface }
            ?: abort("ItemAttribute has no exact LegacyDeserializer interface")
        val canReadContract = legacyOwner.methods.singleOrNull { method ->
            method.nameAsString == "canRead" && method.parameters.size == 1 &&
                index.declaredType(method.parameters.single().type, method) == COMPOUND_TAG
        } ?: abort("LegacyDeserializer has no exact canRead(CompoundTag) contract")
        proveNamedOverrideFamily("LegacyDeserializer.canRead", canReadContract, index, effects)
        val readContract = legacyOwner.methods.singleOrNull { method ->
            method.nameAsString == "readNBT" && method.parameters.size == 1 &&
                index.declaredType(method.parameters.single().type, method) == COMPOUND_TAG
        } ?: abort("LegacyDeserializer has no exact readNBT(CompoundTag) contract")
        val readFamily = proveNamedOverrideFamily(
            "LegacyDeserializer.readNBT",
            readContract,
            index,
            effects
        )
        if (readFamily.none { it.owner.endsWith(".StandardTraitsLegacyDeserializer") }) {
            abort("LegacyDeserializer source family is missing StandardTraitsLegacyDeserializer")
        }

        val admittedLegacyAdapter = proveAnonymousLegacyAdapter(index.unit(legacyFile), index)
        proveAnonymousSourceImplementations(sourceRoot, index, admittedLegacyAdapter)
    }

    private fun proveNamedOverrideFamily(
        label: String,
        contract: MethodDeclaration,
        index: JavaProjectTypeIndex,
        effects: ExactProjectTagEffects
    ): List<JavaProjectTypeIndex.ExactProjectMethod> {
        val family = index.exactProjectOverrideFamily(contract) { root -> root === contract }
            ?: abort("$label source override family is ambiguous or open")
        val implementations = family.filter { member -> member.method !== contract }
        if (implementations.isEmpty() && contract.body.isEmpty) {
            abort("$label has no concrete source implementation")
        }
        family.forEach { member ->
            val method = member.method
            if (method.body.isEmpty) {
                if (!method.isAbstract && method !== contract) {
                    abort("$label has a bodyless non-abstract source implementation: ${member.owner}")
                }
                return@forEach
            }
            val effect = effects.sourceMethodParameterEffect(method, 0)
                ?: abort("$label retains or has an unknown CompoundTag effect: ${member.owner}")
            if (effect !in setOf(
                    ExactExternalTagContracts.Effect.READ,
                    ExactExternalTagContracts.Effect.MUTATE
                )
            ) {
                abort("$label has an unsupported CompoundTag effect: ${member.owner}")
            }
        }
        return family
    }

    private fun proveAnonymousLegacyAdapter(
        unit: CompilationUnit,
        index: JavaProjectTypeIndex
    ): ObjectCreationExpr {
        val owner = exactOwner(unit, LEGACY_ADAPTER_OWNER)
        val register = owner.methods.singleOrNull {
            it.nameAsString == "register" && it.parameters.isEmpty() && it.isPublic && it.isStatic
        } ?: abort("Legacy adapter has no unique public static register() method")
        if (!bodyEquals(register, legacyRegisterBody())) {
            abort("Legacy adapter register() call graph is outside the exact source-owned family")
        }
        val overloads = owner.methods.filter {
            it.nameAsString == "createLegacyDeserializer" && it.parameters.size == 2 &&
                it.isPrivate && it.isStatic
        }
        val typeOverload = overloads.singleOrNull {
            index.declaredRawType(it.parameters[1].type, it) == ITEM_ATTRIBUTE_TYPE
        } ?: abort("Legacy adapter has no exact private static ItemAttributeType overload")
        val functionOverload = overloads.singleOrNull {
            index.declaredRawType(it.parameters[1].type, it) == FUNCTION
        } ?: abort("Legacy adapter has no exact private static Function overload")
        if (!bodyEquals(typeOverload, legacyTypeAdapterBody()) ||
            !bodyEquals(functionOverload, legacyFunctionAdapterBody())
        ) {
            abort("Legacy adapter may retain a CompoundTag or dispatch an unknown callback")
        }
        val add = owner.methods.singleOrNull {
            it.nameAsString == "addLegacyDeserializer" && it.parameters.size == 1 &&
                it.isPrivate && it.isStatic
        } ?: abort("Legacy adapter has no exact private static addLegacyDeserializer method")
        if (!bodyEquals(add, legacyAddBody())) {
            abort("Legacy adapter registration ownership changed")
        }

        provePrivateCallSites(owner, "createLegacyDeserializer", listOf(register, typeOverload))
        provePrivateCallSites(owner, "addLegacyDeserializer", listOf(register, functionOverload))
        return functionOverload.findAll(ObjectCreationExpr::class.java).singleOrNull { creation ->
            creation.anonymousClassBody.isPresent &&
                index.declaredRawType(
                    creation.type,
                    creation.parentNode.orElseThrow()
                ) == LEGACY_DESERIALIZER_OWNER
        } ?: abort("Legacy adapter has no exact anonymous LegacyDeserializer implementation")
    }

    private fun provePrivateCallSites(
        owner: ClassOrInterfaceDeclaration,
        methodName: String,
        admittedOwners: List<MethodDeclaration>
    ) {
        val admitted = admittedOwners.flatMap { method ->
            method.findAll(MethodCallExpr::class.java).filter { it.nameAsString == methodName }
        }
        val observed = owner.findAll(MethodCallExpr::class.java).filter { it.nameAsString == methodName }
        if (observed.size != admitted.size || observed.any { call -> admitted.none { it === call } }) {
            abort("Legacy adapter has an unproved $methodName call site")
        }
    }

    private fun proveAnonymousSourceImplementations(
        sourceRoot: Path,
        index: JavaProjectTypeIndex,
        admittedLegacyAdapter: ObjectCreationExpr
    ) {
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .forEach { file ->
                    val unit = index.unit(file)
                    unit.findAll(ObjectCreationExpr::class.java)
                        .filter { it.anonymousClassBody.isPresent }
                        .forEach creationLoop@{ creation ->
                            if (creation === admittedLegacyAdapter) return@creationLoop
                            val use = creation.parentNode.orElseThrow()
                            val rawType = index.declaredRawType(creation.type, use)
                                ?: return@creationLoop
                            if (index.isTypeAssignableTo(rawType, LEGACY_DESERIALIZER_OWNER)) {
                                abort(
                                    "Unproved anonymous LegacyDeserializer implementation in " +
                                        sourceRoot.relativize(file)
                                )
                            }
                            if (index.isTypeAssignableTo(rawType, ATTRIBUTE_OWNER)) {
                                abort(
                                    "Unproved anonymous ItemAttribute implementation in " +
                                        sourceRoot.relativize(file)
                                )
                            }
                        }
                    unit.findAll(EnumConstantDeclaration::class.java)
                        .filter { it.classBody.isNotEmpty() }
                        .forEach { constant ->
                            val owner = constant.findAncestor(EnumDeclaration::class.java).orElse(null)
                                ?: abort("Enum constant implementation has no source owner")
                            val ownerName = owner.fullyQualifiedName.orElse(null)
                                ?: abort("Enum constant implementation has no qualified source owner")
                            if (index.isTypeAssignableTo(ownerName, LEGACY_DESERIALIZER_OWNER) ||
                                index.isTypeAssignableTo(ownerName, ATTRIBUTE_OWNER)
                            ) {
                                abort(
                                    "Unproved enum-constant attribute implementation: " +
                                        "$ownerName.${constant.nameAsString}"
                                )
                            }
                        }
                }
        }
    }

    private fun exactOwner(unit: CompilationUnit, qualifiedName: String): ClassOrInterfaceDeclaration =
        unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull {
            it.fullyQualifiedName.orElse(null) == qualifiedName
        } ?: abort("Missing exact source owner $qualifiedName")

    private fun exactMethod(
        owner: ClassOrInterfaceDeclaration,
        name: String,
        parameterType: String,
        index: JavaProjectTypeIndex
    ): MethodDeclaration = owner.methods.singleOrNull { method ->
        method.nameAsString == name && method.parameters.size == 1 &&
            index.declaredType(method.parameters.single().type, method) == parameterType
    } ?: abort("${owner.nameAsString} has no unique $name($parameterType) method")

    private fun exactHelper(
        owner: ClassOrInterfaceDeclaration,
        index: JavaProjectTypeIndex
    ): Boolean {
        val helpers = owner.methods.filter { it.nameAsString == HELPER }
        val helper = helpers.singleOrNull() ?: return false
        if (!helper.isPrivate || !helper.isStatic || helper.parameters.size != 3) return false
        val types = helper.parameters.map { index.declaredType(it.type, helper) }
        return types == listOf(ITEM_STACK, COMPOUND_TAG, COMPOUND_TAG) &&
            bodyEquals(helper, helperBody())
    }

    private fun parser(): JavaParser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )

    private fun parseBlock(source: String): BlockStmt {
        val parsed = parser().parseBlock(source)
        if (!parsed.isSuccessful || parsed.result.isEmpty) {
            abort("AttributeFilter expected block is invalid: ${parsed.problems.joinToString()}")
        }
        return parsed.result.orElseThrow()
    }

    private fun parseMethodDeclaration(source: String): MethodDeclaration {
        val parsed = parser().parseMethodDeclaration(source)
        if (!parsed.isSuccessful || parsed.result.isEmpty) {
            abort("AttributeFilter helper declaration is invalid: ${parsed.problems.joinToString()}")
        }
        return parsed.result.orElseThrow()
    }

    private fun validateMigratedOutput(source: String, ownerName: String) {
        val parsed = parser().parse(source)
        if (!parsed.isSuccessful || parsed.result.isEmpty) {
            abort("AttributeFilter migration emitted invalid Java for $ownerName")
        }
        val unit = parsed.result.orElseThrow()
        val owner = exactOwner(unit, ownerName)
        val expectedBodies = if (ownerName == ITEM_OWNER) {
            mapOf(
                "makeSummary" to migratedMakeSummary(),
                "getFilterItems" to migratedGetFilterItems()
            )
        } else {
            mapOf("initAndReadInventory" to migratedInitAndReadInventory())
        }
        expectedBodies.forEach { (name, body) ->
            val method = owner.methods.singleOrNull { it.nameAsString == name && it.parameters.size == 1 }
                ?: abort("Migrated $ownerName is missing $name")
            if (!bodyEquals(method, body)) {
                abort(
                    "Migrated $ownerName.$name changed after printing; actual=" +
                        method.body.orElseThrow() + "; expected=" + body
                )
            }
        }
        val helper = owner.methods.singleOrNull { it.nameAsString == HELPER }
            ?: abort("Migrated $ownerName is missing $HELPER")
        if (!bodyEquals(helper, helperBody())) abort("Migrated $ownerName helper changed after printing")
    }

    private fun bodyEquals(method: MethodDeclaration, expected: BlockStmt): Boolean =
        method.body.orElse(null) == expected

    private fun helperMethod(): MethodDeclaration =
        parseMethodDeclaration(
            """
            private static ItemAttribute $HELPER(ItemStack owner, CompoundTag root, CompoundTag child) {
                try {
                    return ItemAttribute.loadStatic(child);
                } finally {
                    if (root.isEmpty())
                        owner.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    else
                        owner.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(root));
                }
            }
            """.trimIndent()
        )

    private fun helperBody(): BlockStmt = helperMethod().body.orElseThrow()

    private fun rewriteSource(
        source: String,
        owner: ClassOrInterfaceDeclaration,
        replacements: List<Pair<MethodDeclaration, BlockStmt>>
    ): String {
        if (replacements.isEmpty()) abort("AttributeFilter source rewrite has no methods")
        val lineStarts = sourceLineStarts(source)
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val memberIndent = lineIndent(source, lineStarts, replacements.first().first)
        if (replacements.any { (method, _) ->
                lineIndent(source, lineStarts, method) != memberIndent
            }
        ) {
            abort("AttributeFilter methods do not share one source indentation")
        }
        val indentUnit = nestedIndent(source, lineStarts, replacements.first().first, owner)
        val edits = replacements.map { (method, replacement) ->
            val body = method.body.orElse(null)
                ?: abort("AttributeFilter ${method.nameAsString} has no body range")
            val bounds = sourceBounds(source, lineStarts, body, "${method.nameAsString} body")
            val original = source.substring(bounds.first, bounds.second)
            if (parseBlock(original) != body) {
                abort("AttributeFilter ${method.nameAsString} body range differs from source")
            }
            SourceEdit(
                bounds.first,
                bounds.second,
                reindentPretty(replacement.toString(), memberIndent, indentUnit, newline, false),
                "${method.nameAsString} body"
            )
        }.toMutableList()

        val ownerRange = owner.range.orElse(null)
            ?: abort("AttributeFilter owner ${owner.nameAsString} has no source range")
        val ownerClose = sourceOffset(source, lineStarts, ownerRange.end.line, ownerRange.end.column)
        if (ownerClose >= source.length || source[ownerClose] != '}') {
            abort("AttributeFilter owner ${owner.nameAsString} closing range is not a brace")
        }
        val ownerCloseLine = lineStarts[ownerRange.end.line - 1]
        if (source.substring(ownerCloseLine, ownerClose).any { it != ' ' && it != '\t' }) {
            abort("AttributeFilter owner ${owner.nameAsString} closing brace is not line-aligned")
        }
        val helper = reindentPretty(
            helperMethod().toString(),
            memberIndent,
            indentUnit,
            newline,
            true
        )
        edits += SourceEdit(
            ownerCloseLine,
            ownerCloseLine,
            newline + helper + newline,
            "$HELPER insertion"
        )
        return applySourceEdits(source, edits)
    }

    private fun nestedIndent(
        source: String,
        lineStarts: IntArray,
        method: MethodDeclaration,
        owner: ClassOrInterfaceDeclaration
    ): String {
        val methodIndent = lineIndent(source, lineStarts, method)
        val body = method.body.orElse(null)
            ?: abort("AttributeFilter ${method.nameAsString} has no body")
        val bodyRange = body.range.orElse(null)
            ?: abort("AttributeFilter ${method.nameAsString} body has no source range")
        for (line in (bodyRange.begin.line + 1) until bodyRange.end.line) {
            val content = sourceLine(source, lineStarts, line)
            if (content.isBlank()) continue
            val indent = content.takeWhile { it == ' ' || it == '\t' }
            if (indent.startsWith(methodIndent) && indent.length > methodIndent.length) {
                return indent.substring(methodIndent.length)
            }
        }
        val ownerRange = owner.range.orElse(null)
            ?: abort("AttributeFilter owner ${owner.nameAsString} has no source range")
        val ownerIndent = sourceLine(source, lineStarts, ownerRange.end.line)
            .takeWhile { it == ' ' || it == '\t' }
        if (methodIndent.startsWith(ownerIndent) && methodIndent.length > ownerIndent.length) {
            return methodIndent.substring(ownerIndent.length)
        }
        return if (source.lineSequence().any { it.startsWith("\t") }) "\t" else "    "
    }

    private fun lineIndent(
        source: String,
        lineStarts: IntArray,
        method: MethodDeclaration
    ): String {
        val range = method.range.orElse(null)
            ?: abort("AttributeFilter ${method.nameAsString} has no source range")
        return sourceLine(source, lineStarts, range.begin.line)
            .takeWhile { it == ' ' || it == '\t' }
    }

    private fun reindentPretty(
        pretty: String,
        baseIndent: String,
        indentUnit: String,
        newline: String,
        indentFirstLine: Boolean
    ): String = pretty.replace("\r\n", "\n").split('\n').mapIndexed { index, line ->
        val spaces = line.takeWhile { it == ' ' }.length
        val converted = indentUnit.repeat(spaces / 4) + " ".repeat(spaces % 4) +
            line.substring(spaces)
        if (index == 0 && !indentFirstLine) converted else baseIndent + converted
    }.joinToString(newline)

    private fun applySourceEdits(source: String, edits: List<SourceEdit>): String {
        val ordered = edits.sortedWith(compareBy<SourceEdit> { it.start }.thenBy { it.endExclusive })
        var previousEnd = 0
        ordered.forEach { edit ->
            if (edit.start < previousEnd || edit.start < 0 || edit.endExclusive > source.length ||
                edit.endExclusive < edit.start
            ) {
                abort("AttributeFilter source edit overlaps or is outside source: ${edit.label}")
            }
            previousEnd = edit.endExclusive
        }
        return StringBuilder(source).also { output ->
            ordered.asReversed().forEach { edit ->
                output.replace(edit.start, edit.endExclusive, edit.replacement)
            }
        }.toString()
    }

    private fun sourceBounds(
        source: String,
        lineStarts: IntArray,
        node: com.github.javaparser.ast.Node,
        label: String
    ): Pair<Int, Int> {
        val range = node.range.orElse(null) ?: abort("AttributeFilter $label has no source range")
        val start = sourceOffset(source, lineStarts, range.begin.line, range.begin.column)
        val end = sourceOffset(source, lineStarts, range.end.line, range.end.column) + 1
        if (start >= source.length || end > source.length || source[start] != '{' || source[end - 1] != '}') {
            abort("AttributeFilter $label range is not a complete block")
        }
        return start to end
    }

    private fun sourceLineStarts(source: String): IntArray {
        val starts = mutableListOf(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') starts += index + 1
        }
        return starts.toIntArray()
    }

    private fun sourceOffset(
        source: String,
        lineStarts: IntArray,
        line: Int,
        column: Int
    ): Int {
        if (line !in 1..lineStarts.size || column < 1) {
            abort("AttributeFilter source position is outside source: $line:$column")
        }
        val offset = lineStarts[line - 1] + column - 1
        if (offset !in 0..source.length) {
            abort("AttributeFilter source offset is outside source: $line:$column")
        }
        return offset
    }

    private fun sourceLine(source: String, lineStarts: IntArray, line: Int): String {
        if (line !in 1..lineStarts.size) abort("AttributeFilter source line is outside source: $line")
        val start = lineStarts[line - 1]
        val end = if (line < lineStarts.size) lineStarts[line] - 1 else source.length
        return source.substring(start, end).removeSuffix("\r")
    }

    private data class SourceEdit(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
        val label: String
    )

    private fun legacyMakeSummary(): BlockStmt = parseBlock(
        """
        {
            if (!filter.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA))
                return Collections.emptyList();
            List<Component> list = new ArrayList<>();
            WhitelistMode whitelistMode = WhitelistMode.values()[filter.getOrCreateTag().getInt("WhitelistMode")];
            list.add((whitelistMode == WhitelistMode.WHITELIST_CONJ ? CreateLang.translateDirect("gui.attribute_filter.allow_list_conjunctive") : whitelistMode == WhitelistMode.WHITELIST_DISJ ? CreateLang.translateDirect("gui.attribute_filter.allow_list_disjunctive") : CreateLang.translateDirect("gui.attribute_filter.deny_list")).withStyle(ChatFormatting.GOLD));
            int count = 0;
            ListTag attributes = filter.getOrCreateTag().getList("MatchedAttributes", Tag.TAG_COMPOUND);
            for (Tag inbt : attributes) {
                CompoundTag compound = (CompoundTag) inbt;
                ItemAttribute attribute = ItemAttribute.loadStatic(compound);
                if (attribute == null)
                    continue;
                boolean inverted = compound.getBoolean("Inverted");
                if (count > 3) {
                    list.add(Component.literal("- ...").withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                list.add(Component.literal("- ").append(attribute.format(inverted)));
                count++;
            }
            if (count == 0)
                return Collections.emptyList();
            return list;
        }
        """.trimIndent()
    )

    private fun migratedMakeSummary(): BlockStmt = parseBlock(
        """
        {
            if (!filter.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA))
                return Collections.emptyList();
            CompoundTag modporterRoot = filter.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            List<Component> list = new ArrayList<>();
            WhitelistMode whitelistMode = WhitelistMode.values()[modporterRoot.getInt("WhitelistMode")];
            list.add((whitelistMode == WhitelistMode.WHITELIST_CONJ ? CreateLang.translateDirect("gui.attribute_filter.allow_list_conjunctive") : whitelistMode == WhitelistMode.WHITELIST_DISJ ? CreateLang.translateDirect("gui.attribute_filter.allow_list_disjunctive") : CreateLang.translateDirect("gui.attribute_filter.deny_list")).withStyle(ChatFormatting.GOLD));
            int count = 0;
            ListTag attributes = modporterRoot.getList("MatchedAttributes", Tag.TAG_COMPOUND);
            for (Tag inbt : attributes) {
                CompoundTag compound = (CompoundTag) inbt;
                ItemAttribute attribute = $HELPER(filter, modporterRoot, compound);
                if (attribute == null)
                    continue;
                boolean inverted = compound.getBoolean("Inverted");
                if (count > 3) {
                    list.add(Component.literal("- ...").withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                list.add(Component.literal("- ").append(attribute.format(inverted)));
                count++;
            }
            if (count == 0)
                return Collections.emptyList();
            return list;
        }
        """.trimIndent()
    )

    private fun legacyGetFilterItems(): BlockStmt = parseBlock(
        """
        {
            CompoundTag tag = itemStack.getOrCreateTag();
            WhitelistMode whitelistMode = WhitelistMode.values()[tag.getInt("WhitelistMode")];
            ListTag attributes = tag.getList("MatchedAttributes", net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (whitelistMode == WhitelistMode.WHITELIST_DISJ && attributes.size() == 1) {
                ItemAttribute fromNBT = ItemAttribute.loadStatic((CompoundTag) attributes.get(0));
                if (fromNBT instanceof InTagAttribute inTag) {
                    if (BuiltInRegistries.ITEM.getTag(inTag.tag).isPresent()) {
                        java.util.List<Item> taggedItems = java.util.stream.StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(inTag.tag).spliterator(), false).map(net.minecraft.core.Holder::value).toList();
                        if (!taggedItems.isEmpty()) {
                            ItemStack[] stacks = new ItemStack[taggedItems.size()];
                            int i = 0;
                            for (Item item : taggedItems) {
                                stacks[i] = new ItemStack(item);
                                i++;
                            }
                            return stacks;
                        }
                    }
                }
            }
            return new ItemStack[0];
        }
        """.trimIndent()
    )

    private fun migratedGetFilterItems(): BlockStmt = parseBlock(
        """
        {
            CompoundTag tag = itemStack.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            WhitelistMode whitelistMode = WhitelistMode.values()[tag.getInt("WhitelistMode")];
            ListTag attributes = tag.getList("MatchedAttributes", net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (whitelistMode == WhitelistMode.WHITELIST_DISJ && attributes.size() == 1) {
                ItemAttribute fromNBT = $HELPER(itemStack, tag, (CompoundTag) attributes.get(0));
                if (fromNBT instanceof InTagAttribute inTag) {
                    if (BuiltInRegistries.ITEM.getTag(inTag.tag).isPresent()) {
                        java.util.List<Item> taggedItems = java.util.stream.StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(inTag.tag).spliterator(), false).map(net.minecraft.core.Holder::value).toList();
                        if (!taggedItems.isEmpty()) {
                            ItemStack[] stacks = new ItemStack[taggedItems.size()];
                            int i = 0;
                            for (Item item : taggedItems) {
                                stacks[i] = new ItemStack(item);
                                i++;
                            }
                            return stacks;
                        }
                    }
                }
            }
            return new ItemStack[0];
        }
        """.trimIndent()
    )

    private fun legacyInitAndReadInventory(): BlockStmt = parseBlock(
        """
        {
            super.initAndReadInventory(filterItem);
            selectedAttributes = new ArrayList<>();
            whitelistMode = WhitelistMode.values()[filterItem.getOrCreateTag()
                .getInt("WhitelistMode")];
            ListTag attributes = filterItem.getOrCreateTag()
                .getList("MatchedAttributes", Tag.TAG_COMPOUND);
            attributes.forEach(inbt -> {
                CompoundTag compound = (CompoundTag) inbt;
                ItemAttribute attribute = ItemAttribute.loadStatic(compound);
                if (attribute != null)
                    selectedAttributes.add(Pair.of(attribute, compound.getBoolean("Inverted")));
            });
        }
        """.trimIndent()
    )

    private fun migratedInitAndReadInventory(): BlockStmt = parseBlock(
        """
        {
            super.initAndReadInventory(filterItem);
            selectedAttributes = new ArrayList<>();
            CompoundTag modporterRoot = filterItem.getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            whitelistMode = WhitelistMode.values()[modporterRoot.getInt("WhitelistMode")];
            ListTag attributes = modporterRoot.getList("MatchedAttributes", Tag.TAG_COMPOUND);
            attributes.forEach(inbt -> {
                CompoundTag compound = (CompoundTag) inbt;
                ItemAttribute attribute = $HELPER(filterItem, modporterRoot, compound);
                if (attribute != null)
                    selectedAttributes.add(Pair.of(attribute, compound.getBoolean("Inverted")));
            });
        }
        """.trimIndent()
    )

    private fun loadStaticBody(): BlockStmt = parseBlock(
        """
        {
            for (LegacyDeserializer deserializer : LegacyDeserializer.ALL) {
                if (deserializer.canRead(nbt)) {
                    return deserializer.readNBT(nbt.getCompound(deserializer.getNBTKey()));
                }
            }
            ResourceLocation id = ResourceLocation.tryParse(nbt.getString("attributeId"));
            if (id == null)
                return null;
            ItemAttributeType type = CreateBuiltInRegistries.ITEM_ATTRIBUTE_TYPE.get(id);
            if (type == null)
                return null;
            ItemAttribute attribute = type.createAttribute();
            attribute.load(nbt);
            return attribute;
        }
        """.trimIndent()
    )

    private fun legacyRegisterBody(): BlockStmt = parseBlock(
        """
        {
            addLegacyDeserializer(new StandardTraitsLegacyDeserializer());
            createLegacyDeserializer("in_tag", tag -> new InTagAttribute(ItemTags.create(ResourceLocation.fromNamespaceAndPath(tag.getString("space"), tag.getString("path")))));
            createLegacyDeserializer("in_item_group", AllItemAttributeTypes.IN_ITEM_GROUP);
            createLegacyDeserializer("added_by", tag -> new AddedByAttribute(tag.getString("id")));
            createLegacyDeserializer("has_enchant", tag -> new EnchantAttribute(BuiltInRegistries.ENCHANTMENT.get(ResourceLocation.tryParse(tag.getString("id")))));
            createLegacyDeserializer("shulker_fill_level", tag -> new ShulkerFillLevelAttribute(ShulkerLevels.fromKey(tag.getString("id"))));
            createLegacyDeserializer("has_color", tag -> new ColorAttribute(DyeColor.byId(tag.getInt("id"))));
            createLegacyDeserializer("has_fluid", tag -> new FluidContentsAttribute(BuiltInRegistries.FLUID.get(ResourceLocation.tryParse(tag.getString("id")))));
            createLegacyDeserializer("has_name", AllItemAttributeTypes.HAS_NAME);
            createLegacyDeserializer("book_author", AllItemAttributeTypes.BOOK_AUTHOR);
            createLegacyDeserializer("book_copy", AllItemAttributeTypes.BOOK_COPY);
            createLegacyDeserializer("astralsorcery_amulet", AllItemAttributeTypes.ASTRAL_AMULET);
            createLegacyDeserializer("astralsorcery_constellation", AllItemAttributeTypes.ASTRAL_ATTUNMENT);
            createLegacyDeserializer("astralsorcery_crystal", AllItemAttributeTypes.ASTRAL_CRYSTAL);
            createLegacyDeserializer("astralsorcery_perk_gem", AllItemAttributeTypes.ASTRAL_PERK_GEM);
        }
        """.trimIndent()
    )

    private fun legacyTypeAdapterBody(): BlockStmt = parseBlock(
        """
        {
            createLegacyDeserializer(nbtKey, tag -> {
                ItemAttribute attribute = type.createAttribute();
                attribute.load(tag);
                return attribute;
            });
        }
        """.trimIndent()
    )

    private fun legacyFunctionAdapterBody(): BlockStmt = parseBlock(
        """
        {
            addLegacyDeserializer(new ItemAttribute.LegacyDeserializer() {
                public String getNBTKey() {
                    return nbtKey;
                }
                public ItemAttribute readNBT(CompoundTag nbt) {
                    return func.apply(nbt);
                }
            });
        }
        """.trimIndent()
    )

    private fun legacyAddBody(): BlockStmt = parseBlock(
        """
        {
            ItemAttribute.LegacyDeserializer.ALL.add(legacyDeserializer);
        }
        """.trimIndent()
    )

    private fun requireFile(projectDir: Path, relative: String): Path {
        val path = projectDir.resolve(relative)
        if (!path.isRegularFile()) abort("AttributeFilter source graph is missing $relative")
        return path
    }

    private fun writeFamily(
        outputs: List<PreparedOutput>,
        commitProbe: ((Int, Path) -> Unit)?
    ) {
        val stages = linkedMapOf<PreparedOutput, Path>()
        val committed = mutableListOf<PreparedOutput>()
        try {
            outputs.forEach { output ->
                if (output.path.readText() != output.before) {
                    throw IllegalStateException("AttributeFilter source changed before staging: ${output.path}")
                }
                val stage = Files.createTempFile(
                    output.path.parent,
                    ".${output.path.fileName}.modporter-attribute-filter-",
                    ".stage"
                )
                stages[output] = stage
                Files.writeString(stage, output.after, StandardCharsets.UTF_8)
                if (Files.readString(stage, StandardCharsets.UTF_8) != output.after) {
                    throw IllegalStateException("AttributeFilter stage changed before publish: ${output.path}")
                }
            }
            outputs.forEachIndexed { index, output ->
                if (output.path.readText() != output.before) {
                    throw IllegalStateException("AttributeFilter source changed before publish: ${output.path}")
                }
                commitProbe?.invoke(index, output.path)
                moveReplace(stages.getValue(output), output.path)
                committed += output
                if (output.path.readText() != output.after) {
                    throw IllegalStateException("Published AttributeFilter source differs: ${output.path}")
                }
            }
        } catch (failure: Throwable) {
            committed.asReversed().forEach { output ->
                try {
                    val rollback = Files.createTempFile(
                        output.path.parent,
                        ".${output.path.fileName}.modporter-attribute-filter-",
                        ".rollback"
                    )
                    try {
                        Files.writeString(rollback, output.before, StandardCharsets.UTF_8)
                        moveReplace(rollback, output.path)
                    } finally {
                        Files.deleteIfExists(rollback)
                    }
                    if (output.path.readText() != output.before) {
                        throw IllegalStateException("AttributeFilter rollback verification failed: ${output.path}")
                    }
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            stages.values.forEach { Files.deleteIfExists(it) }
        }
    }

    private fun moveReplace(source: Path, target: Path) {
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

    private data class PreparedOutput(
        val path: Path,
        val before: String,
        val after: String,
        val description: String
    )

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)
}
