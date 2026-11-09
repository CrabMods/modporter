package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.TypePatternExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Migrates removed record-item and jukebox inventory APIs using declared receiver types. */
class LegacyJukeboxApiMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val changes = mutableListOf<Change>()
        Files.walk(srcDir)
            .filter { it.extension == "java" }
            .toList()
            .forEach { file ->
                val source = file.readText()
                if (!source.contains("RecordItem") &&
                    !source.contains("JukeboxBlockEntity") &&
                    !source.contains(".getFirstItem()")) {
                    return@forEach
                }
                val parsed = parser.parse(source)
                val cu = parsed.result.orElseThrow {
                    IllegalStateException("Cannot parse jukebox API source $file: ${parsed.problems.joinToString()}")
                }
                LexicalPreservingPrinter.setup(cu)
                var recordChecks = 0
                var blockEntityCalls = 0

                cu.findAll(InstanceOfExpr::class.java).toList().forEach instanceLoop@{ expression ->
                    if (expression.type.toString().substringAfterLast('.') != "RecordItem") return@instanceLoop
                    if (expression.pattern.isPresent) {
                        throw IllegalStateException(
                            "RecordItem pattern variable cannot be removed without migrating its uses in $file: $expression"
                        )
                    }
                    val getItem = expression.expression as? MethodCallExpr ?: return@instanceLoop
                    if (getItem.nameAsString != "getItem" || getItem.arguments.isNotEmpty() || getItem.scope.isEmpty) {
                        return@instanceLoop
                    }
                    val componentGet = MethodCallExpr(getItem.scope.get().clone(), "get")
                        .addArgument("DataComponents.JUKEBOX_PLAYABLE")
                    val parent = expression.parentNode.orElse(null)
                    val negation = when {
                        parent is UnaryExpr -> parent
                        parent is EnclosedExpr -> parent.parentNode.orElse(null) as? UnaryExpr
                        else -> null
                    }
                    if (negation?.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                        negation.replace(BinaryExpr(componentGet, NullLiteralExpr(), BinaryExpr.Operator.EQUALS))
                    } else {
                        expression.replace(BinaryExpr(componentGet, NullLiteralExpr(), BinaryExpr.Operator.NOT_EQUALS))
                    }
                    recordChecks++
                }

                cu.findAll(MethodCallExpr::class.java).toList().forEach callLoop@{ call ->
                    if (call.nameAsString !in setOf("getFirstItem", "setItem")) return@callLoop
                    val receiver = call.scope.orElse(null) as? NameExpr ?: return@callLoop
                    if (declaredTypeAt(call, receiver.nameAsString) != "JukeboxBlockEntity") return@callLoop
                    when (call.nameAsString) {
                        "getFirstItem" -> {
                            if (call.arguments.isNotEmpty()) return@callLoop
                            call.setName("getTheItem")
                            blockEntityCalls++
                        }
                        "setItem" -> {
                            if (call.arguments.size != 2 || call.arguments[0].toString() != "0") return@callLoop
                            call.setName("setTheItem")
                            call.arguments.removeAt(0)
                            blockEntityCalls++
                        }
                    }
                }

                if (recordChecks == 0 && blockEntityCalls == 0) return@forEach
                if (recordChecks > 0) {
                    cu.addImport("net.minecraft.core.component.DataComponents")
                    cu.imports.filter {
                        !it.isStatic && it.nameAsString == "net.minecraft.world.item.RecordItem"
                    }.forEach { it.remove() }
                }
                val migrated = LexicalPreservingPrinter.print(cu)
                if (!dryRun) file.writeText(migrated)
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Migrate record items and jukebox inventory access to 1.21 component APIs",
                    before = "RecordItem instanceof / JukeboxBlockEntity getFirstItem or setItem(0, stack)",
                    after = "DataComponents.JUKEBOX_PLAYABLE / getTheItem or setTheItem(stack)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-jukebox-playable-component"
                )
            }
        return changes
    }

    private fun declaredTypeAt(node: Node, name: String): String? {
        val callable = node.findAncestor(CallableDeclaration::class.java).orElse(null)
        if (callable != null) {
            callable.parameters.filter { it.nameAsString == name }.singleOrNull()?.let {
                return simpleType(it.typeAsString)
            }
            val before = node.range.orElse(null)?.begin
            val localTypes = buildList {
                callable.findAll(VariableDeclarator::class.java)
                    .filter { it.nameAsString == name && startsBefore(it, before) }
                    .forEach { add(simpleType(it.typeAsString)) }
                callable.findAll(TypePatternExpr::class.java)
                    .filter { it.nameAsString == name && startsBefore(it, before) }
                    .forEach { add(simpleType(it.type.toString())) }
            }.distinct()
            if (localTypes.size > 1) {
                throw IllegalStateException("Ambiguous local types for jukebox receiver '$name': $localTypes")
            }
            localTypes.singleOrNull()?.let { return it }
        }
        val owner = node.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: return null
        val fieldTypes = owner.fields.flatMap { it.variables }
            .filter { it.nameAsString == name }
            .map { simpleType(it.typeAsString) }
            .distinct()
        if (fieldTypes.size > 1) {
            throw IllegalStateException("Ambiguous field types for jukebox receiver '$name': $fieldTypes")
        }
        return fieldTypes.singleOrNull()
    }

    private fun startsBefore(node: Node, before: com.github.javaparser.Position?): Boolean {
        if (before == null) return true
        val begin = node.range.orElse(null)?.begin ?: return true
        return begin.line < before.line || begin.line == before.line && begin.column < before.column
    }

    private fun simpleType(type: String): String =
        type.substringBefore('<').removeSuffix("[]").substringAfterLast('.')
}
