package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.mapping.TargetMembers

/** The old instance load entry point now restores components before invoking loadAdditional. */
internal fun StructuralRefactorPass.migrateBlockEntitySelfLoads(source: String): String {
    if (!source.contains("load(") || !source.contains("HolderLookup")) return source
    if (TargetMembers.inheritedMethod("net.minecraft.world.level.block.entity.BlockEntity", "loadWithComponents",
            "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup\$Provider;)V")?.access != "public") return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    if (unit.imports.any { it.isStatic && (it.isAsterisk || it.name.identifier == "load") }) return source
    val typing = SourceValueTypes(this, source)
    val starts = buildList {
        add(0)
        source.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }
    fun offset(node: Node) = node.range.map { starts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    val pkg = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "load" || call.arguments.size != 1 || call.scope.isPresent || call.typeArguments.isPresent) continue
        if (call.findAncestor(ObjectCreationExpr::class.java).map { it.anonymousClassBody.isPresent }.orElse(false)) continue
        val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
        if (method.isStatic) continue
        val provider = method.parameters.singleOrNull {
            typing.isTypeName(it.type.asString(), "net.minecraft.core.HolderLookup.Provider")
        } ?: continue
        if (typing.typeOf(call.arguments[0].toString(), offset(call.arguments[0])) != "net.minecraft.nbt.CompoundTag") continue
        val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: continue
        val nested = generateSequence(owner) { it.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) }
            .toList().asReversed().joinToString(".") { it.nameAsString }
        var current = if (pkg.isBlank()) nested else "$pkg.$nested"
        val seen = mutableSetOf<String>()
        var proven = false
        while (seen.add(current)) {
            if (projectValueFacts.methods[current]?.containsKey("load/1") == true) break
            if (TargetMembers.inheritsFrom(current, "net.minecraft.world.level.block.entity.BlockEntity")) {
                proven = true
                break
            }
            current = projectValueFacts.parents[current] ?: break
        }
        if (!proven) continue
        call.setName("loadWithComponents")
        call.addArgument(provider.nameAsString)
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}
