package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NodeList
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

/** Serializable entity providers that own their one LazyOptional keep that identity as an attachment. */
internal fun StructuralRefactorPass.migrateSelfCapabilityAttachments(project: Path, dryRun: Boolean): List<Change> {
    val root = project.resolve("src/main/java")
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    val files = walkJavaSourceRoots(root).use { paths -> paths.filter { it.toString().endsWith(".java") }
        .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }.toList() }
    val sources = files.associateWith { it.readText() }.toMutableMap()
    val originalSources = sources.toMap()
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    fun parse(source: String) = parser.parse(source).result.orElse(null)
    fun packageOf(unit: CompilationUnit) = unit.packageDeclaration.map { it.nameAsString }.orElse("")
    fun returnCall(method: MethodDeclaration): MethodCallExpr? = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)?.expression?.orElse(null) as? MethodCallExpr
    fun expressionStatement(call: MethodCallExpr): ExpressionStmt? = call.parentNode.orElse(null) as? ExpressionStmt
    fun ownerOf(scope: String, unit: CompilationUnit): String? {
        if (scope.contains('.') && scope.firstOrNull()?.isLowerCase() == true) return scope
        unit.imports.firstOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == scope }?.let { return it.nameAsString }
        return if (scope.matches(Regex("""[A-Za-z_$][\w$]*"""))) "${packageOf(unit)}.$scope" else null
    }
    fun resourceFactory(expression: Expression, unit: CompilationUnit, parameters: Set<String> = emptySet()): Boolean {
        val arguments = when (expression) {
            is ObjectCreationExpr -> if (expression.type.nameAsString == "ResourceLocation") expression.arguments else return false
            is MethodCallExpr -> if (expression.scope.map { it.toString() }.orElse("") in setOf("ResourceLocation", "net.minecraft.resources.ResourceLocation") &&
                expression.nameAsString in setOf("fromNamespaceAndPath", "parse", "withDefaultNamespace")) expression.arguments else return false
            else -> return false
        }
        return arguments.isNotEmpty() && arguments.all { argument ->
            argument is StringLiteralExpr || argument is NameExpr && (argument.nameAsString in parameters ||
                unit.findAll(FieldDeclaration::class.java).any { field -> field.isStatic && field.isFinal && field.variables.any {
                    it.nameAsString == argument.nameAsString && it.type.asString() == "String" && it.initializer.orElse(null) is StringLiteralExpr
                } })
        }
    }
    fun pureId(expression: Expression, unit: CompilationUnit): Boolean {
        if (resourceFactory(expression, unit)) return true
        val call = expression as? MethodCallExpr ?: return false
        if (call.arguments.any { it !is StringLiteralExpr }) return false
        val helperOwner = call.scope.map { ownerOf(it.toString(), unit) }.orElse(null) ?: return false
        val helperSource = sources.entries.singleOrNull { (_, text) ->
            val simple = helperOwner.substringAfterLast('.')
            Regex("""\bclass\s+${Regex.escape(simple)}\b""").containsMatchIn(maskJavaCommentsAndLiterals(text)) && Regex("""(?m)^\s*package\s+${Regex.escape(helperOwner.substringBeforeLast('.'))}\s*;""").containsMatchIn(text)
        }?.value ?: return false
        val helperUnit = parse(helperSource) ?: return false
        val method = helperUnit.findAll(MethodDeclaration::class.java).singleOrNull { it.nameAsString == call.nameAsString && it.isStatic &&
            it.parameters.size == call.arguments.size && it.parameters.all { p -> p.type.asString() == "String" } } ?: return false
        val value = (method.body.orElse(null)?.statements?.singleOrNull() as? ReturnStmt)?.expression?.orElse(null) ?: return false
        return resourceFactory(value, helperUnit, method.parameters.map { it.nameAsString }.toSet())
    }
    data class Spec(val file: Path, val owner: String, val qualified: String, val key: String, val attach: String, val register: String, val accessor: String)
    val specs = mutableListOf<Spec>()
    for ((file, source) in originalSources) {
        if (!source.contains("ICapabilitySerializable") || !source.contains("CapabilityToken") || !source.contains("AttachCapabilitiesEvent")) continue
        val unit = parse(source) ?: continue
        val owner = unit.findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull { type -> type.implementedTypes.any { it.nameAsString == "ICapabilitySerializable" } } ?: continue
        val capabilities = owner.fields.filter { it.isStatic }.flatMap { it.variables }.filter { it.type.isClassOrInterfaceType && it.type.asClassOrInterfaceType().nameAsString == "Capability" }
        val capability = capabilities.singleOrNull() ?: continue
        val key = capability.nameAsString
        if (!Regex("""CapabilityManager\.get\s*\(\s*new\s+CapabilityToken<>\s*\(\s*\)\s*\{\s*}\s*\)""").matches(capability.initializer.map { it.toString() }.orElse(""))) continue
        val valueType = capability.type.asClassOrInterfaceType().typeArguments.orElse(null)?.singleOrNull()?.asString() ?: continue
        if (originalSources.values.any { text -> Regex("""(?<![\w$])(?:${Regex.escape(owner.nameAsString)}\.)?${Regex.escape(key)}\s*=(?!=)""").findAll(maskJavaCommentsAndLiterals(text)).count() > if (text === source) 1 else 0 }) continue
        val getter = owner.methods.singleOrNull { it.nameAsString == "getCapability" && it.parameters.size == 2 } ?: continue
        val body = getter.body.orElse(null)?.statements ?: continue
        if (body.size != 2 || body[0] !is IfStmt || body[1] !is ReturnStmt) continue
        val branch = body[0] as IfStmt
        val condition = branch.condition as? BinaryExpr ?: continue
        val parameter = getter.parameters[0].nameAsString
        if (condition.operator != BinaryExpr.Operator.EQUALS || setOf(condition.left.toString(), condition.right.toString()) != setOf(parameter, key)) continue
        val accepted = (branch.thenStmt as? ReturnStmt ?: (branch.thenStmt as? BlockStmt)?.statements?.singleOrNull() as? ReturnStmt)?.expression?.orElse(null) as? MethodCallExpr ?: continue
        if (accepted.nameAsString != "cast" || accepted.arguments.isNotEmpty()) continue
        val cache = accepted.scope.map { it.toString().removePrefix("this.") }.orElse("")
        val cacheField = owner.fields.flatMap { it.variables }.singleOrNull { it.nameAsString == cache && it.type.asString().replace(" ", "") == "LazyOptional<$valueType>" } ?: continue
        val empty = (body[1] as ReturnStmt).expression.orElse(null)?.toString()
        if (empty != "LazyOptional.empty()") continue
        val save = owner.methods.singleOrNull { it.nameAsString == "serializeNBT" && it.parameters.isEmpty() } ?: continue
        val saveCall = returnCall(save) ?: continue
        val backend = saveCall.scope.map { it.toString().removePrefix("this.") }.orElse("")
        if (saveCall.nameAsString != "serializeNBT" || saveCall.arguments.isNotEmpty()) continue
        if (owner.fields.flatMap { it.variables }.none { it.nameAsString == backend && it.type.asString() == valueType }) continue
        val backendOwner = ownerOf(valueType, unit) ?: continue
        val backendSource = sources.values.singleOrNull { text ->
            Regex("""\bclass\s+${Regex.escape(backendOwner.substringAfterLast('.'))}\b""").containsMatchIn(maskJavaCommentsAndLiterals(text)) &&
                Regex("""(?m)^\s*package\s+${Regex.escape(backendOwner.substringBeforeLast('.'))}\s*;""").containsMatchIn(text)
        } ?: continue
        val backendClass = parse(backendSource)?.getClassByName(backendOwner.substringAfterLast('.'))?.orElse(null) ?: continue
        if (backendClass.implementedTypes.none { it.nameAsString == "INBTSerializable" }) continue
        val load = owner.methods.singleOrNull { it.nameAsString == "deserializeNBT" && it.parameters.size == 1 } ?: continue
        val loadCall = (load.body.orElse(null)?.statements?.singleOrNull() as? ExpressionStmt)?.expression as? MethodCallExpr ?: continue
        if (loadCall.nameAsString != "deserializeNBT" || loadCall.scope.map { it.toString().removePrefix("this.") }.orElse("") != backend || loadCall.arguments.size != 1) continue
        val constructor = owner.constructors.singleOrNull { it.parameters.size == 1 } ?: continue
        val kind = constructor.parameters[0].type.asString()
        val canonicalKind = ownerOf(kind, unit)
        if (canonicalKind == null || !TargetMembers.inheritsFrom(canonicalKind, "net.minecraft.world.entity.Entity")) continue
        val attach = owner.methods.singleOrNull { it.parameters.size == 1 && it.parameters[0].type.asString().replace(" ", "").endsWith("AttachCapabilitiesEvent<Entity>") } ?: continue
        val event = attach.parameters[0].nameAsString
        val add = attach.findAll(MethodCallExpr::class.java).singleOrNull { it.nameAsString == "addCapability" && it.scope.map { s -> s.toString() }.orElse("") == event && it.arguments.size == 2 } ?: continue
        val providerVariable = (add.arguments[1] as? NameExpr)?.nameAsString ?: continue
        val provider = attach.findAll(VariableDeclarator::class.java).singleOrNull { it.nameAsString == providerVariable } ?: continue
        val creation = provider.initializer.orElse(null) as? ObjectCreationExpr ?: continue
        if (creation.type.nameAsString != owner.nameAsString || creation.arguments.size != 1) continue
        val entityExpression = creation.arguments[0].clone()
        val idExpression = when (val id = add.arguments[0]) {
            is NameExpr -> attach.findAll(VariableDeclarator::class.java).singleOrNull { it.nameAsString == id.nameAsString }?.initializer?.orElse(null)
            else -> id
        } ?: continue
        if (!pureId(idExpression, unit) || expressionStatement(add) == null) continue
        val listenerCall = attach.findAll(MethodCallExpr::class.java).singleOrNull { it.nameAsString == "addListener" && it.scope.map { s -> s.toString() }.orElse("") == event && it.arguments.size == 1 } ?: continue
        val listener = listenerCall.arguments[0] as? LambdaExpr ?: continue
        if (listener.parameters.isNotEmpty() || expressionStatement(listenerCall) == null || listener.body.findAll(NameExpr::class.java).any { it.nameAsString != providerVariable }) continue
        val modId = detectModId(project) ?: projectMetadataModId(project) ?: continue
        var stem = "modporterEntityAttachment"
        while (source.contains(stem)) stem += "_"
        val register = "${stem}Register"
        val accessor = "${stem}Get"
        val qualified = "${packageOf(unit)}.${owner.nameAsString}"
        LexicalPreservingPrinter.setup(unit)
        val capField = capability.parentNode.orElse(null) as FieldDeclaration
        val insertion = owner.members.indexOf(capField)
        val fields = """
            private static final net.minecraft.resources.ResourceLocation ${stem}Id = $idExpression;
            private static final net.neoforged.neoforge.registries.DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> ${stem}Types =
                net.neoforged.neoforge.registries.DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, ${stem}Id.getNamespace());
        """.trimIndent()
        val fieldUnit = parser.parse("class Fields {$fields}").result.orElseThrow()
        fieldUnit.getClassByName("Fields").get().members.asReversed().forEach { owner.members.add(insertion, it.clone()) }
        capability.setType("net.neoforged.neoforge.registries.DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<?>, net.neoforged.neoforge.attachment.AttachmentType<${owner.nameAsString}>>")
        capability.setInitializer("${stem}Types.register(${stem}Id.getPath(), () -> net.neoforged.neoforge.attachment.AttachmentType.<net.minecraft.nbt.CompoundTag, ${owner.nameAsString}>serializable(holder -> new ${owner.nameAsString}(($kind) holder)).build())")
        capField.setFinal(true)
        getter.remove()
        owner.implementedTypes.removeIf { it.nameAsString == "ICapabilitySerializable" }
        owner.addImplementedType("net.neoforged.neoforge.common.util.INBTSerializable<net.minecraft.nbt.CompoundTag>")
        val registries = "${stem}Registries"
        save.addParameter("net.minecraft.core.HolderLookup.Provider", registries)
        saveCall.addArgument(registries)
        load.parameters.addFirst(parser.parseParameter("net.minecraft.core.HolderLookup.Provider $registries").result.orElseThrow())
        loadCall.arguments.addFirst(NameExpr(registries))
        provider.setInitializer("($entityExpression).getData($key)")
        attach.parameters[0].setType("net.neoforged.neoforge.event.entity.EntityJoinLevelEvent")
        if (attach.annotations.none { it.name.identifier == "SubscribeEvent" }) attach.addAnnotation("net.neoforged.bus.api.SubscribeEvent")
        attach.findAll(MethodCallExpr::class.java).filter { it.nameAsString == "getObject" && it.scope.map { s -> s.toString() }.orElse("") == event }.forEach { it.setName("getEntity") }
        expressionStatement(add)!!.remove()
        val invalidation = listener.body.clone().toString()
        expressionStatement(listenerCall)!!.remove()
        if (owner.annotations.none { it.name.identifier == "EventBusSubscriber" }) owner.addAnnotation(parser.parseAnnotation("@net.neoforged.fml.common.EventBusSubscriber(modid = ${StringLiteralExpr(modId)})").result.orElseThrow())
        val members = """
            public static void $register(net.neoforged.bus.api.IEventBus bus) { ${stem}Types.register(bus); }
            public static ${cacheField.type} $accessor(net.minecraft.world.entity.Entity entity, net.minecraft.core.Direction side) {
                java.util.Objects.requireNonNull(entity);
                if (!(entity instanceof $kind)) return LazyOptional.empty();
                return entity.getData($key).$cache;
            }
            @net.neoforged.bus.api.SubscribeEvent
            public static void ${stem}Leave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
                if (event.getEntity() instanceof $kind && event.getEntity().hasData($key)) {
                    ${owner.nameAsString} $providerVariable = event.getEntity().getData($key);
                    $invalidation
                }
            }
        """.trimIndent()
        parser.parse("class Members {$members}").result.orElseThrow().getClassByName("Members").get().members.forEach { owner.addMember(it.clone()) }
        var result = LexicalPreservingPrinter.print(unit)
        result = removeUnusedSimpleImports(result, unit.imports.map { it.nameAsString }.filter { it.substringAfterLast('.') in setOf("Capability", "CapabilityManager", "CapabilityToken", "ICapabilitySerializable", "AttachCapabilitiesEvent") })
        sources[file] = result
        specs += Spec(file, owner.nameAsString, qualified, key, attach.nameAsString, register, accessor)
    }
    if (specs.isEmpty()) return emptyList()
    for ((file, source) in sources.toMap()) {
        if (!specs.any {
                source.contains(it.key) ||
                    source.contains("${it.owner}::${it.attach}") ||
                    source.contains("${it.owner}.class") ||
                    source.contains(it.qualified)
            }
        ) continue
        val unit = parse(source) ?: continue
        LexicalPreservingPrinter.setup(unit)
        var changed = false
        for (spec in specs) {
            fun ownReference(text: String) = text == "${spec.qualified}.${spec.key}" || text == "${spec.owner}.${spec.key}" && ownerOf(spec.owner, unit) == spec.qualified ||
                text == spec.key && (file == spec.file || unit.imports.any { it.isStatic && it.nameAsString == "${spec.qualified}.${spec.key}" })
            for (call in unit.findAll(MethodCallExpr::class.java).toList()) {
                if (call.nameAsString == "getCapability" && call.scope.isPresent && call.arguments.size in 1..2 && ownReference(call.arguments[0].toString())) {
                    val side = call.arguments.getOrNull(1)?.toString() ?: "null"
                    call.replace(parser.parseExpression<Expression>("${spec.qualified}.${spec.accessor}(${call.scope.get()}, $side)").result.orElseThrow())
                    changed = true
                }
                if (call.nameAsString == "addGenericListener" && call.arguments.size == 2) {
                    val ref = call.arguments[1] as? MethodReferenceExpr ?: continue
                    if (ref.identifier == spec.attach && ownerOf(ref.scope.toString(), unit) == spec.qualified && expressionStatement(call) != null) {
                        expressionStatement(call)!!.remove()
                        changed = true
                    }
                }
            }
            for (method in unit.findAll(MethodDeclaration::class.java).toList()) {
                val parameter = method.parameters.singleOrNull() ?: continue
                if (ownerOf(parameter.type.asString(), unit) !=
                    "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent"
                ) continue
                val eventName = parameter.nameAsString
                val obsoleteRegistrations = method.findAll(MethodCallExpr::class.java)
                    .filter { call ->
                        call.findAncestor(MethodDeclaration::class.java).orElse(null) === method &&
                            call.nameAsString == "register" &&
                            call.scope.map { it.toString() }.orElse("") == eventName &&
                            call.arguments.singleOrNull() is ClassExpr &&
                            ownerOf(
                                call.arguments.single().asClassExpr().type.asString(),
                                unit
                            ) == spec.qualified &&
                            expressionStatement(call) != null
                    }
                    .toList()
                if (obsoleteRegistrations.isEmpty()) continue
                obsoleteRegistrations.forEach { expressionStatement(it)!!.remove() }
                changed = true
                if (method.body.orElse(null)?.statements?.isEmpty() == true) {
                    method.remove()
                }
            }
        }
        if (changed) {
            var migrated = LexicalPreservingPrinter.print(unit)
            migrated = removeUnusedSimpleImports(
                migrated,
                listOf(
                    "net.neoforged.bus.api.SubscribeEvent",
                    "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent"
                )
            )
            sources[file] = migrated
        }
    }
    val main = detectModMainClass(project)
    if (main != null) {
        var text = sources[main] ?: main.readText()
        val bus = requireModEventBusName(text, "serializable entity provider attachment")
        for (spec in specs) {
            if (packageNameOf(text) != spec.qualified.substringBeforeLast('.')) text = addImportIfMissing(text, spec.qualified)
            text = insertModBusListener(text, bus, "        ${spec.owner}.${spec.register}($bus);", "${spec.owner}.${spec.register}")
        }
        sources[main] = text
    }
    return sources.mapNotNull { (file, source) ->
        if (source == originalSources[file]) null else {
            if (!dryRun) file.writeText(source)
            Change(file, 1, "Retain serializable entity provider identity, invalidation and join initialization using attachments",
                "ICapabilitySerializable + AttachCapabilitiesEvent", "entity attachment and join/leave hooks", Confidence.HIGH, "struct-self-capability-attachment")
        }
    }
}
