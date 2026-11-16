package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A fixed default MODIFIERS hide mask belongs to the default attribute component's tooltip flag. */
internal fun StructuralRefactorPass.migrateDefaultAttributeTooltip(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }.toList() }
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val changes = mutableListOf<Change>()
    for (file in files) {
        val source = file.readText()
        if (!source.contains("getDefaultTooltipHideFlags")) continue
        val unit = parser.parse(source).result.orElse(null) ?: continue
        val typing = SourceValueTypes(this, source)
        LexicalPreservingPrinter.setup(unit)
        var changed = false
        for (method in unit.findAll(MethodDeclaration::class.java)) {
            if (method.nameAsString != "getDefaultTooltipHideFlags" || method.isStatic || method.parameters.size != 1 ||
                !typing.isTypeName(method.parameters[0].type.asString(), "net.minecraft.world.item.ItemStack")) continue
            val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: continue
            val owners = mutableListOf(owner.nameAsString)
            var parent = owner.parentNode.orElse(null)
            while (parent != null) { if (parent is TypeDeclaration<*>) owners += parent.nameAsString; parent = parent.parentNode.orElse(null) }
            val namespace = unit.packageDeclaration.map { it.nameAsString }.orElse("")
            var ancestor: String? = (listOf(namespace) + owners.asReversed()).filter { it.isNotBlank() }.joinToString(".")
            val seen = mutableSetOf<String>()
            while (ancestor != null && seen.add(ancestor) && !TargetMembers.hasClass(ancestor)) ancestor = projectValueFacts.parents[ancestor]
            if (ancestor == null || !TargetMembers.inheritsFrom(ancestor, "net.minecraft.world.item.Item")) continue
            val value = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)?.expression?.orElse(null) as? MethodCallExpr ?: continue
            val flag = value.scope.orElse(null) as? FieldAccessExpr ?: continue
            if (value.nameAsString != "getMask" || value.arguments.isNotEmpty() || flag.nameAsString != "MODIFIERS" ||
                !typing.isTypeName(flag.scope.toString(), "net.minecraft.world.item.ItemStack.TooltipPart")) continue

            fun isItemPropertiesParameter(constructor: ConstructorDeclaration, name: String): Boolean {
                val parameter = constructor.parameters.singleOrNull { it.nameAsString == name } ?: return false
                if (typing.isTypeName(parameter.type.asString(), "net.minecraft.world.item.Item.Properties")) return true
                if (parameter.type.asString() != "Properties") return false
                if (unit.imports.any { !it.isStatic && (it.isAsterisk || it.name.identifier == "Properties") }) return false
                if (owner.members.filterIsInstance<TypeDeclaration<*>>().any { it.nameAsString == "Properties" }) return false
                return projectValueFacts.types.none { it == "$namespace.Properties" }
            }

            fun rootedInProperties(expression: Expression, constructor: ConstructorDeclaration): Boolean = when (expression) {
                is NameExpr -> isItemPropertiesParameter(constructor, expression.nameAsString)
                is EnclosedExpr -> rootedInProperties(expression.inner, constructor)
                is MethodCallExpr -> expression.scope.map { rootedInProperties(it, constructor) }.orElse(false)
                else -> false
            }

            data class Plan(val call: MethodCallExpr)
            val plans = mutableListOf<Plan>()
            var closed = owner.constructors.isNotEmpty()
            for (constructor in owner.constructors) {
                val invocation = constructor.body.statements.firstOrNull() as? ExplicitConstructorInvocationStmt
                if (invocation?.isThis == true) continue
                if (invocation == null) { closed = false; break }
                val attributes = invocation.arguments.mapNotNull { argument ->
                    var expression = argument
                    while (expression is EnclosedExpr) expression = expression.inner
                    (expression as? MethodCallExpr)?.takeIf { call ->
                        call.nameAsString == "attributes" && call.arguments.size == 1 &&
                            call.scope.map { rootedInProperties(it, constructor) }.orElse(false)
                    }
                }
                if (attributes.size != 1) { closed = false; break }
                plans += Plan(attributes.single())
            }
            if (!closed || plans.isEmpty()) continue
            for (plan in plans) {
                val modifiers = plan.call.arguments.single()
                if (modifiers !is MethodCallExpr || modifiers.nameAsString != "withTooltip" ||
                    modifiers.arguments.singleOrNull()?.toString() != "false") {
                    plan.call.setArgument(0, parser.parseExpression<Expression>("($modifiers).withTooltip(false)").result.orElseThrow())
                }
            }
            method.remove()
            changed = true
        }
        if (!changed) continue
        val result = removeUnusedSimpleImports(LexicalPreservingPrinter.print(unit),
            listOf("net.minecraft.world.item.ItemStack.TooltipPart", "org.jetbrains.annotations.NotNull"))
        check(parser.parse(result).isSuccessful) { "Default attribute tooltip migration emitted invalid Java: $file" }
        if (!dryRun) file.writeText(result)
        changes += Change(file, 1, "Retain an item's fixed default attribute tooltip hiding", "TooltipPart.MODIFIERS mask", "Item.Properties.attributes(...withTooltip(false))", Confidence.HIGH, "struct-default-attribute-tooltip")
    }
    return changes
}
