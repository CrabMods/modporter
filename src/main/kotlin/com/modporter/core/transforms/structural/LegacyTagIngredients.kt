package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.*
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Tag-backed AbstractIngredient implementations retain their predicate and legacy serializers. */
internal fun StructuralRefactorPass.migrateLegacyTagIngredients(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }
        .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }.toList() }
    val sources = files.associateWith { it.readText() }.toMutableMap()
    val original = sources.toMap()
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    fun parse(source: String) = parser.parse(source).result.orElse(null)
    fun qualified(name: String, unit: CompilationUnit): String {
        val first = name.substringBefore('.')
        unit.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == first }?.let { return it.nameAsString + name.removePrefix(first) }
        if (name.contains('.') && name.first().isLowerCase()) return name
        return unit.packageDeclaration.map { it.nameAsString + "." + name }.orElse(name)
    }
    fun returnValue(method: MethodDeclaration) = (method.body.orElse(null)?.statements?.lastOrNull() as? ReturnStmt)?.expression?.orElse(null)
    data class Registration(val file: Path, val reference: String, val event: String)
    for ((file, source) in original) {
        if (!source.contains("AbstractIngredient")) continue
        val unit = parse(source) ?: continue
        val owner = unit.types.filterIsInstance<ClassOrInterfaceDeclaration>().singleOrNull { type ->
            type.extendedTypes.singleOrNull()?.let { qualified(it.nameWithScope, unit) } in setOf("net.minecraftforge.common.crafting.AbstractIngredient", "net.neoforged.neoforge.common.crafting.AbstractIngredient")
        } ?: continue
        if (owner.typeParameters.isNotEmpty() || owner.isAbstract || owner.members.filterIsInstance<InitializerDeclaration>().any { it.isStatic } || owner.fields.any { it.isStatic }) continue
        val fields = owner.fields.flatMap { field -> field.variables.map { field to it } }
        val tag = fields.singleOrNull { (field, variable) -> field.isFinal && variable.type.isClassOrInterfaceType && qualified(variable.type.asClassOrInterfaceType().nameWithScope, unit) == "net.minecraft.tags.TagKey" }?.second ?: continue
        if (fields.any { (field, variable) -> variable !== tag && (field.isFinal || variable.type.asString().replace(" ", "") !in setOf("ItemStack[]", "IntList")) }) continue
        val simple = owner.nameAsString
        val canonical = qualified(simple, unit)
        val externalUse = original.any { (otherFile, text) ->
            if (otherFile == file || !Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(text))) false else {
                val other = parse(text)
                other != null && other.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType::class.java).any { qualified(it.nameWithScope, other) == canonical }
            }
        }
        if (externalUse) continue
        val items = owner.methods.singleOrNull { it.nameAsString == "getItems" && it.parameters.isEmpty() && it.type.isArrayType && qualified(it.type.asArrayType().componentType.asString(), unit) == "net.minecraft.world.item.ItemStack" } ?: continue
        if (items.findAll(LambdaExpr::class.java).isNotEmpty()) continue
        if (owner.findAll(MethodCallExpr::class.java).any { it.nameAsString == "getItems" }) continue
        if (owner.methods.any { it.nameAsString == "equals" } != owner.methods.any { it.nameAsString == "hashCode" }) continue
        val json = owner.methods.singleOrNull { it.nameAsString == "toJson" && it.parameters.isEmpty() } ?: continue
        val jsonVariable = json.findAll(VariableDeclarator::class.java).singleOrNull { it.type.asString() == "JsonObject" && it.initializer.orElse(null) is ObjectCreationExpr } ?: continue
        if (returnValue(json)?.toString() != jsonVariable.nameAsString) continue
        val jsonUses = json.findAll(NameExpr::class.java).filter { it.nameAsString == jsonVariable.nameAsString }
        if (jsonUses.any { use ->
                val parent = use.parentNode.orElse(null)
                parent !is ReturnStmt && (parent !is MethodCallExpr || parent.scope.orElse(null) !== use || parent.nameAsString !in setOf("add", "addProperty"))
            }) continue
        val jsonCalls = json.findAll(MethodCallExpr::class.java).filter { it.scope.map { s -> s.toString() }.orElse("") == jsonVariable.nameAsString }
        if (jsonCalls.any { it.arguments.size != 2 || it.arguments[0] !is StringLiteralExpr }) continue
        val keys = jsonCalls.map { (it.arguments[0] as StringLiteralExpr).asString() }.filter { it != "type" }.distinct()
        val serializerGetter = owner.methods.singleOrNull { it.nameAsString == "getSerializer" && it.parameters.isEmpty() } ?: continue
        val serializerRef = returnValue(serializerGetter) as? FieldAccessExpr ?: continue
        val serializerName = (serializerRef.scope as? NameExpr)?.nameAsString ?: continue
        val serializer = owner.members.filterIsInstance<ClassOrInterfaceDeclaration>().singleOrNull { it.nameAsString == serializerName && it.isStatic } ?: continue
        if (serializer.implementedTypes.none { it.nameAsString == "IIngredientSerializer" }) continue
        val parseJson = serializer.methods.singleOrNull { it.nameAsString == "parse" && it.parameters.size == 1 && it.parameters[0].type.asString() == "JsonObject" } ?: continue
        val parseBuffer = serializer.methods.singleOrNull { it.nameAsString == "parse" && it.parameters.size == 1 && it.parameters[0].type.asString().substringAfterLast('.') in setOf("FriendlyByteBuf", "RegistryFriendlyByteBuf") } ?: continue
        val writeBuffer = serializer.methods.singleOrNull { it.nameAsString == "write" && it.parameters.size == 2 && it.parameters[1].type.asString() == simple } ?: continue
        if (owner.methods.any { it.nameAsString == "getType" }) continue
        val stacking = owner.methods.firstOrNull { it.nameAsString == "getStackingIds" }
        if (stacking != null && unit.findAll(MethodCallExpr::class.java).any { it.nameAsString == "getStackingIds" }) continue
        if (unit.findAll(MethodCallExpr::class.java).any { call -> call.nameAsString in setOf("checkInvalidation", "markValid") && call.findAncestor(MethodDeclaration::class.java).orElse(null) !== stacking }) continue
        if (stacking != null) {
            val ids = returnValue(stacking)?.toString()?.removePrefix("this.") ?: continue
            val stacks = returnValue(items)?.toString()?.removePrefix("this.") ?: continue
            val helper = ((items.body.orElse(null)?.statements?.firstOrNull() as? ExpressionStmt)?.expression as? MethodCallExpr)?.takeIf { it.arguments.isEmpty() } ?: continue
            val dissolve = owner.methods.singleOrNull { it.nameAsString == helper.nameAsString && it.parameters.isEmpty() } ?: continue
            val lazy = dissolve.body.orElse(null)?.statements?.singleOrNull() as? IfStmt ?: continue
            if (lazy.elseStmt.isPresent || lazy.condition.toString().replace("this.", "") != "$stacks == null") continue
            if (owner.methods.any { it.nameAsString in setOf("checkInvalidation", "markValid") }) continue
            val iterator = stacking.findAll(ForEachStmt::class.java).singleOrNull() ?: continue
            val item = iterator.variable.variables.singleOrNull()?.nameAsString ?: continue
            val expected = parser.parseBlock("""
                {
                    if ($ids == null || checkInvalidation()) {
                        markValid(); $helper;
                        $ids = new IntArrayList($stacks.length);
                        for (ItemStack $item : $stacks) { $ids.add(StackedContents.getStackingIndex($item)); }
                        $ids.sort(IntComparators.NATURAL_COMPARATOR);
                    }
                    return $ids;
                }
            """.trimIndent()).result.orElse(null) ?: continue
            val actual = stacking.body.get().clone()
            actual.findAll(ForEachStmt::class.java).filter { it.body !is BlockStmt }.forEach { it.setBody(BlockStmt().addStatement(it.body.clone())) }
            fun normalized(node: Node) = maskJavaCommentsAndLiterals(node.toString()).replace("this.", "").replace(Regex("""\s+"""), "")
            if (normalized(actual) != normalized(expected)) continue
        }
        val registrations = mutableListOf<Registration>()
        for ((otherFile, text) in original) {
            if (!text.contains("CraftingHelper.register") || !text.contains(simple)) continue
            val other = parse(text) ?: continue
            val typing = SourceValueTypes(this, text)
            for (call in other.findAll(MethodCallExpr::class.java)) {
                if (call.nameAsString != "register" || call.arguments.size != 2 || !typing.isTypeName(call.scope.map { it.toString() }.orElse(""), "net.neoforged.neoforge.common.crafting.CraftingHelper")) continue
                val reference = call.arguments[1].toString()
                if (!reference.endsWith(".$serializerName.${serializerRef.nameAsString}")) continue
                val prefix = reference.removeSuffix(".$serializerName.${serializerRef.nameAsString}")
                if (qualified(prefix, other) != canonical) continue
                val method = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: continue
                val eventParameter = method.parameters.singleOrNull { qualified(it.type.asString(), other) == "net.neoforged.neoforge.registries.RegisterEvent" } ?: continue
                val event = eventParameter.nameAsString
                val guard = call.findAncestor(IfStmt::class.java).orElse(null) ?: continue
                val only = (guard.thenStmt as? BlockStmt)?.statements?.singleOrNull() ?: guard.thenStmt
                if (only !== call.parentNode.orElse(null) || guard.elseStmt.isPresent || guard.condition.toString() != "$event.getRegistryKey().equals(Registries.RECIPE_SERIALIZER)" ||
                    !typing.isTypeName("Registries", "net.minecraft.core.registries.Registries")) continue
                registrations += Registration(otherFile, reference, event)
            }
        }
        if (registrations.isEmpty()) continue
        LexicalPreservingPrinter.setup(unit)
        owner.extendedTypes.clear()
        owner.addImplementedType("net.neoforged.neoforge.common.crafting.ICustomIngredient")
        items.setType("java.util.stream.Stream<net.minecraft.world.item.ItemStack>")
        for (exit in items.body.get().findAll(ReturnStmt::class.java)) {
            val value = exit.expression.orElse(null) ?: continue
            exit.setExpression(parser.parseExpression<Expression>("java.util.Arrays.stream($value)").result.orElseThrow())
        }
        stacking?.remove()
        serializer.implementedTypes.removeIf { it.nameAsString == "IIngredientSerializer" }
        for (method in listOf(json, parseJson, parseBuffer, writeBuffer)) method.annotations.removeIf { it.name.identifier == "Override" }
        var stem = "modporterIngredient"
        while (source.contains(stem)) stem += "_"
        serializerGetter.setName("getType").setType("net.neoforged.neoforge.common.crafting.IngredientType<?>")
        serializerGetter.setBody(parser.parseBlock("{ return ${stem}Type; }").result.orElseThrow())
        val sourceTyping = SourceValueTypes(this, source)
        for (call in json.findAll(MethodCallExpr::class.java).toList()) {
            if (call.nameAsString == "getID" && call.arguments.size == 1 && call.arguments[0].toString() == serializerRef.toString() &&
                sourceTyping.isTypeName(call.scope.map { it.toString() }.orElse(""), "net.neoforged.neoforge.common.crafting.CraftingHelper"))
                call.replace(parser.parseExpression<Expression>("net.neoforged.neoforge.registries.NeoForgeRegistries.INGREDIENT_TYPES.getKey(${stem}Type)").result.orElseThrow())
        }
        for (method in owner.methods.filter { it.isStatic && it.type.asString() == simple }) {
            val value = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)?.expression?.orElse(null) as? ObjectCreationExpr ?: continue
            if (value.type.nameAsString != simple) continue
            method.setType("net.minecraft.world.item.crafting.Ingredient")
            value.replace(MethodCallExpr(EnclosedExpr(value.clone()), "toVanilla"))
        }
        // A tag lookup yields holders on 1.21; keep the original raw loop binding in its body.
        for (loop in owner.findAll(ForEachStmt::class.java)) {
            val call = loop.iterable as? MethodCallExpr ?: continue
            val field = call.scope.orElse(null) as? FieldAccessExpr ?: continue
            if (call.nameAsString !in setOf("getOrCreateTag", "getTagOrEmpty") || call.arguments.size != 1 || call.arguments[0].toString().removePrefix("this.") != tag.nameAsString || qualified(field.scope.toString(), unit) != "net.minecraft.core.registries.BuiltInRegistries") continue
            val variable = loop.variable.variables.singleOrNull() ?: continue
            val element = qualified(variable.type.asString(), unit)
            if (!TargetMembers.fieldSignatures("net.minecraft.core.registries.BuiltInRegistries")[field.nameAsString].orEmpty().contains("L${element.replace('.', '/')};")) continue
            var holder = "${stem}Holder"
            while (source.contains(holder) || unit.findAll(VariableDeclarator::class.java).any { it.nameAsString == holder }) holder += "_"
            // A fresh parse prevents lexical-preservation metadata from linking the two declarations.
            val binding = parser.parseVariableDeclarationExpr(loop.variable.toString()).result.orElseThrow()
            binding.variables[0].setInitializer("$holder.value()")
            variable.setName(holder).setType("net.minecraft.core.Holder<$element>")
            val body = loop.body as? BlockStmt ?: BlockStmt().addStatement(loop.body.clone()).also { loop.setBody(it) }
            body.addStatement(0, ExpressionStmt(binding))
        }
        val template = javaClass.getResource("/compat/LegacyTagIngredientCodecs.java.template")!!.readText()
            .replace("@OWNER@", simple).replace("@STEM@", stem).replace("@SERIALIZER@", serializerRef.toString())
            .replace("@KEYS@", keys.joinToString(", ") { StringLiteralExpr(it).toString() })
        parser.parse("class Members {$template}").result.orElseThrow().getClassByName("Members").get().members.forEach { owner.addMember(it.clone()) }
        if (owner.methods.none { it.nameAsString == "equals" } && owner.methods.none { it.nameAsString == "hashCode" }) {
            val equality = """
                @Override public boolean equals(Object other) {
                    if (this == other) return true;
                    if (!(other instanceof $simple)) return false;
                    return java.util.Objects.equals(this.${tag.nameAsString}, (($simple) other).${tag.nameAsString});
                }
                @Override public int hashCode() { return java.util.Objects.hashCode(this.${tag.nameAsString}); }
            """.trimIndent()
            parser.parse("class Equality {$equality}").result.orElseThrow().getClassByName("Equality").get().members.forEach { owner.addMember(it.clone()) }
        }
        var converted = LexicalPreservingPrinter.print(unit)
        converted = replaceExecutableJavaRegex(converted, Regex("""\bimplements(?=net\.neoforged\.neoforge\.common\.crafting\.ICustomIngredient\b)""")) { "implements " }
        converted = removeUnusedSimpleImports(converted, unit.imports.map { it.nameAsString }.filter { it.substringAfterLast('.') in setOf("AbstractIngredient", "IIngredientSerializer", "CraftingHelper") })
        check(parser.parse(converted).isSuccessful) { "Tag ingredient migration emitted invalid Java: $file" }
        sources[file] = converted
        for (registration in registrations) {
            val registered = parse(sources.getValue(registration.file)) ?: continue
            LexicalPreservingPrinter.setup(registered)
            for (call in registered.findAll(MethodCallExpr::class.java).toList()) {
                if (call.nameAsString != "register" || call.arguments.size != 2 || call.arguments[1].toString() != registration.reference) continue
                val guard = call.findAncestor(IfStmt::class.java).orElse(null) ?: continue
                guard.setCondition(parser.parseExpression<Expression>("${registration.event}.getRegistryKey().equals(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.INGREDIENT_TYPES)").result.orElseThrow())
                call.replace(parser.parseExpression<Expression>("${registration.event}.register(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.INGREDIENT_TYPES, ${call.arguments[0]}, () -> $canonical.${stem}Type)").result.orElseThrow())
            }
            sources[registration.file] = LexicalPreservingPrinter.print(registered)
        }
    }
    return sources.mapNotNull { (file, source) ->
        if (source == original[file]) null else {
            if (!dryRun) file.writeText(source)
            Change(file, 1, "Tag ingredients retain source matching and serializers through ICustomIngredient", "AbstractIngredient / IIngredientSerializer", "IngredientType codecs and vanilla wrappers", Confidence.HIGH, "struct-legacy-tag-ingredient")
        }
    }
}
