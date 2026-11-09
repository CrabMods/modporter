package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Restores Create's 1.21 data-driven enchantment bootstrap after legacy Registrate enchantments
 * have been converted to ResourceKeys.
 *
 * The ResourceKeys alone are not registry entries. Create's runData provider must bootstrap the
 * two enchantments so their JSON is generated and packaged. Both Java owners are admitted and
 * published as one transaction because either source without the other leaves runtime lookups
 * such as BacktankUtil.maxAir() unresolved.
 */
internal object CreateEnchantmentDatagenBootstrapMigration {
    const val RULE_ID = "struct-create-enchantment-datagen-bootstrap"

    private const val ENCHANTMENTS_RELATIVE =
        "src/main/java/com/simibubi/create/AllEnchantments.java"
    private const val PROVIDER_RELATIVE =
        "src/main/java/com/simibubi/create/infrastructure/data/GeneratedEntriesProvider.java"
    private const val DATAGEN_RELATIVE =
        "src/main/java/com/simibubi/create/infrastructure/data/CreateDatagen.java"
    private const val TAGS_PROVIDER_RELATIVE =
        "src/main/java/com/simibubi/create/infrastructure/data/CreateEnchantmentTagsProvider.java"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result =
        migrateWithMove(projectDir, dryRun, ::atomicMove)

    internal fun migrateForTest(
        projectDir: Path,
        dryRun: Boolean,
        move: (Path, Path) -> Unit
    ): Result = migrateWithMove(projectDir, dryRun, move)

    private fun migrateWithMove(
        projectDir: Path,
        dryRun: Boolean,
        move: (Path, Path) -> Unit
    ): Result = try {
        migrateChecked(projectDir, dryRun, move)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create enchantment bootstrap admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Create enchantment bootstrap migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(
        projectDir: Path,
        dryRun: Boolean,
        move: (Path, Path) -> Unit
    ): Result {
        val enchantmentsFile = projectDir.resolve(ENCHANTMENTS_RELATIVE)
        if (!enchantmentsFile.isRegularFile()) return Result.EMPTY
        val providerFile = projectDir.resolve(PROVIDER_RELATIVE)
        if (!providerFile.isRegularFile()) {
            abort("Create enchantment source graph is missing $PROVIDER_RELATIVE")
        }
        val datagenFile = projectDir.resolve(DATAGEN_RELATIVE)
        if (!datagenFile.isRegularFile()) {
            abort("Create enchantment source graph is missing $DATAGEN_RELATIVE")
        }
        val tagsProviderFile = projectDir.resolve(TAGS_PROVIDER_RELATIVE)

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val enchantmentsBefore = enchantmentsFile.readText()
        val providerBefore = providerFile.readText()
        val datagenBefore = datagenFile.readText()
        val tagsProviderBefore =
            if (tagsProviderFile.isRegularFile()) tagsProviderFile.readText() else null
        val enchantmentsMode = classifyEnchantments(parser, enchantmentsBefore, enchantmentsFile)
        val providerMode = classifyProvider(parser, providerBefore, providerFile)
        val datagenMode = classifyDatagen(parser, datagenBefore, datagenFile)
        val tagsProviderMode =
            tagsProviderBefore?.let {
                classifyTagsProvider(parser, it, tagsProviderFile)
            } ?: Mode.MISSING

        if (enchantmentsMode == Mode.TARGET &&
            providerMode == Mode.TARGET &&
            datagenMode == Mode.TARGET &&
            tagsProviderMode == Mode.TARGET
        ) {
            return Result.EMPTY
        }
        if (enchantmentsMode != Mode.LEGACY ||
            providerMode != Mode.LEGACY ||
            datagenMode != Mode.LEGACY ||
            tagsProviderMode != Mode.MISSING
        ) {
            abort(
                "Create enchantment bootstrap family is partial or incompatible: " +
                    "AllEnchantments=$enchantmentsMode, " +
                    "GeneratedEntriesProvider=$providerMode, " +
                    "CreateDatagen=$datagenMode, " +
                    "CreateEnchantmentTagsProvider=$tagsProviderMode"
            )
        }

        val enchantmentsAfter = targetAllEnchantments()
        val providerAfter = migrateProvider(providerBefore)
        val datagenAfter = migrateDatagen(datagenBefore)
        val tagsProviderAfter = targetTagsProvider()
        if (classifyEnchantments(parser, enchantmentsAfter, enchantmentsFile) != Mode.TARGET) {
            abort("AllEnchantments did not reach the source-preserving 1.21 bootstrap contract")
        }
        if (classifyProvider(parser, providerAfter, providerFile) != Mode.TARGET) {
            abort("GeneratedEntriesProvider did not bind the enchantment bootstrap")
        }
        if (classifyDatagen(parser, datagenAfter, datagenFile) != Mode.TARGET) {
            abort("CreateDatagen did not bind the enchantment tag provider")
        }
        if (classifyTagsProvider(parser, tagsProviderAfter, tagsProviderFile) != Mode.TARGET) {
            abort("CreateEnchantmentTagsProvider did not reach the source-preserving tag contract")
        }

        val outputs = linkedMapOf(
            enchantmentsFile to enchantmentsAfter,
            providerFile to providerAfter,
            datagenFile to datagenAfter,
            tagsProviderFile to tagsProviderAfter
        )
        if (!dryRun) {
            writeAtomicFamily(
                outputs,
                mapOf(
                    enchantmentsFile to enchantmentsFile.readBytes(),
                    providerFile to providerFile.readBytes(),
                    datagenFile to datagenFile.readBytes(),
                    tagsProviderFile to tagsProviderBefore?.toByteArray(StandardCharsets.UTF_8)
                ),
                move
            )
        }
        return Result(
            outputs.map { (file, after) ->
                val before = when (file) {
                    enchantmentsFile -> enchantmentsBefore
                    providerFile -> providerBefore
                    datagenFile -> datagenBefore
                    else -> tagsProviderBefore.orEmpty()
                }
                Change(
                    file = file,
                    line = 1,
                    description =
                        "Restore Create 1.21 enchantment data with legacy gameplay semantics",
                    before = before.take(160),
                    after = after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun classifyEnchantments(
        parser: JavaParser,
        source: String,
        path: Path
    ): Mode {
        val unit = parse(parser, source, path)
        val owner = requireClass(unit, "com.simibubi.create", "AllEnchantments")
        val fields = owner.fields.flatMap { field -> field.variables.map { it.nameAsString } }.toSet()
        val methods = owner.methods.map { it.nameAsString }.toSet()
        val normalized = normalize(source)

        val sharedKeys =
            normalized.contains(
                "ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT," +
                    "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"create\",\"potato_recovery\"))"
            ) &&
                normalized.contains(
                    "ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT," +
                        "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"create\",\"capacity\"))"
                )
        val legacy =
            fields == setOf("POTATO_RECOVERY", "CAPACITY", "ENCHANTMENTS") &&
                methods == setOf("register") &&
                owner.methods.singleOrNull {
                    it.nameAsString == "register" && it.parameters.isEmpty()
                }?.body?.map { it.statements.isEmpty() }?.orElse(false) == true &&
                sharedKeys &&
                normalized.contains("java.util.List.of(POTATO_RECOVERY,CAPACITY)")
        if (legacy) return Mode.LEGACY

        val target =
            fields == setOf("POTATO_RECOVERY", "CAPACITY", "ENCHANTMENTS") &&
                methods == setOf("key", "bootstrap", "register") &&
                normalized.contains("ResourceKey.create(Registries.ENCHANTMENT,Create.asResource(name))") &&
                normalized.contains("context.register(key,builder.build(key.location()))") &&
                normalized.contains("HolderSet.direct(AllItems.POTATO_CANNON)") &&
                normalized.contains(
                    "HolderSet.direct(AllItems.COPPER_BACKTANK,AllItems.NETHERITE_BACKTANK)"
                ) &&
                normalized.contains(
                    "HolderSet.direct(AllItems.POTATO_CANNON),5,3," +
                        "Enchantment.dynamicCost(11,10),Enchantment.dynamicCost(16,10),2," +
                        "EquipmentSlotGroup.HAND"
                ) &&
                normalized.contains(
                    "HolderSet.direct(AllItems.COPPER_BACKTANK,AllItems.NETHERITE_BACKTANK)," +
                        "10,3,Enchantment.dynamicCost(11,10)," +
                        "Enchantment.dynamicCost(16,10),1,EquipmentSlotGroup.CHEST"
                ) &&
                normalized.contains("java.util.List.of(POTATO_RECOVERY,CAPACITY)") &&
                !normalized.contains("EnchantmentEffectComponents") &&
                owner.methods.singleOrNull {
                    it.nameAsString == "register" && it.parameters.isEmpty()
                }?.body?.map { it.statements.isEmpty() }?.orElse(false) == true &&
                owner.methods.singleOrNull {
                    it.nameAsString == "register" && it.parameters.size == 3
                } != null
        return if (target) Mode.TARGET else Mode.OTHER
    }

    private fun classifyProvider(
        parser: JavaParser,
        source: String,
        path: Path
    ): Mode {
        val unit = parse(parser, source, path)
        val owner = requireClass(
            unit,
            "com.simibubi.create.infrastructure.data",
            "GeneratedEntriesProvider"
        )
        if (owner.extendedTypes.map { it.nameAsString } != listOf("DatapackBuiltinEntriesProvider")) {
            return Mode.OTHER
        }
        val builder = owner.fields
            .flatMap { it.variables }
            .singleOrNull { it.nameAsString == "BUILDER" }
            ?: return Mode.OTHER
        if (builder.typeAsString != "RegistrySetBuilder" || builder.initializer.isEmpty) {
            return Mode.OTHER
        }
        val normalizedBuilder = normalize(builder.initializer.orElseThrow().toString())
        val enchantmentBinding =
            ".add(Registries.ENCHANTMENT,AllEnchantments::bootstrap)"
        val expectedTail =
            ".add(Registries.DAMAGE_TYPE,AllDamageTypes::bootstrap)" +
                ".add(Registries.CONFIGURED_FEATURE,AllConfiguredFeatures::bootstrap)" +
                ".add(Registries.PLACED_FEATURE,AllPlacedFeatures::bootstrap)" +
                ".add(NeoForgeRegistries.Keys.BIOME_MODIFIERS,AllBiomeModifiers::bootstrap)" +
                ".add(CreateRegistries.POTATO_PROJECTILE_TYPE,AllPotatoProjectileTypes::bootstrap)"
        if (!normalizedBuilder.endsWith(expectedTail)) return Mode.OTHER
        val enchantmentImportCount = unit.imports.count {
            !it.isStatic && !it.isAsterisk &&
                it.nameAsString == "com.simibubi.create.AllEnchantments"
        }
        return when {
            normalizedBuilder == "newRegistrySetBuilder()$expectedTail" &&
                enchantmentImportCount == 0 -> Mode.LEGACY
            normalizedBuilder == "newRegistrySetBuilder()$enchantmentBinding$expectedTail" &&
                enchantmentImportCount == 1 -> Mode.TARGET
            else -> Mode.OTHER
        }
    }

    private fun migrateProvider(source: String): String {
        val importAnchor = "import com.simibubi.create.AllDamageTypes;"
        val builderAnchor =
            "private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()"
        if (source.countOccurrences(importAnchor) != 1 ||
            source.countOccurrences(builderAnchor) != 1
        ) {
            abort("GeneratedEntriesProvider anchors are not unique")
        }
        return source
            .replace(
                importAnchor,
                "$importAnchor\nimport com.simibubi.create.AllEnchantments;"
            )
            .replace(
                builderAnchor,
                "$builderAnchor\n\t\t.add(Registries.ENCHANTMENT, AllEnchantments::bootstrap)"
            )
    }

    private fun classifyDatagen(
        parser: JavaParser,
        source: String,
        path: Path
    ): Mode {
        val unit = parse(parser, source, path)
        requireClass(unit, "com.simibubi.create.infrastructure.data", "CreateDatagen")
        val binding =
            "generator.addProvider(event.includeServer()," +
                "newCreateEnchantmentTagsProvider(output,lookupProvider,existingFileHelper));"
        return when (normalize(source).countOccurrences(binding)) {
            0 -> Mode.LEGACY
            1 -> Mode.TARGET
            else -> Mode.OTHER
        }
    }

    private fun migrateDatagen(source: String): String {
        val anchor = Regex(
            """generator\.addProvider\(\s*event\.includeServer\(\),\s*""" +
                """new\s+DamageTypeTagGen\(\s*output,\s*lookupProvider,\s*""" +
                """existingFileHelper\s*\)\s*\);"""
        )
        val matches = anchor.findAll(source).toList()
        if (matches.size != 1) {
            abort("CreateDatagen enchantment-tag insertion anchor is not unique")
        }
        val match = matches.single()
        return source.replaceRange(
            match.range,
            match.value + "\n\t\tgenerator.addProvider(event.includeServer(), " +
                "new CreateEnchantmentTagsProvider(output, lookupProvider, existingFileHelper));"
        )
    }

    private fun classifyTagsProvider(
        parser: JavaParser,
        source: String,
        path: Path
    ): Mode {
        val unit = parse(parser, source, path)
        requireClass(
            unit,
            "com.simibubi.create.infrastructure.data",
            "CreateEnchantmentTagsProvider"
        )
        return if (normalize(source) == normalize(targetTagsProvider())) {
            Mode.TARGET
        } else {
            Mode.OTHER
        }
    }

    private fun targetAllEnchantments(): String = """
        package com.simibubi.create;

        import net.minecraft.core.HolderSet;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.data.worldgen.BootstrapContext;
        import net.minecraft.resources.ResourceKey;
        import net.minecraft.world.entity.EquipmentSlotGroup;
        import net.minecraft.world.item.enchantment.Enchantment;

        public class AllEnchantments {
            public static final ResourceKey<Enchantment>
                    POTATO_RECOVERY = key("potato_recovery"),
                    CAPACITY = key("capacity");

            public static final java.util.List<ResourceKey<Enchantment>> ENCHANTMENTS =
                    java.util.List.of(POTATO_RECOVERY, CAPACITY);

            private static ResourceKey<Enchantment> key(String name) {
                return ResourceKey.create(Registries.ENCHANTMENT, Create.asResource(name));
            }

            public static void bootstrap(BootstrapContext<Enchantment> context) {
                register(
                        context,
                        POTATO_RECOVERY,
                        Enchantment.enchantment(
                                Enchantment.definition(
                                        HolderSet.direct(AllItems.POTATO_CANNON),
                                        5,
                                        3,
                                        Enchantment.dynamicCost(11, 10),
                                        Enchantment.dynamicCost(16, 10),
                                        2,
                                        EquipmentSlotGroup.HAND
                                )
                        )
                );

                register(
                        context,
                        CAPACITY,
                        Enchantment.enchantment(
                                Enchantment.definition(
                                        HolderSet.direct(
                                                AllItems.COPPER_BACKTANK,
                                                AllItems.NETHERITE_BACKTANK
                                        ),
                                        10,
                                        3,
                                        Enchantment.dynamicCost(11, 10),
                                        Enchantment.dynamicCost(16, 10),
                                        1,
                                        EquipmentSlotGroup.CHEST
                                )
                        )
                );
            }

            private static void register(
                    BootstrapContext<Enchantment> context,
                    ResourceKey<Enchantment> key,
                    Enchantment.Builder builder
            ) {
                context.register(key, builder.build(key.location()));
            }

            public static void register() {
            }
        }
    """.trimIndent() + "\n"

    private fun targetTagsProvider(): String = """
        package com.simibubi.create.infrastructure.data;

        import java.util.concurrent.CompletableFuture;

        import org.jetbrains.annotations.Nullable;

        import com.simibubi.create.AllEnchantments;
        import com.simibubi.create.Create;

        import net.minecraft.core.HolderLookup;
        import net.minecraft.core.HolderLookup.Provider;
        import net.minecraft.data.PackOutput;
        import net.minecraft.data.tags.EnchantmentTagsProvider;
        import net.minecraft.tags.EnchantmentTags;

        import net.neoforged.neoforge.common.data.ExistingFileHelper;

        public class CreateEnchantmentTagsProvider extends EnchantmentTagsProvider {
            public CreateEnchantmentTagsProvider(
                    PackOutput output,
                    CompletableFuture<HolderLookup.Provider> lookupProvider,
                    @Nullable ExistingFileHelper existingFileHelper
            ) {
                super(output, lookupProvider, Create.ID, existingFileHelper);
            }

            @Override
            protected void addTags(Provider provider) {
                tag(EnchantmentTags.NON_TREASURE)
                        .add(AllEnchantments.CAPACITY, AllEnchantments.POTATO_RECOVERY);
                tag(EnchantmentTags.IN_ENCHANTING_TABLE)
                        .add(AllEnchantments.CAPACITY, AllEnchantments.POTATO_RECOVERY);
            }
        }
    """.trimIndent() + "\n"

    private fun parse(parser: JavaParser, source: String, path: Path): CompilationUnit {
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "Cannot parse $path: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
                        .ifBlank { "unknown parse failure" }
            )
        }
        return result.result.orElseThrow()
    }

    private fun requireClass(
        unit: CompilationUnit,
        packageName: String,
        className: String
    ): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
            abort("$className source has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("$className source must contain one top-level class")
        if (owner.nameAsString != className || owner.isInterface) {
            abort("$className source owns an unexpected top-level type")
        }
        return owner
    }

    private fun writeAtomicFamily(
        outputs: Map<Path, String>,
        originalBytes: Map<Path, ByteArray?>,
        move: (Path, Path) -> Unit
    ) {
        val stagedOutputs = linkedMapOf<Path, Path>()
        val stagedOriginals = linkedMapOf<Path, Path>()
        val committed = mutableListOf<Path>()
        val preservedRollbackStages = mutableSetOf<Path>()
        try {
            for ((path, source) in outputs.entries.sortedBy { it.key.toString() }) {
                val outputStage =
                    Files.createTempFile(path.parent, ".modporter-create-enchant-stage-", ".java")
                stagedOutputs[path] = outputStage
                Files.writeString(outputStage, source, StandardCharsets.UTF_8)
                originalBytes.getValue(path)?.let { original ->
                    val originalStage =
                        Files.createTempFile(
                            path.parent,
                            ".modporter-create-enchant-original-",
                            ".java"
                        )
                    stagedOriginals[path] = originalStage
                    Files.write(originalStage, original)
                }
            }
            for ((path, temporary) in stagedOutputs) {
                move(temporary, path)
                committed.add(path)
            }
        } catch (failure: Exception) {
            for (path in committed.asReversed()) {
                try {
                    val original = stagedOriginals[path]
                    if (original == null) {
                        Files.deleteIfExists(path)
                    } else {
                        move(original, path)
                    }
                } catch (rollbackFailure: Exception) {
                    stagedOriginals[path]?.let(preservedRollbackStages::add)
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        } finally {
            (stagedOutputs.values + stagedOriginals.values).forEach {
                if (it !in preservedRollbackStages) runCatching { Files.deleteIfExists(it) }
            }
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }

    private fun normalize(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private enum class Mode {
        LEGACY,
        TARGET,
        MISSING,
        OTHER
    }
}
