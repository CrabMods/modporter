package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/** NeoForge registry aliases retain renamed ids without Forge's removed missing-mapping event. */
internal fun StructuralRefactorPass.migrateLegacyRegistryAliases(source: String): String {
    if (!source.contains("MissingMappingsEvent") || !source.contains("EventBusSubscriber")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == "MissingMappingsEvent" }) return source
    data class Edit(val method: MethodDeclaration, val body: BlockStmt)
    val edits = mutableListOf<Edit>()
    val registryFields = setOf("BLOCK", "ITEM", "FLUID", "BLOCK_ENTITY_TYPE", "ENTITY_TYPE", "RECIPE_SERIALIZER",
        "MENU", "PARTICLE_TYPE", "SOUND_EVENT", "MOB_EFFECT", "ATTRIBUTE")
    val methods = unit.findAll(MethodDeclaration::class.java).filter { method ->
        method.annotations.any { it.name.identifier == "SubscribeEvent" }
    }
    if (methods.isEmpty()) return source
    for (method in methods) {
        if (!method.isStatic || method.parameters.size != 1 || method.parameters[0].type.asString() != "MissingMappingsEvent") return source
        val event = method.parameters[0].nameAsString
        val statements = method.body.orElse(null)?.statements ?: return source
        if (statements.size != 1 || statements[0] !is ForEachStmt) return source
        val loop = (statements[0] as ForEachStmt).clone()
        val input = loop.iterable as? MethodCallExpr ?: return source
        if (input.nameAsString != "getMappings" || input.scope.map { it.toString() }.orElse("") != event || input.arguments.size != 2) return source
        val registry = input.arguments[0].toString().removePrefix("net.minecraft.core.registries.")
        if (!registry.startsWith("Registries.") || registry.substringAfter('.') !in registryFields) return source
        if (!unit.imports.any { it.nameAsString == "net.minecraft.core.registries.Registries" } && !input.arguments[0].toString().startsWith("net.minecraft.")) return source
        val builtin = "net.minecraft.core.registries.BuiltInRegistries.${registry.substringAfter('.')}"
        val mapping = loop.variable.variables.singleOrNull()?.nameAsString ?: return source
        val body = loop.body as? BlockStmt ?: return source
        if (body.statements.size < 3) return source
        val keyDeclaration = (body.statements[0] as? com.github.javaparser.ast.stmt.ExpressionStmt)?.expression as? VariableDeclarationExpr ?: return source
        val keyVariable = keyDeclaration.variables.singleOrNull() ?: return source
        val key = keyVariable.nameAsString
        if (keyVariable.initializer.map { it.toString() }.orElse("") != "$mapping.getKey()") return source
        val pathDeclaration = (body.statements[1] as? com.github.javaparser.ast.stmt.ExpressionStmt)?.expression as? VariableDeclarationExpr ?: return source
        val pathVariable = pathDeclaration.variables.singleOrNull() ?: return source
        val path = pathVariable.nameAsString
        if (pathVariable.type.asString() != "String" || pathVariable.initializer.map { it.toString() }.orElse("") != "$key.getPath()") return source
        val mappingUses = body.findAll(NameExpr::class.java).filter { it.nameAsString == mapping }
        if (mappingUses.any { use ->
                val call = use.parentNode.orElse(null) as? MethodCallExpr
                call == null || call.scope.orElse(null) !== use || call.nameAsString !in setOf("getKey", "remap")
            }) return source
        var namespace = "modporterAliasNamespace"
        while (source.contains(namespace)) namespace += "_"
        var entry = "modporterAlias"
        while (source.contains(entry)) entry += "_"
        val lookup = body.findAll(MethodCallExpr::class.java).firstOrNull {
            it.nameAsString == "get" && it.arguments.size == 1 && it.arguments[0].toString() == path &&
                it.scope.orElse(null) is NameExpr && it.parentNode.orElse(null) is com.github.javaparser.ast.body.VariableDeclarator
        }
        body.statements.removeAt(0)
        body.statements.removeAt(0)
        val prelude: String
        if (lookup != null) {
            val map = lookup.scope.get().toString()
            val declaredMap = unit.findAll(com.github.javaparser.ast.body.VariableDeclarator::class.java).firstOrNull {
                it.nameAsString == map && it.parentNode.orElse(null) is com.github.javaparser.ast.body.FieldDeclaration
            } ?: return source
            if (declaredMap.type.asString().replace(" ", "") !in setOf("Map<String,ResourceLocation>", "java.util.Map<String,ResourceLocation>")) return source
            loop.setVariable(parser.parseVariableDeclarationExpr("var $entry").result.orElseThrow())
            loop.setIterable(parser.parseExpression<Expression>("$map.entrySet()").result.orElseThrow())
            prelude = """
                String $path = $entry.getKey();
                net.minecraft.resources.ResourceLocation $key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($namespace, $path);
                if ($builtin.containsKey($key) || !$builtin.containsKey($entry.getValue())) continue;
            """.trimIndent()
        } else {
            val paths = body.findAll(MethodCallExpr::class.java).filter {
                it.nameAsString == "equals" && it.scope.map { scope -> scope.toString() }.orElse("") == path &&
                    it.arguments.size == 1 && it.arguments[0] is StringLiteralExpr
            }.map { it.arguments[0].clone() }.distinctBy { it.toString() }
            if (paths.isEmpty()) return source
            loop.setVariable(parser.parseVariableDeclarationExpr("String $path").result.orElseThrow())
            loop.setIterable(MethodCallExpr(NameExpr("java.util.List"), "of", NodeList(paths)))
            prelude = """
                net.minecraft.resources.ResourceLocation $key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($namespace, $path);
                if ($builtin.containsKey($key)) continue;
            """.trimIndent()
        }
        val remaps = body.findAll(MethodCallExpr::class.java).filter { it.nameAsString == "remap" && it.scope.map { scope -> scope.toString() }.orElse("") == mapping }
        if (remaps.isEmpty() || remaps.any { it.arguments.size != 1 }) return source
        for (remap in remaps) remap.replace(parser.parseExpression<Expression>("$builtin.addAlias($key, $builtin.getKey(${remap.arguments[0]}))").result.orElseThrow())
        for (call in body.findAll(MethodCallExpr::class.java)) {
            if (call.nameAsString == "warn" && call.arguments.any { it.toString() == key } && call.findAncestor(CatchClause::class.java).isEmpty) call.setName("debug")
        }
        val prefix = parser.parseBlock("{$prelude}").result.orElseThrow().statements
        prefix.asReversed().forEach { body.addStatement(0, it.clone()) }
        edits += Edit(method, parser.parseBlock("{ $event.enqueueWork(() -> { String $namespace = ${input.arguments[1]}; $loop }); }").result.orElseThrow())
    }
    val subscribers = unit.findAll(com.github.javaparser.ast.expr.AnnotationExpr::class.java).filter { it.name.identifier == "EventBusSubscriber" }
    if (subscribers.size != 1) return source
    LexicalPreservingPrinter.setup(unit)
    for (edit in edits) {
        edit.method.parameters[0].setType("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent")
        edit.method.setBody(edit.body)
    }
    val subscriber = subscribers.single()
    val pairs = if (subscriber is NormalAnnotationExpr) subscriber.pairs.filter { it.nameAsString != "bus" }.map { it.clone() } else emptyList()
    subscriber.replace(NormalAnnotationExpr(subscriber.name.clone(), NodeList(pairs + MemberValuePair("bus", parser.parseExpression<Expression>("net.neoforged.fml.common.EventBusSubscriber.Bus.MOD").result.orElseThrow()))))
    unit.imports.removeIf { it.nameAsString.startsWith("net.neoforged.neoforge.registries.MissingMappingsEvent") || it.nameAsString.startsWith("net.minecraftforge.registries.MissingMappingsEvent") }
    return LexicalPreservingPrinter.print(unit)
}
