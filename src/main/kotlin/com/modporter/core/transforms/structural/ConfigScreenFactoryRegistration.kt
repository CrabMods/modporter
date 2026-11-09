package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers

/**
 * Draft migration for the removed ConfigScreenHandler.ConfigScreenFactory extension point.
 *
 * It accepts a proven ModContainer registration, or the canonical ModLoadingContext route that
 * delegates to its active container, with the old lazy supplier shape. Inline two-argument factory
 * lambdas migrate only when their old Minecraft parameter is unused. Exact same-source static
 * method references with the old (Minecraft, Screen) -> Screen signature use a typed adapter inside
 * the supplier, preserving both supplier laziness and one-time factory capture.
 *
 * Forge 1.20.1's ModListScreen invoked the old callback with its Screen.minecraft field.
 * Minecraft.setScreen initializes that field with the singleton receiver returned by getInstance,
 * so the method-reference adapter can recover the same Minecraft instance on the target.
 */
internal fun StructuralRefactorPass.migrateConfigScreenFactoryRegistrationExact(
    source: String
): String {
    if (!source.contains("ConfigScreenFactory")) return source
    if (!targetConfigScreenFactoryContract()) return source
    val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val simpleTargetAvailable = simpleConfigFactoryNameAvailable(source)
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int =
        node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(0)
    fun uniqueName(base: String): String {
        var name = base
        while (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source)) name += "_"
        return name
    }

    LexicalPreservingPrinter.setup(unit)
    var changed = false
    var needsTargetImport = false
    for (registration in unit.findAll(MethodCallExpr::class.java)) {
        if (registration.nameAsString != "registerExtensionPoint" ||
            registration.arguments.size != 2
        ) continue
        val receiver = registration.scope.orElse(null) ?: continue
        val activeContainer = when {
            typing.typeOf(receiver.toString(), offset(receiver)) == MOD_CONTAINER -> null
            exactModLoadingContextReceiver(receiver, typing, ::offset) -> {
                parser.parseExpression<com.github.javaparser.ast.expr.Expression>(
                    "${receiver}.getActiveContainer()"
                ).result.orElse(null) ?: continue
            }
            else -> continue
        }

        val point = registration.arguments[0] as? ClassExpr ?: continue
        val oldPoint = oldConfigFactoryOwner(point.type.asString(), typing) ?: continue
        val supplier = registration.arguments[1] as? LambdaExpr ?: continue
        if (supplier.parameters.isNotEmpty()) continue
        val creation = supplier.expressionBody.orElse(null) as? ObjectCreationExpr ?: continue
        if (!typing.isTypeName(creation.type.asString(), oldPoint) ||
            creation.arguments.size != 1
        ) continue
        val factory = creation.arguments[0]
        val replacementFactory = when (factory) {
            is LambdaExpr -> {
                if (factory.parameters.any { !it.type.isUnknownType }) continue
                when (factory.parameters.size) {
                    // Only the (Screen) -> Screen overload accepts a one-parameter lambda; it
                    // delegated as (minecraft, screen) -> function.apply(screen).
                    1 -> LambdaExpr(
                        com.github.javaparser.ast.NodeList(
                            com.github.javaparser.ast.body.Parameter(
                                com.github.javaparser.ast.type.UnknownType(),
                                uniqueName("modporterContainer")
                            ),
                            factory.parameters[0].clone()
                        ),
                        factory.body.clone(),
                        true
                    )
                    2 -> {
                        val oldMinecraftParameter = factory.parameters[0].nameAsString
                        if (factory.body.findAll(NameExpr::class.java)
                                .any { it.nameAsString == oldMinecraftParameter }
                        ) continue
                        factory.clone()
                    }
                    else -> continue
                }
            }
            is MethodReferenceExpr -> {
                val arity = exactSameSourceConfigFactoryReference(factory, unit, typing) ?: continue
                if (arity == 1) {
                    // A (Screen) -> Screen reference binds only the Function overload.
                    val containerName = uniqueName("modporterContainer")
                    val parentName = uniqueName("modporterParent")
                    val call = if (factory.identifier == "new") {
                        "new ${factory.scope}($parentName)"
                    } else {
                        "${factory.scope}.${factory.identifier}($parentName)"
                    }
                    parser.parseExpression<com.github.javaparser.ast.expr.Expression>(
                        "($containerName, $parentName) -> $call"
                    ).result.orElse(null) ?: continue
                } else {
                    val capturedFactory = uniqueName("modporterConfigFactory")
                    val containerName = uniqueName("modporterContainer")
                    val parentName = uniqueName("modporterParent")
                    parser.parseExpression<com.github.javaparser.ast.expr.Expression>(
                        "((java.util.function.Function<" +
                            "java.util.function.BiFunction<$MINECRAFT, $SCREEN, $SCREEN>, " +
                            "$TARGET_CONFIG_FACTORY>) ($capturedFactory) -> " +
                            "($containerName, $parentName) -> " +
                            "$capturedFactory.apply($MINECRAFT.getInstance(), $parentName))" +
                            ".apply(${factory})"
                    ).result.orElse(null) ?: continue
                }
            }
            else -> continue
        }

        val qualified = point.type.asString().startsWith("net.")
        if (!qualified && !simpleTargetAvailable) continue
        activeContainer?.let(registration::setScope)
        point.setType(if (qualified) TARGET_CONFIG_FACTORY else "IConfigScreenFactory")
        creation.replace(replacementFactory)
        needsTargetImport = needsTargetImport || !qualified
        changed = true
    }
    if (!changed) return source

    var result = LexicalPreservingPrinter.print(unit)
    for (oldImport in listOf(
        "net.minecraftforge.client.ConfigScreenHandler",
        "net.neoforged.neoforge.client.ConfigScreenHandler"
    )) {
        val without = JavaSourceText.removeImport(result, oldImport)
        if (!without.contains("ConfigScreenHandler")) result = without
    }
    if (needsTargetImport) {
        result = JavaSourceText.addImport(result, TARGET_CONFIG_FACTORY)
    }
    return result
}

private const val MOD_CONTAINER = "net.neoforged.fml.ModContainer"
private const val MOD_LOADING_CONTEXT = "net.neoforged.fml.ModLoadingContext"
private const val MINECRAFT = "net.minecraft.client.Minecraft"
private const val SCREEN = "net.minecraft.client.gui.screens.Screen"
private const val TARGET_CONFIG_FACTORY =
    "net.neoforged.neoforge.client.gui.IConfigScreenFactory"
private val OLD_CONFIG_FACTORIES = listOf(
    "net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory",
    "net.neoforged.neoforge.client.ConfigScreenHandler.ConfigScreenFactory"
)

private fun oldConfigFactoryOwner(type: String, typing: SourceValueTypes): String? =
    OLD_CONFIG_FACTORIES.singleOrNull { typing.isTypeName(type, it) }

private fun exactModLoadingContextReceiver(
    receiver: com.github.javaparser.ast.expr.Expression,
    typing: SourceValueTypes,
    offset: (Node) -> Int
): Boolean {
    if (typing.typeOf(receiver.toString(), offset(receiver)) == MOD_LOADING_CONTEXT) return true
    val getter = receiver as? MethodCallExpr ?: return false
    if (getter.nameAsString != "get" ||
        getter.arguments.isNotEmpty() ||
        getter.typeArguments.isPresent
    ) return false
    val owner = getter.scope.orElse(null) ?: return false
    return typing.isTypeName(owner.toString(), MOD_LOADING_CONTEXT)
}

/** FML moved config registration onto the same active container used by the old context. */
internal fun StructuralRefactorPass.migrateModLoadingContextConfigCalls(source: String): String {
    if (!source.contains(".registerConfig(")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val lines = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(node: Node): Int = node.range.map { lines[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
    LexicalPreservingPrinter.setup(unit)
    var changed = false
    for (call in unit.findAll(MethodCallExpr::class.java)) {
        if (call.nameAsString != "registerConfig" || call.arguments.size !in 2..3 || call.typeArguments.isPresent) continue
        val receiver = call.scope.orElse(null) ?: continue
        if (!exactModLoadingContextReceiver(receiver, typing, ::offset)) continue
        val parameters = "Lnet/neoforged/fml/config/ModConfig\$Type;Lnet/neoforged/fml/config/IConfigSpec;" +
            if (call.arguments.size == 3) "Ljava/lang/String;" else ""
        val registration = TargetMembers.inheritedMethod(MOD_CONTAINER, "registerConfig", "($parameters)V")
        if (registration?.owner != MOD_CONTAINER || registration.access != "public") continue
        call.setScope(parser.parseExpression<com.github.javaparser.ast.expr.Expression>("$receiver.getActiveContainer()").result.orElseThrow())
        changed = true
    }
    return if (changed) LexicalPreservingPrinter.print(unit) else source
}

/**
 * The parameter count of the single same-source static factory a reference names: 2 for
 * (Minecraft, Screen) -> Screen, 1 for (Screen) -> Screen; null when it is not exactly one of them.
 */
private fun exactSameSourceConfigFactoryReference(
    reference: MethodReferenceExpr,
    unit: com.github.javaparser.ast.CompilationUnit,
    typing: SourceValueTypes
): Int? {
    val ownerType = (reference.scope as? TypeExpr)?.type?.asString() ?: return null
    val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    val owner = unit.findAll(ClassOrInterfaceDeclaration::class.java)
        .filter { declaration ->
            declaration.findAncestor(ClassOrInterfaceDeclaration::class.java).isEmpty &&
                typing.isTypeName(
                    ownerType,
                    if (packageName.isBlank()) declaration.nameAsString
                    else "$packageName.${declaration.nameAsString}"
                )
        }
        .singleOrNull()
        ?: return null
    fun shape(parameters: List<com.github.javaparser.ast.body.Parameter>): Int? {
        if (parameters.any { it.isVarArgs }) return null
        val types = parameters.map { it.type.asString() }
        return when {
            types.size == 2 && typing.isTypeName(types[0], MINECRAFT) &&
                typing.isTypeName(types[1], SCREEN) -> 2
            types.size == 1 && typing.isTypeName(types[0], SCREEN) -> 1
            else -> null
        }
    }
    if (reference.identifier == "new") {
        // A constructor reference of a concrete, non-generic same-source screen class binds the
        // overload of its single factory-shaped constructor.
        if (owner.isAbstract || owner.isInterface || owner.typeParameters.isNotEmpty()) return null
        val shaped = owner.constructors.filter { constructor ->
            constructor.thrownExceptions.isEmpty() && shape(constructor.parameters) != null
        }
        return shaped.singleOrNull()?.let { shape(it.parameters) }
    }
    val method = owner.getMethodsByName(reference.identifier).singleOrNull() ?: return null
    if (!method.isStatic ||
        method.thrownExceptions.isNotEmpty() ||
        !typing.isTypeName(method.type.asString(), SCREEN)
    ) return null
    return shape(method.parameters)
}

private fun simpleConfigFactoryNameAvailable(source: String): Boolean {
    val code = JavaSourceText.maskCommentsAndLiterals(source)
    if (Regex("""\b(?:class|interface|record|enum)\s+IConfigScreenFactory\b""")
            .containsMatchIn(code)
    ) return false
    return Regex("""(?m)^\s*import\s+([\w.]+\.IConfigScreenFactory)\s*;""")
        .findAll(code)
        .map { it.groupValues[1] }
        .all { it == TARGET_CONFIG_FACTORY }
}

private fun targetConfigScreenFactoryContract(): Boolean {
    val create = TargetMembers.inheritedMethod(
        TARGET_CONFIG_FACTORY,
        "createScreen",
        "(Lnet/neoforged/fml/ModContainer;Lnet/minecraft/client/gui/screens/Screen;)" +
            "Lnet/minecraft/client/gui/screens/Screen;"
    )
    val minecraft = TargetMembers.inheritedMethod(
        MINECRAFT,
        "getInstance",
        "()Lnet/minecraft/client/Minecraft;"
    )
    return create?.owner == TARGET_CONFIG_FACTORY &&
        create.access == "public" &&
        minecraft?.owner == MINECRAFT &&
        minecraft.access == "public" &&
        TargetMembers.inheritsFrom(TARGET_CONFIG_FACTORY, "net.neoforged.fml.IExtensionPoint")
}
