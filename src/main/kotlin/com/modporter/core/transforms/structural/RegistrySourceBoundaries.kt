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
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Repairs registry source boundaries whose target type follows from the declaring receiver:
 *
 * 1. vanilla Registry parameters expose `key()`, while NeoForge DeferredRegister retains
 *    `getRegistryKey()`;
 * 2. DeferredRegister<ParticleType<?>> fixes the first DeferredHolder generic without changing
 *    its specialized ParticleType<D> value;
 * 3. `WritableRegistry#register(key, value, Lifecycle)` became `register(key, value,
 *    RegistrationInfo)` (1.21.1 member table); a `Lifecycle.stable()` slot is exactly
 *    `RegistrationInfo.BUILT_IN` when the receiver is proven to be a writable registry; and
 * 4. 1.20.1 `CriteriaTriggers.register(T)` registered a trigger under its own `getId()`; 1.21.1
 *    triggers carry no id, so a `forEach(CriteriaTriggers::register)` over triggers whose project
 *    type still declares `ResourceLocation getId()` registers each through the trigger registry
 *    under that id.
 *
 * Every admission is tied to canonical imports and the declaration that supplies the receiver,
 * element or generic position (project declarations through [ProjectSourceDeclarations]).
 * Unrelated method names, lifecycle arguments, and holder types are left unchanged.
 */
internal fun StructuralRefactorPass.migrateRegistrySourceBoundaries(
    source: String
): String {
    if (!source.contains("getRegistryKey()") &&
        !source.contains("DeferredHolder") &&
        !source.contains("Lifecycle.stable()") &&
        !source.contains("CriteriaTriggers::register")
    ) {
        return source
    }

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parsed = parser.parse(source)
    val unit = parsed.result.orElse(null) ?: return source
    if (parsed.problems.isNotEmpty()) return source
    LexicalPreservingPrinter.setup(unit)

    val registryKeyChanged = migrateVanillaRegistryKeyCalls(unit)
    val particleChanged = migrateParticleDeferredHolderBase(unit, parser)
    val registrationInfoChanged = migrateBuiltInRegistrationInfo(unit, parser, projectSourceDeclarations)
    val triggersChanged = migrateIdBearingTriggerRegistration(unit, parser, projectSourceDeclarations)
    if (!registryKeyChanged && !particleChanged &&
        !registrationInfoChanged && !triggersChanged
    ) {
        return source
    }

    var result = LexicalPreservingPrinter.print(unit)
    if (registrationInfoChanged) {
        result = JavaSourceText.addImport(
            result,
            "net.minecraft.core.RegistrationInfo"
        )
    }
    if (triggersChanged) {
        result = JavaSourceText.addImport(result, REGISTRY)
        result = JavaSourceText.addImport(result, BUILT_IN_REGISTRIES)
        result = removeRegistryBoundaryImportIfUnused(
            result,
            CRITERIA_TRIGGERS,
            "CriteriaTriggers"
        )
    }
    val reparsed = parser.parse(result)
    return if (reparsed.result.isPresent && reparsed.problems.isEmpty()) result else source
}

private fun migrateVanillaRegistryKeyCalls(unit: CompilationUnit): Boolean {
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (call.nameAsString != "getRegistryKey" ||
            call.arguments.isNotEmpty()
        ) {
            continue
        }
        val receiver = call.scope.orElse(null) as? NameExpr ?: continue
        val method = call.findAncestor(MethodDeclaration::class.java)
            .orElse(null) ?: continue
        if (registryBoundaryShadowedBetween(
                call,
                method,
                receiver.nameAsString
            )
        ) {
            continue
        }
        val parameter = method.parameters.singleOrNull {
            it.nameAsString == receiver.nameAsString
        } ?: continue
        if (!registryBoundaryCanonicalType(
                unit,
                parameter.type.asString(),
                "Registry",
                REGISTRY
            )
        ) {
            continue
        }
        call.setName("key")
        changed = true
    }
    return changed
}

private fun migrateParticleDeferredHolderBase(
    unit: CompilationUnit,
    parser: JavaParser
): Boolean {
    if (!registryBoundaryCanonicalSimpleType(
            unit,
            "DeferredHolder",
            DEFERRED_HOLDER
        ) ||
        !registryBoundaryCanonicalSimpleType(
            unit,
            "DeferredRegister",
            DEFERRED_REGISTER
        ) ||
        !registryBoundaryCanonicalSimpleType(
            unit,
            "ParticleType",
            PARTICLE_TYPE
        ) ||
        !registryBoundaryCanonicalSimpleType(
            unit,
            "ParticleOptions",
            PARTICLE_OPTIONS
        )
    ) {
        return false
    }

    var changed = false
    for (owner in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
        val particleParameters = owner.typeParameters.filter { parameter ->
            parameter.typeBound.size == 1 &&
                registryBoundaryCanonicalType(
                    unit,
                    parameter.typeBound.single().asString(),
                    "ParticleOptions",
                    PARTICLE_OPTIONS
                )
        }
        if (particleParameters.size != 1) continue
        val parameterName = particleParameters.single().nameAsString

        val register = owner.fields.flatMap { it.variables }
            .singleOrNull { variable ->
                variable.nameAsString == "REGISTER" &&
                    registryBoundaryNormalized(variable.type.asString()) ==
                    "DeferredRegister<ParticleType<?>>"
            } ?: continue
        if (!registryBoundaryCanonicalType(
                unit,
                register.type.asString(),
                "DeferredRegister",
                DEFERRED_REGISTER
            ) ||
            !registryBoundaryCanonicalType(
                unit,
                register.type.asString(),
                "ParticleType",
                PARTICLE_TYPE
            )
        ) {
            continue
        }

        val candidates = owner.fields.flatMap { field ->
            if (field.variables.size == 1) field.variables.toList()
            else emptyList()
        }.filter { variable ->
            registryBoundaryNormalized(variable.type.asString()) ==
                "DeferredHolder<ParticleType<$parameterName>,ParticleType<$parameterName>>" &&
                registryBoundaryCanonicalType(
                    unit,
                    variable.type.asString(),
                    "DeferredHolder",
                    DEFERRED_HOLDER
                ) &&
                registryBoundaryCanonicalType(
                    unit,
                    variable.type.asString(),
                    "ParticleType",
                    PARTICLE_TYPE
                ) &&
                particleHolderAssignedByRegister(
                    owner,
                    variable.nameAsString,
                    register.nameAsString
                )
        }
        for (variable in candidates) {
            val migrated = parser.parseClassOrInterfaceType(
                "DeferredHolder<ParticleType<?>, ParticleType<$parameterName>>"
            ).result.orElse(null) ?: continue
            variable.setType(migrated)
            changed = true
        }
    }
    return changed
}

private fun particleHolderAssignedByRegister(
    owner: ClassOrInterfaceDeclaration,
    fieldName: String,
    registerName: String
): Boolean {
    val assignments = owner.constructors.flatMap { constructor ->
        constructor.findAll(AssignExpr::class.java).filter { assignment ->
            assignment.findAncestor(
                ClassOrInterfaceDeclaration::class.java
            ).orElse(null) === owner &&
                registryBoundaryAssignedName(assignment.target) == fieldName &&
                (assignment.value as? MethodCallExpr)?.let { call ->
                    call.nameAsString == "register" &&
                        call.scope.map {
                            (it as? NameExpr)?.nameAsString == registerName
                        }.orElse(false)
                } == true
        }
    }
    return assignments.size == 1
}

private fun registryBoundaryAssignedName(expression: Expression): String? =
    when (expression) {
        is NameExpr -> expression.nameAsString
        is FieldAccessExpr -> expression.nameAsString
            .takeIf { expression.scope.toString() == "this" }
        else -> null
    }

private fun migrateBuiltInRegistrationInfo(
    unit: CompilationUnit,
    parser: JavaParser,
    declarations: ProjectSourceDeclarations
): Boolean {
    if (!registryBoundaryCanonicalSimpleType(unit, "Lifecycle", LIFECYCLE) ||
        !registryBoundarySimpleNameAvailable(unit, "RegistrationInfo", REGISTRATION_INFO) ||
        !TargetMembers.hasField(REGISTRATION_INFO, "BUILT_IN") ||
        !writableRegistrationTakesRegistrationInfo()
    ) {
        return false
    }

    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (call.nameAsString != "register" || call.arguments.size != 3) continue
        val lifecycle = call.arguments[2] as? MethodCallExpr ?: continue
        if (lifecycle.nameAsString != "stable" ||
            lifecycle.arguments.isNotEmpty() ||
            !registryBoundaryCanonicalOwner(
                unit,
                lifecycle.scope.orElse(null),
                "Lifecycle",
                LIFECYCLE
            )
        ) {
            continue
        }
        val receiver = call.scope.orElse(null) ?: continue
        val receiverType = runCatching { declarations.expressionType(receiver) }.getOrNull()?.name ?: continue
        if (!isWritableRegistry(receiverType)) continue
        call.setArgument(
            2,
            parser.parseExpression<Expression>(
                "RegistrationInfo.BUILT_IN"
            ).result.orElseThrow()
        )
        changed = true
    }
    return changed
}

/** 1.21.1 `WritableRegistry#register` takes `RegistrationInfo` in the slot 1.20.1 gave a `Lifecycle`. */
private fun writableRegistrationTakesRegistrationInfo(): Boolean {
    val descriptors = TargetMembers.methodDescriptors(WRITABLE_REGISTRY, "register")
    return descriptors.any {
        TargetMembers.parameterTypes(it) == listOf(
            "Lnet/minecraft/resources/ResourceKey;",
            "Ljava/lang/Object;",
            "Lnet/minecraft/core/RegistrationInfo;"
        )
    } && descriptors.none { it.contains("Lcom/mojang/serialization/Lifecycle;") }
}

private fun isWritableRegistry(type: String): Boolean {
    if (type == WRITABLE_REGISTRY) return true
    val binary = ProjectSourceDeclarations.platformBinary(type) ?: return false
    return TargetMembers.inheritsFrom(binary, WRITABLE_REGISTRY)
}

private fun migrateIdBearingTriggerRegistration(
    unit: CompilationUnit,
    parser: JavaParser,
    declarations: ProjectSourceDeclarations
): Boolean {
    if (!registryBoundaryCanonicalSimpleType(unit, "CriteriaTriggers", CRITERIA_TRIGGERS) ||
        !registryBoundarySimpleNameAvailable(unit, "Registry", REGISTRY) ||
        !registryBoundarySimpleNameAvailable(unit, "BuiltInRegistries", BUILT_IN_REGISTRIES) ||
        !TargetMembers.hasField(BUILT_IN_REGISTRIES, "TRIGGER_TYPES") ||
        // 1.21.1 offers only register(String, T): the 1.20.1 id-taking register(T) is gone
        TargetMembers.methodDescriptors(CRITERIA_TRIGGERS, "register")
            .any { TargetMembers.parameterTypes(it).size == 1 }
    ) {
        return false
    }

    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
        if (call.nameAsString != "forEach" || call.arguments.size != 1) continue
        val reference = call.arguments.single() as? MethodReferenceExpr ?: continue
        if (reference.identifier != "register" ||
            !registryBoundaryCanonicalOwner(unit, reference.scope, "CriteriaTriggers", CRITERIA_TRIGGERS)
        ) {
            continue
        }
        val triggers = call.scope.orElse(null) as? NameExpr ?: continue
        val element = runCatching { declarations.declaredElementType(triggers) }.getOrNull()?.name ?: continue
        if (!declaresResourceLocationId(element, declarations)) continue
        val name = freshLambdaParameterName(call, "trigger")
        val replacement = parser.parseExpression<Expression>(
            "$name -> Registry.register(" +
                "BuiltInRegistries.TRIGGER_TYPES, " +
                "$name.getId(), $name)"
        ).result.orElse(null) as? LambdaExpr ?: continue
        call.setArgument(0, replacement)
        changed = true
    }
    return changed
}

/** Every `getId()` a value of the project type [owner] can dispatch to returns a `ResourceLocation`. */
private fun declaresResourceLocationId(owner: String, declarations: ProjectSourceDeclarations): Boolean {
    if (!declarations.declares(owner)) return false
    val getters = runCatching { declarations.methods(owner, "getId", 0) }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: return false
    return getters.all { method ->
        (declarations.resolve(method.declaration.type, method.declaration) as?
            ProjectSourceDeclarations.TypeRef.Named)?.name == RESOURCE_LOCATION
    }
}

/** [base], or [base] with underscores, that no local, parameter or lambda parameter around [node] uses. */
private fun freshLambdaParameterName(node: Node, base: String): String {
    val callable = node.findAncestor(com.github.javaparser.ast.body.CallableDeclaration::class.java).orElse(null)
    val taken = buildSet {
        callable?.parameters?.forEach { add(it.nameAsString) }
        callable?.findAll(VariableDeclarator::class.java)?.forEach { add(it.nameAsString) }
        callable?.findAll(LambdaExpr::class.java)?.forEach { lambda ->
            lambda.parameters.forEach { add(it.nameAsString) }
        }
    }
    var name = base
    while (name in taken) name += "_"
    return name
}

private fun registryBoundaryShadowedBetween(
    node: Node,
    method: MethodDeclaration,
    name: String
): Boolean {
    var current = node.parentNode.orElse(null)
    while (current != null && current !== method) {
        if (current is LambdaExpr &&
            current.parameters.any { it.nameAsString == name }
        ) {
            return true
        }
        current = current.parentNode.orElse(null)
    }
    return false
}

private fun registryBoundaryCanonicalOwner(
    unit: CompilationUnit,
    expression: Expression?,
    simple: String,
    qualified: String
): Boolean {
    expression ?: return false
    if (expression.toString() == qualified) return true
    if (expression.toString() != simple) return false
    if (!registryBoundaryCanonicalSimpleType(unit, simple, qualified)) {
        return false
    }
    if (unit.findAll(Parameter::class.java).any {
            it.nameAsString == simple
        } ||
        unit.findAll(VariableDeclarator::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    return true
}

private fun registryBoundaryCanonicalType(
    unit: CompilationUnit,
    typeText: String,
    simple: String,
    qualified: String
): Boolean {
    if (Regex(
            """(?<![A-Za-z0-9_$])${Regex.escape(qualified)}""" +
                """(?![A-Za-z0-9_$])"""
        ).containsMatchIn(typeText)
    ) {
        return true
    }
    if (!Regex(
            """(?<![A-Za-z0-9_$])${Regex.escape(simple)}""" +
                """(?![A-Za-z0-9_$])"""
        ).containsMatchIn(typeText)
    ) {
        return false
    }
    return registryBoundaryCanonicalSimpleType(unit, simple, qualified)
}

private fun registryBoundaryCanonicalSimpleType(
    unit: CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk &&
            it.name.identifier == simple
    }
    return imports.size == 1 &&
        imports.single().nameAsString == qualified
}

private fun registryBoundarySimpleNameAvailable(
    unit: CompilationUnit,
    simple: String,
    qualified: String
): Boolean {
    if (unit.findAll(TypeDeclaration::class.java).any {
            it.nameAsString == simple
        }
    ) {
        return false
    }
    val imports = unit.imports.filter {
        !it.isStatic && !it.isAsterisk &&
            it.name.identifier == simple
    }
    return imports.isEmpty() ||
        imports.size == 1 && imports.single().nameAsString == qualified
}

private fun removeRegistryBoundaryImportIfUnused(
    source: String,
    qualified: String,
    simple: String
): String {
    val without = JavaSourceText.removeImport(source, qualified)
    return if (Regex("""\b${Regex.escape(simple)}\b""")
            .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(without))
    ) {
        source
    } else {
        without
    }
}

private fun registryBoundaryNormalized(type: String): String =
    type.replace(Regex("""\s+"""), "")

private const val BUILT_IN_REGISTRIES =
    "net.minecraft.core.registries.BuiltInRegistries"
private const val CRITERIA_TRIGGERS =
    "net.minecraft.advancements.CriteriaTriggers"
private const val DEFERRED_HOLDER =
    "net.neoforged.neoforge.registries.DeferredHolder"
private const val DEFERRED_REGISTER =
    "net.neoforged.neoforge.registries.DeferredRegister"
private const val LIFECYCLE =
    "com.mojang.serialization.Lifecycle"
private const val PARTICLE_OPTIONS =
    "net.minecraft.core.particles.ParticleOptions"
private const val PARTICLE_TYPE =
    "net.minecraft.core.particles.ParticleType"
private const val REGISTRATION_INFO = "net.minecraft.core.RegistrationInfo"
private const val REGISTRY = "net.minecraft.core.Registry"
private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
private const val WRITABLE_REGISTRY =
    "net.minecraft.core.WritableRegistry"
