package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.TargetMembers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 1.20.1 enchantments were code-registered `Enchantment` subclasses; 1.21.1 enchantments are
 * data-driven registry entries. The text pass already derives each definition from the mod's own
 * class (`data/<ns>/enchantment/<path>.json`, its private item-support tags, and the vanilla
 * enchantment tags its `isTreasureOnly`/`isCurse`/`isDiscoverable`/`isTradeable` flags select) and
 * leaves `ResourceKey<Enchantment>` constants in the declaring class.
 *
 * A project whose own datagen writes its datapack registry entries (a
 * `DatapackBuiltinEntriesProvider` built from a `RegistrySetBuilder`) regenerates its generated
 * resources from that provider, so the derived data must come out of the same datagen: the key
 * class gains `bootstrap(BootstrapContext<Enchantment>)` registering exactly the derived
 * definitions (private item tags expanded to their items, which datagen does not regenerate), the
 * builder registers `Registries.ENCHANTMENT` with it, and an `EnchantmentTagsProvider` on the
 * patched lookup re-emits the derived vanilla tag memberships. Definitions with data-driven
 * effects, or a datagen graph that is not one exact provider/builder pair, are left alone.
 */
internal object LegacyEnchantmentDatagenBootstrap {
    const val RULE_ID = "struct-legacy-enchantment-datagen-bootstrap"

    private const val RESOURCE_KEY = "net.minecraft.resources.ResourceKey"
    private const val RESOURCE_LOCATION = "net.minecraft.resources.ResourceLocation"
    private const val REGISTRIES = "net.minecraft.core.registries.Registries"
    private const val ENCHANTMENT = "net.minecraft.world.item.enchantment.Enchantment"
    private const val REGISTRY_SET_BUILDER = "net.minecraft.core.RegistrySetBuilder"
    private const val ENTRIES_PROVIDER = "net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider"
    private const val GATHER_DATA_EVENT = "net.neoforged.neoforge.data.event.GatherDataEvent"
    private const val EXISTING_FILE_HELPER = "net.neoforged.neoforge.common.data.ExistingFileHelper"
    private const val ENCHANTMENT_TAGS = "net.minecraft.tags.EnchantmentTags"
    private const val TAG_KEY_DESCRIPTOR = "Lnet/minecraft/tags/TagKey;"
    private const val TAGS_PROVIDER = "ModPorterEnchantmentTagsProvider"
    private val slotGroups = setOf(
        "any", "mainhand", "offhand", "hand", "feet", "legs", "chest", "head", "armor", "body"
    )
    private val identifier = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")

    private class Parsed(val file: Path, val source: String, val unit: CompilationUnit, val typing: SourceValueTypes) {
        private val lineStarts = buildList {
            add(0)
            source.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val packageName: String = unit.packageDeclaration.map { it.nameAsString }.orElse("")

        fun offset(node: Node): Int = node.range.map { lineStarts[it.begin.line - 1] + it.begin.column - 1 }.orElse(-1)
        fun endOffset(node: Node): Int = node.range.map { lineStarts[it.end.line - 1] + it.end.column }.orElse(-1)
        fun text(node: Node): String = source.substring(offset(node), endOffset(node))
        fun indentAt(offset: Int): String {
            val lineStart = source.lastIndexOf('\n', offset - 1) + 1
            return source.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        }
        fun imports(fqn: String): Boolean = unit.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == fqn }
        fun sees(fqn: String): Boolean = imports(fqn) || fqn.substringBeforeLast('.') == packageName
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    private class Key(val field: String, val id: String, val definition: JsonObject)

    private class Builder(val parsed: Parsed, val creation: ObjectCreationExpr, val chain: Expression)

    private class Registration(
        val parsed: Parsed,
        val after: ExpressionStmt,
        val generator: String,
        val condition: String,
        val output: String,
        val lookup: String,
        val existingFiles: String
    )

    fun migrate(pass: StructuralRefactorPass, projectDir: Path, dryRun: Boolean): List<Change> {
        val root = projectDir.resolve("src/main/java")
        val data = projectDir.resolve("src/generated/resources/data")
        if (!Files.isDirectory(root) || !Files.isDirectory(data)) return emptyList()
        val files = pass.walkJavaSourceRoots(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.sorted().toList()
        }
        val texts = files.associateWith { it.readText() }
        if (texts.values.none { it.contains("ENCHANTMENT") } ||
            texts.values.none { it.contains("DatapackBuiltinEntriesProvider") }
        ) {
            return emptyList()
        }
        val previousFacts = pass.projectValueFacts
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        try {
            return plan(pass, projectDir, data, texts, dryRun)
        } finally {
            pass.projectValueFacts = previousFacts
        }
    }

    private fun plan(
        pass: StructuralRefactorPass,
        projectDir: Path,
        data: Path,
        texts: Map<Path, String>,
        dryRun: Boolean
    ): List<Change> {
        if (!targetContractsVerified()) return emptyList()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val sources = texts.mapNotNull { (file, text) ->
            val result = parser.parse(text)
            val unit = result.result.orElse(null)?.takeIf { result.isSuccessful } ?: return@mapNotNull null
            Parsed(file, text, unit, SourceValueTypes(pass, text))
        }
        val constants = stringConstants(sources)

        // the converted keys, with the definitions derived from their 1.20.1 classes
        val keysByOwner = linkedMapOf<ClassOrInterfaceDeclaration, MutableList<Key>>()
        val ownerSources = mutableMapOf<ClassOrInterfaceDeclaration, Parsed>()
        for (parsed in sources) {
            if (!parsed.source.contains("ENCHANTMENT")) continue
            for (field in parsed.unit.findAll(FieldDeclaration::class.java)) {
                if (!field.isStatic) continue
                val owner = field.parentNode.orElse(null) as? ClassOrInterfaceDeclaration ?: continue
                for (variable in field.variables) {
                    val id = enchantmentKeyId(parsed, variable, owner, constants) ?: continue
                    val (namespace, path) = id.split(':', limit = 2)
                    val file = data.resolve("$namespace/enchantment/$path.json")
                    if (!Files.isRegularFile(file)) continue
                    val definition = runCatching { Json.parseToJsonElement(file.readText()) as JsonObject }
                        .getOrNull() ?: return emptyList()
                    keysByOwner.getOrPut(owner) { mutableListOf() } += Key(variable.nameAsString, id, definition)
                    ownerSources[owner] = parsed
                }
            }
        }
        if (keysByOwner.size != 1) return emptyList()
        val (holder, keys) = keysByOwner.entries.single()
        val holderSource = ownerSources.getValue(holder)
        val holderName = holder.fullyQualifiedName.orElse(null) ?: return emptyList()
        val namespaces = keys.map { it.id.substringBefore(':') }.distinct()
        if (namespaces.size != 1) return emptyList()
        if (holder.methods.any { it.nameAsString == "bootstrap" }) return emptyList()

        // the one datapack-entries provider and the builder it hands to its super constructor
        val providers = sources.flatMap { parsed ->
            parsed.unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { type -> type.extendedTypes.any { parsed.typing.isTypeName(it.nameWithScope, ENTRIES_PROVIDER) } }
                .map { parsed to it }
        }
        val (providerSource, provider) = providers.singleOrNull() ?: return emptyList()
        val builder = providerBuilder(providerSource, provider) ?: return emptyList()
        if (builder.chain.findAll(MethodCallExpr::class.java).any { call ->
                call.nameAsString == "add" &&
                    (call.arguments.firstOrNull() as? FieldAccessExpr)?.let {
                        it.nameAsString == "ENCHANTMENT" && builder.parsed.typing.isTypeName(it.scope.toString(), REGISTRIES)
                    } == true
            }
        ) {
            return emptyList()
        }

        val bootstrap = bootstrapMethod(holderSource, holder, keys, data) ?: return emptyList()
        val tags = enchantmentTags(data, keys) ?: return emptyList()
        val providerName = provider.fullyQualifiedName.orElse(null) ?: return emptyList()
        val tagsProviderName = "${providerSource.packageName.let { if (it.isEmpty()) "" else "$it." }}$TAGS_PROVIDER"
        if (tags.isNotEmpty() && sources.any { parsed ->
                parsed.unit.findAll(TypeDeclaration::class.java).any { it.fullyQualifiedName.orElse(null) == tagsProviderName }
            }
        ) {
            return emptyList()
        }
        val registration = if (tags.isEmpty()) null else (datagenRegistration(sources, providerName) ?: return emptyList())

        val edits = linkedMapOf<Parsed, MutableList<Edit>>()
        edits.getOrPut(holderSource) { mutableListOf() } += bootstrap
        edits.getOrPut(builder.parsed) { mutableListOf() } += builderBinding(builder, holderName)
        if (registration != null) {
            val reference = if (registration.parsed.sees(tagsProviderName)) TAGS_PROVIDER else tagsProviderName
            val statementEnd = registration.parsed.endOffset(registration.after)
            val indent = registration.parsed.indentAt(registration.parsed.offset(registration.after))
            edits.getOrPut(registration.parsed) { mutableListOf() } += Edit(
                statementEnd,
                statementEnd,
                "${registration.parsed.newline}$indent${registration.generator}.addProvider(${registration.condition}, " +
                    "new $reference(${registration.output}, ${registration.lookup}, ${registration.existingFiles}));"
            )
        }

        val outputs = linkedMapOf<Path, Pair<String?, String>>()
        for ((parsed, fileEdits) in edits) {
            val migrated = fileEdits.sortedByDescending { it.start }.fold(parsed.source) { text, edit ->
                text.replaceRange(edit.start, edit.end, edit.text)
            }
            outputs[parsed.file] = parsed.source to migrated
        }
        if (registration != null) {
            val file = providerSource.file.resolveSibling("$TAGS_PROVIDER.java")
            if (Files.exists(file)) return emptyList()
            outputs[file] = null to tagsProviderSource(providerSource, holderName, namespaces.single(), tags, keys)
        }
        outputs.forEach { (file, texts) ->
            val reparsed = parser.parse(texts.second)
            check(reparsed.isSuccessful) {
                "Legacy enchantment datagen bootstrap emitted invalid Java in $file: ${pass.parseProblems(reparsed)}"
            }
        }
        if (!dryRun) outputs.forEach { (file, texts) -> file.writeText(texts.second) }
        return outputs.map { (file, texts) ->
            Change(
                file = file,
                line = 1,
                description = "Generate the data-driven enchantments derived from 1.20.1 classes in the project datagen",
                before = texts.first?.let { "ResourceKey<Enchantment> constants without a datagen bootstrap" }
                    ?: "no enchantment tag provider",
                after = "Registries.ENCHANTMENT bootstrap and vanilla enchantment tags from the derived data",
                confidence = Confidence.HIGH,
                ruleId = RULE_ID
            )
        }
    }

    // ---- keys ---------------------------------------------------------------------------------

    /** `ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath(ns, path))` with constant parts. */
    private fun enchantmentKeyId(
        parsed: Parsed,
        variable: VariableDeclarator,
        owner: ClassOrInterfaceDeclaration,
        constants: Map<String, String>
    ): String? {
        val create = variable.initializer.orElse(null) as? MethodCallExpr ?: return null
        if (create.nameAsString != "create" || create.arguments.size != 2 ||
            create.scope.map { parsed.typing.isTypeName(it.toString(), RESOURCE_KEY) }.orElse(false) != true
        ) {
            return null
        }
        val registry = create.arguments[0] as? FieldAccessExpr ?: return null
        if (registry.nameAsString != "ENCHANTMENT" || !parsed.typing.isTypeName(registry.scope.toString(), REGISTRIES)) return null
        val location = create.arguments[1] as? MethodCallExpr ?: return null
        if (location.nameAsString != "fromNamespaceAndPath" || location.arguments.size != 2 ||
            location.scope.map { parsed.typing.isTypeName(it.toString(), RESOURCE_LOCATION) }.orElse(false) != true
        ) {
            return null
        }
        val namespace = constantString(parsed, location.arguments[0], owner, constants) ?: return null
        val path = constantString(parsed, location.arguments[1], owner, constants) ?: return null
        return "$namespace:$path".takeIf { identifier.matches(it) }
    }

    /** `static final String NAME = "literal"` fields of the project, by `OwnerFqn.NAME`. */
    private fun stringConstants(sources: List<Parsed>): Map<String, String> = buildMap {
        for (parsed in sources) {
            for (field in parsed.unit.findAll(FieldDeclaration::class.java)) {
                if (!field.isStatic || !field.isFinal) continue
                val owner = (field.parentNode.orElse(null) as? TypeDeclaration<*>)?.fullyQualifiedName?.orElse(null) ?: continue
                for (variable in field.variables) {
                    val literal = variable.initializer.orElse(null) as? StringLiteralExpr ?: continue
                    if (variable.typeAsString != "String" && variable.typeAsString != "java.lang.String") continue
                    put("$owner.${variable.nameAsString}", literal.asString())
                }
            }
        }
    }

    private fun constantString(
        parsed: Parsed,
        expression: Expression,
        owner: ClassOrInterfaceDeclaration,
        constants: Map<String, String>
    ): String? = when (expression) {
        is StringLiteralExpr -> expression.asString()
        is NameExpr -> {
            val name = expression.nameAsString
            generateSequence<Node>(owner) { it.parentNode.orElse(null) }
                .filterIsInstance<TypeDeclaration<*>>()
                .firstNotNullOfOrNull { type -> type.fullyQualifiedName.orElse(null)?.let { constants["$it.$name"] } }
                ?: parsed.unit.imports
                    .filter { it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == name }
                    .firstNotNullOfOrNull { constants[it.nameAsString] }
        }
        is FieldAccessExpr -> {
            val scope = expression.scope.toString()
            val candidates = buildList {
                if ('.' in scope) add(scope)
                parsed.unit.imports.filter { !it.isStatic && !it.isAsterisk && it.nameAsString.substringAfterLast('.') == scope }
                    .forEach { add(it.nameAsString) }
                add(if (parsed.packageName.isEmpty()) scope else "${parsed.packageName}.$scope")
            }
            candidates.firstNotNullOfOrNull { constants["$it.${expression.nameAsString}"] }
        }
        else -> null
    }

    // ---- bootstrap ------------------------------------------------------------------------------

    private fun bootstrapMethod(
        parsed: Parsed,
        holder: ClassOrInterfaceDeclaration,
        keys: List<Key>,
        data: Path
    ): Edit? {
        val registrations = keys.map { key -> enchantmentRegistration(key, data) ?: return null }
        val usesEnchantments = registrations.any { it.contains("enchantments.") }
        val body = buildList {
            add("public static void bootstrap(net.minecraft.data.worldgen.BootstrapContext<$ENCHANTMENT> context) {")
            add("    net.minecraft.core.HolderGetter<net.minecraft.world.item.Item> items =")
            add("        context.lookup($REGISTRIES.ITEM);")
            if (usesEnchantments) {
                add("    net.minecraft.core.HolderGetter<$ENCHANTMENT> enchantments =")
                add("        context.lookup($REGISTRIES.ENCHANTMENT);")
            }
            registrations.forEach { addAll(it.lines()) }
            add("}")
        }
        return memberInsertion(parsed, holder, body)
    }

    /** The Java builder call that encodes exactly the derived definition JSON. */
    private fun enchantmentRegistration(key: Key, data: Path): String? {
        val definition = key.definition
        val known = setOf(
            "anvil_cost", "description", "exclusive_set", "max_cost", "max_level", "min_cost",
            "primary_items", "slots", "supported_items", "weight"
        )
        if (definition.keys.any { it !in known }) return null
        val (namespace, path) = key.id.split(':', limit = 2)
        val description = definition["description"] as? JsonObject ?: return null
        if (description.keys != setOf("translate") ||
            (description["translate"] as? JsonPrimitive)?.content != "enchantment.$namespace.$path"
        ) {
            return null
        }
        val supported = itemSet(definition["supported_items"] ?: return null, data) ?: return null
        val primary = definition["primary_items"]?.let { itemSet(it, data) ?: return null }
        val weight = int(definition["weight"]) ?: return null
        val maxLevel = int(definition["max_level"]) ?: return null
        val minCost = cost(definition["min_cost"]) ?: return null
        val maxCost = cost(definition["max_cost"]) ?: return null
        val anvilCost = int(definition["anvil_cost"]) ?: return null
        val slots = (definition["slots"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString && value.content in slotGroups }?.content
                ?: return null
        }?.takeIf { it.isNotEmpty() } ?: return null
        val exclusive = definition["exclusive_set"]?.let { enchantmentSet(it) ?: return null }
        val arguments = listOfNotNull(supported, primary, "$weight", "$maxLevel", minCost, maxCost, "$anvilCost") +
            slots.map { "net.minecraft.world.entity.EquipmentSlotGroup.${it.uppercase()}" }
        return buildString {
            append("    context.register(${key.field}, $ENCHANTMENT.enchantment($ENCHANTMENT.definition(\n")
            append(arguments.joinToString(",\n") { "            $it" })
            append("))\n")
            if (exclusive != null) append("        .exclusiveWith($exclusive)\n")
            append("        .build(${key.field}.location()));")
        }
    }

    private fun int(element: JsonElement?): Int? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun cost(element: JsonElement?): String? {
        val cost = element as? JsonObject ?: return null
        if (cost.keys != setOf("base", "per_level_above_first")) return null
        val base = int(cost["base"]) ?: return null
        val perLevel = int(cost["per_level_above_first"]) ?: return null
        return "$ENCHANTMENT.dynamicCost($base, $perLevel)"
    }

    /** An item holder set; a project tag the text pass generated is expanded, since datagen does not regenerate it. */
    private fun itemSet(element: JsonElement, data: Path): String? {
        val ids = when (element) {
            is JsonPrimitive -> {
                if (!element.isString) return null
                val value = element.content
                if (value.startsWith("#")) {
                    val tag = value.removePrefix("#").takeIf { identifier.matches(it) } ?: return null
                    val (namespace, path) = tag.split(':', limit = 2)
                    val generated = data.resolve("$namespace/tags/item/$path.json")
                    if (!Files.isRegularFile(generated)) {
                        return "items.getOrThrow(net.minecraft.tags.TagKey.create($REGISTRIES.ITEM, " +
                            "$RESOURCE_LOCATION.parse(\"$tag\")))"
                    }
                    val tagJson = runCatching { Json.parseToJsonElement(generated.readText()) as JsonObject }.getOrNull()
                        ?: return null
                    if (tagJson.keys != setOf("values")) return null
                    (tagJson["values"] as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: return null
                    } ?: return null
                } else {
                    listOf(value)
                }
            }
            is JsonArray -> element.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: return null }
            else -> return null
        }
        if (ids.isEmpty() || ids.any { !identifier.matches(it) }) return null
        return "net.minecraft.core.HolderSet.direct(" + ids.joinToString(", ") {
            "items.getOrThrow($RESOURCE_KEY.create($REGISTRIES.ITEM, $RESOURCE_LOCATION.parse(\"$it\")))"
        } + ")"
    }

    private fun enchantmentSet(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.takeIf { it.isString && it.content.startsWith("#") }
            ?.content?.removePrefix("#")?.takeIf { identifier.matches(it) }
            ?.let {
                "enchantments.getOrThrow(net.minecraft.tags.TagKey.create($REGISTRIES.ENCHANTMENT, " +
                    "$RESOURCE_LOCATION.parse(\"$it\")))"
            }
        is JsonArray -> element.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString && identifier.matches(value.content) }?.content
                ?: return null
        }.takeIf { it.isNotEmpty() }?.joinToString(", ", "net.minecraft.core.HolderSet.direct(", ")") {
            "enchantments.getOrThrow($RESOURCE_KEY.create($REGISTRIES.ENCHANTMENT, $RESOURCE_LOCATION.parse(\"$it\")))"
        }
        else -> null
    }

    // ---- vanilla enchantment tags -----------------------------------------------------------------

    /**
     * The derived vanilla tag memberships (`data/minecraft/tags/enchantment/<tag>.json`) of [keys];
     * null when a membership cannot be re-emitted exactly.
     */
    private fun enchantmentTags(data: Path, keys: List<Key>): Map<String, List<Key>>? {
        val directory = data.resolve("minecraft/tags/enchantment")
        if (!Files.isDirectory(directory)) return emptyMap()
        val byId = keys.associateBy { it.id }
        val result = linkedMapOf<String, List<Key>>()
        val files = Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.sorted().toList()
        }
        for (file in files) {
            val tag = file.fileName.toString().removeSuffix(".json")
            val values = runCatching { Json.parseToJsonElement(file.readText()) as JsonObject }.getOrNull()
                ?.get("values") as? JsonArray ?: return null
            val members = values.mapNotNull { (it as? JsonPrimitive)?.content?.let(byId::get) }
            if (members.isEmpty()) continue
            if (TargetMembers.fieldDescriptor(ENCHANTMENT_TAGS, tag.uppercase()) != TAG_KEY_DESCRIPTOR) return null
            result[tag] = members
        }
        return result
    }

    private fun tagsProviderSource(
        provider: Parsed,
        holderName: String,
        modId: String,
        tags: Map<String, List<Key>>,
        keys: List<Key>
    ): String {
        val nl = provider.newline
        val packageLine = if (provider.packageName.isEmpty()) "" else "package ${provider.packageName};$nl$nl"
        val holder = if (holderName.substringBeforeLast('.') == provider.packageName) holderName.substringAfterLast('.') else holderName
        return buildString {
            append(packageLine)
            append("public class $TAGS_PROVIDER extends net.minecraft.data.tags.EnchantmentTagsProvider {$nl")
            append("    public $TAGS_PROVIDER($nl")
            append("            net.minecraft.data.PackOutput output,$nl")
            append("            java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> lookupProvider,$nl")
            append("            $EXISTING_FILE_HELPER existingFileHelper) {$nl")
            append("        super(output, lookupProvider, \"$modId\", existingFileHelper);$nl")
            append("    }$nl$nl")
            append("    @Override$nl")
            append("    protected void addTags(net.minecraft.core.HolderLookup.Provider provider) {$nl")
            for ((tag, members) in tags) {
                val ordered = keys.filter { it in members }
                append("        tag($ENCHANTMENT_TAGS.${tag.uppercase()})$nl")
                append("            .add(${ordered.joinToString(", ") { "$holder.${it.field}" }});$nl")
            }
            append("    }$nl")
            append("}$nl")
        }
    }

    // ---- datagen graph ------------------------------------------------------------------------------

    /** The static `new RegistrySetBuilder()...` field the provider's super constructor receives. */
    private fun providerBuilder(parsed: Parsed, provider: ClassOrInterfaceDeclaration): Builder? {
        val superArguments = provider.constructors.flatMap { constructor ->
            constructor.body.statements.filterIsInstance<ExplicitConstructorInvocationStmt>()
                .filter { !it.isThis }
                .flatMap { it.arguments }
        }
        val names = superArguments.mapNotNull { (it as? NameExpr)?.nameAsString }.toSet()
        val builders = provider.fields.filter { it.isStatic }.flatMap { it.variables }.filter { variable ->
            variable.nameAsString in names &&
                (variable.type as? ClassOrInterfaceType)?.let { parsed.typing.isTypeName(it.nameWithScope, REGISTRY_SET_BUILDER) } == true
        }
        val variable = builders.singleOrNull() ?: return null
        val chain = variable.initializer.orElse(null) ?: return null
        var innermost: Expression = chain
        while (innermost is MethodCallExpr) {
            if (innermost.nameAsString != "add") return null
            innermost = innermost.scope.orElse(null) ?: return null
        }
        val creation = innermost as? ObjectCreationExpr ?: return null
        if (!parsed.typing.isTypeName(creation.type.nameWithScope, REGISTRY_SET_BUILDER) ||
            creation.arguments.isNotEmpty() || creation.anonymousClassBody.isPresent
        ) {
            return null
        }
        return Builder(parsed, creation, chain)
    }

    private fun builderBinding(builder: Builder, holderName: String): Edit {
        val parsed = builder.parsed
        val end = parsed.endOffset(builder.creation)
        val holder = if (parsed.sees(holderName)) holderName.substringAfterLast('.') else holderName
        val registries = if (parsed.imports(REGISTRIES)) "Registries" else REGISTRIES
        val following = Regex("""^(\r?\n[ \t]*)\.""").find(parsed.source.substring(end))
        val separator = following?.groupValues?.get(1) ?: ""
        return Edit(end, end, "$separator.add($registries.ENCHANTMENT, $holder::bootstrap)")
    }

    /**
     * Where the GatherDataEvent handler registers the provider: `P x = new P(output, lookup)` and
     * `generator.addProvider(condition, x)`; the tag provider must see the patched lookup.
     */
    private fun datagenRegistration(sources: List<Parsed>, providerName: String): Registration? {
        val candidates = sources.flatMap { parsed ->
            parsed.unit.findAll(ObjectCreationExpr::class.java)
                .filter { parsed.typing.isTypeName(it.type.nameWithScope, providerName) }
                .map { parsed to it }
        }
        val (parsed, creation) = candidates.singleOrNull() ?: return null
        val method = creation.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return null
        val event = method.parameters.singleOrNull {
            (it.type as? ClassOrInterfaceType)?.let { type -> parsed.typing.isTypeName(type.nameWithScope, GATHER_DATA_EVENT) } == true
        } ?: return null
        val variable = creation.parentNode.orElse(null) as? VariableDeclarator ?: return null
        val name = variable.nameAsString
        if (creation.arguments.size != 2) return null
        val registrations = method.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "addProvider" && call.arguments.size == 2 &&
                (call.arguments[1] as? NameExpr)?.nameAsString == name && call.scope.isPresent &&
                call.parentNode.orElse(null) is ExpressionStmt
        }
        val registration = registrations.singleOrNull() ?: return null
        val statement = registration.parentNode.get() as ExpressionStmt
        val patched = method.findAll(AssignExpr::class.java).filter { assign ->
            assign.operator == AssignExpr.Operator.ASSIGN &&
                (assign.value as? MethodCallExpr)?.let {
                    it.nameAsString == "getRegistryProvider" && it.arguments.isEmpty() &&
                        (it.scope.orElse(null) as? NameExpr)?.nameAsString == name
                } == true &&
                assign.target is NameExpr &&
                parsed.offset(assign) < parsed.offset(statement)
        }
        val lookup = patched.singleOrNull()?.target?.toString() ?: "$name.getRegistryProvider()"
        val existingFiles = method.findAll(VariableDeclarator::class.java).singleOrNull { local ->
            (local.type as? ClassOrInterfaceType)?.let { parsed.typing.isTypeName(it.nameWithScope, EXISTING_FILE_HELPER) } == true &&
                parsed.offset(local) < parsed.offset(statement)
        }?.nameAsString ?: "${event.nameAsString}.getExistingFileHelper()"
        return Registration(
            parsed = parsed,
            after = statement,
            generator = parsed.text(registration.scope.get()),
            condition = parsed.text(registration.arguments[0]),
            output = parsed.text(creation.arguments[0]),
            lookup = lookup,
            existingFiles = existingFiles
        )
    }

    // ---- shared -------------------------------------------------------------------------------------------

    private fun memberInsertion(parsed: Parsed, owner: TypeDeclaration<*>, lines: List<String>): Edit? {
        val close = parsed.endOffset(owner) - 1
        if (close <= 0 || parsed.source[close] != '}') return null
        val ownerIndent = parsed.indentAt(parsed.offset(owner))
        val memberIndent = owner.members.firstOrNull()
            ?.let { parsed.indentAt(parsed.offset(it)) }
            ?.takeIf { it.length > ownerIndent.length }
            ?: (ownerIndent + if (ownerIndent.startsWith("\t")) "\t" else "    ")
        val unit = if (memberIndent.endsWith("\t")) "\t" else "    "
        val text = lines.flatMap { it.lines() }.joinToString(parsed.newline) { line ->
            if (line.isBlank()) "" else {
                val spaces = line.takeWhile { it == ' ' }.length
                memberIndent + unit.repeat(spaces / 4) + " ".repeat(spaces % 4) + line.substring(spaces)
            }
        }
        val lineStart = parsed.source.lastIndexOf('\n', close - 1) + 1
        return if (parsed.source.substring(lineStart, close).isBlank()) {
            Edit(lineStart, lineStart, parsed.newline + text + parsed.newline)
        } else {
            Edit(close, close, parsed.newline + text + parsed.newline + ownerIndent)
        }
    }

    private fun targetContractsVerified(): Boolean {
        val enchantment = ENCHANTMENT.replace('.', '/')
        val holderSet = "Lnet/minecraft/core/HolderSet;"
        val cost = "L$enchantment\$Cost;"
        val definition = "L$enchantment\$EnchantmentDefinition;"
        fun public(owner: String, name: String, descriptor: String) =
            TargetMembers.inheritedMethod(owner, name, descriptor)?.access == "public"
        return public(ENCHANTMENT, "definition", "(${holderSet}II$cost${cost}I[Lnet/minecraft/world/entity/EquipmentSlotGroup;)$definition") &&
            public(ENCHANTMENT, "definition", "($holderSet${holderSet}II$cost${cost}I[Lnet/minecraft/world/entity/EquipmentSlotGroup;)$definition") &&
            public(ENCHANTMENT, "dynamicCost", "(II)$cost") &&
            public(ENCHANTMENT, "enchantment", "($definition)L$enchantment\$Builder;") &&
            public("$ENCHANTMENT\$Builder", "build", "(Lnet/minecraft/resources/ResourceLocation;)L$enchantment;") &&
            public("$ENCHANTMENT\$Builder", "exclusiveWith", "($holderSet)L$enchantment\$Builder;") &&
            public("net.minecraft.data.worldgen.BootstrapContext", "lookup", "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/HolderGetter;") &&
            public("net.minecraft.data.worldgen.BootstrapContext", "register", "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;)Lnet/minecraft/core/Holder\$Reference;") &&
            public(REGISTRY_SET_BUILDER, "add", "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/RegistrySetBuilder\$RegistryBootstrap;)Lnet/minecraft/core/RegistrySetBuilder;") &&
            public(ENTRIES_PROVIDER, "getRegistryProvider", "()Ljava/util/concurrent/CompletableFuture;") &&
            public("net.minecraft.data.tags.EnchantmentTagsProvider", "<init>",
                "(Lnet/minecraft/data/PackOutput;Ljava/util/concurrent/CompletableFuture;Ljava/lang/String;Lnet/neoforged/neoforge/common/data/ExistingFileHelper;)V") &&
            public(RESOURCE_LOCATION, "parse", "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;") &&
            TargetMembers.fieldDescriptor(REGISTRIES, "ENCHANTMENT") == "Lnet/minecraft/resources/ResourceKey;" &&
            TargetMembers.fieldDescriptor(REGISTRIES, "ITEM") == "Lnet/minecraft/resources/ResourceKey;"
    }
}
