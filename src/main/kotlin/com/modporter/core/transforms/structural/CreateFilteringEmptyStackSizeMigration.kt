package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Preserves Forge 1.20.1's empty ItemStack maximum in Create's filtering behaviour.
 *
 * Forge's empty stack resolves through AIR and reports a maximum of 64. NeoForge 1.21.1 reads the
 * MAX_STACK_SIZE component with a default of 1. Create 0.5 uses the old empty-stack result as the
 * unfiltered transfer limit, the count-control visibility predicate, and the legacy zero-count NBT
 * fallback. Leaving those calls native therefore changes bulk transfers to single-item transfers.
 *
 * This migration is deliberately bound to the verified Create owner and its eight legacy call
 * sites. Non-empty stacks still call the target-native method, preserving custom data components.
 */
internal object CreateFilteringEmptyStackSizeMigration {
    const val RULE_ID = "struct-create-filtering-empty-stack-max"

    private const val BEHAVIOUR_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/" +
            "filtering/FilteringBehaviour.java"
    private const val FILTER_STACK_RELATIVE =
        "src/main/java/com/simibubi/create/content/logistics/filter/FilterItemStack.java"
    private const val PACKAGE =
        "com.simibubi.create.foundation.blockEntity.behaviour.filtering"
    private const val FILTER_STACK_PACKAGE =
        "com.simibubi.create.content.logistics.filter"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val FILTER_STACK =
        "com.simibubi.create.content.logistics.filter.FilterItemStack"
    private const val HELPER_BASE = "modporterLegacyMaxStackSize"

    private val expectedReceivers = linkedMapOf(
        "read" to "filter.item()",
        "setFilter" to "stack",
        "setValueSettings" to "filter.item()",
        "getValueSettings" to "filter.item()",
        "isCountVisible" to "filter.item()",
        "createBoard" to "filter",
        "formatValue" to "filter.item()",
        "getCountLabelForValueBox" to "filter.item()"
    )

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result = try {
        migrateChecked(projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create filtering admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Create filtering empty-stack migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(projectDir: Path, dryRun: Boolean): Result {
        val behaviourFile = projectDir.resolve(BEHAVIOUR_RELATIVE)
        if (!behaviourFile.isRegularFile()) return Result.EMPTY
        val filterStackFile = projectDir.resolve(FILTER_STACK_RELATIVE)
        if (!filterStackFile.isRegularFile()) {
            abort("Create filtering source graph is missing $FILTER_STACK_RELATIVE")
        }
        requireTargetItemStackContract()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        requireFilterStackItemContract(parse(parser, filterStackFile.readText(), filterStackFile))

        val before = behaviourFile.readText()
        val unit = parse(parser, before, behaviourFile)
        requireExactImport(unit, ITEM_STACK, "FilteringBehaviour")
        requireExactImport(unit, FILTER_STACK, "FilteringBehaviour")
        val owner = requireOwner(unit)
        val mode = classify(owner)
        if (mode is Mode.Target) return Result.EMPTY
        if (mode !is Mode.Legacy) {
            abort("Create FilteringBehaviour stack-size family is partial or incompatible: $mode")
        }

        LexicalPreservingPrinter.setup(unit)
        val helperName = uniqueHelperName(owner)
        mode.calls.forEach { call ->
            val receiver = call.scope.orElseThrow().clone()
            call.replace(MethodCallExpr(helperName).addArgument(receiver))
        }
        val helper = StaticJavaParser.parseBodyDeclaration(
            """
            private static int $helperName(ItemStack stack) {
                return stack.isEmpty() ? 64 : stack.getMaxStackSize();
            }
            """.trimIndent()
        )
        val insertion = owner.members.indexOfFirst {
            it is MethodDeclaration && it.nameAsString == "isCountVisible"
        }
        if (insertion < 0) abort("Create FilteringBehaviour lacks isCountVisible")
        owner.members.add(insertion, helper)

        val after = LexicalPreservingPrinter.print(unit)
        val verified = requireOwner(parse(parser, after, behaviourFile))
        val verifiedMode = classify(verified)
        if (verifiedMode !is Mode.Target || verifiedMode.helperName != helperName) {
            abort("Create FilteringBehaviour did not reach the empty-aware target family")
        }

        if (!dryRun) atomicWrite(behaviourFile, after)
        return Result(
            listOf(
                Change(
                    file = behaviourFile,
                    line = 1,
                    description =
                        "Preserve Create's legacy empty-filter maximum while retaining " +
                            "native component limits for non-empty stacks",
                    before = "Eight FilteringBehaviour ItemStack#getMaxStackSize calls",
                    after = "Eight calls use one empty-aware, single-evaluation helper",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            ),
            emptyList()
        )
    }

    private fun classify(owner: ClassOrInterfaceDeclaration): Mode {
        if (
            owner.extendedTypes.singleOrNull()?.nameAsString != "BlockEntityBehaviour" ||
            owner.implementedTypes.none { it.nameAsString == "ValueSettingsBehaviour" }
        ) {
            return Mode.Other("unexpected owner inheritance")
        }
        if (owner.typeParameters.isNotEmpty()) {
            return Mode.Other("generic FilteringBehaviour shadows exact native receiver types")
        }
        if (
            owner.findAll(ClassOrInterfaceDeclaration::class.java).any {
                it !== owner && it.nameAsString in setOf("ItemStack", "FilterItemStack")
            }
        ) {
            return Mode.Other("FilteringBehaviour shadows exact native receiver types")
        }
        val filterFields = owner.fields.flatMap { it.variables }.filter {
            it.nameAsString == "filter" && it.typeAsString == "FilterItemStack"
        }
        if (filterFields.size != 1) return Mode.Other("missing FilterItemStack filter field")
        val filterField = filterFields.single()
        if (
            filterField.findAncestor(TypeDeclaration::class.java).orElse(null) !== owner ||
            filterField.findAncestor(FieldDeclaration::class.java)
                .map { it.isStatic }
                .orElse(true)
        ) {
            return Mode.Other("filter is not the verified native instance field")
        }

        val helpers = owner.methods.filter(::isEmptyAwareHelper)
        if (helpers.size > 1) return Mode.Other("multiple empty-aware stack-size helpers")
        val helper = helpers.singleOrNull()
        val allMaxCalls = owner.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "getMaxStackSize" && it.arguments.isEmpty()
        }
        val helperNativeCall = helper?.findAll(MethodCallExpr::class.java)?.singleOrNull {
            it.nameAsString == "getMaxStackSize" && it.arguments.isEmpty()
        }
        val rawCalls = allMaxCalls.filter { it !== helperNativeCall }

        if (helper == null) {
            val legacy = matchLegacyCalls(owner, rawCalls) ?: return Mode.Other(
                "expected exactly the eight verified legacy getMaxStackSize receivers"
            )
            return Mode.Legacy(legacy)
        }

        if (rawCalls.isNotEmpty()) {
            return Mode.Other("target helper exists beside unconverted getMaxStackSize calls")
        }
        val helperCalls = owner.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == helper.nameAsString &&
                it.scope.isEmpty &&
                it.arguments.size == 1 &&
                it.findAncestor(MethodDeclaration::class.java).orElse(null) !== helper
        }
        if (!matchTargetCalls(owner, helperCalls)) {
            return Mode.Other("target helper is not used by the eight verified receivers")
        }
        return Mode.Target(helper.nameAsString)
    }

    private fun matchLegacyCalls(
        owner: ClassOrInterfaceDeclaration,
        calls: List<MethodCallExpr>
    ): List<MethodCallExpr>? {
        if (calls.size != expectedReceivers.size) return null
        val matched = linkedMapOf<String, MethodCallExpr>()
        for (call in calls) {
            val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
            val expected = expectedReceivers[method.nameAsString] ?: return null
            if (method.nameAsString in matched) return null
            val receiver = call.scope.orElse(null) ?: return null
            if (compact(receiver.toString()) != compact(expected)) return null
            if (!hasKnownNativeReceiver(owner, method, call)) return null
            matched[method.nameAsString] = call
        }
        return if (matched.keys == expectedReceivers.keys) matched.values.toList() else null
    }

    private fun matchTargetCalls(
        owner: ClassOrInterfaceDeclaration,
        calls: List<MethodCallExpr>
    ): Boolean {
        if (calls.size != expectedReceivers.size) return false
        val matched = linkedSetOf<String>()
        for (call in calls) {
            val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
            val expected = expectedReceivers[method.nameAsString] ?: return false
            if (!matched.add(method.nameAsString)) return false
            if (compact(call.arguments.single().toString()) != compact(expected)) return false
            if (!hasKnownNativeReceiver(owner, method, call)) {
                return false
            }
        }
        return matched == expectedReceivers.keys
    }

    private fun hasKnownNativeReceiver(
        owner: ClassOrInterfaceDeclaration,
        method: MethodDeclaration,
        call: MethodCallExpr
    ): Boolean {
        if (method.typeParameters.isNotEmpty()) return false
        if (methodOwner(method) !== owner) return false
        if (call.findAncestor(TypeDeclaration::class.java).orElse(null) !== owner) return false
        if (
            call.findAncestor(ObjectCreationExpr::class.java)
                .filter { it.anonymousClassBody.isPresent }
                .isPresent
        ) {
            return false
        }
        return when (method.nameAsString) {
            "setFilter" -> {
                val stack = method.parameters.singleOrNull() ?: return false
                stack.nameAsString == "stack" &&
                    stack.typeAsString == "ItemStack" &&
                    method.findAll(VariableDeclarator::class.java)
                        .none { it.nameAsString == "stack" }
            }
            "createBoard" -> {
                if (
                    method.findAll(Parameter::class.java)
                        .any { it.nameAsString == "filter" }
                ) {
                    return false
                }
                val filter = method.findAll(VariableDeclarator::class.java)
                    .filter { it.nameAsString == "filter" }
                    .singleOrNull()
                    ?: return false
                if (
                    filter.typeAsString != "ItemStack" ||
                    filter.findAncestor(MethodDeclaration::class.java).orElse(null) !== method ||
                    filter.findAncestor(TypeDeclaration::class.java).orElse(null) !== owner
                ) {
                    return false
                }
                val initializer = filter.initializer.orElse(null) as? MethodCallExpr
                    ?: return false
                initializer.nameAsString == "getFilter" &&
                    initializer.scope.map { compact(it.toString()) == "this" }.orElse(true)
            }
            else -> {
                method.findAll(Parameter::class.java)
                    .none { it.nameAsString == "filter" } &&
                    method.findAll(VariableDeclarator::class.java)
                        .none { it.nameAsString == "filter" }
            }
        }
    }

    private fun methodOwner(method: MethodDeclaration): ClassOrInterfaceDeclaration? =
        method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)

    private fun isEmptyAwareHelper(method: MethodDeclaration): Boolean {
        if (
            !method.isPrivate ||
            !method.isStatic ||
            method.typeAsString != "int" ||
            method.typeParameters.isNotEmpty() ||
            method.parameters.size != 1 ||
            method.parameters.single().typeAsString != "ItemStack" ||
            method.body.isEmpty
        ) {
            return false
        }
        val body = method.body.orElseThrow()
        if (body.statements.size != 1) return false
        val returned = body.statements.single() as? ReturnStmt ?: return false
        val expression = returned.expression.orElse(null) ?: return false
        val parameter = Regex.escape(method.parameters.single().nameAsString)
        return Regex(
            """^$parameter\.isEmpty\(\)\?64:$parameter\.getMaxStackSize\(\)$"""
        ).matches(compact(expression.toString()))
    }

    private fun uniqueHelperName(owner: ClassOrInterfaceDeclaration): String {
        val used = owner.methods.mapTo(linkedSetOf()) { it.nameAsString }
        if (HELPER_BASE !in used) return HELPER_BASE
        var suffix = 2
        while ("$HELPER_BASE$suffix" in used) suffix++
        return "$HELPER_BASE$suffix"
    }

    private fun requireFilterStackItemContract(unit: CompilationUnit) {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != FILTER_STACK_PACKAGE) {
            abort("FilterItemStack source has an unexpected package")
        }
        requireExactImport(unit, ITEM_STACK, "FilterItemStack")
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("FilterItemStack source must contain one top-level class")
        if (owner.nameAsString != "FilterItemStack" || owner.isInterface) {
            abort("FilterItemStack source owns an unexpected top-level type")
        }
        val item = owner.methods.singleOrNull {
            it.nameAsString == "item" &&
                it.isPublic &&
                !it.isStatic &&
                it.parameters.isEmpty() &&
                it.typeAsString == "ItemStack"
        } ?: abort("FilterItemStack lacks the verified public ItemStack item() contract")
        if (item.body.isEmpty) abort("FilterItemStack.item() has no source body")
    }

    private fun requireTargetItemStackContract() {
        if (
            "()Z" !in TargetMembers.methodDescriptors(ITEM_STACK, "isEmpty") ||
            "()I" !in TargetMembers.methodDescriptors(ITEM_STACK, "getMaxStackSize")
        ) {
            abort(
                "Target ItemStack lacks the verified isEmpty()Z/getMaxStackSize()I ABI"
            )
        }
    }

    private fun requireExactImport(
        unit: CompilationUnit,
        canonical: String,
        owner: String
    ) {
        val simpleName = canonical.substringAfterLast('.')
        val exact = unit.imports.count {
            !it.isStatic && !it.isAsterisk && it.nameAsString == canonical
        }
        val conflicting = unit.imports.filter {
            !it.isStatic &&
                !it.isAsterisk &&
                it.nameAsString.substringAfterLast('.') == simpleName &&
                it.nameAsString != canonical
        }
        if (exact != 1 || conflicting.isNotEmpty()) {
            abort("$owner does not bind $simpleName to exact native owner $canonical")
        }
    }

    private fun requireOwner(unit: CompilationUnit): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != PACKAGE) {
            abort("FilteringBehaviour source has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("FilteringBehaviour source must contain one top-level class")
        if (owner.nameAsString != "FilteringBehaviour" || owner.isInterface) {
            abort("FilteringBehaviour source owns an unexpected top-level type")
        }
        return owner
    }

    private fun parse(parser: JavaParser, source: String, path: Path): CompilationUnit {
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

    private fun compact(source: String): String = source.replace(Regex("""\s+"""), "")

    private fun atomicWrite(path: Path, source: String) {
        val temporary = Files.createTempFile(
            path.parent,
            ".modporter-create-filtering-max-",
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

    private sealed interface Mode {
        data class Legacy(val calls: List<MethodCallExpr>) : Mode
        data class Target(val helperName: String) : Mode
        data class Other(val reason: String) : Mode
    }
}
