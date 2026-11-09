package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

private data class SubscribedEventMethod(val owner: String, val name: String)
private data class EventForwarder(val file: Path, val owner: String, val method: MethodDeclaration)

/**
 * Removes an event-bus forwarding method after its sole delegate has become a
 * directly subscribed static handler.
 *
 * This is intentionally a project-wide proof: both declarations must be in the
 * converted sources, both must carry `@SubscribeEvent`, the forwarding body must
 * contain exactly one static call with its event parameter unchanged, and no
 * ordinary source call or method reference may still target the forwarder.
 */
internal fun StructuralRefactorPass.removeDirectlySubscribedEventForwarders(
    project: Path,
    dryRun: Boolean
): List<Change> {
    val root = project.resolve("src/main/java")
    if (!Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths ->
        paths.filter { it.toString().endsWith(".java") }
            .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
            .toList()
    }
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val sources = files.associateWith { it.readText() }
    val units = sources.mapNotNull { (file, source) ->
        parser.parse(source).result.orElse(null)?.let { file to it }
    }.toMap()
    if (units.isEmpty()) return emptyList()

    val subscribed = buildSet {
        units.values.forEach { unit ->
            unit.findAll(MethodDeclaration::class.java).forEach { method ->
                if (!method.isStatic || method.parameters.size != 1 || !method.hasSubscribeEvent()) return@forEach
                val owner = method.topLevelOwner(unit) ?: return@forEach
                add(SubscribedEventMethod(owner, method.nameAsString))
            }
        }
    }
    if (subscribed.isEmpty()) return emptyList()

    val forwarders = mutableListOf<EventForwarder>()
    units.forEach { (file, unit) ->
        unit.findAll(MethodDeclaration::class.java).forEach { method ->
            if (!method.isStatic || !method.type.isVoidType || method.parameters.size != 1 || !method.hasSubscribeEvent()) return@forEach
            val body = method.body.orElse(null) ?: return@forEach
            val statement = body.statements.singleOrNull() as? ExpressionStmt ?: return@forEach
            val call = statement.expression as? MethodCallExpr ?: return@forEach
            if (call.arguments.size != 1 || call.scope.isEmpty) return@forEach
            val argument = call.arguments.single() as? NameExpr ?: return@forEach
            if (argument.nameAsString != method.parameters.single().nameAsString) return@forEach
            val targetOwner = resolveStaticOwner(call.scope.get().toString(), unit) ?: return@forEach
            if (SubscribedEventMethod(targetOwner, call.nameAsString) !in subscribed) return@forEach
            val owner = method.topLevelOwner(unit) ?: return@forEach
            if (owner == targetOwner && method.nameAsString == call.nameAsString) return@forEach
            forwarders += EventForwarder(file, owner, method)
        }
    }
    if (forwarders.isEmpty()) return emptyList()

    val removable = forwarders.filter { forwarder ->
        units.none { (_, unit) -> unit.explicitlyReferences(forwarder) }
    }
    if (removable.isEmpty()) return emptyList()

    val byFile = removable.groupBy { it.file }
    return byFile.mapNotNull { (file, removed) ->
        val unit = units.getValue(file)
        LexicalPreservingPrinter.setup(unit)
        removed.forEach { it.method.remove() }
        var migrated = LexicalPreservingPrinter.print(unit)
        migrated = removeUnusedSimpleImports(
            migrated,
            listOf(
                "net.minecraftforge.event.AttachCapabilitiesEvent",
                "net.neoforged.neoforge.event.AttachCapabilitiesEvent",
                "net.minecraftforge.eventbus.api.SubscribeEvent",
                "net.neoforged.bus.api.SubscribeEvent"
            )
        )
        if (migrated == sources.getValue(file)) return@mapNotNull null
        if (!dryRun) file.writeText(migrated)
        Change(
            file,
            1,
            "Remove duplicate event forwarding after the delegate became directly subscribed",
            "@SubscribeEvent forwarding wrapper",
            "direct @SubscribeEvent delegate",
            Confidence.HIGH,
            "struct-direct-subscribed-event-forwarder"
        )
    }
}

private fun MethodDeclaration.hasSubscribeEvent(): Boolean =
    annotations.any { it.name.identifier == "SubscribeEvent" }

private fun MethodDeclaration.topLevelOwner(unit: CompilationUnit): String? {
    val declaration = findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: return null
    if (declaration.parentNode.orElse(null) !is CompilationUnit) return null
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) declaration.nameAsString else "$packageName.${declaration.nameAsString}"
}

private fun resolveStaticOwner(scope: String, unit: CompilationUnit): String? {
    if (scope.contains('.') && scope.firstOrNull()?.isLowerCase() == true) return scope
    if (!scope.matches(Regex("""[A-Za-z_$][\w$]*"""))) return null
    unit.imports.firstOrNull {
        !it.isStatic && !it.isAsterisk && it.name.identifier == scope
    }?.let { return it.nameAsString }
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) scope else "$packageName.$scope"
}

private fun CompilationUnit.explicitlyReferences(forwarder: EventForwarder): Boolean {
    for (reference in findAll(MethodReferenceExpr::class.java)) {
        if (reference.identifier == forwarder.method.nameAsString &&
            resolveStaticOwner(reference.scope.toString(), this) == forwarder.owner
        ) return true
    }
    for (call in findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != forwarder.method.nameAsString) continue
        if (call.scope.isPresent) {
            if (resolveStaticOwner(call.scope.get().toString(), this) == forwarder.owner) return true
            continue
        }
        val caller = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: continue
        if (caller.parentNode.orElse(null) !is CompilationUnit) continue
        val packageName = packageDeclaration.map { it.nameAsString }.orElse("")
        val callerOwner = if (packageName.isBlank()) caller.nameAsString else "$packageName.${caller.nameAsString}"
        if (callerOwner == forwarder.owner) return true
    }
    return false
}
