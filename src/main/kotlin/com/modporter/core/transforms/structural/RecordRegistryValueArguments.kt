package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A project record may deliberately keep a raw registry value while vanilla constants become holders. */
internal fun StructuralRefactorPass.migrateRecordRegistryValueArguments(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }
        .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }.toList() }
    val sources = files.associateWith { it.readText() }
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val rawValues = setOf("net.minecraft.world.effect.MobEffect", "net.minecraft.world.item.alchemy.Potion", "net.minecraft.world.entity.ai.attributes.Attribute")
    fun pkg(unit: CompilationUnit) = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    fun imports(unit: CompilationUnit) = unit.imports.filter { !it.isStatic && !it.isAsterisk }.associate { it.name.identifier to it.nameAsString }
    fun owners(node: Node): List<String> {
        val names = mutableListOf<String>()
        var parent = node.parentNode.orElse(null)
        while (parent != null) { if (parent is TypeDeclaration<*>) names += parent.nameAsString; parent = parent.parentNode.orElse(null) }
        return names.asReversed()
    }
    val records = mutableMapOf<String, List<String?>>()
    for ((_, source) in sources) {
        if (!source.contains("record") || rawValues.none { source.contains(it.substringAfterLast('.')) }) continue
        val unit = parser.parse(source).result.orElse(null) ?: continue
        val aliases = imports(unit)
        for (record in unit.findAll(RecordDeclaration::class.java)) {
            if (record.parentNode.orElse(null) !is CompilationUnit && record.parentNode.orElse(null) !is TypeDeclaration<*>) continue
            val shape = record.parameters.map { parameter ->
                val type = parameter.type.asString()
                (aliases[type] ?: type).takeIf { it in rawValues }
            }
            if (shape.none { it != null }) continue
            val name = (listOf(pkg(unit)) + owners(record) + record.nameAsString).filter { it.isNotBlank() }.joinToString(".")
            records[name] = shape
        }
    }
    if (records.isEmpty()) return emptyList()
    val simpleNames = records.keys.map { it.substringAfterLast('.') }.toSet()
    val changes = mutableListOf<Change>()
    for ((file, source) in sources) {
        if (!source.contains("new ") || simpleNames.none { source.contains(it) }) continue
        val unit = parser.parse(source).result.orElse(null) ?: continue
        val aliases = imports(unit)
        val typing = SourceValueTypes(this, source)
        val starts = buildList { add(0); source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
        LexicalPreservingPrinter.setup(unit)
        var changed = false
        for (call in unit.findAll(ObjectCreationExpr::class.java)) {
            val name = call.type.nameWithScope
            val scope = owners(call)
            val candidates = mutableSetOf(name)
            aliases[name.substringBefore('.')]?.let { candidates += it + name.removePrefix(name.substringBefore('.')) }
            for (count in 0..scope.size) candidates += (listOf(pkg(unit)) + scope.take(count) + name).filter { it.isNotBlank() }.joinToString(".")
            val shape = candidates.filter { it in records }.singleOrNull()?.let { records[it] } ?: continue
            if (shape.size != call.arguments.size) continue
            for ((index, expected) in shape.withIndex()) {
                if (expected == null) continue
                val argument = call.arguments[index]
                val offset = argument.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
                if (offset < 0 || typing.holderValueType(argument.toString(), offset) != expected) continue
                argument.replace(MethodCallExpr(EnclosedExpr(argument.clone()), "value"))
                changed = true
            }
        }
        if (!changed) continue
        if (!dryRun) file.writeText(LexicalPreservingPrinter.print(unit))
        changes += Change(file, 1, "Records retain raw registry value components when callers use vanilla holder constants", "Holder<T> argument", "holder.value() for a proven raw T component", Confidence.HIGH, "struct-record-registry-value-arguments")
    }
    return changes
}
