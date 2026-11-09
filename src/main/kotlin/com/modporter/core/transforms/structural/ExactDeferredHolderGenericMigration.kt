package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Runs the exact lexical type-parameter proof before the broader DeferredHolder
 * generic migration consumes the source.
 */
internal fun StructuralRefactorPass.migrateLexicallyBoundDeferredHolderGenerics(
    projectDir: Path,
    dryRun: Boolean
): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val changes = mutableListOf<Change>()

    Files.walk(srcDir).use { files ->
        files.filter { it.extension == "java" }.toList().forEach { file ->
            val original = file.readText()
            if (!original.contains("DeferredHolder")) return@forEach
            val migrated = migrateLexicallyBoundDeferredHolderGenerics(original)
            if (migrated == original) return@forEach
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate lexically bound DeferredHolder declarations to their registry base",
                before = "DeferredHolder<T, T> where T extends Block",
                after = "DeferredHolder<Block, T>",
                confidence = Confidence.HIGH,
                ruleId = "struct-deferredholder-generics"
            )
            if (!dryRun) file.writeText(migrated)
        }
    }
    return changes
}

private fun migrateLexicallyBoundDeferredHolderGenerics(source: String): String {
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val parseResult = parser.parse(source)
    if (!parseResult.isSuccessful) return source
    val cu = parseResult.result.orElse(null) ?: return source
    LexicalPreservingPrinter.setup(cu)
    var changed = false

    cu.findAll(ClassOrInterfaceType::class.java)
        .filter {
            it.hasExactJavaOwner(cu, "net.neoforged.neoforge.registries.DeferredHolder") &&
                it.typeArguments.map { args -> args.size }.orElse(0) == 2
        }
        .forEach { holder ->
            val arguments = holder.typeArguments.orElse(null) ?: return@forEach
            val first = arguments[0].asSimpleUnscopedTypeName() ?: return@forEach
            val second = arguments[1].asSimpleUnscopedTypeName() ?: return@forEach
            if (first != second && first != "Item") return@forEach
            val bound = lexicalTypeParameterBound(holder, second) ?: return@forEach
            if (!bound.hasExactJavaOwner(cu, "net.minecraft.world.level.block.Block")) return@forEach
            arguments.set(0, StaticJavaParser.parseClassOrInterfaceType("Block"))
            changed = true
        }

    return if (changed) LexicalPreservingPrinter.print(cu) else source
}

private fun lexicalTypeParameterBound(use: Node, name: String): ClassOrInterfaceType? {
    var current = use.parentNode.orElse(null)
    while (current != null) {
        val parameters = when (current) {
            is CallableDeclaration<*> -> current.typeParameters
            is ClassOrInterfaceDeclaration -> current.typeParameters
            is RecordDeclaration -> current.typeParameters
            else -> null
        }
        if (parameters != null) {
            val parameter = parameters.singleOrNull { it.nameAsString == name }
            if (parameter != null) {
                return parameter.typeBound.singleOrNull()
            }
        }
        current = current.parentNode.orElse(null)
    }
    return null
}

private fun Type.asSimpleUnscopedTypeName(): String? {
    if (!isClassOrInterfaceType) return null
    val type = asClassOrInterfaceType()
    if (type.scope.isPresent || type.typeArguments.map { it.isNotEmpty() }.orElse(false)) return null
    return type.nameAsString
}

private fun ClassOrInterfaceType.hasExactJavaOwner(
    cu: CompilationUnit,
    qualifiedName: String
): Boolean {
    val simpleName = qualifiedName.substringAfterLast('.')
    if (nameAsString != simpleName) return false
    val packageName = qualifiedName.substringBeforeLast('.')
    if (scope.isPresent) {
        return "${scope.get().asString()}.$nameAsString" == qualifiedName
    }
    return cu.imports.any { import ->
        !import.isStatic &&
            if (import.isAsterisk) {
                import.nameAsString == packageName
            } else {
                import.nameAsString == qualifiedName
            }
    }
}
