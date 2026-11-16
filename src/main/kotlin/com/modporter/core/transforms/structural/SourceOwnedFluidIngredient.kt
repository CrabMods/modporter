package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Migrates a source-owned family of the removed Forge FluidStack-tag and registry-id members
 * without replacing the project's own JSON or network schema with NeoForge's ingredient types.
 *
 * Admission is by source shape and platform evidence only, never by class, member, package or
 * file name. A family is one top-level source type; it is rewritten only when every legacy member
 * it contains has one of these exact shapes and its proof holds:
 *
 * - `target = stack.getTag()` on a FluidStack parameter of a static method, guarded by
 *   `stack.has(DataComponents.CUSTOM_DATA)` (or `hasTag()`): Forge stored the live tag, the target
 *   stores a snapshot. The snapshot is admitted only when the parameter is used solely for
 *   read-only queries, is dead after the capture, and every project call passes a fresh stack that
 *   is dead after the call (private static helper parameters are traced to all their callers).
 * - `field = buffer.readRegistryId();` and `buffer.writeRegistryId(BuiltInRegistries.N, value);`
 *   on a RegistryFriendlyByteBuf: Forge framed the entry as registry-name ResourceLocation, then
 *   registry numeric VarInt; the target spells out that frame. The registry of a read is the
 *   unique BuiltInRegistries field whose element type is the field's declared type, and its key is
 *   the Registries field with the same element type (both from the 1.21.1 member table).
 * - the removed `new FluidStack(fluid, amount, tag)` copied its tag; CustomData.of copies it as
 *   well. As a conditional return `return f(tag.q() ? new FluidStack(x, n) : new FluidStack(x, n,
 *   tag))` the tag is non-null on the tagged branch, which becomes an early return plus a stack
 *   with custom data; as a lone return or declaration it is null-guarded like the Forge copy.
 *
 * Any other legacy member in an admitted family leaves the whole family unchanged with a gate; a
 * family with no admitted shape is left to other rules.
 */
internal object SourceOwnedFluidIngredient {
    const val RULE_ID = "struct-source-owned-fluid-ingredient"

    private const val FLUID_STACK = "net.neoforged.neoforge.fluids.FluidStack"
    private const val BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf"
    private const val BUILT_IN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries"
    private const val REGISTRIES = "net.minecraft.core.registries.Registries"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
    private const val CUSTOM_DATA = "net.minecraft.core.component.DataComponents.CUSTOM_DATA"
    private const val CUSTOM_DATA_TYPE = "net.minecraft.world.item.component.CustomData"
    private val READ_ONLY_STACK_QUERIES = setOf(
        "getFluid", "getAmount", "has", "hasTag", "isEmpty", "getFluidHolder", "getTag"
    )

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
        migrateChecked(projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "FluidStack legacy family admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "FluidStack legacy family migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(projectDir: Path, dryRun: Boolean): Result {
        val files = javaFiles(projectDir)
        val texts = files.associateWith { it.readText() }
        val hinted = texts.filterValues { text ->
            val code = JavaSourceText.maskCommentsAndLiterals(text)
            code.contains("readRegistryId") || code.contains("writeRegistryId") ||
                (code.contains("FluidStack") && (code.contains(".getTag()") || code.contains("new FluidStack")))
        }
        if (hinted.isEmpty()) return Result.EMPTY

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(
                ParserConfiguration.LanguageLevel.BLEEDING_EDGE
            )
        )
        val parsed = files.mapNotNull { file ->
            val text = texts.getValue(file)
            val result = parser.parse(text)
            if (!result.isSuccessful || result.result.isEmpty) {
                null
            } else {
                ParsedSource(file, text, result.result.orElseThrow())
            }
        }
        // A source that does not parse could hide callers; caller proofs then fail closed.
        val index = SourceIndex(parsed, complete = parsed.size == files.size)
        val families = index.files.filter { it.path in hinted }.flatMap { file ->
            file.unit.types.filterIsInstance<ClassOrInterfaceDeclaration>().mapNotNull { type ->
                planFamily(file, type, index)
            }
        }
        if (families.isEmpty()) return Result.EMPTY
        if (!targetContractIsVerified()) {
            abort("FluidStack legacy family target registry and CustomData contracts are not verified")
        }

        val edits = families.groupBy { it.file }
        val migrated = edits.mapValues { (file, plans) ->
            val all = plans.flatMap { it.edits }.sortedByDescending { it.start }
            all.zipWithNext().forEach { (later, earlier) ->
                if (earlier.end > later.start) abort("Overlapping FluidStack legacy edits in ${file.path}")
            }
            val text = all.fold(file.source) { current, edit ->
                current.replaceRange(edit.start, edit.end, edit.text)
            }
            if (!parser.parse(text).isSuccessful) {
                abort("FluidStack legacy family migration emitted invalid Java for ${file.path}")
            }
            text
        }
        if (!dryRun) migrated.forEach { (file, text) -> file.path.writeText(text) }
        return Result(
            migrated.keys.map { file ->
                Change(
                    file = file.path,
                    line = 1,
                    description =
                        "Preserve the source-owned FluidStack data and registry wire family",
                    before =
                        "live Forge tag, registry-name/VarInt helpers, and three-argument stack",
                    after =
                        "proven snapshot, explicit registry-name/VarInt frame, and CustomData copy",
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    /** Plans one top-level family, or null when it contains no admitted legacy shape. */
    private fun planFamily(
        file: ParsedSource,
        type: ClassOrInterfaceDeclaration,
        index: SourceIndex
    ): FamilyPlan? {
        val label = canonicalName(type, file) ?: return null
        val legacy = legacyMembers(file, type)
        val recognized = mutableListOf<Node>()
        val edits = mutableListOf<Edit>()
        val failures = mutableListOf<String>()

        legacy.tagCaptures.forEach { call ->
            val result = planTagCapture(call, file, index)
            if (result != null) {
                recognized += call
                result.fold({ edits += it }, { failures += it })
            }
        }
        legacy.registryReads.forEach { call ->
            val result = planRegistryRead(call, file, index)
            if (result != null) {
                recognized += call
                result.fold({ edits += it }, { failures += it })
            }
        }
        legacy.registryWrites.forEach { call ->
            val result = planRegistryWrite(call, file)
            if (result != null) {
                recognized += call
                result.fold({ edits += it }, { failures += it })
            }
        }
        val constructions = legacy.taggedConstructions.toMutableList()
        while (constructions.isNotEmpty()) {
            val creation = constructions.removeAt(0)
            val result = planTaggedConstruction(creation, file)
            if (result != null) {
                recognized += creation
                result.fold({ edits += it }, { failures += it })
            }
        }
        if (recognized.isEmpty()) return null
        val unrecognized = legacy.all.filter { member -> recognized.none { it === member } }
        if (unrecognized.isNotEmpty()) {
            abort(
                "$label has legacy FluidStack or registry-id members outside the admitted shapes: " +
                    unrecognized.joinToString { file.line(it).toString() }
            )
        }
        if (failures.isNotEmpty()) abort("$label: ${failures.joinToString("; ")}")
        return FamilyPlan(file, edits)
    }

    private fun legacyMembers(file: ParsedSource, type: ClassOrInterfaceDeclaration): LegacyMembers {
        val captures = type.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "getTag" && call.arguments.isEmpty() &&
                (call.scope.orElse(null) as? NameExpr)?.let { scope ->
                    declaredTypeOf(scope.nameAsString, call)?.let { file.typeIs(it, FLUID_STACK) }
                } == true
        }
        val reads = type.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "readRegistryId"
        }
        val writes = type.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "writeRegistryId"
        }
        val constructions = type.findAll(ObjectCreationExpr::class.java).filter { creation ->
            creation.arguments.size == 3 && file.typeIs(creation.type.nameWithScope, FLUID_STACK) &&
                !isComponentCopy(creation)
        }
        return LegacyMembers(captures, reads, writes, constructions)
    }

    /** `new FluidStack(X.getFluid(), n, X.getTag())` belongs to the generic copyWithAmount rule. */
    private fun isComponentCopy(creation: ObjectCreationExpr): Boolean {
        val fluid = creation.arguments[0] as? MethodCallExpr ?: return false
        val tag = creation.arguments[2] as? MethodCallExpr ?: return false
        return fluid.nameAsString == "getFluid" && tag.nameAsString == "getTag" &&
            fluid.arguments.isEmpty() && tag.arguments.isEmpty() &&
            fluid.scope.isPresent && fluid.scope.map(Node::toString) == tag.scope.map(Node::toString)
    }

    private fun planTagCapture(
        call: MethodCallExpr,
        file: ParsedSource,
        index: SourceIndex
    ): Outcome? {
        val assignment = call.parentNode.orElse(null) as? AssignExpr ?: return null
        if (assignment.value !== call || assignment.operator != AssignExpr.Operator.ASSIGN) return null
        val statement = assignment.parentNode.orElse(null) as? ExpressionStmt ?: return null
        val stackName = (call.scope.orElseThrow() as NameExpr).nameAsString
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val parameter = method.parameters.singleOrNull { it.nameAsString == stackName } ?: return null
        val guard = statement.parentNode.orElse(null)
            .let { if (it is BlockStmt && it.statements.size == 1) it.parentNode.orElse(null) else it }
            as? IfStmt
        val guarded = guard != null && !guard.elseStmt.isPresent &&
            (guard.thenStmt === statement || guard.thenStmt.findFirst(ExpressionStmt::class.java)
                .orElse(null) === statement) &&
            customDataPresence(guard.condition, stackName)
        // Forge's getTag() was null without a tag; only a presence-guarded capture stores the same
        // value as the custom-data snapshot. A static method has a closed set of callers.
        if (!guarded || !method.isStatic) return null
        val label = "${index.ownerOf(method, file)}.${method.nameAsString}"
        val body = method.body.orElse(null) ?: return null
        val uses = body.findAll(NameExpr::class.java).filter { it.nameAsString == stackName }
        val readOnly = uses.all { use ->
            val parent = use.parentNode.orElse(null) as? MethodCallExpr
            parent != null && parent.scope.orElse(null) === use &&
                parent.nameAsString in READ_ONLY_STACK_QUERIES
        }
        if (!readOnly || body.findAll(AssignExpr::class.java).any {
                (it.target as? NameExpr)?.nameAsString == stackName
            } || !index.deadAfter(stackName, statement, body, file)
        ) {
            return Outcome.failure("$label uses its FluidStack after or beyond read-only tag capture")
        }
        val proof = index.provenFreshCallers(method, parameter, file)
        if (proof != null) return Outcome.failure("$label $proof")
        return Outcome.success(
            file.edit(
                call,
                "$stackName.getOrDefault($CUSTOM_DATA, $CUSTOM_DATA_TYPE.EMPTY).copyTag()"
            )
        )
    }

    private fun customDataPresence(condition: Expression, stackName: String): Boolean {
        val call = condition.unwrap() as? MethodCallExpr ?: return false
        if ((call.scope.orElse(null) as? NameExpr)?.nameAsString != stackName) return false
        return (call.nameAsString == "hasTag" && call.arguments.isEmpty()) ||
            (call.nameAsString == "has" && call.arguments.singleOrNull()?.toString()?.let {
                it == "DataComponents.CUSTOM_DATA" || it == CUSTOM_DATA
            } == true)
    }

    private fun planRegistryRead(
        call: MethodCallExpr,
        file: ParsedSource,
        index: SourceIndex
    ): Outcome? {
        if (call.arguments.isNotEmpty()) return null
        val assignment = call.parentNode.orElse(null) as? AssignExpr ?: return null
        if (assignment.value !== call || assignment.operator != AssignExpr.Operator.ASSIGN) return null
        val statement = assignment.parentNode.orElse(null) as? ExpressionStmt ?: return null
        if (statement.parentNode.orElse(null) !is BlockStmt) return null
        val buffer = call.scope.orElse(null) as? NameExpr ?: return null
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        // registryAccess() exists only on the registry buffer the propagation pass introduces.
        if (declaredTypeOf(buffer.nameAsString, call)?.let { file.typeIs(it, BUFFER) } != true) return null
        val target = assignment.target
        val targetName = when (target) {
            is NameExpr -> target.nameAsString
            is FieldAccessExpr -> target.nameAsString.takeIf { target.scope is ThisExpr }
            else -> null
        } ?: return null
        val targetType = index.declaredValueType(targetName, target, file) ?: return null
        val registry = registryForElement(targetType) ?: return null
        val body = method.body.orElseThrow()
        val nameLocal = freshName(body, method, "registryName")
        val idLocal = freshName(body, method, "${targetName}Id")
        val noun = registry.lowercase().replace('_', ' ')
        val registries = file.simpleOrQualified(REGISTRIES, "Registries")
        val location = file.simpleOrQualified(RESOURCE_LOCATION, "ResourceLocation")
        val key = "$registries.$registry"
        val b = buffer.nameAsString
        val target0 = target.toString()
        val lines = listOf(
            "$location $nameLocal = $b.readResourceLocation();",
            "if (!$nameLocal.equals($key.location()))",
            "    throw new IllegalArgumentException(\"Expected $noun registry, got \" + $nameLocal);",
            "int $idLocal = $b.readVarInt();",
            "$target0 = $b.registryAccess().registryOrThrow($key).byId($idLocal);",
            "if ($target0 == null || $b.registryAccess().registryOrThrow($key).getIdOrThrow($target0) != $idLocal)",
            "    throw new IllegalArgumentException(\"Unknown $noun id \" + $idLocal);"
        )
        return Outcome.success(file.statementEdit(statement, lines))
    }

    private fun planRegistryWrite(call: MethodCallExpr, file: ParsedSource): Outcome? {
        if (call.arguments.size != 2) return null
        val statement = call.parentNode.orElse(null) as? ExpressionStmt ?: return null
        if (statement.parentNode.orElse(null) !is BlockStmt) return null
        val buffer = call.scope.orElse(null) as? NameExpr ?: return null
        if (declaredTypeOf(buffer.nameAsString, call)?.let { file.typeIs(it, BUFFER) } != true) return null
        val registryField = call.arguments[0] as? FieldAccessExpr ?: return null
        if (!file.typeIs(registryField.scope.toString(), BUILT_IN_REGISTRIES)) return null
        val registry = registryField.nameAsString
        if (registryKeyFor(registry) == null) return null
        val registries = file.simpleOrQualified(REGISTRIES, "Registries")
        val key = "$registries.$registry"
        val b = buffer.nameAsString
        val value = call.arguments[1].toString()
        return Outcome.success(
            file.statementEdit(
                statement,
                listOf(
                    "$b.writeResourceLocation($key.location());",
                    "$b.writeVarInt($b.registryAccess().registryOrThrow($key).getIdOrThrow($value));"
                )
            )
        )
    }

    private fun planTaggedConstruction(creation: ObjectCreationExpr, file: ParsedSource): Outcome? {
        val (fluid, amount, tag) = creation.arguments.map { it.unwrap() }
        if (!sideEffectFree(fluid) || !sideEffectFree(amount) || !simpleValue(tag)) return null
        val stackType = file.simpleOrQualified(FLUID_STACK, "FluidStack")
        val conditional = creation.parentNode.orElse(null)
            ?.let { if (it is EnclosedExpr) it.parentNode.orElse(null) else it } as? ConditionalExpr
        if (conditional != null) {
            if (conditional.elseExpr.unwrap() !== creation) return null
            val untagged = conditional.thenExpr.unwrap() as? ObjectCreationExpr ?: return null
            if (!file.typeIs(untagged.type.nameWithScope, FLUID_STACK) ||
                untagged.arguments.map { it.unwrap().toString() } != listOf(fluid.toString(), amount.toString())
            ) return null
            val guard = conditional.condition.unwrap() as? MethodCallExpr ?: return null
            if (guard.scope.map { it.unwrap().toString() }.orElse("") != tag.toString() ||
                !guard.arguments.all(::sideEffectFree)
            ) return null
            val returned = conditional.findAncestor(ReturnStmt::class.java).orElse(null) ?: return null
            val expression = returned.expression.orElse(null) ?: return null
            if (returned.parentNode.orElse(null) !is BlockStmt ||
                expression.findAll(ConditionalExpr::class.java).size != 1 ||
                !onlyCallsAround(expression, conditional)
            ) return null
            val body = returned.findAncestor(BlockStmt::class.java).orElseThrow()
            val method = returned.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
            val local = freshName(body, method, "stack")
            val text = file.text(expression)
            val start = file.offset(conditional) - file.offset(expression)
            val end = file.endOffset(conditional) - file.offset(expression) + 1
            fun replacing(replacement: String) = text.replaceRange(start, end, replacement)
            return Outcome.success(
                file.statementEdit(
                    returned,
                    listOf(
                        "if (${file.text(conditional.condition)})",
                        "    return ${replacing(file.text(untagged))};",
                        "$stackType $local = new $stackType(${file.text(fluid)}, ${file.text(amount)});",
                        "$local.set($CUSTOM_DATA, $CUSTOM_DATA_TYPE.of(${file.text(tag)}));",
                        "return ${replacing(local)};"
                    )
                )
            )
        }
        val parent = creation.parentNode.orElse(null)
        val direct = "$stackType(${file.text(fluid)}, ${file.text(amount)})"
        val tagged = { local: String ->
            listOf(
                "if (${file.text(tag)} != null)",
                "    $local.set($CUSTOM_DATA, $CUSTOM_DATA_TYPE.of(${file.text(tag)}));"
            )
        }
        if (parent is ReturnStmt && parent.parentNode.orElse(null) is BlockStmt) {
            val method = parent.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
            val local = freshName(parent.findAncestor(BlockStmt::class.java).orElseThrow(), method, "stack")
            return Outcome.success(
                file.statementEdit(
                    parent,
                    listOf("$stackType $local = new $direct;") + tagged(local) + "return $local;"
                )
            )
        }
        val declarator = parent as? VariableDeclarator ?: return null
        val declaration = declarator.parentNode.orElse(null) as? VariableDeclarationExpr ?: return null
        val statement = declaration.parentNode.orElse(null) as? ExpressionStmt ?: return null
        if (declaration.variables.size != 1 || statement.parentNode.orElse(null) !is BlockStmt) return null
        return Outcome.success(
            file.statementEdit(
                statement,
                listOf("${file.text(declaration.elementType)} ${declarator.nameAsString} = new $direct;") +
                    tagged(declarator.nameAsString)
            )
        )
    }

    /** Whether [expression] evaluates nothing but [inner] and side-effect-free operands. */
    private fun onlyCallsAround(expression: Expression, inner: Expression): Boolean {
        if (expression === inner) return true
        val call = expression as? MethodCallExpr ?: return false
        val scope = call.scope.orElse(null)
        if (scope != null && scope !is TypeExpr && !(scope is NameExpr && scope.nameAsString.first().isUpperCase()) &&
            !(scope is FieldAccessExpr && scope.toString().split('.').all { it.isNotEmpty() && it.first().isLetter() })
        ) return false
        val containing = call.arguments.filter { argument ->
            argument === inner || argument.findAll(ConditionalExpr::class.java).any { it === inner }
        }
        if (containing.size != 1) return false
        return call.arguments.all { it === containing.single() || sideEffectFree(it) } &&
            onlyCallsAround(containing.single().unwrap(), inner)
    }

    private fun sideEffectFree(expression: Expression): Boolean = when (val value = expression.unwrap()) {
        is NameExpr, is LiteralExpr, is ThisExpr -> true
        is FieldAccessExpr -> sideEffectFree(value.scope) || value.scope is TypeExpr
        else -> false
    }

    private fun simpleValue(expression: Expression): Boolean = when (val value = expression.unwrap()) {
        is NameExpr -> true
        is FieldAccessExpr -> value.scope is ThisExpr
        else -> false
    }

    /**
     * [name] when BuiltInRegistries.[name] (`...Registry<E>`) and Registries.[name]
     * (`ResourceKey<Registry<E>>`) both exist on 1.21.1 with the same element type E, else null.
     */
    private fun registryKeyFor(name: String): String? {
        val element = builtInElement(name) ?: return null
        val key = TargetMembers.fieldSignatures(REGISTRIES)[name]
            ?.let(::firstTypeArgument)?.let(::firstTypeArgument)?.let(::className)
            ?: return null
        return name.takeIf { element == key }
    }

    private fun builtInElement(name: String): String? =
        TargetMembers.fieldSignatures(BUILT_IN_REGISTRIES)[name]
            ?.let(::firstTypeArgument)?.let(::className)

    /** The unique built-in registry whose element type is [type] (a canonical class name). */
    private fun registryForElement(type: String): String? {
        val matches = TargetMembers.fieldSignatures(BUILT_IN_REGISTRIES).keys.filter {
            builtInElement(it) == type
        }
        return matches.singleOrNull()?.takeIf { registryKeyFor(it) != null }
    }

    /** The text of the first type argument list of a generic JVM signature. */
    private fun firstTypeArgument(signature: String): String? {
        val open = signature.indexOf('<')
        if (open < 0) return null
        var depth = 0
        for (index in open until signature.length) {
            when (signature[index]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return signature.substring(open + 1, index)
                }
            }
        }
        return null
    }

    /** The canonical class name of a leading `L...;` signature, without its type arguments. */
    private fun className(signature: String): String? {
        if (!signature.startsWith("L")) return null
        val generic = signature.indexOf('<')
        val semicolon = signature.indexOf(';')
        val end = listOf(generic, semicolon).filter { it > 0 }.minOrNull() ?: return null
        return signature.substring(1, end).replace('/', '.').replace('$', '.')
    }

    private fun targetContractIsVerified(): Boolean {
        fun public(owner: String, name: String, descriptor: String): Boolean =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"
        return public(
            BUFFER,
            "registryAccess",
            "()Lnet/minecraft/core/RegistryAccess;"
        ) && public(
            "net.minecraft.core.RegistryAccess",
            "registryOrThrow",
            "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Registry;"
        ) && public(
            "net.minecraft.core.IdMap",
            "byId",
            "(I)Ljava/lang/Object;"
        ) && public(
            "net.minecraft.core.IdMap",
            "getIdOrThrow",
            "(Ljava/lang/Object;)I"
        ) && public(
            "net.minecraft.network.FriendlyByteBuf",
            "readResourceLocation",
            "()Lnet/minecraft/resources/ResourceLocation;"
        ) && public(
            "net.minecraft.network.FriendlyByteBuf",
            "writeResourceLocation",
            "(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/network/FriendlyByteBuf;"
        ) && public(
            "net.minecraft.resources.ResourceKey",
            "location",
            "()Lnet/minecraft/resources/ResourceLocation;"
        ) && public(
            CUSTOM_DATA_TYPE,
            "of",
            "(Lnet/minecraft/nbt/CompoundTag;)" +
                "Lnet/minecraft/world/item/component/CustomData;"
        ) && public(
            CUSTOM_DATA_TYPE,
            "copyTag",
            "()Lnet/minecraft/nbt/CompoundTag;"
        ) && public(
            FLUID_STACK,
            "set",
            "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)" +
                "Ljava/lang/Object;"
        ) && public(
            FLUID_STACK,
            "getOrDefault",
            "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)" +
                "Ljava/lang/Object;"
        ) && "(Lnet/minecraft/world/level/material/Fluid;I)V" in
            TargetMembers.methodDescriptors(FLUID_STACK, "<init>")
    }

    /** The declared type text of the parameter or local named [name] visible at [use]. */
    private fun declaredTypeOf(name: String, use: Node): String? {
        val method = use.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val locals = method.findAll(VariableDeclarator::class.java).filter { it.nameAsString == name }
        val parameters = method.parameters.filter { it.nameAsString == name }
        if (locals.size + parameters.size != 1) return null
        return (locals.singleOrNull()?.typeAsString ?: parameters.single().typeAsString)
            .takeIf { it != "var" }
    }

    private fun freshName(scope: Node, method: MethodDeclaration, preferred: String): String {
        val used = method.parameters.map { it.nameAsString }.toSet() +
            method.findAll(VariableDeclarator::class.java).map { it.nameAsString } +
            scope.findAll(NameExpr::class.java).map { it.nameAsString }
        return generateSequence(0) { it + 1 }
            .map { if (it == 0) preferred else "$preferred$it" }
            .first { it !in used }
    }

    private class SourceIndex(val files: List<ParsedSource>, private val complete: Boolean) {
        private val types = files.flatMap { file ->
            file.unit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull {
                canonicalName(it, file)?.let { name -> TypeRef(file, name, it) }
            }
        }
        private val methods = types.flatMap { type ->
            type.type.methods.map { MethodRef(type.file, type.name, it) }
        }
        val calls = files.flatMap { file ->
            file.unit.findAll(MethodCallExpr::class.java).mapNotNull { call ->
                val method = call.findAncestor(MethodDeclaration::class.java).orElse(null)
                    ?: return@mapNotNull null
                val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                    .map { canonicalName(it, file) }.orElse(null)
                    ?: return@mapNotNull null
                CallRef(file, owner, method, call)
            }
        }

        fun ownerOf(method: MethodDeclaration, file: ParsedSource): String =
            method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .map { canonicalName(it, file) }.orElse(null) ?: file.path.toString()

        fun method(method: MethodDeclaration, owner: String): MethodRef =
            methods.single { it.method === method && it.owner == owner }

        /**
         * The project method [call] statically targets: the named type of a type-qualified call, or
         * the innermost enclosing type that declares the name with this arity for an unqualified
         * call. Null when the receiver is an instance expression or the type is unknown.
         */
        fun resolve(call: CallRef): MethodRef? {
            val arity = call.call.arguments.size
            val scope = call.call.scope.orElse(null)
            if (scope == null) {
                var current: Node? = call.call
                while (current != null) {
                    if (current is ClassOrInterfaceDeclaration) {
                        val owner = canonicalName(current, call.file) ?: return null
                        methods.singleOrNull {
                            it.owner == owner && it.method.nameAsString == call.call.nameAsString &&
                                it.method.parameters.size == arity
                        }?.let { return it }
                    }
                    current = current.parentNode.orElse(null)
                }
                return null
            }
            if (!typeReference(scope)) return null
            val targetOwner = resolveType(scope.toString(), call.file) ?: return null
            return methods.singleOrNull {
                it.owner == targetOwner &&
                    it.method.nameAsString == call.call.nameAsString &&
                    it.method.parameters.size == arity
            }
        }

        /**
         * Null when every project use of the static [method] passes a fresh FluidStack for
         * [parameter] that is dead after the call; otherwise the reason it is not proven.
         */
        fun provenFreshCallers(
            method: MethodDeclaration,
            parameter: Parameter,
            file: ParsedSource
        ): String? {
            if (!complete) return "cannot prove its callers because a project source does not parse"
            val target = method(method, ownerOf(method, file))
            val position = method.parameters.indexOf(parameter)
            val sameName = calls.filter {
                it.call.nameAsString == method.nameAsString &&
                    it.call.arguments.size == method.parameters.size
            }
            val callers = mutableListOf<CallRef>()
            sameName.forEach { call ->
                val resolved = resolve(call)
                when {
                    resolved == target -> callers += call
                    resolved != null -> Unit
                    call.call.scope.map { typeReference(it) }.orElse(false) &&
                        resolveType(call.call.scope.get().toString(), call.file) != target.owner -> Unit
                    else -> return "has a same-named call in ${call.file.path} that cannot be resolved"
                }
            }
            files.forEach { source ->
                source.unit.findAll(MethodReferenceExpr::class.java).forEach { reference ->
                    if (reference.identifier == method.nameAsString) {
                        val scope = reference.scope
                        if (!typeReference(scope) ||
                            resolveType(scope.toString(), source) == target.owner
                        ) {
                            return "is referenced as a method reference in ${source.path}"
                        }
                    }
                }
            }
            if (callers.isEmpty()) return "has no resolved project caller"
            callers.forEach { call ->
                if (!freshAndDead(call.call.arguments[position], call, mutableSetOf())) {
                    return "may retain mutable tag aliasing through a call in ${call.file.path}"
                }
            }
            return null
        }

        private fun typeReference(scope: Expression): Boolean = when (scope) {
            is NameExpr -> scope.nameAsString.first().isUpperCase()
            is FieldAccessExpr -> scope.nameAsString.first().isUpperCase() &&
                scope.toString().split('.').all { part -> part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '_' || it == '$' } }
            is TypeExpr -> true
            else -> false
        }

        /** The canonical declared type of the field or local [name] assigned at [use]. */
        fun declaredValueType(name: String, use: Node, file: ParsedSource): String? {
            val method = use.findAncestor(MethodDeclaration::class.java).orElse(null)
            val local = method?.findAll(VariableDeclarator::class.java)?.filter { it.nameAsString == name }.orEmpty()
            val parameter = method?.parameters?.filter { it.nameAsString == name }.orEmpty()
            if (local.size + parameter.size > 1) return null
            if (local.size + parameter.size == 1) {
                val text = local.singleOrNull()?.typeAsString ?: parameter.single().typeAsString
                return file.canonicalType(text)
            }
            var owner: ClassOrInterfaceDeclaration? =
                use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            var ownerFile = file
            val seen = mutableSetOf<String>()
            while (owner != null) {
                val field = owner.fields.flatMap { it.variables }.singleOrNull { it.nameAsString == name }
                if (field != null) return ownerFile.canonicalType(field.typeAsString)
                val ownerName = canonicalName(owner, ownerFile) ?: return null
                if (!seen.add(ownerName)) return null
                val parent = owner.extendedTypes.singleOrNull()?.nameWithScope ?: return null
                val parentName = resolveType(parent, ownerFile) ?: return null
                val parentRef = types.singleOrNull { it.name == parentName } ?: return null
                owner = parentRef.type
                ownerFile = parentRef.file
            }
            return null
        }

        fun freshAndDead(
            expression: Expression,
            use: CallRef,
            seenParameters: MutableSet<String>
        ): Boolean {
            val direct = expression.unwrap()
            if (direct !is NameExpr) {
                return freshExpression(direct, method(use), mutableSetOf())
            }
            val local = localBinding(direct.nameAsString, direct, method(use))
            if (local != null) {
                return freshLocal(local, direct, method(use), mutableSetOf()) &&
                    deadAfter(direct.nameAsString, use.call, local.scope, use.file)
            }
            val parameter = use.method.parameters.singleOrNull {
                it.nameAsString == direct.nameAsString
            } ?: return false
            val target = method(use)
            if (!target.method.isPrivate || !target.method.isStatic ||
                !deadAfter(parameter.nameAsString, use.call, target.method.body.orElseThrow(), use.file)
            ) return false
            val index = target.method.parameters.indexOf(parameter)
            val key = "${target.owner}#${target.method.nameAsString}/${target.method.parameters.size}:$index"
            if (!seenParameters.add(key)) return false
            val callers = calls.filter { resolve(it) == target }
            return callers.isNotEmpty() && callers.all { caller ->
                freshAndDead(caller.call.arguments[index], caller, seenParameters)
            }
        }

        private fun method(call: CallRef): MethodRef =
            methods.single { it.method === call.method && it.owner == call.owner }

        private fun freshExpression(
            expression: Expression,
            context: MethodRef,
            seenMethods: MutableSet<String>
        ): Boolean {
            return when (val direct = expression.unwrap()) {
                is ObjectCreationExpr ->
                    direct.type.nameAsString == "FluidStack" ||
                        direct.type.nameWithScope == FLUID_STACK
                is FieldAccessExpr ->
                    direct.toString() == "FluidStack.EMPTY" ||
                        direct.toString() == "$FLUID_STACK.EMPTY"
                is ConditionalExpr ->
                    freshExpression(direct.thenExpr, context, seenMethods) &&
                        freshExpression(direct.elseExpr, context, seenMethods)
                is MethodCallExpr -> {
                    if (direct.nameAsString == "copy" && direct.arguments.isEmpty()) {
                        true
                    } else {
                        val call = CallRef(context.file, context.owner, context.method, direct)
                        val target = resolve(call) ?: return false
                        freshMethod(target, seenMethods)
                    }
                }
                is NameExpr -> {
                    val binding = localBinding(direct.nameAsString, direct, context)
                        ?: return false
                    freshLocal(binding, direct, context, seenMethods)
                }
                else -> false
            }
        }

        private fun freshMethod(
            method: MethodRef,
            seenMethods: MutableSet<String>
        ): Boolean {
            val key = "${method.owner}#${method.method.nameAsString}/${method.method.parameters.size}"
            if (!seenMethods.add(key)) return false
            return try {
                val returns = method.method.findAll(ReturnStmt::class.java).filter {
                    it.findAncestor(MethodDeclaration::class.java).orElse(null) === method.method
                }
                returns.isNotEmpty() && returns.all {
                    it.expression.map { value ->
                        freshExpression(value, method, seenMethods)
                    }.orElse(false)
                }
            } finally {
                seenMethods.remove(key)
            }
        }

        private fun freshLocal(
            binding: LocalBinding,
            use: Node,
            context: MethodRef,
            seenMethods: MutableSet<String>
        ): Boolean {
            val values = mutableListOf<Expression>()
            binding.declaration.initializer.ifPresent(values::add)
            binding.scope.findAll(AssignExpr::class.java).filter { assignment ->
                assignment.target.unwrap().let {
                    it is NameExpr && it.nameAsString == binding.declaration.nameAsString
                } &&
                    binding.file.offset(assignment) > binding.file.offset(binding.declaration) &&
                    binding.file.offset(assignment) < binding.file.offset(use)
            }.forEach { values += it.value }
            return values.isNotEmpty() && values.all {
                freshExpression(it, context, seenMethods)
            }
        }

        private fun localBinding(
            name: String,
            use: Node,
            context: MethodRef
        ): LocalBinding? {
            val candidates = context.method.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == name }
                .filter { context.file.offset(it) < context.file.offset(use) }
                .mapNotNull { declaration ->
                    val scope = declaration.findAncestor(BlockStmt::class.java)
                        .orElse(null) ?: return@mapNotNull null
                    if (contains(scope, use, context.file)) {
                        LocalBinding(context.file, declaration, scope)
                    } else {
                        null
                    }
                }
            return candidates.maxByOrNull {
                context.file.offset(it.declaration)
            }
        }

        fun deadAfter(
            name: String,
            use: Node,
            scope: Node,
            file: ParsedSource
        ): Boolean = scope.findAll(NameExpr::class.java).none {
            it.nameAsString == name && file.offset(it) > file.endOffset(use)
        }

        fun resolveType(source: String, file: ParsedSource): String? {
            val plain = source.replace(" ", "")
            if (plain.firstOrNull()?.isLowerCase() == true && '.' in plain) return plain
            val first = plain.substringBefore('.')
            val explicit = file.unit.imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == first
            }.map { it.nameAsString }.distinct()
            if (explicit.size == 1) return explicit.single() + plain.removePrefix(first)
            val packageName = file.unit.packageDeclaration.map {
                it.nameAsString
            }.orElse("")
            val samePackage = types.filter {
                it.name.substringAfterLast('.') == first &&
                    it.name.substringBeforeLast('.', "") == packageName
            }
            if (samePackage.size == 1) {
                return samePackage.single().name + plain.removePrefix(first)
            }
            val nested = types.filter {
                it.file === file && it.name.substringAfterLast('.') == first
            }
            if (nested.size == 1) return nested.single().name + plain.removePrefix(first)
            val unique = types.filter { it.name.substringAfterLast('.') == first }
            return unique.singleOrNull()?.name?.plus(plain.removePrefix(first))
        }
    }

    private fun canonicalName(
        declaration: ClassOrInterfaceDeclaration,
        file: ParsedSource
    ): String? {
        val names = mutableListOf<String>()
        var current: Node = declaration
        while (current is ClassOrInterfaceDeclaration) {
            names += current.nameAsString
            current = current.parentNode.orElse(null) ?: return null
        }
        if (current !== file.unit) return null
        val packageName = file.unit.packageDeclaration.map {
            it.nameAsString
        }.orElse("")
        val nested = names.asReversed().joinToString(".")
        return if (packageName.isBlank()) nested else "$packageName.$nested"
    }

    private fun contains(scope: Node, child: Node, file: ParsedSource): Boolean =
        file.offset(scope) <= file.offset(child) &&
            file.endOffset(scope) >= file.endOffset(child)

    private fun Expression.unwrap(): Expression {
        var value = this
        while (true) {
            value = when (value) {
                is EnclosedExpr -> value.inner
                is CastExpr -> value.expression
                else -> return value
            }
        }
    }

    private fun javaFiles(projectDir: Path): List<Path> {
        val root = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return JavaSourceText.walkJavaSourceRoots(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "java" }
                .sorted()
                .toList()
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private class Outcome private constructor(val edit: Edit?, val failure: String?) {
        fun fold(onSuccess: (Edit) -> Unit, onFailure: (String) -> Unit) {
            if (edit != null) onSuccess(edit) else onFailure(failure.orEmpty())
        }

        companion object {
            fun success(edit: Edit) = Outcome(edit, null)
            fun failure(reason: String) = Outcome(null, reason)
        }
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    private class LegacyMembers(
        val tagCaptures: List<MethodCallExpr>,
        val registryReads: List<MethodCallExpr>,
        val registryWrites: List<MethodCallExpr>,
        val taggedConstructions: List<ObjectCreationExpr>
    ) {
        val all: List<Node> get() = tagCaptures + registryReads + registryWrites + taggedConstructions
    }

    private class FamilyPlan(val file: ParsedSource, val edits: List<Edit>)

    private class ParsedSource(
        val path: Path,
        val source: String,
        val unit: CompilationUnit
    ) {
        private val starts = buildList {
            add(0)
            source.forEachIndexed { index, char ->
                if (char == '\n') add(index + 1)
            }
        }
        private val lineSeparator = if (source.contains("\r\n")) "\r\n" else "\n"

        fun offset(node: Node): Int = node.range.map {
            starts[it.begin.line - 1] + it.begin.column - 1
        }.orElse(-1)

        fun endOffset(node: Node): Int = node.range.map {
            starts[it.end.line - 1] + it.end.column - 1
        }.orElse(-1)

        fun line(node: Node): Int = node.range.map { it.begin.line }.orElse(0)

        fun text(node: Node): String = source.substring(offset(node), endOffset(node) + 1)

        fun edit(node: Node, replacement: String): Edit = Edit(offset(node), endOffset(node) + 1, replacement)

        /** Replaces [statement] with [lines], each further line indented like the statement. */
        fun statementEdit(statement: Statement, lines: List<String>): Edit {
            val start = offset(statement)
            val lineStart = source.lastIndexOf('\n', start - 1) + 1
            val indent = source.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
            return Edit(start, endOffset(statement) + 1, lines.joinToString(lineSeparator + indent))
        }

        /**
         * Whether the source type [raw] denotes [fqn]: written qualified, imported explicitly or on
         * demand, and not shadowed by a type of this unit with the same simple name.
         */
        fun typeIs(raw: String, fqn: String): Boolean = canonicalType(raw) == fqn

        fun canonicalType(raw: String): String? {
            val cleaned = raw.replace(Regex("""@[\w.]+(?:\([^)]*\))?\s*"""), "")
                .substringBefore('<').replace(" ", "").trim()
            if (cleaned.isEmpty()) return null
            val first = cleaned.substringBefore('.')
            val rest = cleaned.removePrefix(first)
            if (first.firstOrNull()?.isLowerCase() == true) return cleaned
            val declared = unit.findAll(TypeDeclaration::class.java).filter { it.nameAsString == first }
            if (declared.isNotEmpty()) {
                return (declared.singleOrNull() as? ClassOrInterfaceDeclaration)
                    ?.let { canonicalName(it, this) }?.plus(rest)
            }
            val explicit = unit.imports.filter {
                !it.isStatic && !it.isAsterisk && it.name.identifier == first
            }.map { it.nameAsString }.distinct()
            if (explicit.isNotEmpty()) return explicit.singleOrNull()?.plus(rest)
            val onDemand = unit.imports.filter { !it.isStatic && it.isAsterisk }
                .map { "${it.nameAsString}.$first" }
                .filter { TargetMembers.hasClass(it) }
            if (onDemand.size == 1) return onDemand.single() + rest
            val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
            return if (packageName.isBlank()) cleaned else "$packageName.$cleaned"
        }

        /** The simple name when it denotes [fqn] in this unit, otherwise the qualified name. */
        fun simpleOrQualified(fqn: String, simple: String): String =
            if (canonicalType(simple) == fqn) simple else fqn
    }

    private data class TypeRef(
        val file: ParsedSource,
        val name: String,
        val type: ClassOrInterfaceDeclaration
    )

    private data class MethodRef(
        val file: ParsedSource,
        val owner: String,
        val method: MethodDeclaration
    )

    private data class CallRef(
        val file: ParsedSource,
        val owner: String,
        val method: MethodDeclaration,
        val call: MethodCallExpr
    )

    private data class LocalBinding(
        val file: ParsedSource,
        val declaration: VariableDeclarator,
        val scope: BlockStmt
    )
}
