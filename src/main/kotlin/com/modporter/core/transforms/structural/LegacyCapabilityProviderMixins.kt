package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/**
 * Removes Forge's non-behavioural [CapabilityProvider] base from mixins.
 *
 * Forge's Entity base used to inherit this class, so some mixins repeated the
 * superclass only to satisfy a one-line `super(baseClass)` constructor. A
 * generated compatibility import can survive after the target Entity no longer
 * has that base. This migration is deliberately limited to mixins whose every
 * constructor only delegates that one parameter and whose body does not use any
 * inherited capability-provider member.
 */
internal fun cleanupMixinLegacyCapabilityProviderSuperclass(source: String): String {
    if (!source.contains("@Mixin") || !source.contains("CapabilityProvider")) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val candidates = unit.findAll(ClassOrInterfaceDeclaration::class.java).filter { declaration ->
        declaration.annotations.any { it.name.identifier == "Mixin" } &&
            declaration.extendedTypes.size == 1 &&
            isLegacyCapabilityProvider(declaration.extendedTypes.single(), unit) &&
            declaration.constructors.isNotEmpty() &&
            declaration.constructors.all(::isCapabilityProviderDelegatingConstructor) &&
            !usesCapabilityProviderMembers(declaration)
    }
    if (candidates.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    candidates.forEach { declaration ->
        declaration.extendedTypes.clear()
        declaration.constructors.toList().forEach(ConstructorDeclaration::remove)
    }
    unit.imports.toList()
        .filter { !it.isStatic && !it.isAsterisk && isLegacyCapabilityProviderName(it.nameAsString) }
        .forEach { it.remove() }
    return LexicalPreservingPrinter.print(unit)
}

private fun isLegacyCapabilityProvider(type: ClassOrInterfaceType, unit: CompilationUnit): Boolean {
    if (type.nameAsString != "CapabilityProvider") return false
    val qualified = type.nameWithScope
    if (qualified != "CapabilityProvider") return isLegacyCapabilityProviderName(qualified)
    return unit.imports.any {
        !it.isStatic && !it.isAsterisk && isLegacyCapabilityProviderName(it.nameAsString)
    }
}

private fun isLegacyCapabilityProviderName(name: String): Boolean {
    if (!name.endsWith(".CapabilityProvider")) return false
    return name == "net.minecraftforge.common.capabilities.CapabilityProvider" ||
        name == "net.neoforged.neoforge.capabilities.CapabilityProvider" ||
        name.contains(".modporter.generated.")
}

private fun isCapabilityProviderDelegatingConstructor(constructor: ConstructorDeclaration): Boolean {
    if (constructor.parameters.size != 1 || constructor.body.statements.size != 1) return false
    val parameter = constructor.parameters.single()
    if (parameter.type.asString().replace(" ", "").let { !it.startsWith("Class<") || !it.endsWith(">") }) return false
    val delegation = constructor.body.statements.single() as? ExplicitConstructorInvocationStmt ?: return false
    if (delegation.isThis || delegation.arguments.size != 1) return false
    val argument = delegation.arguments.single() as? NameExpr ?: return false
    return argument.nameAsString == parameter.nameAsString
}

private fun usesCapabilityProviderMembers(declaration: ClassOrInterfaceDeclaration): Boolean {
    val constructors = declaration.constructors.toSet()
    if (declaration.findAll(FieldAccessExpr::class.java).any {
            it.findAncestor(ConstructorDeclaration::class.java).orElse(null) !in constructors &&
                it.scope is SuperExpr
        }) return true
    if (declaration.findAll(MethodCallExpr::class.java).any {
            it.findAncestor(ConstructorDeclaration::class.java).orElse(null) !in constructors &&
                it.scope.map { scope -> scope is SuperExpr }.orElse(false)
        }) return true

    val locallyDeclared = declaration.methods.map { it.nameAsString }.toSet()
    val inheritedCapabilityMethods = setOf(
        "getCapability",
        "invalidateCaps",
        "reviveCaps",
        "serializeCaps",
        "deserializeCaps",
        "areCapsCompatible",
        "getCapabilities"
    )
    return declaration.findAll(MethodCallExpr::class.java).any {
        it.findAncestor(ConstructorDeclaration::class.java).orElse(null) !in constructors &&
            it.scope.isEmpty &&
            it.nameAsString in inheritedCapabilityMethods &&
            it.nameAsString !in locallyDeclared
    }
}
