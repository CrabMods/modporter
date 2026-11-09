package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaProjectIndex
import com.modporter.core.transforms.util.JavaScopedDeclarations
import com.modporter.core.transforms.util.JavaSourceText
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * NeoForge 1.21 has no `BlockEntity#getCapability(Capability, Direction)`: block entities expose
 * capabilities through `RegisterCapabilitiesEvent#registerBlockEntity` providers. A block entity
 * the direct provider migration left declaring the legacy query keeps that method as the mod's
 * own API and is adapted at the boundary: the legacy constants become
 * `LegacyCapabilities.ITEM_HANDLER/FLUID_HANDLER/ENERGY`, the root class implements
 * `LegacyCapabilityProvider`, and one generated mod-bus subscriber registers item, fluid and
 * energy providers for every block entity type of the mod that forward into the legacy method.
 *
 * The adaptation is applied only where its equivalence is proven from the source, and refused
 * (with a gate error) otherwise:
 * - every method taking a `Capability` compares it only with the three bridged constants or with
 *   the project's own capability keys, so no NeoForge query is answered differently;
 * - the root's `super.getCapability` appears only as a fall-through `return`, which becomes the
 *   empty optional because NeoForge consults the other providers itself;
 * - every `LazyOptional#invalidate()` of a block entity, or of a helper holding one, also calls
 *   `invalidateCapabilities()`, which is what refreshes NeoForge's capability caches.
 */
internal object LegacyBlockEntityCapabilities {
    const val RULE_ID = "struct-legacy-block-entity-capabilities"
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val vanillaBlockEntityTypes = setOf(
        "BlockEntity", "BaseContainerBlockEntity", "RandomizableContainerBlockEntity", "AbstractFurnaceBlockEntity",
        "FurnaceBlockEntity", "SmokerBlockEntity", "BlastFurnaceBlockEntity", "ChestBlockEntity", "BarrelBlockEntity",
        "ShulkerBoxBlockEntity", "SkullBlockEntity", "SignBlockEntity", "HangingSignBlockEntity", "TheEndGatewayBlockEntity",
        "BrushableBlockEntity"
    )
    private val legacyMethod = Regex("""(?m)^([ \t]*)((?:@Override\s*)?)(public\s+<T>\s+LazyOptional<T>\s+getCapability\s*\(\s*(?:@\w+\s+)*Capability<T>\s+($ID)\s*,\s*(?:@\w+\s+)*Direction\s+($ID)\s*\)\s*\{)""")
    private val superQuery = Regex("""\bsuper\.getCapability\(\s*$ID\s*,\s*$ID\s*\)""")
    private val superQueryReturn = Regex("""\breturn\s+super\.getCapability\(\s*$ID\s*,\s*$ID\s*\)\s*;""")
    private val capabilityParameterMethod = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private|static|final|synchronized|abstract|default)\s+)*(?:<[^>]+>\s+)?[\w.<>\[\]?, ]+?\s+($ID)\s*\(([^()]*\bCapability<[^()]*)\)\s*(?:throws[^{]+)?\{"""
    )
    private val neoForgeCapability = Regex("""(?<![\w$])Capabilities\.""")
    private val lazyOptionalField = Regex("""\bLazyOptional<[^;=]*>\s+($ID)\s*[;=]""")
    private val invalidation = Regex("""(?m)^([ \t]*)(?:this\.)?($ID)\.invalidate\(\);[ \t]*$""")
    private val capabilities = listOf(
        Triple("ItemHandler", "ITEM_HANDLER", "net.neoforged.neoforge.items.IItemHandler"),
        Triple("FluidHandler", "FLUID_HANDLER", "net.neoforged.neoforge.fluids.capability.IFluidHandler"),
        Triple("EnergyStorage", "ENERGY", "net.neoforged.neoforge.energy.IEnergyStorage")
    )

    /** The changes made, the files whose legacy query was kept behind generated providers, and the shapes refused. */
    data class Result(val changes: List<Change>, val adaptedFiles: Set<Path>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptySet(), emptyList())
        }
    }

    fun migrate(
        projectDir: Path,
        modId: String,
        compatPackageSupplier: () -> String,
        dryRun: Boolean,
        priorErrors: List<String> = emptyList()
    ): Result {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.isDirectory()) return Result.EMPTY
        val files = JavaSourceText.walkJavaSourceRoots(srcDir).use { paths -> paths.filter { it.extension == "java" }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val index = JavaProjectIndex.build(files)
        val exactProviderRefusals = exactProviderRefusalFiles(projectDir, priorErrors)
        val legacyOwners = sources.filter { (_, source) -> legacyMethod.containsMatchIn(JavaSourceText.maskCommentsAndLiterals(source)) }
            .keys
            .mapNotNull { file -> index.declarationsIn(file).firstOrNull { isBlockEntity(it.name, index) }?.let { it.name to file } }
            .toMap()
        if (legacyOwners.isEmpty()) return Result.EMPTY
        fun relative(file: Path) = projectDir.relativize(file).invariantSeparatorsPathString

        val gates = mutableListOf<String>()
        val refused = mutableSetOf<Path>()
        val adapted = linkedMapOf<Path, String>()
        val roots = legacyOwners.keys.associateWith { owner -> ancestors(owner, index).lastOrNull { it in legacyOwners } ?: owner }
        for ((owner, file) in legacyOwners.entries.sortedBy { (owner, _) -> if (roots.getValue(owner) == owner) 0 else 1 }) {
            if (file.toAbsolutePath().normalize() in exactProviderRefusals) {
                refused.add(file)
                continue
            }
            val root = roots.getValue(owner)
            if (root != owner && legacyOwners.getValue(root) !in adapted) {
                gates += "Legacy block entity capability adapter cannot keep ${relative(file)}: its root $root was not adapted"
                refused.add(file)
                continue
            }
            var migrated = legacyConstants(sources.getValue(file))
            val unproven = unprovenShapes(migrated, isRoot = root == owner)
            if (unproven.isNotEmpty()) {
                gates += "Legacy block entity capability adapter cannot keep ${relative(file)}: ${unproven.joinToString("; ")}"
                refused.add(file)
                continue
            }
            if (root == owner) {
                migrated = legacyMethod.replace(migrated) { match -> "${match.groupValues[1]}${match.groupValues[3]}" }
                migrated = replaceCode(migrated, superQueryReturn) { "return LazyOptional.empty();" }
                migrated = implementProvider(migrated, owner)
                if (!implementsProvider(migrated, owner)) {
                    gates += "Legacy block entity capability adapter cannot keep ${relative(file)}: the root $owner did not retain LegacyCapabilityProvider in its parsed class declaration"
                    refused.add(file)
                    continue
                }
                migrated = JavaSourceText.addImport(migrated, "${compatPackageSupplier()}.LegacyCapabilityProvider")
            }
            adapted[file] = migrated
        }
        if (adapted.isEmpty()) return Result(emptyList(), emptySet(), gates)

        val compatPackage = compatPackageSupplier()
        val changes = mutableListOf<Change>()
        changes += compatSources(srcDir, compatPackage, modId, dryRun)
        val keepingTypes = adapted.keys.flatMap { file -> index.declarationsIn(file).map { it.name } }
            .flatMap { owner -> listOf(owner) + index.subtypesOf(owner).map { it.name } }
            .toSet()
        for ((file, source) in sources) {
            if (file in refused) continue
            val kept = file in adapted
            var migrated = adapted[file] ?: legacyConstants(source)
            migrated = propagateInvalidation(migrated, file, index, sources)
            migrated = levelLookupQueries(migrated, keepingTypes, index, compatPackage)
            if (migrated == source) continue
            if (migrated.contains("LegacyCapabilities.")) migrated = JavaSourceText.addImport(migrated, "$compatPackage.LegacyCapabilities")
            if (!dryRun) file.writeText(migrated)
            changes += if (kept) {
                Change(file, 1, "Block entity keeps its legacy getCapability as the mod's own API behind the generated capability providers",
                    "getCapability(Capability<T>, Direction) override", "LegacyCapabilityProvider + LegacyCapabilities constants", Confidence.HIGH, RULE_ID)
            } else {
                Change(file, 1, "Legacy block entity capability queries use the legacy capability constants and invalidations reach the capability caches",
                    "getCapability(Capabilities.ItemHandler.BLOCK, side) / lazy.invalidate()", "getCapability(LegacyCapabilities.ITEM_HANDLER, side) / invalidateCapabilities()", Confidence.HIGH, RULE_ID)
            }
        }
        return Result(changes, adapted.keys, gates)
    }

    private fun exactProviderRefusalFiles(projectDir: Path, errors: List<String>): Set<Path> {
        val refusal = Regex(
            """^(?:Cannot migrate|Cannot partially migrate) BlockEntity getCapability in (.+?): """ +
                """(?:no source-shaped provider for Capabilities\.(?!\*)|""" +
                """external block capability predicate/provider branch is not structurally closed|""" +
                """unproven predicate branches )"""
        )
        return errors.mapNotNull { error ->
            val relative = refusal.find(error)?.groupValues?.get(1) ?: return@mapNotNull null
            projectDir.resolve(relative).toAbsolutePath().normalize()
        }.toSet()
    }

    /**
     * A query on a receiver declared as a block entity that does not keep the legacy method (a vanilla
     * block entity, or one of the mod's that the direct migration gave providers) asks NeoForge's
     * level lookup instead: `Level#getCapability(BlockCapability, BlockPos, BlockState, BlockEntity, C)`.
     */
    private fun levelLookupQueries(source: String, keepingTypes: Set<String>, index: JavaProjectIndex, compatPackage: String): String {
        if (!source.contains("LegacyCapabilities.")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val holders = capabilities.associate { (holder, constant, _) -> constant to holder }
        fun declaredType(receiver: String, at: Int): String? =
            JavaScopedDeclarations.visibleAt(masked, receiver, at)?.type
                ?: Regex("""\binstanceof\s+([A-Z][\w$]*)\s+${Regex.escape(receiver)}(?![\w$])""").find(masked)?.groupValues?.get(1)
        // a capability variable stands for the constant it is initialised with when it is never reassigned
        fun constantOf(argument: String, at: Int): String? {
            Regex("""LegacyCapabilities\.($ID)""").matchEntire(argument)?.let { return it.groupValues[1] }
            if (!Regex(ID).matches(argument) || JavaScopedDeclarations.isReassigned(masked, argument)) return null
            val declaration = JavaScopedDeclarations.visibleAt(masked, argument, at)?.takeIf { it.type == "Capability" }
            return declaration?.initializer?.let { Regex("""LegacyCapabilities\.($ID)""").matchEntire(it)?.groupValues?.get(1) }
        }
        var result = replaceQuery(source, Regex("""(?<![\w$.])($ID)\.getCapability\(\s*(?:LegacyCapabilities\.)?$ID\s*,"""), rewriteWhole = true) { match, args ->
            val receiver = match.groupValues[1]
            val constant = constantOf(args[0], match.range.first) ?: return@replaceQuery null
            if (holders[constant] == null) return@replaceQuery null
            val type = declaredType(receiver, match.range.first) ?: return@replaceQuery null
            if (type in keepingTypes || !(type == "BlockEntity" || isBlockEntity(type, index) || (type.endsWith("BlockEntity") && index.declaration(type) == null))) return@replaceQuery null
            "LegacyBlockEntityCapabilityQueries.get($receiver, LegacyCapabilities.$constant, ${args[1]})"
        }
        if (result != source) {
            result = JavaSourceText.addImport(result, "$compatPackage.LegacyBlockEntityCapabilityQueries")
        }
        return result
    }

    /** The direct migration's gates on files this adapter has since taken over. */
    fun withdrawnGates(errors: List<String>, projectDir: Path, adaptedFiles: Set<Path>): List<String> {
        val adapted = adaptedFiles.map { projectDir.relativize(it).invariantSeparatorsPathString }
        return errors.filter { error ->
            error.contains("migrate BlockEntity getCapability in ") && adapted.any { error.contains(" in $it:") }
        }
    }

    /** Why the adaptation of [source] would not be equivalent, if anything in it is not understood. */
    private fun unprovenShapes(source: String, isRoot: Boolean): List<String> {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val reasons = mutableListOf<String>()
        capabilityParameterMethod.findAll(masked).forEach { method ->
            val close = JavaSourceText.matchingBrace(masked, method.range.last)
            if (close < 0) return@forEach
            if (neoForgeCapability.containsMatchIn(masked.substring(method.range.last, close))) {
                reasons += "${method.groupValues[1]}(Capability) compares a capability the adapter does not bridge"
            }
        }
        if (isRoot && superQuery.findAll(masked).count() != superQueryReturn.findAll(masked).count()) {
            reasons += "super.getCapability is used in an expression rather than returned"
        }
        return reasons
    }

    /** `lazy.invalidate();` of a block entity, or of a helper that holds one, also invalidates NeoForge's capability caches. */
    private fun propagateInvalidation(source: String, file: Path, index: JavaProjectIndex, sources: Map<Path, String>): String {
        if (!source.contains(".invalidate()")) return source
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val fields = lazyOptionalField.findAll(masked).map { it.groupValues[1] }.toSet()
        if (fields.isEmpty()) return source
        val declared = index.declarationsIn(file).map { it.name }
        val target = when {
            declared.any { isBlockEntity(it, index) } -> "invalidateCapabilities();"
            else -> declared.firstNotNullOfOrNull { blockEntityField(it, index, sources) }?.let { "$it.invalidateCapabilities();" }
        } ?: return source
        return replaceCode(source, invalidation) { match ->
            val (indent, field) = match.destructured
            val following = masked.substring(match.range.last + 1).trimStart()
            if (field !in fields || following.startsWith(target)) match.value else "${match.value}\n$indent$target"
        }
    }

    /** A field of [className] or a project ancestor typed as a block entity. */
    private fun blockEntityField(className: String, index: JavaProjectIndex, sources: Map<Path, String>): String? {
        var current: String? = className
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            val declaration = index.declaration(current) ?: return null
            val masked = JavaSourceText.maskCommentsAndLiterals(sources[declaration.file] ?: return null)
            Regex("""(?m)^[ \t]*(?:(?:public|protected|private|final|transient)\s+)*(?!static\b)($ID)\s+($ID)\s*[;=]""")
                .findAll(masked)
                .firstOrNull { field -> field.groupValues[1] == "BlockEntity" || isBlockEntity(field.groupValues[1], index) }
                ?.let { return it.groupValues[2] }
            current = declaration.directSuper
        }
        return null
    }

    private fun isBlockEntity(name: String, index: JavaProjectIndex): Boolean {
        var current: String? = name
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current in vanillaBlockEntityTypes) return true
            current = index.declaration(current)?.directSuper
        }
        return current in vanillaBlockEntityTypes
    }

    private fun ancestors(name: String, index: JavaProjectIndex): List<String> {
        val result = mutableListOf<String>()
        var current = index.declaration(name)?.directSuper
        while (current != null && current !in result) {
            result += current
            current = index.declaration(current)?.directSuper
        }
        return result
    }

    /**
     * Legacy query shapes, which NeoForge's typed capability API has no counterpart for: comparisons
     * with `Capabilities.X.BLOCK`, `Capability<...>` variables initialised with it, two-argument
     * `getCapability(Capabilities.X.BLOCK, side)`, and the one-argument query (Forge's `side == null`).
     */
    fun legacyConstants(source: String): String {
        var result = source
        for ((holder, constant, _) in capabilities) {
            val neo = """(?:net\.neoforged\.neoforge\.capabilities\.)?Capabilities\.$holder\.BLOCK"""
            result = replaceCode(result, Regex("""(==|!=)\s*$neo(?![\w$])""")) { "${it.groupValues[1]} LegacyCapabilities.$constant" }
            result = replaceCode(result, Regex("""(?<![\w$.])$neo\s*(==|!=)""")) { "LegacyCapabilities.$constant ${it.groupValues[1]}" }
            result = replaceCode(result, Regex("""\bCapability<[^=;]*>\s+($ID)\s*=\s*$neo\s*;""")) { match ->
                match.value.replaceRange(match.value.lastIndexOf("Capabilities."), match.value.length - 1, "LegacyCapabilities.$constant")
            }
            result = replaceQuery(result, Regex("""\.getCapability\(\s*$neo\s*(,|\))""")) { args ->
                if (args.size == 1) "LegacyCapabilities.$constant, null" else "LegacyCapabilities.$constant, ${args[1]}"
            }
        }
        val capabilityVariables = Regex("""\bCapability<[^=;]*>\s+($ID)\s*[;=]""")
            .findAll(JavaSourceText.maskCommentsAndLiterals(result)).map { it.groupValues[1] }.toSet()
        for (variable in capabilityVariables) {
            result = replaceQuery(result, Regex("""\.getCapability\(\s*${Regex.escape(variable)}\s*\)""")) { "$variable, null" }
        }
        return result
    }

    /** Rewrites the argument list of each `.getCapability(` call [pattern] starts, with at most two arguments. */
    private fun replaceQuery(source: String, pattern: Regex, arguments: (List<String>) -> String): String =
        replaceQuery(source, pattern, rewriteWhole = false) { _, args -> arguments(args) }

    /** Rewrites each `getCapability(` call [pattern] starts: its argument list, or with [rewriteWhole] the whole call from the match start. */
    private fun replaceQuery(source: String, pattern: Regex, rewriteWhole: Boolean, rewrite: (MatchResult, List<String>) -> String?): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).mapNotNull { match ->
            val open = masked.indexOf('(', match.range.first + match.value.indexOf(".getCapability"))
            val close = JavaSourceText.matchingParen(masked, open)
            if (close < 0) return@mapNotNull null
            val args = JavaSourceText.splitTopLevelArgs(source.substring(open + 1, close)).map { it.trim() }
            if (args.size !in 1..2) return@mapNotNull null
            val value = rewrite(match, args) ?: return@mapNotNull null
            (if (rewriteWhole) match.range.first..close else (open + 1 until close)) to value
        }.toList()
        return edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }

    private fun implementProvider(source: String, owner: String): String {
        val unit = parseUnit(source) ?: return source
        val declaration = unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .singleOrNull { it.nameAsString == owner && !it.isInterface }
            ?: return source
        if (declaration.implementedTypes.any { it.nameAsString == "LegacyCapabilityProvider" }) return source
        LexicalPreservingPrinter.setup(unit)
        declaration.addImplementedType("LegacyCapabilityProvider")
        return LexicalPreservingPrinter.print(unit)
    }

    private fun implementsProvider(source: String, owner: String): Boolean =
        parseUnit(source)
            ?.findAll(ClassOrInterfaceDeclaration::class.java)
            ?.singleOrNull { it.nameAsString == owner && !it.isInterface }
            ?.implementedTypes
            ?.any { it.nameAsString == "LegacyCapabilityProvider" }
            ?: false

    private fun parseUnit(source: String) = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    ).parse(source).result.orElse(null)

    private fun replaceCode(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val masked = JavaSourceText.maskCommentsAndLiterals(source)
        val edits = pattern.findAll(masked).map { it.range to replacement(it) }.toList()
        return edits.asReversed().fold(source) { text, (range, value) -> text.replaceRange(range, value) }
    }

    /**
     * The legacy capability keys and the provider interface. Any generated source that names
     * `LegacyCapabilities` or `LegacyCapabilityProvider` (the adapted block entities, and the query
     * boundary of [LegacyBlockEntityCapabilityQueries]) needs these two types.
     */
    internal fun keySources(compatPackage: String): Map<String, String> = mapOf(
        "LegacyCapabilities" to """
            |package $compatPackage;
            |
            |/** The capability keys the mod's legacy getCapability(Capability, Direction) methods compare against. */
            |public final class LegacyCapabilities {
            |    public static final Capability<net.neoforged.neoforge.items.IItemHandler> ITEM_HANDLER = new Capability<>();
            |    public static final Capability<net.neoforged.neoforge.fluids.capability.IFluidHandler> FLUID_HANDLER = new Capability<>();
            |    public static final Capability<net.neoforged.neoforge.energy.IEnergyStorage> ENERGY = new Capability<>();
            |
            |    private LegacyCapabilities() {
            |    }
            |}
            |""".trimMargin(),
        "LegacyCapabilityProvider" to """
            |package $compatPackage;
            |
            |import net.minecraft.core.Direction;
            |
            |/** A block entity that still answers the legacy capability query; the generated providers forward into it. */
            |public interface LegacyCapabilityProvider {
            |    <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side);
            |}
            |""".trimMargin()
    )

    private fun compatSources(srcDir: Path, compatPackage: String, modId: String, dryRun: Boolean): List<Change> {
        val registrations = capabilities.joinToString("\n") { (holder, constant, _) ->
            "        event.registerBlockEntity(Capabilities.$holder.BLOCK, type, (be, side) -> be instanceof LegacyCapabilityProvider provider ? provider.getCapability(LegacyCapabilities.$constant, side).orElse(null) : null);"
        }
        val sources = keySources(compatPackage) + mapOf(
            "LegacyBlockEntityCapabilities" to """
                |package $compatPackage;
                |
                |import net.minecraft.core.registries.BuiltInRegistries;
                |import net.minecraft.resources.ResourceLocation;
                |import net.minecraft.world.level.block.entity.BlockEntity;
                |import net.minecraft.world.level.block.entity.BlockEntityType;
                |import net.neoforged.bus.api.SubscribeEvent;
                |import net.neoforged.fml.common.EventBusSubscriber;
                |import net.neoforged.neoforge.capabilities.Capabilities;
                |import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
                |
                |/** Registers item, fluid and energy providers for every block entity type of the mod that forward into the legacy query. */
                |@EventBusSubscriber(modid = "$modId")
                |public final class LegacyBlockEntityCapabilities {
                |    private LegacyBlockEntityCapabilities() {
                |    }
                |
                |    @SubscribeEvent
                |    public static void register(RegisterCapabilitiesEvent event) {
                |        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                |            ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
                |            if (key != null && key.getNamespace().equals("$modId")) {
                |                register(event, type);
                |            }
                |        }
                |    }
                |
                |    private static <BE extends BlockEntity> void register(RegisterCapabilitiesEvent event, BlockEntityType<BE> type) {
                |$registrations
                |    }
                |}
                |""".trimMargin()
        )
        val changes = mutableListOf<Change>()
        for ((name, text) in sources) {
            val file = srcDir.resolve(compatPackage.replace('.', '/')).resolve("$name.java")
            if (file.exists() && file.readText() == text) continue
            if (!dryRun) {
                file.parent.createDirectories()
                file.writeText(text)
            }
            changes += Change(file, 1, "Generated $name for the mod's legacy block entity capability queries", "(absent)", "$compatPackage.$name", Confidence.HIGH, RULE_ID)
        }
        return changes
    }
}
