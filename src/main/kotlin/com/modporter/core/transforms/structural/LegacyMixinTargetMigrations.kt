package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.LegacyMembers
import com.modporter.mapping.TargetMembers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Mixin selectors, @At targets, invokers and plugin guards written against Minecraft 1.20.1
 * members, moved onto the 1.21.1 members that run the same code.
 *
 * Every move is decided by platform facts only: the 1.20.1 member table (SRG/Mojang), the 1.21.1
 * member table, and the bytecode facts `scripts/mixin_target_facts.py` derives from (and verifies
 * against) the real game jars. A handler is touched only where it mirrors a changed member;
 * anything those facts do not decide is left as it is.
 */
internal fun StructuralRefactorPass.migrateLegacyMixinTargets(source: String): String {
    var result = source
    if (result.contains("@Mixin")) {
        result = migrateSplitBlockUseSelectors(result)
        result = migrateRemovedInvokeTargets(result)
        result = migrateMovedEnclosingRedirects(result)
        result = migrateInsertedInvokerParameters(result)
    }
    result = migrateInsertedInvokerArguments(result)
    result = migrateUnreachableMixinPluginGuards(result)
    return result
}

/**
 * Bytecode facts of the 1.21.1 game jar (and the curated member moves verified against both game
 * jars), read from the resources `scripts/mixin_target_facts.py` writes and checks.
 */
internal object LegacyMixinTargetFacts {
    class SplitMethod(val name: String, val descriptor: String, val invokes: Set<String>)

    class Forwarder(val extendedDescriptor: String, val parameterNames: List<String>, val invokes: Set<String>)

    class EnclosingMove(
        val owner: String,
        val legacyMethod: String,
        val nativeMethod: String,
        val legacyInvoke: String,
        val nativeInvoke: String,
        val optionalElement: String,
        val invokeStatic: Boolean
    )

    class ParameterInsertion(
        val owner: String,
        val name: String,
        val legacyDescriptor: String,
        val nativeDescriptor: String,
        val insertedIndex: Int,
        val insertedName: String,
        val anyServerLevel: Boolean
    ) {
        val insertedType: String get() = TargetMembers.parameterTypes(nativeDescriptor)[insertedIndex]

        /** The table rows agree with the entry: legacy only on 1.20.1, native only on 1.21.1, one inserted parameter. */
        val proven: Boolean by lazy {
            val legacyParameters = TargetMembers.parameterTypes(legacyDescriptor)
            val nativeParameters = TargetMembers.parameterTypes(nativeDescriptor)
            LegacyMembers.declaresMethod(owner, name, legacyDescriptor) &&
                legacyDescriptor !in TargetMembers.methodDescriptors(owner, name) &&
                nativeDescriptor in TargetMembers.methodDescriptors(owner, name) &&
                insertedIndex in nativeParameters.indices &&
                nativeParameters.filterIndexed { index, _ -> index != insertedIndex } == legacyParameters &&
                returnDescriptor(legacyDescriptor) == returnDescriptor(nativeDescriptor)
        }
    }

    private const val ROOT = "/mappings/forge2neo/"

    private val split: JsonObject by lazy { resource("vanilla-block-use-split.json") }

    val legacyUseDescriptor: String by lazy {
        split.getValue("legacyMethod").jsonObject.getValue("descriptor").jsonPrimitive.content
    }

    val blockUseSplit: Map<String, List<SplitMethod>> by lazy {
        split.getValue("classes").jsonObject.mapValues { (_, methods) ->
            methods.jsonObject.map { (name, method) ->
                val fields = method.jsonObject
                SplitMethod(
                    name,
                    fields.getValue("descriptor").jsonPrimitive.content,
                    fields.getValue("invokes").jsonArray.map { it.jsonPrimitive.content }.toSet()
                )
            }
        }
    }

    private val forwarders: Map<Triple<String, String, String>, Forwarder> by lazy {
        resource("vanilla-prefix-forwarders.json").getValue("forwarders").jsonArray.associate { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            Triple(text("owner"), text("name"), text("legacyDescriptor")) to Forwarder(
                text("extendedDescriptor"),
                fields.getValue("extendedParameterNames").jsonArray.map { it.jsonPrimitive.content },
                fields.getValue("extendedInvokes").jsonArray.map { it.jsonPrimitive.content }.toSet()
            )
        }
    }

    fun forwarder(owner: String, name: String, legacyDescriptor: String): Forwarder? =
        forwarders[Triple(owner, name, legacyDescriptor)]

    private val moves: JsonObject by lazy { resource("legacy-mixin-member-moves.json") }

    val enclosingMoves: List<EnclosingMove> by lazy {
        moves["enclosingMoves"]?.jsonArray.orEmpty().map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            EnclosingMove(
                text("owner"),
                text("legacyMethod"),
                text("nativeMethod"),
                text("legacyInvoke"),
                text("nativeInvoke"),
                text("nativeInvokeOptionalOf"),
                fields.getValue("invokeStatic").jsonPrimitive.boolean
            )
        }
    }

    val parameterInsertions: List<ParameterInsertion> by lazy {
        moves["parameterInsertions"]?.jsonArray.orEmpty().map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            ParameterInsertion(
                text("owner"),
                text("name"),
                text("legacyDescriptor"),
                text("nativeDescriptor"),
                fields.getValue("insertedIndex").jsonPrimitive.int,
                text("insertedName"),
                fields["anyServerLevel"]?.jsonPrimitive?.boolean == true
            )
        }
    }

    /** An injector target method 1.21.1 replaced by a method of a subclass running the same check. */
    class InjectorMove(
        val legacyOwner: String,
        val legacyMethod: String,
        val nativeOwner: String,
        val nativeMethod: String,
        val sharedInvokes: Set<String>
    ) {
        val proven: Boolean by lazy {
            val (legacyName, legacyDescriptor) = splitSelector(legacyMethod) ?: return@lazy false
            val (nativeName, nativeDescriptor) = splitSelector(nativeMethod) ?: return@lazy false
            LegacyMembers.declaresMethod(legacyOwner, legacyName, legacyDescriptor) &&
                legacyDescriptor !in TargetMembers.methodDescriptors(legacyOwner, legacyName) &&
                nativeDescriptor in TargetMembers.methodDescriptors(nativeOwner, nativeName) &&
                TargetMembers.inheritsFrom(nativeOwner, legacyOwner)
        }
    }

    /** A static field 1.21.1 replaced by an instance method building the same value. */
    class StaticFieldMethod(
        val owner: String,
        val legacyField: String,
        val fieldDescriptor: String,
        val nativeMethod: String
    ) {
        val proven: Boolean by lazy {
            val (name, descriptor) = splitSelector(nativeMethod) ?: return@lazy false
            TargetMembers.fieldDescriptor(owner, legacyField) == null &&
                descriptor in TargetMembers.methodDescriptors(owner, name) &&
                returnDescriptor(descriptor) == fieldDescriptor
        }
    }

    /** A method 1.21.1 replaced by a call on a field of the renamed owner. */
    class InvokerFieldMove(
        val nativeOwner: String,
        val legacyMethod: String,
        val nativeField: String,
        val nativeFieldDescriptor: String,
        val nativeCall: String
    ) {
        val fieldType: String get() = nativeFieldDescriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

        /** Parameter types the native call takes after the legacy ones (they must come from the caller's scope). */
        val appendedParameters: List<String> get() =
            TargetMembers.parameterTypes(nativeCall).drop(TargetMembers.parameterTypes(legacyMethod).size)

        val proven: Boolean by lazy {
            val (legacyName, legacyDescriptor) = splitSelector(legacyMethod) ?: return@lazy false
            val (callName, callDescriptor) = splitSelector(nativeCall) ?: return@lazy false
            val legacyParameters = TargetMembers.parameterTypes(legacyDescriptor)
            val nativeParameters = TargetMembers.parameterTypes(callDescriptor)
            legacyDescriptor !in TargetMembers.methodDescriptors(nativeOwner, legacyName) &&
                TargetMembers.fieldDescriptor(nativeOwner, nativeField) == nativeFieldDescriptor &&
                TargetMembers.inheritedMethod(fieldType, callName, callDescriptor) != null &&
                nativeParameters.take(legacyParameters.size) == legacyParameters &&
                returnDescriptor(legacyDescriptor) == returnDescriptor(callDescriptor)
        }
    }

    /** A removed static helper whose 1.21.1 replacement iterates a data component's list. */
    class RemovedStaticHelper(
        val owner: String,
        val legacyMethod: String,
        val component: String,
        val componentDefault: String,
        val listMethod: String,
        val elementType: String,
        val elementValue: String
    ) {
        val proven: Boolean by lazy {
            val (name, descriptor) = splitSelector(legacyMethod) ?: return@lazy false
            LegacyMembers.declaresMethod(owner, name, descriptor) &&
                descriptor !in TargetMembers.methodDescriptors(owner, name) &&
                TargetMembers.hasField(component.substringBeforeLast('.'), component.substringAfterLast('.')) &&
                TargetMembers.hasField(
                    mixinBinaryName(componentDefault.substringBeforeLast('.')),
                    componentDefault.substringAfterLast('.')
                ) &&
                TargetMembers.hasMethod(mixinBinaryName(elementType), elementValue)
        }
    }

    class LibraryCallback(
        val method: String,
        val argument: Int,
        val lambdaParameter: Int,
        val parameterType: String,
        val platformSupertype: String
    )

    /** A library builder whose fluent chain types the implicit parameters of its callback lambdas. */
    class LibraryBuilder(
        val type: String,
        val roots: Map<String, Set<String>>,
        val selfReturning: Set<String>,
        val transform: String,
        val transformFunctions: Map<String, Int>,
        val callbacks: List<LibraryCallback>
    )

    val injectorMoves: List<InjectorMove> by lazy {
        moves["injectorMoves"]?.jsonArray.orEmpty().map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            InjectorMove(
                text("legacyOwner"),
                text("legacyMethod"),
                text("nativeOwner"),
                text("nativeMethod"),
                fields.getValue("sharedInvokes").jsonArray.map { it.jsonPrimitive.content }.toSet()
            )
        }
    }

    val staticFieldMethods: List<StaticFieldMethod> by lazy {
        moves["staticFieldMethods"]?.jsonArray.orEmpty().map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            StaticFieldMethod(text("owner"), text("legacyField"), text("fieldDescriptor"), text("nativeMethod"))
        }
    }

    val invokerFieldMoves: List<InvokerFieldMove> by lazy {
        moves["invokerFieldMoves"]?.jsonArray.orEmpty().map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            InvokerFieldMove(
                text("nativeOwner"),
                text("legacyMethod"),
                text("nativeField"),
                text("nativeFieldDescriptor"),
                text("nativeCall")
            )
        }
    }

    val removedStaticHelpers: List<RemovedStaticHelper> by lazy {
        moves["removedStaticHelpers"]?.jsonArray.orEmpty().map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            RemovedStaticHelper(
                text("owner"),
                text("legacyMethod"),
                text("component"),
                text("componentDefault"),
                text("listMethod"),
                text("elementType"),
                text("elementValue")
            )
        }
    }

    val libraryBuilders: List<LibraryBuilder> by lazy {
        resource("library-callback-parameters.json").getValue("builders").jsonArray.map { element ->
            val fields = element.jsonObject
            fun text(name: String) = fields.getValue(name).jsonPrimitive.content
            fun names(name: String) = fields.getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()
            LibraryBuilder(
                text("type"),
                fields.getValue("roots").jsonArray.associate { root ->
                    val rootFields = root.jsonObject
                    rootFields.getValue("owner").jsonPrimitive.content to
                        rootFields.getValue("methods").jsonArray.map { it.jsonPrimitive.content }.toSet()
                },
                names("selfReturning"),
                text("transform"),
                fields.getValue("transformFunctions").jsonArray.associate { function ->
                    val functionFields = function.jsonObject
                    functionFields.getValue("type").jsonPrimitive.content to
                        functionFields.getValue("result").jsonPrimitive.int
                },
                fields.getValue("callbacks").jsonArray.map { callback ->
                    val callbackFields = callback.jsonObject
                    fun callbackText(name: String) = callbackFields.getValue(name).jsonPrimitive.content
                    LibraryCallback(
                        callbackText("method"),
                        callbackFields.getValue("argument").jsonPrimitive.int,
                        callbackFields.getValue("lambdaParameter").jsonPrimitive.int,
                        callbackText("parameterType"),
                        callbackText("platformSupertype")
                    )
                }
            )
        }
    }

    private fun resource(name: String): JsonObject {
        val text = LegacyMixinTargetFacts::class.java.getResourceAsStream(ROOT + name)
            ?.use { it.readBytes().decodeToString() }
            ?: error("Missing Mixin target fact resource: $ROOT$name")
        return Json.parseToJsonElement(text).jsonObject
    }
}

/**
 * The project's Mixin configs (package and plugin) and the @Invoker methods it declares for members
 * whose parameter list 1.21.1 widened, collected before the per-file rules run.
 */
internal data class MixinProjectFacts(
    val configs: List<Config> = emptyList(),
    /** Accessor canonical name -> invoker method name -> the widened platform member it invokes. */
    val invokers: Map<String, Map<String, LegacyMixinTargetFacts.ParameterInsertion>> = emptyMap()
) {
    data class Config(val packageName: String, val plugin: String?)

    companion object {
        fun collect(pass: StructuralRefactorPass, projectDir: Path, javaFiles: List<Path>): MixinProjectFacts {
            val resources = projectDir.resolve("src/main/resources")
            val configs = if (!resources.isDirectory()) emptyList() else Files.walk(resources).use { paths ->
                paths.filter { path ->
                    path.isRegularFile() && path.name.endsWith(".json") &&
                        resources.relativize(path).firstOrNull()?.toString() !in setOf("assets", "data")
                }.toList()
            }.mapNotNull { path -> runCatching { path.readText() }.getOrNull()?.let(::parseConfig) }
            val sources = javaFiles.mapNotNull { runCatching { it.readText() }.getOrNull() }
            return fromSources(pass, sources, configs)
        }

        fun parseConfig(text: String): Config? {
            if (!text.contains("\"package\"")) return null
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val packageName = (root["package"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (listOf("mixins", "client", "server").none { root[it] is JsonArray }) return null
            val plugin = (root["plugin"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Config(packageName, plugin)
        }

        fun fromSources(pass: StructuralRefactorPass, sources: List<String>, configs: List<Config>): MixinProjectFacts {
            val insertions = LegacyMixinTargetFacts.parameterInsertions.filter { it.proven }
            val invokers = linkedMapOf<String, MutableMap<String, LegacyMixinTargetFacts.ParameterInsertion>>()
            if (insertions.isNotEmpty()) {
                for (source in sources) {
                    if (!source.contains("@Mixin") || !source.contains("Invoker")) continue
                    val parsed = pass.parseMixinSource(source) ?: continue
                    for (type in parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                        val target = parsed.mixinTarget(type) ?: continue
                        val accessor = type.fullyQualifiedName.orElse(null) ?: continue
                        for (insertion in insertions.filter { it.owner == target }) {
                            for (method in parsed.legacyInvokers(type, insertion)) {
                                invokers.getOrPut(accessor) { linkedMapOf() }[method.nameAsString] = insertion
                            }
                        }
                    }
                }
            }
            return MixinProjectFacts(configs, invokers)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 1.20.5 split Block#use into useItemOn and useWithoutItem: a selector naming `use` follows the
// one 1.21.1 method whose own code contains the injector's @At INVOKE target.

private fun StructuralRefactorPass.migrateSplitBlockUseSelectors(source: String): String {
    if (!source.contains("\"use")) return source
    val parsed = parseMixinSource(source) ?: return source
    val legacy = LegacyMixinTargetFacts.legacyUseDescriptor
    val edits = mutableListOf<MixinEdit>()
    for ((target, method, injector) in parsed.mixinInjectors()) {
        val split = LegacyMixinTargetFacts.blockUseSplit[target] ?: continue
        if (LegacyMembers.methodDescriptors(target, "use") != setOf(legacy) ||
            TargetMembers.methodDescriptors(target, "use").isNotEmpty()
        ) {
            continue
        }
        val selector = injector.annotation.singleString("method") ?: continue
        val qualified = when (selector.value) {
            "use" -> false
            "use$legacy" -> true
            else -> continue
        }
        val invoke = parsed.singleAt(injector.annotation)?.invokeTarget()?.value ?: continue
        val chosen = split.filter { invoke in it.invokes }.singleOrNull() ?: continue
        if (TargetMembers.methodDescriptors(target, chosen.name) != setOf(chosen.descriptor)) continue
        if (!parsed.followsSplitMethod(injector, method, legacy, chosen.descriptor)) continue
        val replacement = if (qualified) chosen.name + chosen.descriptor else chosen.name
        edits += parsed.replace(selector, javaString(replacement)) ?: continue
    }
    return applyMixinEdits(source, edits) ?: source
}

/**
 * Whether a handler written for `use` stays valid on [chosen]: it mirrors none of the target's
 * parameters, its callback (if any) keeps its return type, and every captured argument is an
 * `@Local(argsOnly = true)` of a type [chosen] takes exactly once.
 */
private fun MixinSource.followsSplitMethod(
    injector: Injector,
    method: MethodDeclaration,
    legacy: String,
    chosen: String
): Boolean {
    val parameters = method.parameters
    val leading = when (injector.kind) {
        INJECT -> {
            if (parameters.indexOfFirst { isCallback(it.type) } != 0) return false
            if (isReturnableCallback(parameters[0].type) && returnDescriptor(legacy) != returnDescriptor(chosen)) {
                return false
            }
            1
        }
        MODIFY_EXPRESSION_VALUE, MODIFY_ARG -> 1
        else -> return false
    }
    if (parameters.size < leading || parameters.any { it.isVarArgs }) return false
    val chosenParameters = TargetMembers.parameterTypes(chosen)
    return parameters.drop(leading).all { parameter ->
        val local = annotation(parameter.annotations, LOCAL) ?: return@all false
        val pairs = if (local.isNormalAnnotationExpr) local.asNormalAnnotationExpr().pairs else return@all false
        val argsOnly = pairs.singleOrNull()?.takeIf { it.nameAsString == "argsOnly" }?.value as? BooleanLiteralExpr
        val type = descriptor(parameter.type)
        argsOnly?.value == true && type != null && chosenParameters.count { it == type } == 1
    }
}

// ---------------------------------------------------------------------------------------------
// An @At INVOKE target whose 1.20.1 member is gone on 1.21.1, where the owner now offers exactly
// one same-name overload of the same arity, is retargeted; a handler mirroring a changed value
// is adjusted only for primitive narrowing (its returns are cast back to the new type).

private fun StructuralRefactorPass.migrateRemovedInvokeTargets(source: String): String {
    if (!source.contains("target")) return source
    val parsed = parseMixinSource(source) ?: return source
    val edits = mutableListOf<MixinEdit>()
    for ((_, method, injector) in parsed.mixinInjectors()) {
        val literal = parsed.singleAt(injector.annotation)?.invokeTarget() ?: continue
        val legacy = MemberReference.parse(literal.value) ?: continue
        if (!TargetMembers.hasClass(legacy.owner) ||
            TargetMembers.inheritedMethod(legacy.owner, legacy.name, legacy.descriptor) != null ||
            !existedOnLegacyPlatform(legacy)
        ) {
            continue
        }
        val arity = TargetMembers.parameterTypes(legacy.descriptor).size
        val native = visibleNativeDescriptors(legacy.owner, legacy.name)
            .filter { TargetMembers.parameterTypes(it).size == arity }
            .singleOrNull() ?: continue
        val handler = parsed.mirroredValueEdits(injector, method, legacy.descriptor, native) ?: continue
        val retarget = parsed.replace(literal, javaString(legacy.copy(descriptor = native).toString())) ?: continue
        edits += handler + retarget
    }
    return applyMixinEdits(source, edits) ?: source
}

private fun MixinSource.mirroredValueEdits(
    injector: Injector,
    method: MethodDeclaration,
    legacy: String,
    native: String
): List<MixinEdit>? = when (injector.kind) {
    INJECT -> emptyList()
    MODIFY_EXPRESSION_VALUE -> {
        val from = returnDescriptor(legacy)
        val to = returnDescriptor(native)
        if (from == to) emptyList() else narrowMirroredValue(method, from, to)
    }
    MODIFY_ARG -> {
        val legacyParameters = TargetMembers.parameterTypes(legacy)
        val nativeParameters = TargetMembers.parameterTypes(native)
        val index = injector.annotation.intMember("index")
            ?: if (legacyParameters.size == 1) 0 else null
        when {
            index == null || index !in legacyParameters.indices -> null
            method.parameters.drop(1).any { annotation(it.annotations, LOCAL) == null } -> null
            legacyParameters[index] == nativeParameters[index] -> emptyList()
            else -> narrowMirroredValue(method, legacyParameters[index], nativeParameters[index])
        }
    }
    else -> null
}

/** Narrows a handler mirroring one primitive value (its first parameter and its return) from [from] to [to]. */
private fun MixinSource.narrowMirroredValue(method: MethodDeclaration, from: String, to: String): List<MixinEdit>? {
    val fromRank = PRIMITIVE_RANKS[from] ?: return null
    val toRank = PRIMITIVE_RANKS[to] ?: return null
    if (toRank >= fromRank) return null
    val parameter = method.parameters.firstOrNull() ?: return null
    if (descriptor(method.type) != from || descriptor(parameter.type) != from) return null
    val body = method.body.orElse(null) ?: return null
    val name = parameter.nameAsString
    if (assigns(body, name)) return null
    val narrowed = javaSourceType(to)
    val edits = mutableListOf(
        replace(method.type, narrowed) ?: return null,
        replace(parameter.type, narrowed) ?: return null
    )
    for (returned in method.ownReturns()) {
        val value = returned.expression.orElse(null) ?: return null
        if ((value as? NameExpr)?.nameAsString == name) continue
        edits += replace(value, "($narrowed) (${text(value) ?: return null})") ?: return null
    }
    return edits
}

// ---------------------------------------------------------------------------------------------
// An explicit-descriptor @Inject selector whose 1.20.1 descriptor is a strict parameter prefix of
// exactly one 1.21.1 overload follows that overload: either the legacy descriptor is gone, or the
// 1.21.1 jar keeps it only as a forwarder into the extended overload (whose code holds the @At).
// The handler gains the extra parameters before its callback.

internal fun StructuralRefactorPass.migrateExtendedInjectorDescriptors(source: String): String {
    if (!source.contains("@Mixin") || !source.contains("Inject")) return source
    val parsed = parseMixinSource(source) ?: return source
    val edits = mutableListOf<MixinEdit>()
    for ((target, method, injector) in parsed.mixinInjectors()) {
        if (injector.kind != INJECT) continue
        val selector = injector.annotation.singleString("method") ?: continue
        val open = selector.value.indexOf('(')
        if (open <= 0) continue
        val name = selector.value.substring(0, open)
        val legacy = selector.value.substring(open)
        if (!LegacyMembers.declaresMethod(target, name, legacy)) continue
        val legacyParameters = TargetMembers.parameterTypes(legacy)
        val overloads = TargetMembers.methodDescriptors(target, name)
        val extended = overloads.filter { candidate ->
            val parameters = TargetMembers.parameterTypes(candidate)
            returnDescriptor(candidate) == returnDescriptor(legacy) &&
                parameters.size > legacyParameters.size &&
                parameters.subList(0, legacyParameters.size) == legacyParameters
        }.singleOrNull() ?: continue
        val forwarder = LegacyMixinTargetFacts.forwarder(target, name, legacy)
            ?.takeIf { it.extendedDescriptor == extended }
        if (legacy in overloads && forwarder == null) continue
        val at = parsed.singleAt(injector.annotation)
        if (at == null && injector.annotation.member("at") != null) continue
        val invoke = at?.invokeTarget()?.value
        if (invoke != null && (forwarder == null || invoke !in forwarder.invokes)) continue
        val callback = method.parameters.indexOfFirst { parsed.isCallback(it.type) }
        if (callback < 0) continue
        val selectorEdit = parsed.replace(selector, javaString(name + extended)) ?: continue
        val mirrored = method.parameters.take(callback)
        if (mirrored.isEmpty()) {
            edits += selectorEdit
            continue
        }
        if (mirrored.size != legacyParameters.size ||
            mirrored.indices.any { parsed.descriptor(mirrored[it].type) != legacyParameters[it] }
        ) {
            continue
        }
        val extendedParameters = TargetMembers.parameterTypes(extended)
        val taken = method.parameters.map { it.nameAsString }.toMutableSet()
        val added = (legacyParameters.size until extendedParameters.size).joinToString("") { index ->
            val parameterName = forwarder?.parameterNames?.getOrNull(index)
                ?.takeIf { isReadableParameterName(it) && it !in taken }
                ?: uniqueName("modporterArg$index", taken)
            taken += parameterName
            "${javaSourceType(extendedParameters[index])} $parameterName, "
        }
        val parameterEdit = parsed.insertBefore(method.parameters[callback], added) ?: continue
        edits += selectorEdit
        edits += parameterEdit
    }
    return applyMixinEdits(source, edits) ?: source
}

// ---------------------------------------------------------------------------------------------
// A @Redirect of a curated moved call (legacy enclosing method + legacy invoke) follows the move
// onto the native enclosing method and native invoke, whose Optional carries the same value.

private fun StructuralRefactorPass.migrateMovedEnclosingRedirects(source: String): String {
    if (!source.contains("Redirect")) return source
    val moves = LegacyMixinTargetFacts.enclosingMoves.filter { source.contains(it.legacyInvoke) && it.proven() }
    if (moves.isEmpty()) return source
    val parsed = parseMixinSource(source) ?: return source
    val edits = mutableListOf<MixinEdit>()
    val droppedImports = mutableListOf<String>()
    for ((target, method, injector) in parsed.mixinInjectors()) {
        if (injector.kind != REDIRECT) continue
        for (move in moves.filter { it.owner == target }) {
            val plan = parsed.movedRedirect(move, method, injector) ?: continue
            edits += plan.edits
            droppedImports += plan.droppedImports
        }
    }
    val migrated = applyMixinEdits(source, edits) ?: return source
    if (migrated == source) return source
    return removeUnusedSimpleImports(migrated, droppedImports.distinct())
}

/** The table rows agree with the curated move (both jars were checked by the generator script). */
internal fun LegacyMixinTargetFacts.EnclosingMove.proven(): Boolean {
    val (legacyName, legacyDescriptor) = splitSelector(legacyMethod) ?: return false
    val (nativeName, nativeDescriptor) = splitSelector(nativeMethod) ?: return false
    val legacyCall = MemberReference.parse(legacyInvoke) ?: return false
    val nativeCall = MemberReference.parse(nativeInvoke) ?: return false
    return LegacyMembers.declaresMethod(owner, legacyName, legacyDescriptor) &&
        legacyDescriptor !in TargetMembers.methodDescriptors(owner, legacyName) &&
        nativeDescriptor in TargetMembers.methodDescriptors(owner, nativeName) &&
        existedOnLegacyPlatform(legacyCall) &&
        TargetMembers.inheritedMethod(nativeCall.owner, nativeCall.name, nativeCall.descriptor) != null &&
        TargetMembers.parameterTypes(legacyCall.descriptor) == TargetMembers.parameterTypes(nativeCall.descriptor) &&
        returnDescriptor(nativeCall.descriptor) == "Ljava/util/Optional;" &&
        returnDescriptor(legacyCall.descriptor) == "L${mixinBinaryName(optionalElement).replace('.', '/')};"
}

private class MovePlan(val edits: List<MixinEdit>, val droppedImports: List<String>)

private fun MixinSource.movedRedirect(
    move: LegacyMixinTargetFacts.EnclosingMove,
    method: MethodDeclaration,
    injector: Injector
): MovePlan? {
    val annotation = injector.annotation
    val selector = annotation.singleString("method") ?: return null
    val (legacyName, legacyDescriptor) = splitSelector(move.legacyMethod) ?: return null
    val (nativeName, nativeDescriptor) = splitSelector(move.nativeMethod) ?: return null
    val nativeSelector = when (selector.value) {
        move.legacyMethod -> move.nativeMethod
        legacyName -> nativeName.takeIf {
            LegacyMembers.methodDescriptors(move.owner, legacyName).size == 1 &&
                TargetMembers.methodDescriptors(move.owner, nativeName).size == 1
        } ?: return null
        else -> return null
    }
    val atTarget = singleAt(annotation)?.invokeTarget() ?: return null
    if (atTarget.value != move.legacyInvoke) return null
    val legacyCall = MemberReference.parse(move.legacyInvoke) ?: return null
    val nativeCall = MemberReference.parse(move.nativeInvoke) ?: return null

    val parameters = method.parameters
    val expectedLeading = (if (move.invokeStatic) emptyList() else listOf("L${legacyCall.internalOwner};")) +
        TargetMembers.parameterTypes(legacyCall.descriptor)
    if (parameters.size < expectedLeading.size || parameters.any { it.isVarArgs }) return null
    if (parameters.take(expectedLeading.size).map { descriptor(it.type) } != expectedLeading) return null
    if (descriptor(method.type) != returnDescriptor(legacyCall.descriptor)) return null
    val legacyEnclosing = TargetMembers.parameterTypes(legacyDescriptor)
    val nativeEnclosing = TargetMembers.parameterTypes(nativeDescriptor)
    val captured = parameters.drop(expectedLeading.size)
    if (captured.size > legacyEnclosing.size ||
        captured.indices.any { descriptor(captured[it].type) != legacyEnclosing[it] }
    ) {
        return null
    }
    val body = method.body.orElse(null) ?: return null

    val edits = mutableListOf(
        replace(selector, javaString(nativeSelector)) ?: return null,
        replace(atTarget, javaString(move.nativeInvoke)) ?: return null,
        replace(method.type, "java.util.Optional<${move.optionalElement}>") ?: return null
    )
    val dropped = mutableListOf<Parameter>()
    if (captured.isNotEmpty()) {
        val taken = parameters.map { it.nameAsString }.toMutableSet()
        val kept = mutableListOf<Parameter>()
        val rebuilt = nativeEnclosing.map { type ->
            val matching = captured.filter { descriptor(it.type) == type }
            if (matching.size > 1) return null
            val reused = matching.singleOrNull()
            if (reused != null) {
                kept += reused
                text(reused) ?: return null
            } else {
                val simple = javaSourceType(type).substringAfterLast('.')
                val parameterName = uniqueName(simple.replaceFirstChar { it.lowercase() }, taken)
                taken += parameterName
                "${javaSourceType(type)} $parameterName"
            }
        }
        dropped += captured.filter { candidate -> kept.none { it === candidate } }
        if (dropped.any { parameter ->
                body.findAll(NameExpr::class.java).any { it.nameAsString == parameter.nameAsString }
            }
        ) {
            return null
        }
        val first = range(captured.first()) ?: return null
        val last = range(captured.last()) ?: return null
        edits += MixinEdit(first.first, last.last + 1, rebuilt.joinToString(", "))
    }
    for (returned in method.ownReturns()) {
        val value = returned.expression.orElse(null) ?: return null
        edits += redirectedReturn(value, legacyCall, nativeCall) ?: return null
    }
    val droppedImports = dropped.flatMap { parameter ->
        listOfNotNull((parameter.type as? ClassOrInterfaceType)?.let { canonical(it.nameWithScope) }) +
            parameter.annotations.mapNotNull { canonical(it.nameAsString) }
    }
    return MovePlan(edits, droppedImports)
}

/** The handler's returned value as the native invoke's Optional of the same value. */
private fun MixinSource.redirectedReturn(
    value: Expression,
    legacyCall: MemberReference,
    nativeCall: MemberReference
): MixinEdit? {
    val expression = unwrapMixinExpression(value)
    if (expression is NullLiteralExpr) return null
    if (expression is MethodCallExpr && isStaticCall(expression, legacyCall)) {
        return if (legacyCall.owner == nativeCall.owner) {
            replace(expression.name, nativeCall.name)
        } else {
            val arguments = expression.arguments.map { text(it) ?: return null }
            replace(expression, "${javaSourceType("L${nativeCall.internalOwner};")}.${nativeCall.name}(${arguments.joinToString(", ")})")
        }
    }
    if (expression is MethodCallExpr && expression.nameAsString == "orElseThrow" && expression.arguments.isEmpty()) {
        val inner = expression.scope.orElse(null)?.let(::unwrapMixinExpression) as? MethodCallExpr
        if (inner != null && isStaticCall(inner, nativeCall)) return replace(value, text(inner) ?: return null)
    }
    return replace(value, "java.util.Optional.of(${text(value) ?: return null})")
}

private fun MixinSource.isStaticCall(call: MethodCallExpr, member: MemberReference): Boolean {
    if (call.nameAsString != member.name ||
        call.arguments.size != TargetMembers.parameterTypes(member.descriptor).size
    ) {
        return false
    }
    val scope = call.scope.orElse(null) ?: return false
    return typing.isTypeName(scope.toString(), javaSourceType("L${member.internalOwner};"))
}

// ---------------------------------------------------------------------------------------------
// @Invoker declarations of a curated member that gained a parameter take it, and their callers
// pass a Level in scope or, when the member only reads server-global state through it, the
// running server's overworld.

private fun StructuralRefactorPass.migrateInsertedInvokerParameters(source: String): String {
    if (!source.contains("Invoker")) return source
    val insertions = LegacyMixinTargetFacts.parameterInsertions.filter { it.proven }
    if (insertions.isEmpty()) return source
    val parsed = parseMixinSource(source) ?: return source
    val edits = mutableListOf<MixinEdit>()
    for (type in parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        val target = parsed.mixinTarget(type) ?: continue
        for (insertion in insertions.filter { it.owner == target }) {
            for (method in parsed.legacyInvokers(type, insertion)) {
                edits += parsed.insertParameter(method, insertion) ?: continue
            }
        }
    }
    return applyMixinEdits(source, edits) ?: source
}

private fun MixinSource.legacyInvokers(
    type: ClassOrInterfaceDeclaration,
    insertion: LegacyMixinTargetFacts.ParameterInsertion
): List<MethodDeclaration> = type.methods.filter { method ->
    val invoker = annotation(method.annotations, INVOKER) ?: return@filter false
    invokedName(method, invoker) == insertion.name &&
        method.typeParameters.isEmpty() &&
        method.parameters.none { it.isVarArgs } &&
        method.parameters.map { descriptor(it.type) } == TargetMembers.parameterTypes(insertion.legacyDescriptor) &&
        descriptor(method.type) == returnDescriptor(insertion.legacyDescriptor)
}

private fun invokedName(method: MethodDeclaration, invoker: AnnotationExpr): String? {
    val explicit = invoker.member("value")
    if (explicit != null) return (explicit as? StringLiteralExpr)?.value?.takeIf { '(' !in it }
    val name = method.nameAsString
    val prefix = listOf("call", "invoke").firstOrNull { name.startsWith(it) && name.length > it.length } ?: return null
    return name.removePrefix(prefix).replaceFirstChar { it.lowercase() }
}

private fun MixinSource.insertParameter(
    method: MethodDeclaration,
    insertion: LegacyMixinTargetFacts.ParameterInsertion
): MixinEdit? {
    val parameters = method.parameters
    val name = uniqueName(insertion.insertedName, parameters.map { it.nameAsString }.toSet())
    val declaration = "${javaSourceType(insertion.insertedType)} $name"
    return when {
        insertion.insertedIndex < parameters.size -> insertBefore(parameters[insertion.insertedIndex], "$declaration, ")
        parameters.isNotEmpty() -> insertAfter(parameters.last(), ", $declaration")
        else -> openParenthesisAfter(method.name)?.let { MixinEdit(it + 1, it + 1, declaration) }
    }
}

private fun StructuralRefactorPass.migrateInsertedInvokerArguments(source: String): String {
    val invokers = projectMixinFacts.invokers
    if (invokers.isEmpty()) return source
    val names = invokers.values.flatMap { it.keys }.toSet()
    if (names.none { source.contains(it) }) return source
    val parsed = parseMixinSource(source) ?: return source
    val edits = mutableListOf<MixinEdit>()
    for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString !in names) continue
        val accessor = parsed.receiverType(call) ?: continue
        val insertion = invokers[accessor]?.get(call.nameAsString) ?: continue
        if (!insertion.proven ||
            call.arguments.size != TargetMembers.parameterTypes(insertion.legacyDescriptor).size
        ) {
            continue
        }
        val level = parsed.levelInScope(call, insertion)
        val edit = when {
            level != null -> parsed.insertArgument(call, insertion.insertedIndex, level)
            insertion.anyServerLevel -> parsed.serverLevelReturn(call, insertion)
            else -> null
        }
        edits += edit ?: continue
    }
    return applyMixinEdits(source, edits) ?: source
}

private fun MixinSource.receiverType(call: MethodCallExpr): String? {
    val scope = call.scope.orElse(null)?.let(::unwrapMixinExpression) ?: return null
    if (scope is CastExpr) {
        return (scope.type as? ClassOrInterfaceType)?.let { canonical(it.nameWithScope) }
    }
    val start = range(scope)?.first ?: return null
    return typing.typeOf(text(scope) ?: return null, start)
}

/** The single Level-typed parameter or earlier local visible at [call] that can carry the inserted argument. */
private fun MixinSource.levelInScope(call: MethodCallExpr, insertion: LegacyMixinTargetFacts.ParameterInsertion): String? =
    platformValueInScope(call, insertion.insertedType.removePrefix("L").removeSuffix(";").replace('/', '.'))

/** The single parameter or earlier local visible at [node] whose declared type is [required] (binary) or a subtype. */
private fun MixinSource.platformValueInScope(node: Node, required: String): String? =
    valuesInScope(node) { type ->
        val binary = (type as? ClassOrInterfaceType)?.let { canonical(it.nameWithScope) }?.let(::mixinBinaryName)
        binary != null && (binary == required || TargetMembers.inheritsFrom(binary, required))
    }.distinct().singleOrNull()

/** Names of the explicitly typed parameters and earlier locals visible at [start] whose type [accepts]. */
private fun MixinSource.valuesInScope(start: Node, accepts: (Type) -> Boolean): List<String> {
    val names = mutableListOf<String>()
    var child: Node = start
    var node: Node? = start.parentNode.orElse(null)
    while (node != null) {
        when (node) {
            is BlockStmt -> node.statements.takeWhile { it !== child }.forEach { statement ->
                val declaration = (statement as? ExpressionStmt)?.expression as? VariableDeclarationExpr
                declaration?.variables?.filter { accepts(it.type) }?.forEach { names += it.nameAsString }
            }
            is LambdaExpr -> node.parameters.filter { !it.type.isUnknownType && accepts(it.type) }
                .forEach { names += it.nameAsString }
            is CallableDeclaration<*> -> {
                node.parameters.filter { accepts(it.type) }.forEach { names += it.nameAsString }
                break
            }
            is TypeDeclaration<*>, is ObjectCreationExpr -> break
        }
        child = node
        node = node.parentNode.orElse(null)
    }
    return names
}

private fun MixinSource.insertArgument(call: MethodCallExpr, index: Int, argument: String): MixinEdit? {
    val arguments = call.arguments
    return when {
        index < arguments.size -> insertBefore(arguments[index], "$argument, ")
        arguments.isNotEmpty() -> insertAfter(arguments.last(), ", $argument")
        else -> openParenthesisAfter(call.name)?.let { MixinEdit(it + 1, it + 1, argument) }
    }
}

private val serverLevelFallbackAvailable: Boolean by lazy {
    "()Lnet/minecraft/server/MinecraftServer;" in
        TargetMembers.methodDescriptors("net.neoforged.neoforge.server.ServerLifecycleHooks", "getCurrentServer") &&
        "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;" in
        TargetMembers.methodDescriptors("net.minecraft.server.MinecraftServer", "getLevel") &&
        TargetMembers.hasField(LEVEL, "OVERWORLD")
}

/**
 * `return accessor.invoker(args);` directly in a method declared @Nullable, where no Level is in
 * scope: the running server's overworld supplies the argument (the member only reads server-global
 * state through it) and, with no server running, the method returns null as its contract allows.
 */
private fun MixinSource.serverLevelReturn(
    call: MethodCallExpr,
    insertion: LegacyMixinTargetFacts.ParameterInsertion
): MixinEdit? {
    if (!serverLevelFallbackAvailable) return null
    val required = insertion.insertedType.removePrefix("L").removeSuffix(";").replace('/', '.')
    if (!TargetMembers.inheritsFrom(SERVER_LEVEL, required)) return null
    val returned = call.parentNode.orElse(null) as? ReturnStmt ?: return null
    val block = returned.parentNode.orElse(null) as? BlockStmt ?: return null
    val method = block.parentNode.orElse(null) as? MethodDeclaration ?: return null
    if (method.type.isPrimitiveType || method.type.isVoidType) return null
    if (method.annotations.none { annotation -> NULLABLE_ANNOTATIONS.any { typing.isTypeName(annotation.nameAsString, it) } }) {
        return null
    }
    val statement = range(returned) ?: return null
    val callRange = range(call) ?: return null
    val lineStart = source.lastIndexOf('\n', statement.first - 1) + 1
    val indent = source.substring(lineStart, statement.first)
    if (indent.any { it != ' ' && it != '\t' }) return null
    val server = pass.uniqueJavaLocalName(text(method) ?: return null, "server")
    val argument = insertArgument(call, insertion.insertedIndex, "$server.getLevel($LEVEL.OVERWORLD)") ?: return null
    val callText = source.substring(callRange.first, argument.start) + argument.replacement +
        source.substring(argument.endExclusive, callRange.last + 1)
    val returnText = source.substring(statement.first, callRange.first) + callText +
        source.substring(callRange.last + 1, statement.last + 1)
    val step = if ('\t' in indent) "\t" else "    "
    val newline = if ("\r\n" in source) "\r\n" else "\n"
    val replacement = buildString {
        append("net.minecraft.server.MinecraftServer ").append(server)
            .append(" = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();").append(newline)
        append(indent).append("if (").append(server).append(" == null)").append(newline)
        append(indent).append(step).append("return null;").append(newline)
        append(newline)
        append(indent).append(returnText)
    }
    return MixinEdit(statement.first, statement.last + 1, replacement)
}

// ---------------------------------------------------------------------------------------------
// IMixinConfigPlugin.shouldApplyMixin(targetClassName, mixinClassName): Mixin never hands a plugin
// a target inside its own config's mixin package, so `targetClassName.startsWith("<that package>.")`
// can never hold; such a guard tests the mixin class name.

private fun StructuralRefactorPass.migrateUnreachableMixinPluginGuards(source: String): String {
    val configs = projectMixinFacts.configs.filter { it.plugin != null }
    if (configs.isEmpty() || !source.contains("shouldApplyMixin")) return source
    val parsed = parseMixinSource(source) ?: return source
    val edits = mutableListOf<MixinEdit>()
    for (type in parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        if (type.isInterface) continue
        val binary = type.fullyQualifiedName.orElse(null)?.let(::mixinBinaryName) ?: continue
        val packages = configs.filter { it.plugin == binary }.map { it.packageName }
        if (packages.isEmpty()) continue
        if (type.implementedTypes.none { parsed.typing.isTypeName(it.nameWithScope, MIXIN_CONFIG_PLUGIN) }) continue
        val method = type.methods.singleOrNull { it.nameAsString == "shouldApplyMixin" && it.parameters.size == 2 }
            ?: continue
        if (method.isStatic ||
            !method.type.isPrimitiveType ||
            method.type.asPrimitiveType().type != PrimitiveType.Primitive.BOOLEAN ||
            method.parameters.any { parsed.descriptor(it.type) != "Ljava/lang/String;" }
        ) {
            continue
        }
        val targetName = method.parameters[0].nameAsString
        val mixinName = method.parameters[1].nameAsString
        val body = method.body.orElse(null) ?: continue
        val names = setOf(targetName, mixinName)
        if (body.findAll(VariableDeclarator::class.java).any { it.nameAsString in names } ||
            body.findAll(Parameter::class.java).any { it.nameAsString in names } ||
            assigns(body, targetName) ||
            assigns(body, mixinName)
        ) {
            continue
        }
        for (call in body.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != "startsWith" || call.arguments.size != 1) continue
            val prefix = (call.arguments.single() as? StringLiteralExpr)?.value ?: continue
            val receiver = call.scope.orElse(null)?.let(::unwrapMixinExpression) as? NameExpr ?: continue
            if (receiver.nameAsString != targetName || packages.none { prefix.startsWith("$it.") }) continue
            edits += parsed.replace(receiver, mixinName) ?: continue
        }
    }
    return applyMixinEdits(source, edits) ?: source
}

// ---------------------------------------------------------------------------------------------
// Shared source model.

private const val MIXIN = "org.spongepowered.asm.mixin.Mixin"
private const val AT = "org.spongepowered.asm.mixin.injection.At"
private const val INJECT = "org.spongepowered.asm.mixin.injection.Inject"
private const val REDIRECT = "org.spongepowered.asm.mixin.injection.Redirect"
private const val MODIFY_ARG = "org.spongepowered.asm.mixin.injection.ModifyArg"
private const val MODIFY_EXPRESSION_VALUE = "com.llamalad7.mixinextras.injector.ModifyExpressionValue"
private const val INVOKER = "org.spongepowered.asm.mixin.gen.Invoker"
private const val LOCAL = "com.llamalad7.mixinextras.sugar.Local"
private const val CALLBACK_INFO = "org.spongepowered.asm.mixin.injection.callback.CallbackInfo"
private const val CALLBACK_INFO_RETURNABLE = "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable"
private const val MIXIN_CONFIG_PLUGIN = "org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin"
private const val LEVEL = "net.minecraft.world.level.Level"
private const val SERVER_LEVEL = "net.minecraft.server.level.ServerLevel"

/** Every injector annotation, so a handler carrying two of them is never treated as one kind. */
private val INJECTORS = listOf(
    INJECT,
    REDIRECT,
    MODIFY_ARG,
    MODIFY_EXPRESSION_VALUE,
    "org.spongepowered.asm.mixin.injection.ModifyArgs",
    "org.spongepowered.asm.mixin.injection.ModifyVariable",
    "org.spongepowered.asm.mixin.injection.ModifyConstant",
    "com.llamalad7.mixinextras.injector.ModifyReturnValue",
    "com.llamalad7.mixinextras.injector.ModifyReceiver",
    "com.llamalad7.mixinextras.injector.WrapWithCondition",
    "com.llamalad7.mixinextras.injector.v2.WrapWithCondition",
    "com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation",
    "com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod"
)

private val NULLABLE_ANNOTATIONS = listOf(
    "org.jetbrains.annotations.Nullable",
    "javax.annotation.Nullable",
    "jakarta.annotation.Nullable",
    "org.checkerframework.checker.nullness.qual.Nullable",
    "edu.umd.cs.findbugs.annotations.Nullable"
)

private val PRIMITIVE_DESCRIPTORS = mapOf(
    PrimitiveType.Primitive.BOOLEAN to "Z",
    PrimitiveType.Primitive.BYTE to "B",
    PrimitiveType.Primitive.CHAR to "C",
    PrimitiveType.Primitive.SHORT to "S",
    PrimitiveType.Primitive.INT to "I",
    PrimitiveType.Primitive.LONG to "J",
    PrimitiveType.Primitive.FLOAT to "F",
    PrimitiveType.Primitive.DOUBLE to "D"
)

/** Numeric primitives ordered by width; a smaller rank is a narrowing conversion target. */
private val PRIMITIVE_RANKS = mapOf("B" to 1, "S" to 2, "C" to 2, "I" to 3, "J" to 4, "F" to 5, "D" to 6)

private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
    "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
    "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "record",
    "yield"
)

private data class MixinEdit(val start: Int, val endExclusive: Int, val replacement: String)

private class Injector(val kind: String, val annotation: AnnotationExpr)

/** `Lowner;name(descriptor)` with [owner] as a binary class name (`net.minecraft.Foo$Bar`). */
private data class MemberReference(val owner: String, val name: String, val descriptor: String) {
    val internalOwner: String get() = owner.replace('.', '/')

    override fun toString(): String = "L$internalOwner;$name$descriptor"

    companion object {
        private val pattern = Regex("""^L([\w/$]+);([\w$]+)(\([^)]*\)\S+)$""")

        fun parse(text: String): MemberReference? = pattern.matchEntire(text)?.let {
            MemberReference(it.groupValues[1].replace('/', '.'), it.groupValues[2], it.groupValues[3])
        }
    }
}

private class MixinSource(val pass: StructuralRefactorPass, val source: String, val unit: CompilationUnit) {
    val typing = SourceValueTypes(pass, source)
    val packageName: String = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    private val lineStarts: List<Int> = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }

    fun range(node: Node): IntRange? = node.range.map {
        (lineStarts[it.begin.line - 1] + it.begin.column - 1)..(lineStarts[it.end.line - 1] + it.end.column - 1)
    }.orElse(null)

    fun text(node: Node): String? = range(node)?.let { source.substring(it.first, it.last + 1) }

    fun replace(node: Node, replacement: String): MixinEdit? =
        range(node)?.let { MixinEdit(it.first, it.last + 1, replacement) }

    fun insertBefore(node: Node, text: String): MixinEdit? = range(node)?.let { MixinEdit(it.first, it.first, text) }

    fun insertAfter(node: Node, text: String): MixinEdit? =
        range(node)?.let { MixinEdit(it.last + 1, it.last + 1, text) }

    fun openParenthesisAfter(node: Node): Int? {
        val end = range(node)?.last ?: return null
        val open = source.indexOf('(', end + 1)
        return open.takeIf { it > end && source.substring(end + 1, it).isBlank() }
    }

    fun annotation(annotations: List<AnnotationExpr>, canonical: String): AnnotationExpr? =
        annotations.singleOrNull { typing.isTypeName(it.nameAsString, canonical) }

    /** The canonical (dotted) name a source type name refers to, or null when it cannot be proven. */
    fun canonical(name: String): String? {
        if (name.isEmpty()) return null
        val outer = name.substringBefore('.')
        val nested = name.removePrefix(outer)
        val candidates = linkedSetOf<String>()
        if (outer.first().isLowerCase() && '.' in name) candidates += name
        unit.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == outer }
            ?.let { candidates += it.nameAsString + nested }
        unit.findAll(TypeDeclaration::class.java).filter { it.nameAsString == outer }.forEach { declaration ->
            declaration.fullyQualifiedName.ifPresent { candidates += it + nested }
        }
        candidates += if (packageName.isEmpty()) name else "$packageName.$name"
        candidates += "java.lang.$name"
        return candidates.firstOrNull { typing.isTypeName(name, it) }
    }

    /** The JVM descriptor of a source type's erasure, or null when it cannot be proven. */
    fun descriptor(type: Type): String? = when {
        type.isPrimitiveType -> PRIMITIVE_DESCRIPTORS[type.asPrimitiveType().type]
        type.isVoidType -> "V"
        type.isArrayType -> descriptor(type.asArrayType().componentType)?.let { "[$it" }
        type.isClassOrInterfaceType -> canonical(type.asClassOrInterfaceType().nameWithScope)
            ?.let { "L" + mixinBinaryName(it).replace('.', '/') + ";" }
        else -> null
    }

    fun isCallback(type: Type): Boolean {
        val classType = type as? ClassOrInterfaceType ?: return false
        return typing.isTypeName(classType.nameWithScope, CALLBACK_INFO) || isReturnableCallback(type)
    }

    fun isReturnableCallback(type: Type): Boolean {
        val classType = type as? ClassOrInterfaceType ?: return false
        return typing.isTypeName(classType.nameWithScope, CALLBACK_INFO_RETURNABLE)
    }

    /** The binary name of the single platform class a @Mixin type targets through a class literal. */
    fun mixinTarget(type: TypeDeclaration<*>): String? {
        val mixin = annotation(type.annotations, MIXIN) ?: return null
        if (mixin.member("targets") != null) return null
        val literal = when (val value = mixin.member("value")) {
            is ClassExpr -> value
            is ArrayInitializerExpr -> value.values.singleOrNull() as? ClassExpr
            else -> null
        } ?: return null
        val classType = literal.type as? ClassOrInterfaceType ?: return null
        val binary = canonical(classType.nameWithScope)?.let(::mixinBinaryName) ?: return null
        return binary.takeIf { TargetMembers.hasClass(it) }
    }

    fun injector(method: MethodDeclaration): Injector? = method.annotations.mapNotNull { annotation ->
        INJECTORS.firstOrNull { typing.isTypeName(annotation.nameAsString, it) }?.let { Injector(it, annotation) }
    }.singleOrNull()

    fun mixinInjectors(): List<Triple<String, MethodDeclaration, Injector>> =
        unit.findAll(ClassOrInterfaceDeclaration::class.java).flatMap { type ->
            val target = mixinTarget(type) ?: return@flatMap emptyList()
            type.methods.mapNotNull { method -> injector(method)?.let { Triple(target, method, it) } }
        }

    fun singleAt(injector: AnnotationExpr): AnnotationExpr? {
        val at = when (val value = injector.member("at")) {
            is AnnotationExpr -> value
            is ArrayInitializerExpr -> value.values.singleOrNull() as? AnnotationExpr
            else -> null
        } ?: return null
        return at.takeIf { typing.isTypeName(it.nameAsString, AT) }
    }
}

private fun StructuralRefactorPass.parseMixinSource(source: String): MixinSource? {
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return null
    return MixinSource(this, source, unit)
}

private fun applyMixinEdits(source: String, edits: List<MixinEdit>): String? {
    if (edits.isEmpty()) return source
    val ordered = edits.distinct().sortedWith(compareBy<MixinEdit> { it.start }.thenBy { it.endExclusive })
    if (ordered.any { it.start < 0 || it.endExclusive < it.start || it.endExclusive > source.length } ||
        ordered.zipWithNext().any { (left, right) -> left.endExclusive > right.start }
    ) {
        return null
    }
    var result = source
    for (edit in ordered.asReversed()) {
        result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.endExclusive)
    }
    return result
}

private fun AnnotationExpr.member(name: String): Expression? = when {
    isSingleMemberAnnotationExpr && name == "value" -> asSingleMemberAnnotationExpr().memberValue
    isNormalAnnotationExpr -> asNormalAnnotationExpr().pairs.singleOrNull { it.nameAsString == name }?.value
    else -> null
}

private fun AnnotationExpr.intMember(name: String): Int? = (member(name) as? IntegerLiteralExpr)?.asNumber()?.toInt()

/** A string member given as one literal (or a one-element array of it). */
private fun AnnotationExpr.singleString(name: String): StringLiteralExpr? = when (val value = member(name)) {
    is StringLiteralExpr -> value
    is ArrayInitializerExpr -> value.values.singleOrNull() as? StringLiteralExpr
    else -> null
}

private fun AnnotationExpr.invokeTarget(): StringLiteralExpr? {
    val value = (member("value") as? StringLiteralExpr)?.value
    if (value != "INVOKE" && value != "INVOKE_ASSIGN") return null
    return member("target") as? StringLiteralExpr
}

/** Return statements of [this] method itself (not of lambdas or classes declared inside it). */
private fun MethodDeclaration.ownReturns(): List<ReturnStmt> = findAll(ReturnStmt::class.java).filter { returned ->
    var parent = returned.parentNode.orElse(null)
    while (parent != null && parent !== this) {
        if (parent is LambdaExpr || parent is TypeDeclaration<*> || parent is ObjectCreationExpr) return@filter false
        parent = parent.parentNode.orElse(null)
    }
    true
}

private fun assigns(node: Node, name: String): Boolean =
    node.findAll(AssignExpr::class.java).any { (it.target as? NameExpr)?.nameAsString == name } ||
        node.findAll(UnaryExpr::class.java).any { unary ->
            unary.operator in setOf(
                UnaryExpr.Operator.PREFIX_INCREMENT,
                UnaryExpr.Operator.PREFIX_DECREMENT,
                UnaryExpr.Operator.POSTFIX_INCREMENT,
                UnaryExpr.Operator.POSTFIX_DECREMENT
            ) && (unary.expression as? NameExpr)?.nameAsString == name
        }

private fun unwrapMixinExpression(expression: Expression): Expression {
    var current = expression
    while (current is EnclosedExpr) current = current.inner
    return current
}

/** Whether [owner] or a platform class above it declared [member] on 1.20.1 (1.21.1 hierarchy). */
private fun existedOnLegacyPlatform(member: MemberReference): Boolean =
    platformAncestors(member.owner).any { LegacyMembers.declaresMethod(it, member.name, member.descriptor) }

/** Descriptors of the 1.21.1 methods [name] that [owner] declares or inherits from platform classes. */
private fun visibleNativeDescriptors(owner: String, name: String): Set<String> =
    platformAncestors(owner).flatMap { TargetMembers.methodDescriptors(it, name) }.toSet()

private fun platformAncestors(owner: String): List<String> {
    val seen = linkedSetOf<String>()
    val frontier = ArrayDeque(listOf(owner))
    while (frontier.isNotEmpty()) {
        val type = frontier.removeFirst()
        if (seen.add(type)) frontier.addAll(TargetMembers.supertypesOf(type))
    }
    return seen.toList()
}

private fun splitSelector(selector: String): Pair<String, String>? {
    val open = selector.indexOf('(')
    if (open <= 0) return null
    return selector.substring(0, open) to selector.substring(open)
}

private fun returnDescriptor(descriptor: String): String = descriptor.substringAfterLast(')')

/** `net.minecraft.Foo.Bar` -> `net.minecraft.Foo$Bar` (package segments are lower case). */
private fun mixinBinaryName(canonical: String): String {
    val parts = canonical.split('.')
    val firstType = parts.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
    if (firstType <= 0) return canonical
    return parts.take(firstType).joinToString(".") + "." + parts.drop(firstType).joinToString("$")
}

/** The Java source spelling of a JVM type descriptor (`F` -> `float`, `Lnet/Foo$Bar;` -> `net.Foo.Bar`). */
private fun javaSourceType(descriptor: String): String {
    val arrays = descriptor.takeWhile { it == '[' }.length
    val element = descriptor.substring(arrays)
    val base = when (element) {
        "Z" -> "boolean"
        "B" -> "byte"
        "C" -> "char"
        "S" -> "short"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        "V" -> "void"
        else -> element.removePrefix("L").removeSuffix(";").replace('/', '.').replace('$', '.')
    }
    return base + "[]".repeat(arrays)
}

private fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun isReadableParameterName(name: String): Boolean =
    Regex("""[A-Za-z_][A-Za-z0-9_]*""").matches(name) &&
        !Regex("""p_\d+_""").matches(name) &&
        name !in JAVA_KEYWORDS

private fun uniqueName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var suffix = 1
    while ("$base$suffix" in taken) suffix++
    return "$base$suffix"
}

// =============================================================================================
// Project-level member moves: these change several files (and the mixin configs) together, and a
// family changes nothing unless every file it touches is proven.

/**
 * Member moves that span files: an injector into a member that 1.21.1 moved to a subclass goes to
 * the project's mixin of that subclass in the same config list; an accessor of a static field that
 * became an instance method turns into an invoker called on a proven instance; invokers of methods
 * that became calls on a field turn into accessors of that field; and invokers of a removed static
 * helper give way, at every call site, to the 1.21.1 data-component iteration (an emptied accessor
 * and its config entry are removed).
 */
internal object LegacyMixinMemberMoves {
    const val RULE_ID = "struct-legacy-mixin-member-moves"

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): List<Change> {
        val project = MixinProject.open(pass, projectDir) ?: return emptyList()
        project.moveInjectors()
        project.migrateStaticFieldAccessors()
        project.migrateInvokerFieldAccessors()
        project.migrateRemovedStaticHelpers()
        return project.commit(dryRun)
    }
}

private const val ACCESSOR = "org.spongepowered.asm.mixin.gen.Accessor"

private class MixinConfigFile(val path: Path, val packageName: String, val lists: Map<String, List<String>>)

private class MixinClassFile(val fqn: String, val config: MixinConfigFile, val list: String, val path: Path)

private class MixinProject(
    val pass: StructuralRefactorPass,
    val javaRoot: Path,
    val javaFiles: List<Path>,
    val configs: List<MixinConfigFile>
) {
    private val originals = linkedMapOf<Path, String>()
    private val current = linkedMapOf<Path, String>()
    private val descriptions = linkedMapOf<Path, String>()
    private val deleted = linkedMapOf<Path, String>()

    val parents: Map<String, String> by lazy {
        pass.projectParents.ifEmpty { ProjectValueFacts.collect(pass, javaFiles).parents }
    }

    fun text(path: Path): String = current[path] ?: path.readText().also {
        originals[path] = it
        current[path] = it
    }

    fun write(path: Path, text: String, description: String) {
        text(path)
        current[path] = text
        descriptions[path] = description
    }

    fun delete(path: Path, description: String) {
        text(path)
        deleted[path] = description
    }

    fun parse(path: Path): MixinSource? = pass.parseMixinSource(text(path))

    fun sourceOf(fqn: String): Path? = javaRoot.resolve(fqn.replace('.', '/') + ".java")
        .takeIf { it.isRegularFile() && it !in deleted }

    fun mixinClasses(): List<MixinClassFile> = configs.flatMap { config ->
        config.lists.flatMap { (list, entries) ->
            entries.mapNotNull { entry ->
                val fqn = "${config.packageName}.$entry"
                sourceOf(fqn)?.let { MixinClassFile(fqn, config, list, it) }
            }
        }
    }

    fun javaFilesContaining(token: String): List<Path> =
        javaFiles.filter { it !in deleted && text(it).contains(token) }

    fun commit(dryRun: Boolean): List<Change> {
        val changed = current.filter { (path, text) -> path !in deleted && text != originals[path] }
        changed.forEach { (path, text) ->
            when (path.extension) {
                "java" -> check(pass.parseMixinSource(text) != null) { "Mixin member move produced invalid Java in $path" }
                "json" -> check(runCatching { Json.parseToJsonElement(text) }.isSuccess) {
                    "Mixin member move produced invalid JSON in $path"
                }
            }
        }
        if (!dryRun) {
            changed.forEach { (path, text) -> path.writeText(text) }
            deleted.keys.forEach { Files.delete(it) }
        }
        return changed.map { (path, text) ->
            Change(
                path, 1, descriptions.getValue(path), originals.getValue(path).take(160), text.take(160),
                Confidence.HIGH, LegacyMixinMemberMoves.RULE_ID
            )
        } + deleted.map { (path, description) ->
            Change(path, 1, description, originals.getValue(path).take(160), "", Confidence.HIGH, LegacyMixinMemberMoves.RULE_ID)
        }
    }

    companion object {
        fun open(pass: StructuralRefactorPass, projectDir: Path): MixinProject? {
            val javaRoot = projectDir.resolve("src/main/java")
            val resources = projectDir.resolve("src/main/resources")
            if (!javaRoot.isDirectory() || !resources.isDirectory()) return null
            val configs = Files.walk(resources).use { paths ->
                paths.filter { path ->
                    path.isRegularFile() && path.name.endsWith(".json") &&
                        resources.relativize(path).firstOrNull()?.toString() !in setOf("assets", "data")
                }.toList()
            }.mapNotNull(::parseMixinConfigFile)
            if (configs.isEmpty()) return null
            val generated = javaRoot.resolve("com/modporter/generated")
            val javaFiles = Files.walk(javaRoot).use { paths ->
                paths.filter { it.isRegularFile() && it.name.endsWith(".java") && !it.startsWith(generated) }
                    .sorted()
                    .toList()
            }
            return MixinProject(pass, javaRoot, javaFiles, configs)
        }

        private fun parseMixinConfigFile(path: Path): MixinConfigFile? {
            val text = runCatching { path.readText() }.getOrNull() ?: return null
            if (!text.contains("\"package\"")) return null
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val packageName = (root["package"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val lists = listOf("mixins", "client", "server").mapNotNull { key ->
                val array = root[key] as? JsonArray ?: return@mapNotNull null
                key to array.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
            }.toMap()
            return if (lists.isEmpty()) null else MixinConfigFile(path, packageName, lists)
        }
    }
}

// --- injectors into a member 1.21.1 moved to a subclass ------------------------------------------

private fun MixinProject.moveInjectors() {
    for (move in LegacyMixinTargetFacts.injectorMoves) {
        if (!move.proven) continue
        for (source in mixinClasses()) {
            var guard = 0
            while (guard++ < 32 && moveOneInjector(move, source)) {
                // the source and destination are re-read after every move
            }
        }
    }
}

private fun MixinProject.moveOneInjector(move: LegacyMixinTargetFacts.InjectorMove, source: MixinClassFile): Boolean {
    val (legacyName, _) = splitSelector(move.legacyMethod) ?: return false
    if (!text(source.path).contains(legacyName)) return false
    val parsed = parse(source.path) ?: return false
    val type = parsed.typeNamed(source.fqn) ?: return false
    if (parsed.mixinTarget(type) != move.legacyOwner) return false
    val handler = type.methods.firstOrNull { parsed.movableTo(move, type, it) } ?: return false
    val destination = mixinClasses().filter { candidate ->
        candidate.config === source.config && candidate.list == source.list && candidate.fqn != source.fqn &&
            parse(candidate.path)?.let { other -> other.typeNamed(candidate.fqn)?.let(other::mixinTarget) } ==
            move.nativeOwner
    }.singleOrNull() ?: return false
    val target = parse(destination.path) ?: return false
    val targetType = target.typeNamed(destination.fqn) ?: return false
    val priority = parsed.mixinPriority(type)
    val targetPriority = target.mixinPriority(targetType)
    if (priority != null && targetPriority != null && priority != targetPriority) return false

    val imports = parsed.importsUsedBy(handler)
    val sameModuleTypes = parsed.samePackageTypesUsedBy(handler, this)
        .takeIf { parsed.packageName != target.packageName }.orEmpty()
    val conflicts = (imports.filter { !it.isStatic }.map { it.nameAsString } + sameModuleTypes).any { fqn ->
        val simple = fqn.substringAfterLast('.')
        target.unit.imports.any { !it.isStatic && it.name.identifier == simple && it.nameAsString != fqn } ||
            target.unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simple }
    }
    if (conflicts) return false

    val moved = parsed.movedHandlerText(handler, move, target, targetType) ?: return false
    var destinationText = target.withMember(targetType, moved) ?: return false
    if (priority != null && targetPriority == null) {
        val reparsed = pass.parseMixinSource(destinationText) ?: return false
        destinationText = reparsed.typeNamed(destination.fqn)?.let { reparsed.withMixinPriority(it, priority) }
            ?: return false
    }
    for (import in imports) {
        destinationText = if (import.isStatic) {
            addStaticImport(destinationText, import.nameAsString)
        } else {
            pass.addImportIfMissing(destinationText, import.nameAsString)
        }
    }
    sameModuleTypes.forEach { destinationText = pass.addImportIfMissing(destinationText, it) }

    var sourceText = parsed.withoutMember(handler) ?: return false
    sourceText = pass.removeUnusedSimpleImports(sourceText, imports.filter { !it.isStatic }.map { it.nameAsString })
    write(destination.path, destinationText, "Move the injector of 1.20.1 ${move.legacyMethod} into this mixin of ${move.nativeOwner}")
    write(source.path, sourceText, "Move the injector of 1.20.1 ${move.legacyMethod} to the mixin of ${move.nativeOwner}")
    return true
}

/** Whether [method] is an injector into the moved member that keeps its meaning on the native method. */
private fun MixinSource.movableTo(
    move: LegacyMixinTargetFacts.InjectorMove,
    type: ClassOrInterfaceDeclaration,
    method: MethodDeclaration
): Boolean {
    val injector = injector(method) ?: return false
    val (legacyName, legacyDescriptor) = splitSelector(move.legacyMethod) ?: return false
    val (_, nativeDescriptor) = splitSelector(move.nativeMethod) ?: return false
    val selector = injector.annotation.singleString("method")?.value ?: return false
    if (selector != move.legacyMethod &&
        !(selector == legacyName && LegacyMembers.methodDescriptors(move.legacyOwner, legacyName).size == 1)
    ) {
        return false
    }
    val invoke = singleAt(injector.annotation)?.invokeTarget()?.value ?: return false
    if (invoke !in move.sharedInvokes || method.typeParameters.isNotEmpty()) return false
    if (!followsSplitMethod(injector, method, legacyDescriptor, nativeDescriptor)) return false
    val body = method.body.orElse(null) ?: return false
    val fields = type.fields.flatMap { it.variables }.map { it.nameAsString }.toSet()
    val methods = type.methods.filter { it !== method }.map { it.nameAsString }.toSet()
    return body.findAll(SuperExpr::class.java).isEmpty() &&
        body.findAll(MethodCallExpr::class.java).none { !it.scope.isPresent && it.nameAsString in methods } &&
        body.findAll(NameExpr::class.java).none { it.nameAsString in fields } &&
        body.findAll(MethodReferenceExpr::class.java).none { it.scope is ThisExpr && it.identifier in methods } &&
        body.findAll(com.github.javaparser.ast.expr.FieldAccessExpr::class.java)
            .none { it.scope is ThisExpr && it.nameAsString in fields }
}

/** The handler's source text re-indented for [targetType], its selector naming the native method. */
private fun MixinSource.movedHandlerText(
    handler: MethodDeclaration,
    move: LegacyMixinTargetFacts.InjectorMove,
    target: MixinSource,
    targetType: ClassOrInterfaceDeclaration
): String? {
    val (legacyName, _) = splitSelector(move.legacyMethod) ?: return null
    val (nativeName, nativeDescriptor) = splitSelector(move.nativeMethod) ?: return null
    val selector = injector(handler)?.annotation?.singleString("method") ?: return null
    val nativeSelector = if (selector.value == legacyName &&
        TargetMembers.methodDescriptors(move.nativeOwner, nativeName).size == 1
    ) {
        nativeName
    } else {
        nativeName + nativeDescriptor
    }
    val start = memberStart(handler) ?: return null
    val end = range(handler)?.last?.plus(1) ?: return null
    val selectorRange = range(selector) ?: return null
    val text = source.substring(start, selectorRange.first) + javaString(nativeSelector) +
        source.substring(selectorRange.last + 1, end)
    val indent = lineIndent(start) ?: return null
    val targetIndent = target.memberIndent(targetType) ?: indent
    return text.replace("\r\n", "\n").split('\n').mapIndexed { index, line ->
        if (index > 0 && line.startsWith(indent)) targetIndent + line.removePrefix(indent) else line
    }.joinToString("\n")
}

private fun MixinSource.typeNamed(fqn: String): ClassOrInterfaceDeclaration? =
    unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull { it.fullyQualifiedName.orElse(null) == fqn }

/** Start of [member] including its documentation comment. */
private fun MixinSource.memberStart(member: Node): Int? {
    val start = range(member)?.first ?: return null
    val comment = member.comment.orElse(null)?.let(::range)?.first
    return if (comment != null && comment < start) comment else start
}

/** The whitespace before [offset] on its line, or null when other text precedes it. */
private fun MixinSource.lineIndent(offset: Int): String? {
    val lineStart = source.lastIndexOf('\n', offset - 1) + 1
    return source.substring(lineStart, offset).takeIf { it.all { character -> character == ' ' || character == '\t' } }
}

private fun MixinSource.memberIndent(type: ClassOrInterfaceDeclaration): String? =
    type.members.firstOrNull()?.let { memberStart(it) }?.let { lineIndent(it) }

private val MixinSource.newline: String get() = if (source.contains("\r\n")) "\r\n" else "\n"

/** [source] with [member] (already indented for [type], first line unindented) added to [type]. */
private fun MixinSource.withMember(type: ClassOrInterfaceDeclaration, member: String): String? {
    val indent = memberIndent(type) ?: return null
    val text = member.replace("\n", newline)
    val constructor = type.constructors.lastOrNull()
    if (constructor != null) {
        val end = range(constructor)?.last?.plus(1) ?: return null
        return source.substring(0, end) + newline + newline + indent + text + source.substring(end)
    }
    val first = type.members.firstOrNull() ?: return null
    val start = memberStart(first) ?: return null
    return source.substring(0, start) + text + newline + newline + indent + source.substring(start)
}

/** [source] without [member]'s lines (and the blank line it leaves after an opening or blank line). */
private fun MixinSource.withoutMember(member: Node): String? {
    val start = memberStart(member) ?: return null
    lineIndent(start) ?: return null
    val lineStart = source.lastIndexOf('\n', start - 1) + 1
    val end = range(member)?.last ?: return null
    val newlineAfter = source.indexOf('\n', end)
    if (newlineAfter < 0) return null
    if (source.substring(end + 1, newlineAfter).isNotBlank()) return null
    var lineEnd = newlineAfter + 1
    val previous = source.substring(0, lineStart).trimEnd(' ', '\t').removeSuffix("\n").removeSuffix("\r")
    val previousLine = previous.substringAfterLast('\n')
    val nextNewline = source.indexOf('\n', lineEnd)
    if (nextNewline >= 0 && source.substring(lineEnd, nextNewline).isBlank() &&
        (previousLine.isBlank() || previousLine.trimEnd().endsWith("{"))
    ) {
        lineEnd = nextNewline + 1
    }
    return source.removeRange(lineStart, lineEnd)
}

private fun MixinSource.mixinPriority(type: TypeDeclaration<*>): String? =
    (annotation(type.annotations, MIXIN)?.member("priority") as? IntegerLiteralExpr)?.value

private fun MixinSource.withMixinPriority(type: TypeDeclaration<*>, priority: String): String? {
    val mixin = annotation(type.annotations, MIXIN) ?: return null
    val range = range(mixin) ?: return null
    val replacement = when {
        mixin.isSingleMemberAnnotationExpr ->
            "@${mixin.nameAsString}(value = ${text(mixin.asSingleMemberAnnotationExpr().memberValue) ?: return null}, priority = $priority)"
        mixin.isNormalAnnotationExpr -> (text(mixin) ?: return null).let { it.substring(0, it.lastIndexOf(')')) + ", priority = $priority)" }
        else -> return null
    }
    return source.substring(0, range.first) + replacement + source.substring(range.last + 1)
}

/** The imports of this file whose simple name [node]'s code mentions. */
private fun MixinSource.importsUsedBy(node: Node): List<com.github.javaparser.ast.ImportDeclaration> {
    val code = com.modporter.core.transforms.util.JavaSourceText.maskCommentsAndLiterals(text(node) ?: return emptyList()) +
        node.findAll(AnnotationExpr::class.java).joinToString(" ") { it.nameAsString }
    return unit.imports.filter { import ->
        !import.isAsterisk &&
            Regex("""(?<![\w$.])${Regex.escape(import.name.identifier)}(?![\w$])""").containsMatchIn(code)
    }
}

/** Types of this file's own package that [node] names by simple name (they need an import elsewhere). */
private fun MixinSource.samePackageTypesUsedBy(node: Node, project: MixinProject): List<String> {
    val code = com.modporter.core.transforms.util.JavaSourceText.maskCommentsAndLiterals(text(node) ?: return emptyList())
    val imported = unit.imports.map { it.name.identifier }.toSet()
    return Regex("""(?<![\w$.])([A-Z][\w$]*)""").findAll(code).map { it.groupValues[1] }.distinct()
        .filter { it !in imported && project.sourceOf("$packageName.$it") != null }
        .map { "$packageName.$it" }
        .toList()
}

private fun addStaticImport(source: String, name: String): String {
    if (Regex("""(?m)^[ \t]*import\s+static\s+${Regex.escape(name)}\s*;""").containsMatchIn(source)) return source
    val imports = Regex("""(?m)^[ \t]*import\s+[^;]+;[ \t]*\r?\n""").findAll(source).lastOrNull() ?: return source
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    return source.substring(0, imports.range.last + 1) + "import static $name;$newline" +
        source.substring(imports.range.last + 1)
}

// --- static fields that became instance methods -------------------------------------------------

private fun MixinProject.migrateStaticFieldAccessors() {
    for (entry in LegacyMixinTargetFacts.staticFieldMethods) {
        if (!entry.proven) continue
        val (nativeName, _) = splitSelector(entry.nativeMethod) ?: continue
        for (accessor in mixinClasses()) {
            if (!text(accessor.path).contains(entry.legacyField)) continue
            val parsed = parse(accessor.path) ?: continue
            val type = parsed.typeNamed(accessor.fqn) ?: continue
            if (parsed.mixinTarget(type) != entry.owner) continue
            val names = type.methods.filter { method ->
                val annotation = parsed.annotation(method.annotations, ACCESSOR)
                (annotation?.member("value") as? StringLiteralExpr)?.value == entry.legacyField &&
                    method.isStatic &&
                    method.parameters.isEmpty() &&
                    parsed.descriptor(method.type) == entry.fieldDescriptor
            }.map { it.nameAsString }
            names.forEach { migrateStaticFieldAccessor(accessor, it, entry, nativeName) }
        }
    }
}

private fun MixinProject.migrateStaticFieldAccessor(
    accessor: MixinClassFile,
    name: String,
    entry: LegacyMixinTargetFacts.StaticFieldMethod,
    nativeName: String
) {
    if (javaFilesContaining("::$name").isNotEmpty()) return
    val callers = linkedMapOf<Path, String>()
    for (file in javaFilesContaining(name)) {
        if (file == accessor.path) continue
        val parsed = parse(file) ?: return
        val edits = mutableListOf<MixinEdit>()
        for (call in parsed.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != name) continue
            val scope = call.scope.orElse(null) ?: return
            if (!parsed.typing.isTypeName(scope.toString(), accessor.fqn)) continue
            if (call.arguments.isNotEmpty()) return
            val receiver = parsed.instanceReceiver(call, entry.owner, this) ?: return
            edits += parsed.replace(call, "((${parsed.text(scope) ?: return}) $receiver).$name()") ?: return
        }
        if (edits.isNotEmpty()) callers[file] = applyMixinEdits(parsed.source, edits) ?: return
    }
    val parsed = parse(accessor.path) ?: return
    val method = parsed.typeNamed(accessor.fqn)?.methods?.singleOrNull { it.nameAsString == name } ?: return
    val range = range(parsed, method) ?: return
    val indent = parsed.lineIndent(range.first) ?: return
    val declaration = "@Invoker(\"$nativeName\")${parsed.newline}$indent${parsed.text(method.type) ?: return} $name();"
    var accessorText = parsed.source.substring(0, range.first) + declaration + parsed.source.substring(range.last + 1)
    accessorText = pass.addImportIfMissing(accessorText, INVOKER)
    accessorText = pass.removeUnusedSimpleImports(accessorText, listOf(ACCESSOR))
    write(accessor.path, accessorText, "Invoke ${entry.nativeMethod} where 1.20.1 read the static ${entry.legacyField}")
    callers.forEach { (file, text) ->
        write(file, text, "Read ${entry.legacyField} through the 1.21.1 instance method on the provider in scope")
    }
}

private fun range(parsed: MixinSource, node: Node): IntRange? = parsed.range(node)

/** The single proven instance of [owner] at [call]: a typed value, a library-typed callback parameter, or `this`. */
private fun MixinSource.instanceReceiver(call: MethodCallExpr, owner: String, project: MixinProject): String? {
    val candidates = linkedSetOf<String>()
    candidates += valuesInScope(call) { type -> classTypeExtends(type, owner, project) }
    val shadowed = mutableSetOf<String>()
    var node: Node? = call.parentNode.orElse(null)
    while (node != null) {
        if (node is ObjectCreationExpr && node.anonymousClassBody.isPresent) break
        if (node is TypeDeclaration<*>) break
        if (node is LambdaExpr) {
            libraryCallbackParameter(node, owner, project)?.takeIf { it !in shadowed }?.let { candidates += it }
            node.parameters.forEach { shadowed += it.nameAsString }
        }
        if (node is CallableDeclaration<*>) {
            val static = (node as? MethodDeclaration)?.isStatic == true
            val enclosing = node.findAncestor(TypeDeclaration::class.java).orElse(null)
                ?.fullyQualifiedName?.orElse(null)
            if (!static && enclosing != null && classExtends(enclosing, owner, project)) candidates += "this"
            break
        }
        node = node.parentNode.orElse(null)
    }
    return candidates.singleOrNull()
}

private fun MixinSource.classTypeExtends(type: Type, owner: String, project: MixinProject): Boolean {
    val canonical = (type as? ClassOrInterfaceType)?.let { canonical(it.nameWithScope) } ?: return false
    return classExtends(canonical, owner, project)
}

/** Whether project or platform class [canonical] is, or extends, [owner] (a platform or library class). */
private fun classExtends(canonical: String, owner: String, project: MixinProject): Boolean {
    var current: String? = canonical
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        val binary = mixinBinaryName(current)
        if (current == owner || binary == owner) return true
        if (TargetMembers.hasClass(binary)) return TargetMembers.inheritsFrom(binary, owner)
        current = project.parents[current]
    }
    return false
}

/** The implicitly typed parameter of [lambda] that a library builder callback types as [owner]. */
private fun MixinSource.libraryCallbackParameter(lambda: LambdaExpr, owner: String, project: MixinProject): String? {
    val call = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return null
    val argument = call.arguments.indexOfFirst { it === lambda }
    for (builder in LegacyMixinTargetFacts.libraryBuilders) {
        for (callback in builder.callbacks) {
            if (call.nameAsString != callback.method || argument != callback.argument) continue
            if (callback.platformSupertype != owner && !TargetMembers.inheritsFrom(callback.platformSupertype, owner)) continue
            val parameter = lambda.parameters.getOrNull(callback.lambdaParameter) ?: continue
            if (!parameter.type.isUnknownType) continue
            if (libraryBuilder(call.scope.orElse(null), project, 0) !== builder) continue
            return parameter.nameAsString
        }
    }
    return null
}

/** The library builder [expression] is proven to produce through a root call and builder methods. */
private fun MixinSource.libraryBuilder(
    expression: Expression?,
    project: MixinProject,
    depth: Int
): LegacyMixinTargetFacts.LibraryBuilder? {
    if (depth > 64) return null
    val call = expression?.let(::unwrapMixinExpression) as? MethodCallExpr ?: return null
    val name = call.nameAsString
    val scope = call.scope.orElse(null)
    LegacyMixinTargetFacts.libraryBuilders.firstOrNull { builder ->
        scope != null && builder.roots.any { (owner, methods) -> name in methods && rootReceiver(scope, owner, project) }
    }?.let { return it }
    val inner = libraryBuilder(scope, project, depth + 1) ?: return null
    if (name in inner.selfReturning) return inner
    if (name == inner.transform && call.arguments.size == 1 &&
        transformResult(call.arguments.single(), inner, project) == inner.type
    ) {
        return inner
    }
    return null
}

private fun MixinSource.rootReceiver(scope: Expression, owner: String, project: MixinProject): Boolean {
    val start = range(scope)?.first ?: return false
    val type = typing.typeOf(text(scope) ?: return false, start) ?: return false
    return classExtends(type, owner, project)
}

/** The builder type a project function passed to the builder's transform method returns. */
private fun MixinSource.transformResult(
    argument: Expression,
    builder: LegacyMixinTargetFacts.LibraryBuilder,
    project: MixinProject
): String? {
    val call = unwrapMixinExpression(argument) as? MethodCallExpr ?: return null
    if (call.arguments.isNotEmpty()) return null
    val owner = when (val scope = call.scope.orElse(null)) {
        null -> unit.imports.singleOrNull { it.isStatic && !it.isAsterisk && it.name.identifier == call.nameAsString }
            ?.name?.qualifier?.orElse(null)?.asString()
        else -> canonical(scope.toString())
    } ?: return null
    val declaring = project.sourceOf(owner)?.let(project::parse) ?: return null
    val method = declaring.typeNamed(owner)?.methods?.singleOrNull {
        it.nameAsString == call.nameAsString && it.parameters.isEmpty() && it.isStatic
    } ?: return null
    val returned = method.type as? ClassOrInterfaceType ?: return null
    val index = builder.transformFunctions[declaring.canonical(returned.nameWithScope) ?: return null] ?: return null
    val result = returned.typeArguments.orElse(null)?.getOrNull(index) as? ClassOrInterfaceType ?: return null
    return declaring.canonical(result.nameWithScope)
}

// --- methods that became calls on a field ---------------------------------------------------------

private fun MixinProject.migrateInvokerFieldAccessors() {
    val moves = LegacyMixinTargetFacts.invokerFieldMoves.filter { it.proven }
    if (moves.isEmpty()) return
    for (accessor in mixinClasses()) {
        val parsed = parse(accessor.path) ?: continue
        val type = parsed.typeNamed(accessor.fqn) ?: continue
        val target = parsed.mixinTarget(type) ?: continue
        val matches = type.methods.mapNotNull { method ->
            val invoker = parsed.annotation(method.annotations, INVOKER) ?: return@mapNotNull null
            val name = invokedName(method, invoker) ?: return@mapNotNull null
            moves.firstOrNull { move ->
                move.nativeOwner == target &&
                    splitSelector(move.legacyMethod)?.first == name &&
                    !method.isStatic &&
                    method.parameters.map { parsed.descriptor(it.type) } == TargetMembers.parameterTypes(move.legacyMethod) &&
                    parsed.descriptor(method.type) == returnDescriptor(move.legacyMethod)
            }?.let { method.nameAsString to it }
        }.toMap()
        if (matches.isNotEmpty()) migrateInvokerFieldFamily(accessor, matches)
    }
}

private fun MixinProject.migrateInvokerFieldFamily(
    accessor: MixinClassFile,
    invokers: Map<String, LegacyMixinTargetFacts.InvokerFieldMove>
) {
    val parsed = parse(accessor.path) ?: return
    val type = parsed.typeNamed(accessor.fqn) ?: return
    val existing = type.methods.map { it.nameAsString }.toMutableSet()
    val prefix = invokers.keys.first().let { if ('$' in it) it.substringBeforeLast('$') + "$" else "" }
    val fieldAccessors = linkedMapOf<String, String>()
    invokers.values.forEach { move ->
        fieldAccessors.getOrPut(move.nativeField) {
            uniqueName(prefix + "get" + move.nativeField.replaceFirstChar { it.uppercase() }, existing).also { existing += it }
        }
    }
    if (invokers.keys.any { javaFilesContaining("::$it").isNotEmpty() }) return
    val callers = linkedMapOf<Path, String>()
    for (file in javaFiles.filter { file -> invokers.keys.any { text(file).contains(it) } }) {
        if (file == accessor.path) continue
        val source = parse(file) ?: return
        val edits = mutableListOf<MixinEdit>()
        for (call in source.unit.findAll(MethodCallExpr::class.java)) {
            val move = invokers[call.nameAsString] ?: continue
            val receiver = source.receiverType(call) ?: return
            if (receiver != accessor.fqn) continue
            val scope = call.scope.orElse(null)?.let { source.text(it) } ?: return
            val appended = move.appendedParameters.map { parameter ->
                source.platformValueInScope(call, parameter.removePrefix("L").removeSuffix(";").replace('/', '.')) ?: return
            }
            val arguments = call.arguments.map { source.text(it) ?: return } + appended
            val (callName, _) = splitSelector(move.nativeCall) ?: return
            edits += source.replace(
                call,
                "$scope.${fieldAccessors.getValue(move.nativeField)}().$callName(${arguments.joinToString(", ")})"
            ) ?: return
        }
        if (edits.isNotEmpty()) callers[file] = applyMixinEdits(source.source, edits) ?: return
    }

    val methods = type.methods.filter { it.nameAsString in invokers }
    val first = methods.first()
    val firstStart = parsed.memberStart(first) ?: return
    val indent = parsed.lineIndent(firstStart) ?: return
    val declarations = invokers.values.map { it.nativeField to it.nativeFieldDescriptor }.distinct()
        .joinToString(parsed.newline + parsed.newline + indent) { (field, descriptor) ->
            "@Accessor(\"$field\")${parsed.newline}$indent${javaSourceType(descriptor)} ${fieldAccessors.getValue(field)}();"
        }
    val edits = mutableListOf(MixinEdit(firstStart, (parsed.range(first) ?: return).last + 1, declarations))
    for (method in methods.drop(1)) {
        val start = parsed.memberStart(method) ?: return
        var lineStart = parsed.source.lastIndexOf('\n', start - 1) + 1
        val previousStart = if (lineStart > 0) parsed.source.lastIndexOf('\n', lineStart - 2) + 1 else -1
        if (previousStart >= 0 && parsed.source.substring(previousStart, lineStart).isBlank()) lineStart = previousStart
        val end = parsed.range(method)?.last ?: return
        val lineEnd = parsed.source.indexOf('\n', end).let { if (it < 0) parsed.source.length else it + 1 }
        edits += MixinEdit(lineStart, lineEnd, "")
    }
    var accessorText = applyMixinEdits(parsed.source, edits) ?: return
    accessorText = pass.addImportIfMissing(accessorText, ACCESSOR)
    val removedTypes = methods.flatMap { method ->
        (method.parameters.map { it.type } + method.type).mapNotNull { (it as? ClassOrInterfaceType)?.let { type -> parsed.canonical(type.nameWithScope) } }
    }
    accessorText = pass.removeUnusedSimpleImports(accessorText, listOf(INVOKER) + removedTypes.distinct())
    write(accessor.path, accessorText, "Access the 1.21.1 fields that replaced the removed ${accessor.fqn.substringAfterLast('.')} invokers")
    callers.forEach { (file, text) -> write(file, text, "Call the 1.21.1 field members that replaced the removed invokers") }
}

// --- removed static helpers replaced by a data-component iteration ---------------------------------

private fun MixinProject.migrateRemovedStaticHelpers() {
    for (helper in LegacyMixinTargetFacts.removedStaticHelpers) {
        if (!helper.proven) continue
        val (legacyName, legacyDescriptor) = splitSelector(helper.legacyMethod) ?: continue
        for (accessor in mixinClasses()) {
            if (!text(accessor.path).contains(legacyName)) continue
            val parsed = parse(accessor.path) ?: continue
            val type = parsed.typeNamed(accessor.fqn) ?: continue
            if (parsed.mixinTarget(type) != helper.owner) continue
            val names = type.methods.filter { method ->
                val invoker = parsed.annotation(method.annotations, INVOKER)
                invoker != null && invokedName(method, invoker) == legacyName && method.isStatic &&
                    method.parameters.map { parsed.descriptor(it.type) } == TargetMembers.parameterTypes(legacyDescriptor) &&
                    parsed.descriptor(method.type) == returnDescriptor(legacyDescriptor)
            }.map { it.nameAsString }
            names.forEach { migrateRemovedStaticHelper(accessor, it, helper) }
        }
    }
}

private fun MixinProject.migrateRemovedStaticHelper(
    accessor: MixinClassFile,
    name: String,
    helper: LegacyMixinTargetFacts.RemovedStaticHelper
) {
    if (javaFilesContaining("::$name").isNotEmpty()) return
    val callers = linkedMapOf<Path, String>()
    for (file in javaFilesContaining(name)) {
        if (file == accessor.path) continue
        val source = parse(file) ?: return
        val edits = mutableListOf<MixinEdit>()
        for (call in source.unit.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString != name) continue
            val scope = call.scope.orElse(null) ?: return
            if (!source.typing.isTypeName(scope.toString(), accessor.fqn)) continue
            val statement = call.parentNode.orElse(null) as? ExpressionStmt ?: return
            edits += source.replace(statement, source.componentLoop(statement, call, helper) ?: return) ?: return
        }
        if (edits.isEmpty()) continue
        val migrated = applyMixinEdits(source.source, edits) ?: return
        callers[file] = pass.removeUnusedSimpleImports(migrated, listOf(accessor.fqn))
    }

    val parsed = parse(accessor.path) ?: return
    val type = parsed.typeNamed(accessor.fqn) ?: return
    val method = type.methods.singleOrNull { it.nameAsString == name } ?: return
    if (type.members.size == 1 && parsed.unit.types.size == 1) {
        val configs = configs.filter { config ->
            config.lists.values.any { entries -> entries.any { "${config.packageName}.$it" == accessor.fqn } }
        }
        val edited = configs.associate { config ->
            config.path to (removeMixinEntry(text(config.path), accessor.fqn.removePrefix(config.packageName + ".")) ?: return)
        }
        delete(accessor.path, "Remove the accessor of the removed ${helper.owner.substringAfterLast('.')} helper")
        edited.forEach { (path, text) -> write(path, text, "Unregister the removed ${accessor.fqn.substringAfterLast('.')}") }
    } else {
        var accessorText = parsed.withoutMember(method) ?: return
        accessorText = pass.removeUnusedSimpleImports(accessorText, listOf(INVOKER))
        write(accessor.path, accessorText, "Remove the invoker of the removed ${helper.legacyMethod}")
    }
    callers.forEach { (file, text) ->
        write(file, text, "Iterate the 1.21.1 component that replaced ${helper.owner.substringAfterLast('.')}.${helper.legacyMethod}")
    }
}

/** `for (Element e : stack.getOrDefault(COMPONENT, EMPTY).list()) consumer(e.value());` for a helper call statement. */
private fun MixinSource.componentLoop(
    statement: ExpressionStmt,
    call: MethodCallExpr,
    helper: LegacyMixinTargetFacts.RemovedStaticHelper
): String? {
    if (call.arguments.size != 2) return null
    val stack = text(call.arguments[0]) ?: return null
    val element = pass.uniqueJavaLocalName(
        statement.findAncestor(CallableDeclaration::class.java).orElse(null)?.let(::text) ?: source,
        helper.elementType.substringAfterLast('.').replaceFirstChar { it.lowercase() }
    )
    val value = "$element.${helper.elementValue}()"
    val enclosingFields = statement.findAncestor(TypeDeclaration::class.java).orElse(null)
        ?.fields?.flatMap { it.variables }?.map { it.nameAsString }?.toSet().orEmpty()
    val callable = statement.findAncestor(CallableDeclaration::class.java).orElse(null)
    // `name::method` parses with a type-shaped scope; it is a value when the callable declares that name
    fun declaredVariable(name: String): Boolean = callable != null && name !in enclosingFields && (
        callable.findAll(Parameter::class.java).any { it.nameAsString == name } ||
            callable.findAll(VariableDeclarator::class.java).any { it.nameAsString == name } ||
            callable.findAll(com.github.javaparser.ast.expr.TypePatternExpr::class.java).any { it.nameAsString == name }
        )
    val accept = when (val consumer = unwrapMixinExpression(call.arguments[1])) {
        is MethodReferenceExpr -> {
            val receiver = consumer.scope
            val typeShaped = (receiver as? com.github.javaparser.ast.expr.TypeExpr)?.type as? ClassOrInterfaceType
            val stable = receiver is ThisExpr ||
                receiver is NameExpr && declaredVariable(receiver.nameAsString) ||
                typeShaped != null && !typeShaped.scope.isPresent && !typeShaped.typeArguments.isPresent &&
                declaredVariable(typeShaped.nameAsString)
            val typeArguments = consumer.typeArguments.map { it.isNonEmpty }.orElse(false)
            if (!stable || consumer.identifier == "new" || typeArguments) return null
            "${text(receiver) ?: return null}.${consumer.identifier}($value);"
        }
        is NameExpr -> if (!declaredVariable(consumer.nameAsString)) return null else "${consumer.nameAsString}.accept($value);"
        else -> return null
    }
    val start = range(statement)?.first ?: return null
    val indent = lineIndent(start) ?: return null
    val step = if ('\t' in indent || source.contains("\n\t")) "\t" else "    "
    return "for (${helper.elementType} $element : $stack.getOrDefault(${helper.component}, ${helper.componentDefault})" +
        ".${helper.listMethod}())$newline$indent$step$accept"
}

/** [config] without the array entry [entry] (fixing the separator when it was the last element). */
private fun removeMixinEntry(config: String, entry: String): String? {
    val line = Regex("""(?m)^[ \t]*"${Regex.escape(entry)}"[ \t]*(,?)[ \t]*\r?\n""")
    val match = line.findAll(config).toList().singleOrNull() ?: return null
    var result = config.removeRange(match.range)
    if (match.groupValues[1].isEmpty()) {
        val before = result.substring(0, match.range.first).trimEnd()
        if (before.endsWith(",")) result = result.removeRange(before.length - 1, before.length)
    }
    return result.takeIf { runCatching { Json.parseToJsonElement(it) }.isSuccess && !it.contains("\"$entry\"") }
}
