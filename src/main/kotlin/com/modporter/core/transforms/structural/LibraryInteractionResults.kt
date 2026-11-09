package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private data class LibraryMethod(
    val owner: String,
    val name: String,
    val parameterCount: Int,
    val returnType: String
)

/**
 * Method metadata from Ponder 1.0.87+mc1.21.1. The descriptors were read from
 * the shipped library class files; both `getOffset` overloads return
 * `PlacementOffset`, whose five-argument `placeInWorld` now returns
 * `ItemInteractionResult`.
 */
private val ponderPlacementMethods = setOf(
    LibraryMethod(
        "net.createmod.catnip.placement.IPlacementHelper",
        "getOffset",
        5,
        "net.createmod.catnip.placement.PlacementOffset"
    ),
    LibraryMethod(
        "net.createmod.catnip.placement.IPlacementHelper",
        "getOffset",
        6,
        "net.createmod.catnip.placement.PlacementOffset"
    ),
    LibraryMethod(
        "net.createmod.catnip.placement.PlacementOffset",
        "placeInWorld",
        5,
        "net.minecraft.world.ItemInteractionResult"
    )
)

private const val ITEM_RESULT_BRIDGE_DESCRIPTOR =
    "()Lnet/minecraft/world/InteractionResult;"

/**
 * Runs the library return bridge after project-level method signature
 * migrations have settled. In particular, a legacy Block#use override must
 * first become ItemInteractionResult-returning Block#useItemOn so its native
 * Ponder return value is not bridged back to InteractionResult.
 */
internal fun StructuralRefactorPass.migrateProjectLibraryItemInteractionResultBridges(
    projectDir: Path,
    dryRun: Boolean
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir).use { paths ->
        paths.filter { it.toString().endsWith(".java") }.toList()
    }
    projectValueFacts = ProjectValueFacts.collect(this, javaFiles)

    return javaFiles.mapNotNull { file ->
        val source = file.readText()
        val migrated = migrateLibraryItemInteractionResultBridges(source)
        if (migrated == source) {
            null
        } else {
            if (!dryRun) file.writeText(migrated)
            Change(
                file = file,
                line = 0,
                description = "Bridge Ponder ItemInteractionResult into a final InteractionResult return",
                before = "return placementOffset.placeInWorld(...);",
                after = "return placementOffset.placeInWorld(...).result();",
                confidence = Confidence.HIGH,
                ruleId = "struct-library-item-interaction-result-bridge"
            )
        }
    }
}

/**
 * Bridges Ponder's changed `PlacementOffset#placeInWorld` return type only when
 * the enclosing source method still returns `InteractionResult`.
 */
internal fun StructuralRefactorPass.migrateLibraryItemInteractionResultBridges(source: String): String {
    if (!source.contains(".placeInWorld(") || !source.contains("InteractionResult")) return source
    if (ITEM_RESULT_BRIDGE_DESCRIPTOR !in
        TargetMembers.methodDescriptors("net.minecraft.world.ItemInteractionResult", "result")
    ) return source

    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int = node.range
        .map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }
        .orElse(-1)
    val typing = SourceValueTypes(this, source)
    val edits = mutableListOf<Pair<ReturnStmt, MethodCallExpr>>()

    for (method in unit.findAll(MethodDeclaration::class.java)) {
        if (qualifiedResultType(method.type.asString(), unit) != "net.minecraft.world.InteractionResult") continue
        val body = method.body.orElse(null) ?: continue
        for (returned in body.findAll(ReturnStmt::class.java)) {
            if (!returned.belongsDirectlyToResultMethod(method)) continue
            val call = returned.expression.orElse(null) as? MethodCallExpr ?: continue
            if (call.nameAsString != "placeInWorld" || call.arguments.size != 5 || call.scope.isEmpty) continue
            if (call.parentNode.orElse(null) is MethodCallExpr &&
                (call.parentNode.get() as MethodCallExpr).nameAsString == "result"
            ) continue
            val at = offset(call)
            if (at < 0 || !isPonderPlacementOffset(call.scope.get(), at, typing)) continue
            val terminal = ponderPlacementMethods.single {
                it.owner == "net.createmod.catnip.placement.PlacementOffset" &&
                    it.name == "placeInWorld" &&
                    it.parameterCount == call.arguments.size
            }
            if (terminal.returnType != "net.minecraft.world.ItemInteractionResult") continue
            edits += returned to call
        }
    }
    if (edits.isEmpty()) return source

    LexicalPreservingPrinter.setup(unit)
    edits.forEach { (returned, call) ->
        val bridged = parser.parseExpression<Expression>("${call}.result()").result.orElseThrow()
        returned.setExpression(bridged)
    }
    return LexicalPreservingPrinter.print(unit)
}

private fun isPonderPlacementOffset(
    expression: Expression,
    offset: Int,
    typing: SourceValueTypes
): Boolean {
    if (typing.typeOf(expression.toString(), offset) ==
        "net.createmod.catnip.placement.PlacementOffset"
    ) return true
    val factory = expression as? MethodCallExpr ?: return false
    val scope = factory.scope.orElse(null) ?: return false
    val owner = typing.typeOf(scope.toString(), offset) ?: return false
    return ponderPlacementMethods.any {
        it.owner == owner &&
            it.name == factory.nameAsString &&
            it.parameterCount == factory.arguments.size &&
            it.returnType == "net.createmod.catnip.placement.PlacementOffset"
    }
}

private fun qualifiedResultType(type: String, unit: CompilationUnit): String {
    val plain = type.substringBefore('<').trim()
    if (plain.contains('.')) return plain
    unit.imports.firstOrNull {
        !it.isStatic && !it.isAsterisk && it.name.identifier == plain
    }?.let { return it.nameAsString }
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    return if (packageName.isBlank()) plain else "$packageName.$plain"
}

private fun ReturnStmt.belongsDirectlyToResultMethod(method: MethodDeclaration): Boolean {
    var current: Node? = parentNode.orElse(null)
    while (current != null && current !== method) {
        if (current is LambdaExpr || current is ConstructorDeclaration ||
            current is MethodDeclaration || current is ObjectCreationExpr && current.anonymousClassBody.isPresent
        ) return false
        current = current.parentNode.orElse(null)
    }
    return current === method
}
