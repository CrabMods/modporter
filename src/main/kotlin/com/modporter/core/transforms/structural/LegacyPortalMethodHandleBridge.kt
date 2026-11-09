package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.TryStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.TypeParameter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val METHOD_HANDLE_PORTAL_INFO = "net.minecraft.world.level.portal.PortalInfo"
private const val METHOD_HANDLE_METHOD_HANDLE = "java.lang.invoke.MethodHandle"
private const val METHOD_HANDLE_METHOD_HANDLES = "java.lang.invoke.MethodHandles"
private const val METHOD_HANDLE_METHOD_TYPE = "java.lang.invoke.MethodType"
private const val METHOD_HANDLE_BI_FUNCTION = "java.util.function.BiFunction"
private const val METHOD_HANDLE_SERVER_LEVEL = "net.minecraft.server.level.ServerLevel"
private const val METHOD_HANDLE_ENTITY = "net.minecraft.world.entity.Entity"
private const val METHOD_HANDLE_RULE = "struct-legacy-portal-method-handle"
private val GENERATED_METHOD_HANDLE_HELPER = Regex(
    """^com\.modporter\.generated\.[a-z0-9_]+\.compat\.LegacyPortalProbe$"""
)

private data class MethodHandleEdit(
    val start: Int,
    val endExclusive: Int,
    val replacement: String
)

private data class MethodHandlePlan(
    val edits: List<MethodHandleEdit>,
    val wrapperMethod: MethodDeclaration,
    val wrapperOwner: TypeDeclaration<*>
)

private data class MethodHandleCallerPlan(
    val file: Path,
    val edits: List<MethodHandleEdit>
)

private data class MethodHandleFamilyPlan(
    val wrapperFile: Path,
    val bridge: MethodHandlePlan,
    val caller: MethodHandleCallerPlan
)

private class MethodHandleOffsets(private val source: String) {
    private val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }

    fun start(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)

    fun endExclusive(node: Node): Int = node.range
        .map { lineStarts[it.end.line - 1] + it.end.column }
        .orElse(-1)

    fun text(node: Node): String {
        val start = start(node)
        val end = endExclusive(node)
        return if (start < 0 || end < start) "" else source.substring(start, end)
    }
}

/**
 * Removes only an exact PortalInfo.class return token from a virtual MethodHandle lookup.
 * This component is invoked only inside [migrateLegacyPortalFamilyTransaction]; root integration
 * must not call it independently.
 *
 * The generated helper resolves the actual declared return class by name plus parameter classes,
 * performs the real lookup with the source-supplied Lookup, admits only the exact four-field value
 * shape, rejects DimensionTransition, and filters that return into the generated PortalInfo value.
 *
 * This lane also requires a bounded project caller chain: the wrapper result must flow through a
 * wildcard-return functional parameter and a runtime value adapter into a local whose uses are
 * null checks or reads of pos/speed/yRot/xRot. It rejects entity placement, changeDimension,
 * callbacks, and unknown uses.
 */
internal fun StructuralRefactorPass.migrateLegacyPortalMethodHandleReturns(
    project: Path,
    dryRun: Boolean
): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }
            .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
            .toList()
    }
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val sources = files.associateWith { it.readText() }
    val units = sources.mapValues { (_, source) -> parser.parse(source).result.orElse(null) }
    val projectTypes = units.values.filterNotNull().flatMap { unit ->
        val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        unit.findAll(TypeDeclaration::class.java).map { pkg to it.nameAsString }
    }.toSet()

    val candidates = mutableListOf<MethodHandleFamilyPlan>()
    for ((file, source) in sources) {
        if (!source.contains("PortalInfo.class")) continue
        val unit = units[file] ?: continue
        if (!hasPortalInfoVirtualLookupCandidate(unit)) continue
        val bridge = planLegacyPortalMethodHandle(source, unit, projectTypes) ?: continue
        val caller = planClosedPortalValueCaller(
            bridge.wrapperMethod,
            bridge.wrapperOwner,
            file,
            units.filterValues { it != null }.mapValues { it.value!! },
            sources,
            projectTypes
        ) ?: continue
        candidates += MethodHandleFamilyPlan(file, bridge, caller)
    }
    val plan = candidates.singleOrNull()

    val existingPackages = units.values.asSequence()
        .filterNotNull()
        .flatMap { it.imports.asSequence() }
        .map { it.nameAsString }
        .filter { GENERATED_METHOD_HANDLE_HELPER.matches(it) }
        .map { it.substringBeforeLast('.') }
        .toSet()
    if (existingPackages.size > 1) return emptyList()
    val compatPackage = if (plan != null) {
        detectRequiredGeneratedCompatPackage(project, "legacy PortalInfo MethodHandle return bridge")
    } else {
        existingPackages.singleOrNull() ?: return emptyList()
    }
    if (existingPackages.isNotEmpty() && existingPackages.single() != compatPackage) return emptyList()

    val changes = mutableListOf<Change>()
    val stagedSources = linkedMapOf<Path, String>()
    if (plan != null) {
        val editsByFile = linkedMapOf(
            plan.wrapperFile to plan.bridge.edits,
            plan.caller.file to plan.caller.edits
        )
        for ((file, edits) in editsByFile) {
            val source = sources.getValue(file)
            var migrated = applyMethodHandleEdits(source, edits)
            migrated = addImportIfMissing(migrated, "$compatPackage.LegacyPortalProbe")
            if (migrated == source) continue
            stagedSources[file] = migrated
            changes += Change(
                file,
                1,
                if (file == plan.wrapperFile) {
                    "Resolve the reflected virtual method's actual value return and adapt it to PortalInfo"
                } else {
                    "Preserve the public BiFunction erasure and adapt its runtime portal value"
                },
                if (file == plan.wrapperFile) {
                    "lookup.findVirtual(... MethodType.methodType(PortalInfo.class, ...))"
                } else {
                    "BiFunction<ServerLevel, Entity, PortalInfo>"
                },
                if (file == plan.wrapperFile) {
                    "LegacyPortalProbe.findVirtual(lookup, owner, name, parameterClasses...)"
                } else {
                    "BiFunction<ServerLevel, Entity, ?> plus LegacyPortalProbe.portalInfo(...)"
                },
                Confidence.HIGH,
                METHOD_HANDLE_RULE
            )
        }
    }

    val generated = root.resolve(compatPackage.replace('.', '/')).resolve("LegacyPortalProbe.java")
    val content = javaClass.getResource("/compat/LegacyPortalProbe.java.template")!!
        .readText()
        .replace("@PACKAGE@", compatPackage)
    val helperChanged = !java.nio.file.Files.exists(generated) || generated.readText() != content
    if (helperChanged) {
        changes += Change(
            generated,
            1,
            "Generate the checked reflected return adapter used by the PortalInfo MethodHandle bridge",
            "",
            "$compatPackage.LegacyPortalProbe.findVirtual",
            Confidence.HIGH,
            METHOD_HANDLE_RULE
        )
    }
    if (!dryRun) {
        stagedSources.forEach { (file, migrated) -> file.writeText(migrated) }
        if (helperChanged) {
            generated.parent.createDirectories()
            generated.writeText(content)
        }
    }
    return changes
}

private fun hasPortalInfoVirtualLookupCandidate(unit: CompilationUnit): Boolean {
    if (unit.findAll(MethodCallExpr::class.java).none { it.nameAsString == "findVirtual" }) {
        return false
    }
    return unit.findAll(ClassExpr::class.java)
        .filter { it.type.asString() == "PortalInfo" }
        .any { literal ->
            val methodType = literal.findAncestor(MethodCallExpr::class.java).orElse(null)
                ?: return@any false
            methodType.nameAsString == "methodType"
        }
}

private fun planLegacyPortalMethodHandle(
    source: String,
    unit: CompilationUnit,
    projectTypes: Set<Pair<String, String>>
): MethodHandlePlan? {
    if (!methodHandleExactBinding(unit, "PortalInfo", METHOD_HANDLE_PORTAL_INFO, projectTypes)) return null
    if (!methodHandleExactBinding(unit, "MethodHandle", METHOD_HANDLE_METHOD_HANDLE, projectTypes)) {
        return null
    }
    if (!methodHandleExactBinding(unit, "MethodHandles", METHOD_HANDLE_METHOD_HANDLES, projectTypes)) {
        return null
    }
    if (!methodHandleExactBinding(unit, "MethodType", METHOD_HANDLE_METHOD_TYPE, projectTypes)) return null
    if (!methodHandleExactBinding(unit, "ServerLevel", METHOD_HANDLE_SERVER_LEVEL, projectTypes)) {
        return null
    }
    if (!methodHandleExactBinding(unit, "Entity", METHOD_HANDLE_ENTITY, projectTypes)) return null

    val portalClassLiterals = unit.findAll(ClassExpr::class.java).filter {
        it.type.asString() == "PortalInfo"
    }
    if (portalClassLiterals.size != 1) return null
    val portalClass = portalClassLiterals.single()
    val methodTypeCall = portalClass.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return null
    if (
        methodTypeCall.nameAsString != "methodType" ||
        methodTypeCall.scope.orElse(null)?.toString() != "MethodType" ||
        methodTypeCall.arguments.firstOrNull() !== portalClass
    ) return null
    val parameterClasses = methodTypeCall.arguments.drop(1).map {
        it as? ClassExpr ?: return null
    }

    val offsets = MethodHandleOffsets(source)
    val edits = mutableListOf<MethodHandleEdit>()
    val directParent = methodTypeCall.parentNode.orElse(null)
    val findVirtual: MethodCallExpr
    if (
        directParent is MethodCallExpr &&
        directParent.nameAsString == "findVirtual" &&
        directParent.arguments.size == 3 &&
        directParent.arguments[2] === methodTypeCall
    ) {
        findVirtual = directParent
    } else {
        val variable = directParent as? VariableDeclarator ?: return null
        if (variable.initializer.orElse(null) !== methodTypeCall || variable.type.asString() != "MethodType") {
            return null
        }
        val methodTypeBlock = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: return null
        val uses = methodTypeBlock.findAll(NameExpr::class.java).filter {
            it.nameAsString == variable.nameAsString
        }
        if (uses.size != 1) return null
        val use = uses.single()
        findVirtual = use.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (
            findVirtual.nameAsString != "findVirtual" ||
            findVirtual.arguments.size != 3 ||
            findVirtual.arguments[2] !== use
        ) return null
        val declaration = variable.findAncestor(ExpressionStmt::class.java).orElse(null) ?: return null
        edits += MethodHandleEdit(offsets.start(declaration), offsets.endExclusive(declaration), "")
    }

    val lookup = findVirtual.scope.orElse(null) ?: return null
    val wrapperOwner = findVirtual.findAncestor(TypeDeclaration::class.java).orElse(null) ?: return null
    if (wrapperOwner.findAncestor(TypeDeclaration::class.java).isPresent) return null
    if (!isClosedLookupBinding(lookup, findVirtual, wrapperOwner)) return null
    val owner = findVirtual.arguments[0]
    val name = findVirtual.arguments[1]
    if (!name.isStringLiteralExpr) return null
    val lookupText = offsets.text(lookup)
    val ownerText = offsets.text(owner)
    val nameText = offsets.text(name)
    val parameterText = parameterClasses.map(offsets::text)
    if (
        lookupText.isBlank() ||
        ownerText.isBlank() ||
        nameText.isBlank() ||
        parameterText.any(String::isBlank)
    ) return null

    val lookupTry = findVirtual.findAncestor(TryStmt::class.java).orElse(null) ?: return null
    if (lookupTry.catchClauses.none { catchClause ->
            catchClause.body.findAll(MethodCallExpr::class.java).any { it.nameAsString == "error" }
        }) return null

    val assignment = findVirtual.parentNode.orElse(null) as? AssignExpr ?: return null
    if (assignment.value !== findVirtual || assignment.operator != AssignExpr.Operator.ASSIGN) return null
    val handleTarget = assignment.target as? NameExpr ?: return null
    val handleField = closedHandleField(
        wrapperOwner,
        handleTarget.nameAsString,
        assignment
    ) ?: return null
    val wrapperMethod = closedHandleInvocationWrapper(
        wrapperOwner,
        handleField,
        assignment
    ) ?: return null

    val replacementArguments = buildList {
        add(lookupText)
        add(ownerText)
        add(nameText)
        addAll(parameterText)
    }.joinToString(", ")
    edits += MethodHandleEdit(
        offsets.start(findVirtual),
        offsets.endExclusive(findVirtual),
        "LegacyPortalProbe.findVirtual($replacementArguments)"
    )
    if (edits.any { it.start < 0 || it.endExclusive <= it.start }) return null
    return MethodHandlePlan(edits, wrapperMethod, wrapperOwner)
}

private fun closedHandleField(
    owner: TypeDeclaration<*>,
    handleName: String,
    assignment: AssignExpr
): VariableDeclarator? {
    val fields = owner.members.filterIsInstance<FieldDeclaration>()
        .filter { it.isPrivate && it.isStatic }
        .flatMap { it.variables }
        .filter {
            it.nameAsString == handleName &&
                it.type.asString() == "MethodHandle"
        }
    val field = fields.singleOrNull() ?: return null
    if (field.initializer.isPresent) return null
    val initializer = assignment.findAncestor(InitializerDeclaration::class.java).orElse(null)
        ?: return null
    if (!initializer.isStatic) return null
    if (initializer.findAncestor(TypeDeclaration::class.java).orElse(null) !== owner) return null
    val valueDeclarations = owner.findAll(VariableDeclarator::class.java).filter {
        it.nameAsString == handleName
    }
    if (valueDeclarations.singleOrNull() !== field) return null
    if (owner.findAll(Parameter::class.java).any { it.nameAsString == handleName }) return null
    val assignments = owner.findAll(AssignExpr::class.java).filter {
        (it.target as? NameExpr)?.nameAsString == handleName
    }
    if (assignments.singleOrNull() !== assignment) return null
    return field
}

private fun closedHandleInvocationWrapper(
    owner: TypeDeclaration<*>,
    handleField: VariableDeclarator,
    assignment: AssignExpr
): MethodDeclaration? {
    val handleName = handleField.nameAsString
    val invocations = owner.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "invoke" &&
            (it.scope.orElse(null) as? NameExpr)?.nameAsString == handleName
    }
    if (invocations.size != 1) return null
    val invocation = invocations.single()
    val cast = invocation.parentNode.orElse(null) as? CastExpr ?: return null
    if (cast.type.asString() != "PortalInfo" || cast.expression !== invocation) return null
    val returned = cast.parentNode.orElse(null) as? ReturnStmt ?: return null
    if (returned.expression.orElse(null) !== cast) return null
    val method = returned.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
    if (method.type.asString() != "PortalInfo") return null
    if (
        method.parameters.size != 2 ||
        method.parameters[0].type.asString() != "ServerLevel" ||
        method.parameters[1].type.asString() != "Entity"
    ) return null
    if (!method.isPublic || !method.isStatic) return null
    if (method.findAncestor(TypeDeclaration::class.java).orElse(null) !== owner) return null
    if (owner.members.filterIsInstance<MethodDeclaration>().count {
            it.nameAsString == method.nameAsString
        } != 1) return null
    val invocationTry = invocation.findAncestor(TryStmt::class.java).orElse(null) ?: return null
    if (invocationTry.catchClauses.none { catchClause ->
            catchClause.body.findAll(MethodCallExpr::class.java).any { it.nameAsString == "error" }
        }) return null
    val nullReturns = method.findAll(ReturnStmt::class.java).filter {
        it.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
            it.expression.orElse(null) is NullLiteralExpr
    }
    if (nullReturns.size != 1) return null
    val handleUses = owner.findAll(NameExpr::class.java).filter {
        it.nameAsString == handleName
    }
    if (handleUses.size != 2) return null
    if (handleUses.none { it === assignment.target }) return null
    if (handleUses.none { it === invocation.scope.orElse(null) }) return null
    return method
}

private fun isClosedLookupBinding(
    expression: Expression,
    use: Node,
    owner: TypeDeclaration<*>,
    visited: Set<VariableDeclarator> = emptySet()
): Boolean {
    val name = expression as? NameExpr ?: return false
    val declarations = owner.findAll(VariableDeclarator::class.java).filter {
        it.nameAsString == name.nameAsString
    }
    val declaration = declarations.singleOrNull() ?: return false
    if (declaration in visited) return false
    if (owner.findAll(Parameter::class.java).any { it.nameAsString == name.nameAsString }) {
        return false
    }
    if (declaration.type.asString() != "MethodHandles.Lookup") return false
    if (owner.findAll(AssignExpr::class.java).any {
            (it.target as? NameExpr)?.nameAsString == name.nameAsString
        }) return false

    val field = declaration.parentNode.orElse(null) as? FieldDeclaration
    if (field != null) {
        if (!field.isPrivate || !field.isStatic || !field.isFinal) return false
    } else {
        val declarationBlock = declaration.findAncestor(BlockStmt::class.java).orElse(null)
            ?: return false
        val useBlock = use.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        if (declarationBlock !== useBlock || !nodeStartsBefore(declaration, use)) return false
    }

    val initializer = declaration.initializer.orElse(null) as? MethodCallExpr ?: return false
    if ((initializer.scope.orElse(null) as? NameExpr)?.nameAsString != "MethodHandles") {
        return false
    }
    return when (initializer.nameAsString) {
        "lookup" -> initializer.arguments.isEmpty()
        "privateLookupIn" -> {
            if (initializer.arguments.size != 2) return false
            isClosedLookupBinding(
                initializer.arguments[1],
                declaration,
                owner,
                visited + declaration
            )
        }
        else -> false
    }
}

private fun nodeStartsBefore(left: Node, right: Node): Boolean {
    val leftRange = left.range.orElse(null) ?: return false
    val rightRange = right.range.orElse(null) ?: return false
    return leftRange.begin.line < rightRange.begin.line ||
        leftRange.begin.line == rightRange.begin.line &&
        leftRange.begin.column < rightRange.begin.column
}

private fun planClosedPortalValueCaller(
    wrapper: MethodDeclaration,
    wrapperOwner: TypeDeclaration<*>,
    wrapperFile: Path,
    units: Map<Path, CompilationUnit>,
    sources: Map<Path, String>,
    projectTypes: Set<Pair<String, String>>
): MethodHandleCallerPlan? {
    val wrapperUnit = units[wrapperFile] ?: return null
    val wrapperPackage = wrapperUnit.packageDeclaration.map { it.nameAsString }.orElse("")
    val wrapperOwnerFqn = listOf(wrapperPackage, wrapperOwner.nameAsString)
        .filter(String::isNotBlank)
        .joinToString(".")

    val references = units.flatMap { (file, unit) ->
        unit.findAll(MethodReferenceExpr::class.java)
            .filter {
                it.identifier == wrapper.nameAsString &&
                    resolvesTopLevelTypeScope(
                        it.scope.toString(),
                        unit,
                        wrapperOwnerFqn,
                        wrapperOwner.nameAsString,
                        projectTypes
                    )
            }
            .map { file to it }
    }
    if (references.size != 1) return null
    val (callerFile, reference) = references.single()
    if (callerFile == wrapperFile) return null
    val callerUnit = units.getValue(callerFile)

    val directCalls = units.flatMap { (file, unit) ->
        unit.findAll(MethodCallExpr::class.java).filter { call ->
            if (call.nameAsString != wrapper.nameAsString) return@filter false
            val scope = call.scope.orElse(null)
            when {
                scope != null ->
                    resolvesTopLevelTypeScope(
                        scope.toString(),
                        unit,
                        wrapperOwnerFqn,
                        wrapperOwner.nameAsString,
                        projectTypes
                    ) || resolvesWrapperInstanceScope(
                        scope,
                        unit,
                        wrapperOwnerFqn,
                        wrapperOwner.nameAsString,
                        projectTypes
                    )
                file == wrapperFile -> true
                unit.imports.any {
                    it.isStatic &&
                        (
                            !it.isAsterisk &&
                                it.nameAsString == "$wrapperOwnerFqn.${wrapper.nameAsString}" ||
                                it.isAsterisk && it.nameAsString == wrapperOwnerFqn
                            )
                } -> true
                else -> false
            }
        }
    }
    if (directCalls.isNotEmpty()) return null

    val call = reference.parentNode.orElse(null) as? MethodCallExpr ?: return null
    val argumentIndex = call.arguments.indexOfFirst { it === reference }
    if (argumentIndex < 0 || call.scope.isPresent) return null
    val callerOwner = call.findAncestor(TypeDeclaration::class.java).orElse(null) ?: return null
    if (callerOwner.findAncestor(TypeDeclaration::class.java).isPresent) return null
    val declarations = callerOwner.members.filterIsInstance<MethodDeclaration>().filter {
        it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
    }
    val declaration = declarations.singleOrNull() ?: return null
    if (declaration.annotations.any { it.nameAsString == "Override" }) return null
    if (!methodHandleExactBinding(
            callerUnit,
            "BiFunction",
            METHOD_HANDLE_BI_FUNCTION,
            projectTypes
        )) return null
    if (!methodHandleExactBinding(
            callerUnit,
            "ServerLevel",
            METHOD_HANDLE_SERVER_LEVEL,
            projectTypes
        )) return null
    if (!methodHandleExactBinding(
            callerUnit,
            "PortalInfo",
            METHOD_HANDLE_PORTAL_INFO,
            projectTypes
        )) return null

    val parameterType = declaration.parameters[argumentIndex].type as? ClassOrInterfaceType
        ?: return null
    val typeArguments = parameterType.typeArguments.orElse(null) ?: return null
    if (
        parameterType.nameWithScope != "BiFunction" ||
        typeArguments.size != 3 ||
        typeArguments[0].asString() != "ServerLevel" ||
        typeArguments[2].asString() != "PortalInfo"
    ) return null
    val entityInputType = typeArguments[1] as? ClassOrInterfaceType ?: return null
    if (!isExactSourceEntitySubtype(entityInputType, callerUnit, units)) return null
    val parameter = declaration.parameters[argumentIndex]
    if (!hasUniqueMethodHandleValueBinding(declaration, parameter.nameAsString, parameter)) {
        return null
    }
    val applyCalls = declaration.findAll(MethodCallExpr::class.java).filter {
        it.nameAsString == "apply" &&
            (it.scope.orElse(null) as? NameExpr)?.nameAsString == parameter.nameAsString &&
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === declaration
    }
    if (applyCalls.size != 1) return null
    val applyCall = applyCalls.single()
    val parameterUses = declaration.findAll(NameExpr::class.java).filter {
        it.nameAsString == parameter.nameAsString &&
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === declaration
    }
    if (parameterUses.size != 1 || parameterUses.single() !== applyCall.scope.orElse(null)) {
        return null
    }
    val value = applyCall.findAncestor(VariableDeclarator::class.java).orElse(null)
        ?: return null
    if (value.type.asString() != "PortalInfo" || value.initializer.orElse(null) !== applyCalls.single()) {
        return null
    }
    if (!hasUniqueMethodHandleValueBinding(declaration, value.nameAsString, value)) return null
    val uses = declaration.findAll(NameExpr::class.java).filter {
        it.nameAsString == value.nameAsString &&
            it.findAncestor(MethodDeclaration::class.java).orElse(null) === declaration
    }
    if (uses.isEmpty() || !uses.all(::isClosedPortalValueUse)) return null

    val source = sources.getValue(callerFile)
    val offsets = MethodHandleOffsets(source)
    val originalApply = offsets.text(applyCall)
    if (originalApply.isBlank()) return null
    val edits = listOf(
        MethodHandleEdit(
            offsets.start(typeArguments[2]),
            offsets.endExclusive(typeArguments[2]),
            "?"
        ),
        MethodHandleEdit(
            offsets.start(applyCall),
            offsets.endExclusive(applyCall),
            "LegacyPortalProbe.portalInfo($originalApply)"
        )
    )
    if (edits.any { it.start < 0 || it.endExclusive <= it.start }) return null
    return MethodHandleCallerPlan(callerFile, edits)
}

private data class MethodHandleSourceClass(
    val unit: CompilationUnit,
    val declaration: ClassOrInterfaceDeclaration
)

private fun isExactSourceEntitySubtype(
    type: ClassOrInterfaceType,
    unit: CompilationUnit,
    units: Map<Path, CompilationUnit>
): Boolean {
    val sourceClasses = units.values
        .flatMap { sourceUnit ->
            val packageName = sourceUnit.packageDeclaration.map { it.nameAsString }.orElse("")
            sourceUnit.types.filterIsInstance<ClassOrInterfaceDeclaration>().map { declaration ->
                val fqn = listOf(packageName, declaration.nameAsString)
                    .filter(String::isNotBlank)
                    .joinToString(".")
                fqn to MethodHandleSourceClass(sourceUnit, declaration)
            }
        }
        .groupBy({ it.first }, { it.second })
    val fqn = resolveExactMethodHandleClass(type, unit, sourceClasses) ?: return false
    return sourceClassExtendsEntity(fqn, sourceClasses, emptySet())
}

private fun sourceClassExtendsEntity(
    fqn: String,
    sourceClasses: Map<String, List<MethodHandleSourceClass>>,
    visited: Set<String>
): Boolean {
    if (fqn == METHOD_HANDLE_ENTITY) return true
    if (fqn in visited) return false
    val sourceClass = sourceClasses[fqn]?.singleOrNull() ?: return false
    if (sourceClass.declaration.isInterface) return false
    val parent = sourceClass.declaration.extendedTypes.singleOrNull() ?: return false
    val parentFqn = resolveExactMethodHandleClass(parent, sourceClass.unit, sourceClasses)
        ?: return false
    return sourceClassExtendsEntity(parentFqn, sourceClasses, visited + fqn)
}

private fun resolveExactMethodHandleClass(
    type: ClassOrInterfaceType,
    unit: CompilationUnit,
    sourceClasses: Map<String, List<MethodHandleSourceClass>>
): String? {
    if (type.typeArguments.isPresent) return null
    val written = type.nameWithScope
    if ('.' in written) {
        return written.takeIf { it == METHOD_HANDLE_ENTITY || sourceClasses[it]?.size == 1 }
    }
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == written }) return null
    if (unit.findAll(TypeDeclaration::class.java).any { declaration ->
            declaration.nameAsString == written &&
                declaration.findAncestor(TypeDeclaration::class.java).isPresent
        }) return null

    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == written
    }.map { it.nameAsString }
    if (imports.size > 1) return null
    if (imports.size == 1) {
        val imported = imports.single()
        return imported.takeIf {
            it == METHOD_HANDLE_ENTITY || sourceClasses[it]?.size == 1
        }
    }

    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val samePackage = listOf(packageName, written)
        .filter(String::isNotBlank)
        .joinToString(".")
    return samePackage.takeIf { sourceClasses[it]?.size == 1 }
}

private fun resolvesTopLevelTypeScope(
    scope: String,
    unit: CompilationUnit,
    expectedFqn: String,
    simpleName: String,
    projectTypes: Set<Pair<String, String>>
): Boolean {
    if (scope == expectedFqn) return true
    if (scope != simpleName) return false
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) {
        return false
    }
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simpleName }) {
        return false
    }
    if (unit.findAll(Parameter::class.java).any { it.nameAsString == simpleName }) {
        return false
    }
    if (unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == simpleName }) {
        return false
    }
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    if ((packageName to simpleName) in projectTypes && "$packageName.$simpleName" != expectedFqn) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.map { it.nameAsString }
    if (imports.isNotEmpty()) return imports == listOf(expectedFqn)
    return expectedFqn.substringBeforeLast('.', "") == packageName
}

private fun resolvesWrapperInstanceScope(
    scope: Expression,
    unit: CompilationUnit,
    expectedFqn: String,
    simpleName: String,
    projectTypes: Set<Pair<String, String>>
): Boolean {
    val name = scope as? NameExpr ?: return false
    val declarations = buildList<Node> {
        addAll(unit.findAll(Parameter::class.java).filter {
            it.nameAsString == name.nameAsString
        })
        addAll(unit.findAll(VariableDeclarator::class.java).filter {
            it.nameAsString == name.nameAsString
        })
    }
    val declaration = declarations.singleOrNull() ?: return false
    val type = when (declaration) {
        is Parameter -> declaration.type.asString()
        is VariableDeclarator -> declaration.type.asString()
        else -> return false
    }
    if (type != simpleName && type != expectedFqn) return false
    return type == expectedFqn || resolvesTopLevelTypeScope(
        simpleName,
        unit,
        expectedFqn,
        simpleName,
        projectTypes
    )
}

private fun hasUniqueMethodHandleValueBinding(
    method: MethodDeclaration,
    name: String,
    expected: Node
): Boolean {
    val declarations = buildList<Node> {
        addAll(method.findAll(Parameter::class.java).filter {
            it.nameAsString == name &&
                it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
        })
        addAll(method.findAll(VariableDeclarator::class.java).filter {
            it.nameAsString == name &&
                it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
        })
    }
    return declarations.singleOrNull() === expected
}

private fun isClosedPortalValueUse(use: NameExpr): Boolean {
    return when (val parent = use.parentNode.orElse(null)) {
        is FieldAccessExpr ->
            parent.scope === use && parent.nameAsString in setOf("pos", "speed", "yRot", "xRot")
        is BinaryExpr -> {
            val other = when {
                parent.left === use -> parent.right
                parent.right === use -> parent.left
                else -> return false
            }
            other is NullLiteralExpr &&
                parent.operator in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)
        }
        else -> false
    }
}

private fun methodHandleExactBinding(
    unit: CompilationUnit,
    simpleName: String,
    fqn: String,
    projectTypes: Set<Pair<String, String>>
): Boolean {
    val imports = unit.imports.filter {
        !it.isStatic &&
            !it.isAsterisk &&
            it.nameAsString.substringAfterLast('.') == simpleName
    }.map { it.nameAsString }
    if (imports != listOf(fqn)) return false
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == simpleName }) return false
    if (unit.findAll(TypeParameter::class.java).any { it.nameAsString == simpleName }) return false
    val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return (pkg to simpleName) !in projectTypes
}

private fun applyMethodHandleEdits(
    source: String,
    edits: List<MethodHandleEdit>
): String {
    var result = source
    val ordered = edits.distinct().sortedByDescending { it.start }
    var previousStart = source.length + 1
    for (edit in ordered) {
        require(edit.endExclusive <= previousStart) { "Overlapping legacy portal MethodHandle edits" }
        result = result.replaceRange(edit.start, edit.endExclusive, edit.replacement)
        previousStart = edit.start
    }
    return result
}
