package com.modporter.core.transforms.text

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.PrimitiveType
import com.modporter.core.pipeline.*
import com.modporter.mapping.MappingDatabase
import com.modporter.mapping.TextReplacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

private data class PublicTypeRename(
    val file: Path,
    val packageName: String,
    val oldType: String,
    val newType: String
) {
    val oldFqn: String = "$packageName.$oldType"
    val newFqn: String = "$packageName.$newType"
}

/**
 * Pass 1: Text-based find-and-replace transformations.
 * Handles package renames, class renames, and other simple substitutions.
 * Order of replacements matters (more specific patterns first).
 */
class TextReplacementPass(
    private val mappingDb: MappingDatabase
) : Pass {
    override val name = "Text Replacement"
    override val order = 1

    private data class PreparedTextRule(val rule: TextReplacement, val pattern: Regex?)

    private inner class TextRuleSource(val source: String) {
        val executableOnlyCode by lazy { maskJavaCommentsAndLiterals(source) }
        val withStringArguments by lazy { maskJavaCommentsAndTextBlocks(source) }
    }

    override fun analyze(projectDir: Path): PassResult {
        return processFiles(projectDir, dryRun = true)
    }

    override fun apply(projectDir: Path): PassResult {
        return processFiles(projectDir, dryRun = false)
    }

    private fun processFiles(projectDir: Path, dryRun: Boolean): PassResult {
        // Combine explicit text replacements with auto-generated rules from class-renames
        val rules = buildRuleList().map { PreparedTextRule(it, if (it.isRegex) Regex(it.pattern) else null) }
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val publicTypeRenames = mutableListOf<PublicTypeRename>()

        val javaFiles = findJavaFiles(projectDir)
        logger.info { "Found ${javaFiles.size} Java files to process" }
        val legacyDyeableLeatherItemClasses = try {
            collectLegacyDyeableLeatherItemClasses(javaFiles)
        } catch (error: StackOverflowError) {
            throw IllegalStateException(
                "Text Replacement pre-scan collectLegacyDyeableLeatherItemClasses overflowed the Java regex stack",
                error
            )
        }
        val legacyLootCodecOwners = try {
            collectLegacyLootCodecOwners(javaFiles)
        } catch (error: StackOverflowError) {
            throw IllegalStateException(
                "Text Replacement pre-scan collectLegacyLootCodecOwners overflowed the Java regex stack",
                error
            )
        }

        val migratedLegacyCustomEnchantments = try {
            migrateLegacyCustomEnchantmentData(projectDir, javaFiles, changes, errors, dryRun)
        } catch (error: StackOverflowError) {
            throw IllegalStateException(
                "Text Replacement pre-scan migrateLegacyCustomEnchantmentData overflowed the Java regex stack",
                error
            )
        } catch (e: Exception) {
            errors.add("Error extracting legacy custom enchantment data: ${e.message}")
            logger.error(e) { "Error extracting legacy custom enchantment data" }
            emptyList()
        }

        for (file in javaFiles) {
            try {
                val result = processFile(
                    projectDir,
                    file,
                    rules,
                    dryRun,
                    errors,
                    legacyDyeableLeatherItemClasses,
                    legacyLootCodecOwners,
                    migratedLegacyCustomEnchantments.filter { it.file == file },
                    publicTypeRenames
                )
                changes.addAll(result)
            } catch (e: Exception) {
                errors.add("Error processing ${file}: ${e.message}")
                logger.error(e) { "Error processing $file" }
            }
        }

        changes.addAll(
            removeMigratedTopLevelCustomEnchantmentClasses(
                migratedLegacyCustomEnchantments,
                javaFiles,
                dryRun,
                errors
            )
        )

        changes.addAll(
            synchronizeRenamedPublicTypes(projectDir, publicTypeRenames, dryRun, errors)
        )

        return PassResult(name, changes, errors)
    }

    private fun processFile(
        projectDir: Path,
        file: Path,
        rules: List<PreparedTextRule>,
        dryRun: Boolean,
        errors: MutableList<String>,
        legacyDyeableLeatherItemClasses: Set<String>,
        legacyLootCodecOwners: Set<String>,
        legacyCustomEnchantments: List<LegacyCustomEnchantmentRegistration>,
        publicTypeRenames: MutableList<PublicTypeRename>
    ): List<Change> {
        val originalContent = file.readText()
        var content = originalContent
        val changes = mutableListOf<Change>()
        val tierIncorrectTagResources = mutableListOf<TierIncorrectTagResource>()
        val relativePath = projectDir.relativize(file).toString().replace('\\', '/')

        val networkHooksOpenScreen = migrateNetworkHooksOpenScreen(content, file, projectDir)
        content = networkHooksOpenScreen.content
        changes.addAll(networkHooksOpenScreen.changes)
        errors.addAll(networkHooksOpenScreen.errors)

        val beforeForgeInternalNames = content
        content = migrateForgeInternalNameDescriptors(content)
        if (content != beforeForgeInternalNames) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate Forge JVM internal names in mixin descriptors to NeoForge owners",
                    before = "Lnet/minecraftforge/...;",
                    after = "Lnet/neoforged/...;",
                    confidence = Confidence.HIGH,
                    ruleId = "forge-internal-name-descriptors"
                )
            )
        }

        if (relativePath.startsWith("src/test/java/")) {
            val beforeTestSourceAssertions = content
            content = migrateTestSourceForgeAssertions(content)
            if (content != beforeTestSourceAssertions) {
                changes.add(
                    Change(
                        file = file,
                        line = 1,
                        description = "Migrate source-level test assertions from Forge symbols to NeoForge symbols",
                        before = "test assertions mention net.minecraftforge or MinecraftForge",
                        after = "test assertions mention NeoForge packages and NeoForge.EVENT_BUS",
                        confidence = Confidence.HIGH,
                        ruleId = "test-source-forge-assertions"
                    )
                )
            }
        }

        val beforeCatnipPlatformServices = content
        content = migrateCatnipLoaderPlatformServices(content)
        if (content != beforeCatnipPlatformServices) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate Catnip's Forge loader service owner to its NeoForge owner",
                    before = "net.createmod.catnip.platform.ForgeCatnipServices",
                    after = "net.createmod.catnip.platform.NeoForgeCatnipServices",
                    confidence = Confidence.HIGH,
                    ruleId = "catnip-neoforge-platform-services"
                )
            )
        }

        val beforeCatnipRegistryHelper = content
        content = migrateCatnipRegistryKeyHelper(content)
        if (content != beforeCatnipRegistryHelper) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate Catnip registry key lookups to the 1.21 registered-object helper",
                    before = "CatnipServices.REGISTRIES.getKeyOrThrow / ::getKeyOrThrow",
                    after = "RegisteredObjectsHelper.getKeyOrThrow / ::getKeyOrThrow",
                    confidence = Confidence.HIGH,
                    ruleId = "catnip-registered-objects-key-helper"
                )
            )
        }

        val beforeInventoryRecipeHolder = content
        content = migrateInventoryRecipeHolderInterface(content)
        if (content != beforeInventoryRecipeHolder) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate container RecipeHolder interface to RecipeCraftingHolder",
                    before = "net.minecraft.world.inventory.RecipeHolder implements RecipeHolder",
                    after = "net.minecraft.world.inventory.RecipeCraftingHolder implements RecipeCraftingHolder",
                    confidence = Confidence.HIGH,
                    ruleId = "inventory-recipeholder-recipecraftingholder"
                )
            )
        }

        val beforeModelRenderPackedColor = content
        content = migrateModelRenderPackedColorText(content)
        if (content != beforeModelRenderPackedColor) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy model render RGBA floats to packed ARGB color",
                    before = "renderToBuffer(..., float red, float green, float blue, float alpha)",
                    after = "renderToBuffer(..., int color) and FastColor.ARGB32.colorFromFloat(...)",
                    confidence = Confidence.HIGH,
                    ruleId = "model-render-packed-color-executable"
                )
            )
        }

        val beforeLegacyGuiOverlayContext = content
        content = migrateLegacyGuiOverlayRenderContext(content)
        if (content != beforeLegacyGuiOverlayContext) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Bind the removed legacy GUI overlay parameter to the Minecraft GUI instance",
                    before = "IGuiOverlay.render(ForgeGui gui, GuiGraphics graphics, ...)",
                    after = "LayeredDraw.Layer.render(GuiGraphics graphics, DeltaTracker deltaTracker) with Minecraft.getInstance().gui",
                    confidence = Confidence.HIGH,
                    ruleId = "gui-overlay-render-context"
                )
            )
        }

        val beforeLegacyGuiOverlayType = content
        content = migrateLegacyGuiOverlayType(content)
        if (content != beforeLegacyGuiOverlayType) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate the removed Forge GUI overlay interface to the vanilla layered-draw contract",
                    before = "IGuiOverlay",
                    after = "LayeredDraw.Layer",
                    confidence = Confidence.HIGH,
                    ruleId = "gui-overlay-layer-owner"
                )
            )
        }

        val beforeRemovedHandReferences = content
        content = migrateUseWithoutItemRemovedHandReferences(content)
        if (content != beforeRemovedHandReferences) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Replace removed useWithoutItem hand references with the main-hand contract",
                    before = "hand / handIn references after useWithoutItem signature migration",
                    after = "InteractionHand.MAIN_HAND and Player#getMainHandItem",
                    confidence = Confidence.HIGH,
                    ruleId = "usewithoutitem-main-hand-context"
                )
            )
        }

        var ruleSource = TextRuleSource(content)
        for ((rule, pattern) in rules) {
            val replacement = try {
                replaceTextRuleInExecutableJava(ruleSource, rule, pattern)
            } catch (error: StackOverflowError) {
                throw IllegalStateException(
                    "Text replacement rule ${rule.id} overflowed the Java regex stack while scanning ${file.toAbsolutePath()}",
                    error
                )
            }
            replacement.changes.forEach { change ->
                changes.add(
                    Change(
                        file = file,
                        line = content.lineNumberAt(change.range.first),
                        description = rule.description,
                        before = content.lineAt(change.range.first).trim(),
                        after = change.replacement.trim(),
                        confidence = Confidence.HIGH,
                        ruleId = rule.id
                    )
                )
            }
            if (replacement.content != content) ruleSource = TextRuleSource(replacement.content)
            content = replacement.content
        }

        val beforeMobEffectInstanceSave = content
        content = migrateMobEffectInstanceSaveCalls(content)
        if (content != beforeMobEffectInstanceSave) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate MobEffectInstance NBT save calls to the 1.21 no-arg save API",
                    before = "mobEffectInstance.save(new CompoundTag())",
                    after = "mobEffectInstance.save()",
                    confidence = Confidence.HIGH,
                    ruleId = "mobeffectinstance-save-noarg"
                )
            )
        }

        val beforeItemPropertiesUseDuration = content
        content = migrateItemPropertiesUseDurationCalls(content)
        if (content != beforeItemPropertiesUseDuration) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate ItemProperties item-stack use duration calls to LivingEntity-aware 1.21 API",
                    before = "ItemProperties.register(..., (stack, world, entity, seed) -> stack.getUseDuration())",
                    after = "stack.getUseDuration(entity)",
                    confidence = Confidence.HIGH,
                    ruleId = "itemproperties-use-duration-livingentity"
                )
            )
        }

        val beforeLegacyEnchantmentSubclasses = content
        content = removeLegacyPrivateStaticEnchantmentSubclasses(content)
        if (content != beforeLegacyEnchantmentSubclasses) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Remove legacy private static custom Enchantment subclasses; 1.21 enchantments are data-driven",
                    before = "private static class ... extends Enchantment",
                    after = "",
                    confidence = Confidence.HIGH,
                    ruleId = "custom-enchantment-remove-subclasses"
                )
            )
        }

        val beforeRemainingRegistryObjectWildcards = content
        content = migrateRemainingRegistryObjectWildcardHolders(content)
        if (content != beforeRemainingRegistryObjectWildcards) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate remaining RegistryObject wildcard holder parameters to DeferredHolder",
                    before = "RegistryObject<? extends T>",
                    after = "DeferredHolder<T, ? extends T>",
                    confidence = Confidence.HIGH,
                    ruleId = "registryobject-wildcard-deferredholder"
                )
            )
        }

        val beforeTagManager = content
        content = migrateRemovedTagManagerAccess(content)
        if (content != beforeTagManager) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate removed ITagManager item tag access to Registry.getTagOrEmpty",
                    before = "ITagManager<Item> tags = BuiltInRegistries.ITEM.tags(); tags.getTag(tag)",
                    after = "BuiltInRegistries.ITEM.getTagOrEmpty(tag)",
                    confidence = Confidence.HIGH,
                    ruleId = "itagmanager-item-gettagorempty"
                )
            )
        }

        val beforeCustomEnchantments = content
        content = migrateAllCustomEnchantmentResourceKeys(content, legacyCustomEnchantments)
        if (content != beforeCustomEnchantments) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate custom enchantment Java registrations to ResourceKey constants",
                    before = "DeferredRegister or Registrate custom Enchantment entries",
                    after = "ResourceKey<Enchantment> constants resolved through Holder lookup",
                    confidence = Confidence.HIGH,
                    ruleId = "custom-enchantment-resourcekey-context"
                )
            )
        }

        val beforeEnchantmentCategoryRuntime = content
        content = migrateEnchantmentCategoryRuntimeChecks(content)
        if (content != beforeEnchantmentCategoryRuntime) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy EnchantmentCategory runtime checks to holder-based item support checks",
                    before = "Enchantment.category == EnchantmentCategory.* || enchantment.canEnchant(stack)",
                    after = "stack.supportsEnchantment(Holder<Enchantment>)",
                    confidence = Confidence.HIGH,
                    ruleId = "enchantment-category-runtime-holder-support"
                )
            )
        }

        val beforeParticleOptions = content
        content = migrateParticleOptionsCodecs(content)
        if (content != beforeParticleOptions) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate ParticleOptions deserializer implementation to MapCodec and StreamCodec",
                    before = "ParticleOptions.Deserializer",
                    after = "ParticleOptions CODEC/STREAM_CODEC",
                    confidence = Confidence.HIGH,
                    ruleId = "particle-options-codec-streamcodec"
                )
            )
        }

        val beforeParticleTypes = content
        content = migrateParticleTypeRegistrations(content)
        if (content != beforeParticleTypes) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate custom ParticleType registrations to codec and streamCodec overrides",
                    before = "new ParticleType<>(..., new Data.Deserializer())",
                    after = "new ParticleType<>(...) with codec() and streamCodec()",
                    confidence = Confidence.HIGH,
                    ruleId = "particle-type-codec-streamcodec"
                )
            )
        }

        val beforeParticleNetwork = content
        content = migrateParticleNetworkCodecs(content)
        if (content != beforeParticleNetwork) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate particle packet serialization to ParticleType streamCodec",
                    before = "ParticleOptions.writeToNetwork / ParticleType.getDeserializer",
                    after = "ParticleType.streamCodec encode/decode",
                    confidence = Confidence.HIGH,
                    ruleId = "particle-network-streamcodec"
                )
            )
        }

        val beforePartialNbt = content
        content = migratePartialNbtIngredients(content)
        if (content != beforePartialNbt) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate PartialNBTIngredient helpers to DataComponentIngredient",
                    before = "PartialNBTIngredient.of(... CompoundTag ...)",
                    after = "DataComponentIngredient.of(... DataComponents ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "partial-nbt-ingredient-data-components"
                )
            )
        }

        val beforeSingleItemResult = content
        content = migrateSingleItemRecipeBuilderResults(content)
        if (content != beforeSingleItemResult) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate SingleItemRecipeBuilder.Result wrappers to RecipeOutput save calls",
                    before = "consumer.accept(stonecutting(...)); class Wrapper extends SingleItemRecipeBuilder.Result",
                    after = "stonecutting(output, ...); SingleItemRecipeBuilder.save(output, id)",
                    confidence = Confidence.HIGH,
                    ruleId = "single-item-recipe-result-recipeoutput"
                )
            )
        }

        val beforeDyeableLeather = content
        content = migrateDyeableLeatherItemColors(content, legacyDyeableLeatherItemClasses)
        if (content != beforeDyeableLeather) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate removed DyeableLeatherItem methods to DyedItemColor data components",
                    before = "DyeableLeatherItem + display/color CustomData",
                    after = "DataComponents.DYED_COLOR + DyedItemColor",
                    confidence = Confidence.HIGH,
                    ruleId = "dyeable-leatheritem-dyed-component"
                )
            )
        }

        val beforeTierSorting = content
        content = migrateTierSortingRegistryTiers(content, tierIncorrectTagResources, projectDir, file, errors)
        if (content != beforeTierSorting) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate TierSortingRegistry custom tiers to 1.21 SimpleTier incorrect-block tags",
                    before = "TierSortingRegistry.registerTier(new SimpleTier(miningLevel, ..., needsTag, repair), ...)",
                    after = "new SimpleTier(incorrectBlocksForDrops, ..., repair)",
                    confidence = Confidence.HIGH,
                    ruleId = "tiersortingregistry-simpletier-incorrect-tags"
                )
            )
        }
        ensureTierIncorrectTagResources(projectDir, tierIncorrectTagResources, changes, dryRun)

        val beforeLootCodecs = content
        content = migrateLootSerializerCodecs(content, legacyLootCodecOwners)
        if (content != beforeLootCodecs) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy loot serializers to 1.21 MapCodec-backed loot types",
                    before = "Loot Serializer<T> inner classes and new LootItem*Type(new Serializer())",
                    after = "public static MapCodec<T> CODEC and new LootItem*Type(T.CODEC)",
                    confidence = Confidence.HIGH,
                    ruleId = "loot-serializer-mapcodec"
                )
            )
        }

        val beforeConditionCodecs = content
        content = migrateNeoForgeConditionSerializerCodecs(content)
        if (content != beforeConditionCodecs) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy NeoForge recipe condition serializers to MapCodec-backed ICondition",
                    before = "IConditionSerializer<T> inner class and ICondition#getID",
                    after = "ICondition#codec with public static MapCodec<T> CODEC",
                    confidence = Confidence.HIGH,
                    ruleId = "neoforge-condition-serializer-mapcodec"
                )
            )
        }

        val beforeJadeTooltipHelper = content
        content = migrateJadeTooltipElementHelper(content)
        if (content != beforeJadeTooltipHelper) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate Jade ITooltip element helper access to the static IElementHelper entrypoint",
                    before = "ITooltip variable getElementHelper() calls",
                    after = "IElementHelper.get()",
                    confidence = Confidence.HIGH,
                    ruleId = "jade-tooltip-elementhelper-static"
                )
            )
        }

        // Post-processing: clean up imports after all replacements
        content = cleanupImports(content, changes, file)

        detectPublicTypeRename(file, originalContent, content)?.let(publicTypeRenames::add)

        if (!dryRun && content != originalContent) {
            file.writeText(content)
        }

        return changes
    }

    /**
     * Post-replacement import cleanup:
     * - Remove stale imports for classes that no longer exist in NeoForge
     * - Remove duplicate imports
     * - Add missing imports for classes introduced by replacements
     */
    private fun cleanupImports(content: String, changes: MutableList<Change>, file: Path): String {
        var result = content

        // Remove stale imports that reference classes removed or renamed in NeoForge
        val staleImports = listOf(
            "import net.neoforged.neoforge.registries.ForgeRegistries;",
            "import net.neoforged.neoforge.registries.RegistryObject;",
            "import net.neoforged.neoforge.common.MinecraftForge;",
            "import net.neoforged.neoforge.network.simple.SimpleChannel;",
            "import net.neoforged.neoforge.network.NetworkRegistry;",
            "import net.neoforged.neoforge.network.NetworkDirection;",
            "import net.neoforged.neoforge.network.NetworkEvent;",
            "import net.neoforged.fml.common.Mod.EventBusSubscriber;",
            "import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;",
        )
        for (stale in staleImports) {
            if (result.contains(stale)) {
                result = removeImportLine(result, stale.removePrefix("import ").removeSuffix(";"))
            }
        }
        val fmlLoadingContext = "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext"
        if (result.contains("import $fmlLoadingContext;")) {
            val withoutImport = removeImportLine(result, fmlLoadingContext)
            val stillUsed = Regex("""(?<![\w$.])FMLJavaModLoadingContext\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(withoutImport))
            if (!stillUsed) result = withoutImport
        }
        if (!maskJavaCommentsAndLiterals(result).contains("NetworkHooks.")) {
            result = removeImportLine(result, "net.neoforged.neoforge.network.NetworkHooks")
            result = removeImportLine(result, "net.minecraftforge.network.NetworkHooks")
        }

        if (!result.contains("ContextNbtProvider.")) {
            result = removeImportLine(result, "net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider")
        }
        if (result.contains("IGlobalLootModifier") || result.contains("GLOBAL_LOOT_MODIFIER_SERIALIZERS")) {
            result = result.replace("MapCodec<? extends ChunkGenerator>", "MapCodec<? extends IGlobalLootModifier>")
            result = result.replace("MapMapCodec", "MapCodec")
            if (!result.contains("ChunkGenerator")) {
                result = removeImportLine(result, "net.minecraft.world.level.chunk.ChunkGenerator")
            }
        }

        if (result.contains("import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;")) {
            result = result.replace(Regex("""\bLivingDamageEvent\.Post\b"""), "Post")
        }
        result = result.replace(
            Regex("""(?m)^[ \t]*//\s*(?:\[[^\]\r\n]+]\s*)?import\s+net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor;\s*\r?\n"""),
            ""
        )
        result = result.replace(
            Regex("""(?m)^[ \t]*//\s*DistExecutor removed in NeoForge\s*\r?\n"""),
            ""
        )
        result = result.replace(
            "import net.neoforged.neoforge.event.entity.living.Post;",
            "import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;"
        )
        result = result.replace(
            Regex("""@Override\s*\r?\n\s*(@SuppressWarnings\("deprecation"\)\s*\r?\n\s*)@Override"""),
            "$1@Override"
        )

        // Add missing imports based on what symbols are used in the code
        val missingImports = mutableListOf<String>()

        if (result.contains("BuiltInRegistries.") && !result.contains("import net.minecraft.core.registries.BuiltInRegistries;")) {
            missingImports.add("import net.minecraft.core.registries.BuiltInRegistries;")
        }
        if (result.contains("NeoForgeRegistries.") && !result.contains("import net.neoforged.neoforge.registries.NeoForgeRegistries;")) {
            missingImports.add("import net.neoforged.neoforge.registries.NeoForgeRegistries;")
        }
        if (result.contains("IElementHelper.") && !result.contains("import snownee.jade.api.ui.IElementHelper;")) {
            missingImports.add("import snownee.jade.api.ui.IElementHelper;")
        }
        // ModContainer import is added by the AST pass when modifying @Mod constructors
        if (result.contains("DeferredHolder") && !result.contains("import net.neoforged.neoforge.registries.DeferredHolder;")) {
            missingImports.add("import net.neoforged.neoforge.registries.DeferredHolder;")
        }
        if (result.contains("@EventBusSubscriber") && !result.contains("import net.neoforged.fml.common.EventBusSubscriber;")) {
            missingImports.add("import net.neoforged.fml.common.EventBusSubscriber;")
        }
        if (result.contains("IPayloadContext") && !result.contains("import net.neoforged.neoforge.network.handling.IPayloadContext;")) {
            missingImports.add("import net.neoforged.neoforge.network.handling.IPayloadContext;")
        }
        if (result.contains("PacketDistributor.send") && !result.contains("import net.neoforged.neoforge.network.PacketDistributor;")) {
            missingImports.add("import net.neoforged.neoforge.network.PacketDistributor;")
        }
        if (result.contains("(ServerPlayer)") && !result.contains("import net.minecraft.server.level.ServerPlayer;")) {
            missingImports.add("import net.minecraft.server.level.ServerPlayer;")
        }
        if (result.contains("CustomPacketPayload") && !result.contains("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")) {
            missingImports.add("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")
        }
        if (result.contains("RenderGuiLayerEvent") && !result.contains("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;")) {
            missingImports.add("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;")
        }
        if (Regex("\\bDeltaTracker\\b").containsMatchIn(result) && !result.contains("import net.minecraft.client.DeltaTracker;")) {
            missingImports.add("import net.minecraft.client.DeltaTracker;")
        }
        if (result.contains("FastColor.ARGB32") && !result.contains("import net.minecraft.util.FastColor;")) {
            missingImports.add("import net.minecraft.util.FastColor;")
        }
        if (result.contains("SynchedEntityData.Builder") && !result.contains("import net.minecraft.network.syncher.SynchedEntityData;")) {
            missingImports.add("import net.minecraft.network.syncher.SynchedEntityData;")
        }
        if (result.contains("MapCodec") && !result.contains("import com.mojang.serialization.MapCodec;")) {
            missingImports.add("import com.mojang.serialization.MapCodec;")
        }
        if (result.contains("StreamCodec") && !result.contains("import net.minecraft.network.codec.StreamCodec;")) {
            missingImports.add("import net.minecraft.network.codec.StreamCodec;")
        }
        if (result.contains("RegistryFriendlyByteBuf") && !result.contains("import net.minecraft.network.RegistryFriendlyByteBuf;")) {
            missingImports.add("import net.minecraft.network.RegistryFriendlyByteBuf;")
        }
        if (result.contains("ByteBufCodecs.") && !result.contains("import net.minecraft.network.codec.ByteBufCodecs;")) {
            missingImports.add("import net.minecraft.network.codec.ByteBufCodecs;")
        }
        if (result.contains("CopyComponentsFunction.") && !result.contains("import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;")) {
            missingImports.add("import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;")
        }
        if (result.contains("CopyCustomDataFunction.") && !result.contains("import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;")) {
            missingImports.add("import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;")
        }
        if (result.contains("DataComponents.") && !result.contains("import net.minecraft.core.component.DataComponents;")) {
            missingImports.add("import net.minecraft.core.component.DataComponents;")
        }
        if (result.contains("DyedItemColor") && !result.contains("import net.minecraft.world.item.component.DyedItemColor;")) {
            missingImports.add("import net.minecraft.world.item.component.DyedItemColor;")
        }
        if (result.contains("DataComponentIngredient.") && !result.contains("import net.neoforged.neoforge.common.crafting.DataComponentIngredient;")) {
            missingImports.add("import net.neoforged.neoforge.common.crafting.DataComponentIngredient;")
        }
        if (result.contains("RecipeCategory.") && !result.contains("import net.minecraft.data.recipes.RecipeCategory;") &&
            !result.contains("import net.minecraft.data.recipes.*;")) {
            missingImports.add("import net.minecraft.data.recipes.RecipeCategory;")
        }
        if (result.contains("PotionContents") && !result.contains("import net.minecraft.world.item.alchemy.PotionContents;")) {
            missingImports.add("import net.minecraft.world.item.alchemy.PotionContents;")
        }
        if (result.contains("BlockBehaviour.Properties") && !result.contains("import net.minecraft.world.level.block.state.BlockBehaviour;")) {
            missingImports.add("import net.minecraft.world.level.block.state.BlockBehaviour;")
        }
        if (result.contains("Tesselator.getInstance()") && !result.contains("import com.mojang.blaze3d.vertex.Tesselator;")) {
            missingImports.add("import com.mojang.blaze3d.vertex.Tesselator;")
        }
        if (Regex("\\bMeshData\\b").containsMatchIn(result) &&
            !result.contains("import com.mojang.blaze3d.vertex.MeshData;") &&
            !result.contains("import com.mojang.blaze3d.vertex.*;")) {
            missingImports.add("import com.mojang.blaze3d.vertex.MeshData;")
        }
        if (result.contains("EntityTypeTags.") && !result.contains("import net.minecraft.tags.EntityTypeTags;")) {
            missingImports.add("import net.minecraft.tags.EntityTypeTags;")
        }
        if (result.contains("HolderLookup.Provider") && !result.contains("import net.minecraft.core.HolderLookup;")) {
            missingImports.add("import net.minecraft.core.HolderLookup;")
        }
        if (result.contains("SavedData.Factory") && !result.contains("import net.minecraft.world.level.saveddata.SavedData;")) {
            missingImports.add("import net.minecraft.world.level.saveddata.SavedData;")
        }
        if (result.contains("AdvancementHolder") && !result.contains("import net.minecraft.advancements.AdvancementHolder;")) {
            missingImports.add("import net.minecraft.advancements.AdvancementHolder;")
        }
        if (result.contains("ClientInformation.createDefault()") && !result.contains("import net.minecraft.server.level.ClientInformation;")) {
            missingImports.add("import net.minecraft.server.level.ClientInformation;")
        }
        if (result.contains("CommonListenerCookie.createInitial(") && !result.contains("import net.minecraft.server.network.CommonListenerCookie;")) {
            missingImports.add("import net.minecraft.server.network.CommonListenerCookie;")
        }
        if (result.contains("GameType.SURVIVAL") && !result.contains("import net.minecraft.world.level.GameType;")) {
            missingImports.add("import net.minecraft.world.level.GameType;")
        }
        if (result.contains("InteractionHand.MAIN_HAND") && !result.contains("import net.minecraft.world.InteractionHand;")) {
            missingImports.add("import net.minecraft.world.InteractionHand;")
        }
        if (result.contains("EquipmentSlot.MAINHAND") && !result.contains("import net.minecraft.world.entity.EquipmentSlot;")) {
            missingImports.add("import net.minecraft.world.entity.EquipmentSlot;")
        }
        if (result.contains("ParticleType<?>") && !result.contains("import net.minecraft.core.particles.ParticleType;")) {
            missingImports.add("import net.minecraft.core.particles.ParticleType;")
        }
        if (result.contains("SpawnPlacementTypes.") &&
            !result.contains("import net.minecraft.world.entity.SpawnPlacementTypes;") &&
            !result.contains("import net.minecraft.world.entity.*;")) {
            missingImports.add("import net.minecraft.world.entity.SpawnPlacementTypes;")
        }
        if (result.contains("DeferredHolder<Fluid,") && !result.contains("import net.minecraft.world.level.material.Fluid;")) {
            missingImports.add("import net.minecraft.world.level.material.Fluid;")
        }
        if (result.contains("@Nullable LivingEntity") && !result.contains("import net.minecraft.world.entity.LivingEntity;")) {
            missingImports.add("import net.minecraft.world.entity.LivingEntity;")
        }
        if (Regex("\\bLevelReader\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.level.LevelReader;")) {
            missingImports.add("import net.minecraft.world.level.LevelReader;")
        }
        if (Regex("\\bHolder<").containsMatchIn(result) && !result.contains("import net.minecraft.core.Holder;")) {
            missingImports.add("import net.minecraft.core.Holder;")
        }
        if (Regex("\\bRegistryAccess\\b").containsMatchIn(result) && !result.contains("import net.minecraft.core.RegistryAccess;")) {
            missingImports.add("import net.minecraft.core.RegistryAccess;")
        }
        if (result.contains("ResourceKey<Level>") && !result.contains("import net.minecraft.resources.ResourceKey;")) {
            missingImports.add("import net.minecraft.resources.ResourceKey;")
        }
        if (result.contains("ResourceKey<Level>") && !result.contains("import net.minecraft.world.level.Level;")) {
            missingImports.add("import net.minecraft.world.level.Level;")
        }
        if (result.contains("ResourceKey<Enchantment>") &&
            !result.contains("import net.minecraft.world.item.enchantment.Enchantment;") &&
            !Regex("""\b(?:class|record|interface|enum)\s+Enchantment\b""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
            missingImports.add("import net.minecraft.world.item.enchantment.Enchantment;")
        }
        if (Regex("\\bRegistries\\.").containsMatchIn(result) && !result.contains("import net.minecraft.core.registries.Registries;")) {
            missingImports.add("import net.minecraft.core.registries.Registries;")
        }
        if (result.contains("ServerLifecycleHooks.") && !result.contains("import net.neoforged.neoforge.server.ServerLifecycleHooks;")) {
            missingImports.add("import net.neoforged.neoforge.server.ServerLifecycleHooks;")
        }
        if (result.contains("RecipeHolder::value") && !result.contains("import net.minecraft.world.item.crafting.RecipeHolder;")) {
            missingImports.add("import net.minecraft.world.item.crafting.RecipeHolder;")
        }
        if (Regex("\\bSingleRecipeInput\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;")) {
            missingImports.add("import net.minecraft.world.item.crafting.SingleRecipeInput;")
        }
        if (Regex("\\bCacheableFunction\\b").containsMatchIn(result) && !result.contains("import net.minecraft.commands.CacheableFunction;")) {
            missingImports.add("import net.minecraft.commands.CacheableFunction;")
        }
        if (Regex("\\bCraftingInput\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.item.crafting.CraftingInput;")) {
            missingImports.add("import net.minecraft.world.item.crafting.CraftingInput;")
        }
        if (Regex("\\bRegistry<").containsMatchIn(result) && !result.contains("import net.minecraft.core.Registry;")) {
            missingImports.add("import net.minecraft.core.Registry;")
        }
        if (Regex("(?<![\\w$.])ShapedRecipe\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.item.crafting.ShapedRecipe;") &&
            !result.contains("import net.minecraft.world.item.crafting.*;")
        ) {
            missingImports.add("import net.minecraft.world.item.crafting.ShapedRecipe;")
        }
        if (Regex("\\bEmptyItemHandler\\.").containsMatchIn(result) && !result.contains("import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;")) {
            missingImports.add("import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;")
        }
        // A source that imports another Supplier (Guava's) keeps it; a second import would make every Supplier ambiguous.
        val supplierAlreadyImported = Regex(
            """(?m)^\s*import\s+(?!static\b)[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.Supplier\s*;"""
        ).containsMatchIn(maskJavaCommentsAndLiterals(result))
        if (Regex("\\bSupplier<").containsMatchIn(result) && !supplierAlreadyImported) {
            missingImports.add("import java.util.function.Supplier;")
        }


        if (missingImports.isNotEmpty()) {
            // Insert after the last existing import line
            val lines = result.lines().toMutableList()
            val lastImportIdx = lines.indexOfLast { it.trimStart().startsWith("import ") }
            if (lastImportIdx >= 0) {
                for ((i, imp) in missingImports.distinct().withIndex()) {
                    lines.add(lastImportIdx + 1 + i, imp)
                }
                result = lines.joinToString("\n")
            } else {
                val packageIdx = lines.indexOfFirst { it.trimStart().startsWith("package ") }
                var insertIdx = if (packageIdx >= 0) packageIdx + 1 else 0
                if (packageIdx >= 0 && insertIdx < lines.size && lines[insertIdx].isNotBlank()) {
                    lines.add(insertIdx, "")
                    insertIdx++
                }
                for ((i, imp) in missingImports.distinct().withIndex()) {
                    lines.add(insertIdx + i, imp)
                }
                val afterImports = insertIdx + missingImports.distinct().size
                if (afterImports < lines.size && lines[afterImports].isNotBlank()) {
                    lines.add(afterImports, "")
                }
                result = lines.joinToString("\n")
            }
        }

        return dedupeImports(result)
    }

    private fun migrateCatnipRegistryKeyHelper(source: String): String {
        val oldOwner = "net.createmod.catnip.platform.CatnipServices"
        val newOwner = "net.createmod.catnip.registry.RegisteredObjectsHelper"
        val executable = maskJavaCommentsAndLiterals(source)
        val importsOldOwner = Regex(
            """(?m)^\s*import\s+${Regex.escape(oldOwner)}\s*;\s*$"""
        ).containsMatchIn(executable)
        val usesQualifiedOldOwner = executable.contains("$oldOwner.REGISTRIES")
        if (!importsOldOwner && !usesQualifiedOldOwner) return source

        var result = source
        val owners = buildList {
            if (importsOldOwner) add("CatnipServices")
            add(oldOwner)
        }
        owners.forEach { owner ->
            result = replaceExecutableRegex(
                result,
                Regex("""\b${Regex.escape(owner)}\s*\.\s*REGISTRIES\s*\.\s*getKeyOrThrow\b""")
            ) { "$newOwner.getKeyOrThrow" }
            result = replaceExecutableRegex(
                result,
                Regex("""\b${Regex.escape(owner)}\s*\.\s*REGISTRIES\s*::\s*getKeyOrThrow\b""")
            ) { "$newOwner::getKeyOrThrow" }
        }
        if (!maskJavaCommentsAndLiterals(result).contains("CatnipServices.")) {
            result = removeImportLine(result, oldOwner)
        }
        return result
    }

    private fun migrateCatnipLoaderPlatformServices(source: String): String {
        val oldOwner = "net.createmod.catnip.platform.ForgeCatnipServices"
        val newOwner = "net.createmod.catnip.platform.NeoForgeCatnipServices"
        val executable = maskJavaCommentsAndLiterals(source)
        val importsOldOwner = Regex(
            """(?m)^\s*import\s+${Regex.escape(oldOwner)}\s*;\s*$"""
        ).containsMatchIn(executable)
        val usesQualifiedOldOwner = executable.contains(oldOwner)
        if (!importsOldOwner && !usesQualifiedOldOwner) return source

        var result = replaceExecutableRegex(
            source,
            Regex("""\b${Regex.escape(oldOwner)}\b""")
        ) { newOwner }
        if (importsOldOwner) {
            result = replaceExecutableRegex(
                result,
                Regex("""\bForgeCatnipServices\b""")
            ) { "NeoForgeCatnipServices" }
        }
        return result
    }

    private fun detectPublicTypeRename(file: Path, before: String, after: String): PublicTypeRename? {
        val oldType = publicTopLevelType(before) ?: return null
        val newType = publicTopLevelType(after) ?: return null
        if (oldType == newType || file.nameWithoutExtension != oldType) return null
        val oldPackage = javaPackage(before) ?: return null
        val newPackage = javaPackage(after) ?: return null
        if (oldPackage != newPackage) return null
        return PublicTypeRename(file, oldPackage, oldType, newType)
    }

    private fun publicTopLevelType(source: String): String? =
        Regex(
            """\bpublic\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\s+)*(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)\b"""
        ).find(maskJavaCommentsAndLiterals(source))?.groupValues?.get(1)

    private fun javaPackage(source: String): String? =
        Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
            .find(maskJavaCommentsAndLiterals(source))?.groupValues?.get(1)

    private fun synchronizeRenamedPublicTypes(
        projectDir: Path,
        requested: List<PublicTypeRename>,
        dryRun: Boolean,
        errors: MutableList<String>
    ): List<Change> {
        if (requested.isEmpty()) return emptyList()
        val renames = requested.distinctBy { it.file }
        val accepted = mutableListOf<PublicTypeRename>()
        val changes = mutableListOf<Change>()
        for (rename in renames) {
            val target = rename.file.resolveSibling("${rename.newType}.java")
            if (target != rename.file && target.exists()) {
                errors += "Cannot synchronize renamed public type ${rename.oldFqn} -> ${rename.newFqn}: target file already exists: $target"
                continue
            }
            accepted += rename
            changes += Change(
                file = rename.file,
                line = 1,
                description = "Synchronize Java file name with a structurally renamed public top-level type",
                before = "${rename.oldType}.java / public ${rename.oldType}",
                after = "${rename.newType}.java / public ${rename.newType}",
                confidence = Confidence.HIGH,
                ruleId = "text-sync-public-type-file-name"
            )
            if (!dryRun) Files.move(rename.file, target)
        }
        if (accepted.isEmpty()) return changes

        val resourceRoots = listOf(
            projectDir.resolve("src/main/resources"),
            projectDir.resolve("src/generated/resources")
        ).filter(Path::exists)
        val json = Json { prettyPrint = true }
        for (root in resourceRoots) {
            Files.walk(root).use { paths ->
                paths.filter { it.extension.equals("json", ignoreCase = true) }
                    .forEach { resource ->
                        val original = resource.readText()
                        if (accepted.none { original.contains(it.oldType) }) return@forEach
                        val parsed = runCatching { json.parseToJsonElement(original) }.getOrElse { error ->
                            errors += "Cannot parse JSON while synchronizing renamed public types in $resource: ${error.message}"
                            return@forEach
                        }
                        val mixinPackage = (parsed as? JsonObject)
                            ?.get("package")
                            ?.jsonPrimitive
                            ?.contentOrNull
                        val replacements = buildMap {
                            for (rename in accepted) {
                                put(rename.oldFqn, rename.newFqn)
                                if (mixinPackage != null && rename.oldFqn.startsWith("$mixinPackage.")) {
                                    put(
                                        rename.oldFqn.removePrefix("$mixinPackage."),
                                        rename.newFqn.removePrefix("$mixinPackage.")
                                    )
                                }
                            }
                        }
                        val rewritten = rewriteJsonStrings(parsed, replacements)
                        if (rewritten == parsed) return@forEach
                        changes += Change(
                            file = resource,
                            line = 1,
                            description = "Synchronize structured resource references with renamed public Java types",
                            before = replacements.keys.joinToString(),
                            after = replacements.values.joinToString(),
                            confidence = Confidence.HIGH,
                            ruleId = "text-sync-public-type-resource-reference"
                        )
                        if (!dryRun) {
                            resource.writeText(json.encodeToString(JsonElement.serializer(), rewritten) + "\n")
                        }
                    }
            }
        }
        return changes
    }

    private fun rewriteJsonStrings(element: JsonElement, replacements: Map<String, String>): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> rewriteJsonStrings(value, replacements) })
            is JsonArray -> JsonArray(element.map { value -> rewriteJsonStrings(value, replacements) })
            is JsonPrimitive -> if (element.isString) {
                replacements[element.contentOrNull]?.let(::JsonPrimitive) ?: element
            } else {
                element
            }
        }

    private fun migrateMobEffectInstanceSaveCalls(source: String): String {
        if (!source.contains("MobEffectInstance") || !source.contains(".save(")) return source
        val executableCode = maskJavaCommentsAndLiterals(source)
        val mobEffectInstanceNames = Regex("""\b(?:net\.minecraft\.world\.effect\.)?MobEffectInstance\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet()
        if (mobEffectInstanceNames.isEmpty()) return source
        var result = source
        for (name in mobEffectInstanceNames) {
            result = replaceExecutableRegex(
                result,
                Regex("""(?<![\w$])((?:this\.)?${Regex.escape(name)})\.save\s*\(\s*new\s+(?:net\.minecraft\.nbt\.)?CompoundTag\s*\(\s*\)\s*\)""")
            ) { match -> "${match.groupValues[1]}.save()" }
        }
        return result
    }

    private fun migrateRemainingRegistryObjectWildcardHolders(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("RegistryObject<")) return source
        return replaceExecutableRegex(
            source,
            Regex("""RegistryObject\s*<\s*\?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>""")
        ) { match ->
            val type = match.groupValues[1]
            "DeferredHolder<$type, ? extends $type>"
        }
    }

    private fun migrateParticleOptionsCodecs(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("ParticleOptions.Deserializer") ||
            !executableCode.contains("implements ParticleOptions") ||
            !executableCode.contains("writeToNetwork")) {
            return source
        }

        val classMatch = Regex("""public\s+class\s+([A-Za-z_$][\w$]*)\s+implements\s+ParticleOptions\s*\{""").find(executableCode)
            ?: return source
        val className = classMatch.groupValues[1]
        val fields = Regex("""(?m)^[ \t]*public\s+final\s+(int)\s+([A-Za-z_$][\w$]*);\s*$""")
            .findAll(executableCode)
            .map { ParticleField(it.groupValues[1], it.groupValues[2]) }
            .toList()
        if (fields.isEmpty()) return source

        var result = source
        val recordParams = fields.joinToString(", ") { "${it.type} ${it.name}" }
        result = replaceExecutableRegex(
            result,
            Regex("""public\s+class\s+${Regex.escape(className)}\s+implements\s+ParticleOptions\s*\{"""),
        ) { "public record $className($recordParams) implements ParticleOptions {" }
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^[ \t]*public\s+final\s+int\s+[A-Za-z_$][\w$]*;\s*\r?\n""")
        ) { "" }

        val constructorParams = fields.joinToString("""\s*,\s*""") { """${Regex.escape(it.type)}\s+${Regex.escape(it.name)}""" }
        val constructorAssignments = fields.joinToString("""\s*""") {
            """this\.${Regex.escape(it.name)}\s*=\s*${Regex.escape(it.name)}\s*;"""
        }
        result = replaceExecutableRegex(
            result,
            """\r?\n\s*public\s+${Regex.escape(className)}\s*\(\s*$constructorParams\s*\)\s*\{\s*$constructorAssignments\s*\}\s*"""
                .toRegex()
        ) { "\n" }

        val codecSource = particleCodecSource(className, fields)
        result = replaceExecutableRegex(
            result,
            """(?s)\r?\n\s*public\s+static\s+Codec<${Regex.escape(className)}>\s+[A-Za-z_$][\w$]*\s*\(\s*\)\s*\{\s*return\s+RecordCodecBuilder\.create\s*\(\s*\(?\s*instance\s*\)?\s*->\s*instance\.group\s*\([\s\S]*?\.apply\s*\(\s*instance\s*,\s*${Regex.escape(className)}::new\s*\)\s*\)\s*;\s*\}\s*"""
                .toRegex()
        ) { "\n\n$codecSource\n" }

        result = replaceExecutableRegex(
            result,
            """(?s)\r?\n\s*@Override\s*\r?\n\s*public\s+void\s+writeToNetwork\s*\([^)]*\)\s*\{.*?\n\s*\}\s*"""
                .toRegex()
        ) { "\n" }
        result = replaceExecutableRegex(
            result,
            """(?s)\r?\n\s*@Nonnull\s*\r?\n\s*@Override\s*\r?\n\s*public\s+String\s+writeToString\s*\(\s*\)\s*\{.*?\n\s*\}\s*"""
                .toRegex()
        ) { "\n" }
        result = replaceExecutableRegex(
            result,
            """(?s)\r?\n\s*@Override\s*\r?\n\s*public\s+String\s+writeToString\s*\(\s*\)\s*\{.*?\n\s*\}\s*"""
                .toRegex()
        ) { "\n" }
        result = removeParticleDeserializerClass(result, className)

        result = removeImportLine(result, "com.mojang.brigadier.StringReader")
        result = removeImportLine(result, "com.mojang.brigadier.exceptions.CommandSyntaxException")
        result = removeImportLine(result, "net.minecraft.network.FriendlyByteBuf")
        return result
    }

    private fun particleCodecSource(className: String, fields: List<ParticleField>): String {
        val codecFields = fields.joinToString(",\n") {
            "\t\tCodec.INT.fieldOf(\"${it.name}\").forGetter((obj) -> obj.${it.name})"
        }
        val streamFields = fields.joinToString(",\n") {
            "\t\tByteBufCodecs.VAR_INT, p -> p.${it.name}"
        }
        return """
	public static MapCodec<$className> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
$codecFields
	).apply(instance, $className::new));

	public static StreamCodec<? super RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.composite(
$streamFields,
		$className::new
	);
""".trimEnd()
    }

    private fun removeParticleDeserializerClass(source: String, className: String): String {
        val marker = "public static class Deserializer implements ParticleOptions.Deserializer<$className>"
        val executableCode = maskJavaCommentsAndLiterals(source)
        val markerIndex = executableCode.indexOf(marker)
        if (markerIndex < 0) return source
        val start = source.lastIndexOf('\n', markerIndex).let { if (it >= 0) it else markerIndex }
        val openBrace = executableCode.indexOf('{', markerIndex)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source
        val end = if (closeBrace + 1 < source.length && source[closeBrace + 1] == '\n') closeBrace + 2 else closeBrace + 1
        return source.removeRange(start, end)
    }

    private fun migrateParticleTypeRegistrations(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("new ParticleType<>(false, new") || !executableCode.contains(".Deserializer()")) {
            return source
        }
        var result = source
        val customTypes = Regex("""new\s+([A-Za-z_$][\w$]*)\.Deserializer\s*\(\s*\)""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet()

        result = replaceExecutableRegex(
            result,
            Regex("""new\s+ParticleType<>\(\s*false\s*,\s*new\s+[A-Za-z_$][\w$]*\.Deserializer\s*\(\s*\)\s*\)""")
        ) { "new ParticleType<>(false)" }
        result = replaceExecutableRegex(
            result,
            Regex("""DeferredHolder<ParticleType<([A-Za-z_$][\w$]*)>,\s*ParticleType<\1>>""")
        ) { match -> "DeferredHolder<ParticleType<?>, ParticleType<${match.groupValues[1]}>>" }
        result = replaceExecutableRegex(
            result,
            Regex("""public\s+Codec<([A-Za-z_$][\w$]*)>\s+codec\s*\(\s*\)""")
        ) { match -> "public MapCodec<${match.groupValues[1]}> codec()" }
        result = replaceExecutableRegex(
            result,
            Regex("""return\s+([A-Za-z_$][\w$]*)\.codec[A-Za-z_$][\w$]*\s*\(\s*\)\s*;""")
        ) { match -> "return ${match.groupValues[1]}.CODEC;" }

        for (typeName in customTypes) {
            val streamSignature = "StreamCodec<? super RegistryFriendlyByteBuf, $typeName> streamCodec()"
            if (result.contains(streamSignature)) continue
            val codecBlock = Regex(
                """@Override\s*\r?\n\s*public\s+MapCodec<${Regex.escape(typeName)}>\s+codec\s*\(\s*\)\s*\{\s*return\s+${Regex.escape(typeName)}\.CODEC;\s*\}"""
            )
            result = replaceExecutableRegex(result, codecBlock) { match ->
                match.value + """


		@Override
		public StreamCodec<? super RegistryFriendlyByteBuf, $typeName> streamCodec() {
			return $typeName.STREAM_CODEC;
		}"""
            }
        }

        if (!Regex("""\bCodec\s*[<.]""").containsMatchIn(result)) {
            result = removeImportLine(result, "com.mojang.serialization.Codec")
        }
        return result
    }

    private fun migratePartialNbtIngredients(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("PartialNBTIngredient")) return source

        var result = source
        var changed = false

        result = replaceExecutableRegex(
            result,
            Regex(
                """(?s)public\s+final\s+PartialNBTIngredient\s+([A-Za-z_$][\w$]*)\s*\(\s*Item\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+PartialNBTIngredient\.of\s*\(\s*\2\s*,\s*Util\.make\s*\(\s*\(\s*\)\s*->\s*\{\s*CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+CompoundTag\s*\(\s*\)\s*;\s*\3\.putInt\s*\(\s*ItemStack\.TAG_DAMAGE\s*,\s*\2\.getMaxDamage\s*\(\s*\)\s*\)\s*;\s*return\s+\3\s*;\s*\}\s*\)\s*\)\s*;\s*\}"""
            )
        ) { match ->
            changed = true
            val method = match.groupValues[1]
            val item = match.groupValues[2]
            "public final Ingredient $method(Item $item) {\n\t\treturn DataComponentIngredient.of(false, DataComponents.DAMAGE, $item.getMaxDamage(), $item);\n\t}"
        }

        result = replaceExecutableRegex(
            result,
            Regex(
                """(?s)public\s+final\s+PartialNBTIngredient\s+([A-Za-z_$][\w$]*)\s*\(\s*Potion\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+PartialNBTIngredient\.of\s*\(\s*Items\.POTION\s*,\s*Util\.make\s*\(\s*\(\s*\)\s*->\s*\{\s*CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+CompoundTag\s*\(\s*\)\s*;\s*\3\.putString\s*\(\s*[^,]+,\s*BuiltInRegistries\.POTION\.getKey\s*\(\s*\2\s*\)\.toString\s*\(\s*\)\s*\)\s*;\s*return\s+\3\s*;\s*\}\s*\)\s*\)\s*;\s*\}"""
            )
        ) { match ->
            if (!Regex("""\.putString\s*\(\s*"Potion"\s*,""").containsMatchIn(match.value)) {
                return@replaceExecutableRegex match.value
            }
            changed = true
            val method = match.groupValues[1]
            val potion = match.groupValues[2]
            "public final Ingredient $method(Holder<Potion> $potion) {\n\t\treturn DataComponentIngredient.of(false, DataComponents.POTION_CONTENTS, new PotionContents($potion), Items.POTION);\n\t}"
        }

        if (!changed) return source
        if (!usesSymbolOutsideImports(result, "PartialNBTIngredient")) {
            result = removeImportLine(result, "net.neoforged.neoforge.common.crafting.PartialNBTIngredient")
        }
        if (!usesSymbolOutsideImports(result, "Util")) result = removeImportLine(result, "net.minecraft.Util")
        if (!usesSymbolOutsideImports(result, "CompoundTag")) result = removeImportLine(result, "net.minecraft.nbt.CompoundTag")
        if (!usesSymbolOutsideImports(result, "ItemStack")) result = removeImportLine(result, "net.minecraft.world.item.ItemStack")
        return result
    }

    private fun migrateSingleItemRecipeBuilderResults(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("SingleItemRecipeBuilder.Result") ||
            !executableCode.contains(".accept(stonecutting(")) {
            return source
        }

        var result = source
        result = replaceExecutableRegex(
            result,
            Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.accept\(\s*stonecutting\(([^;\r\n]+)\)\s*\);\s*$""")
        ) { match ->
            "${match.groupValues[1]}stonecutting(${match.groupValues[2]}, ${match.groupValues[3].trim()});"
        }

        result = replaceExecutableRegex(
            result,
            Regex(
                """(?s)\r?\n\s*private\s+static\s+Wrapper\s+stonecutting\s*\(\s*ItemLike\s+input\s*,\s*ItemLike\s+output\s*\)\s*\{\s*return\s+stonecutting\s*\(\s*input\s*,\s*output\s*,\s*1\s*\)\s*;\s*\}\s*\r?\n\s*private\s+static\s+Wrapper\s+stonecutting\s*\(\s*ItemLike\s+input\s*,\s*ItemLike\s+output\s*,\s*int\s+count\s*\)\s*\{\s*return\s+new\s+Wrapper\s*\(\s*getIdFor\s*\(\s*input\.asItem\s*\(\s*\)\s*,\s*output\.asItem\s*\(\s*\)\s*\)\s*,\s*Ingredient\.of\s*\(\s*input\s*\)\s*,\s*output\.asItem\s*\(\s*\)\s*,\s*count\s*\)\s*;\s*\}\s*"""
            )
        ) { """

	private static void stonecutting(RecipeOutput recipe, ItemLike input, ItemLike output) {
		stonecutting(recipe, input, output, 1);
	}

	private static void stonecutting(RecipeOutput recipe, ItemLike input, ItemLike output, int count) {
		SingleItemRecipeBuilder.stonecutting(Ingredient.of(input), RecipeCategory.BUILDING_BLOCKS, output.asItem(), count).unlockedBy("has_block", has(input)).save(recipe, getIdFor(input, output));
	}
""" }

        result = replaceExecutableRegex(
            result,
            Regex("""private\s+static\s+ResourceLocation\s+getIdFor\s*\(\s*Item\s+input\s*,\s*Item\s+output\s*\)""")
        ) {
            "private static ResourceLocation getIdFor(ItemLike input, ItemLike output)"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""BuiltInRegistries\.ITEM\.getKey\s*\(\s*input\s*\)\.getPath\s*\(\s*\)""")
        ) {
            "BuiltInRegistries.ITEM.getKey(input.asItem()).getPath()"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""BuiltInRegistries\.ITEM\.getKey\s*\(\s*output\s*\)\.getPath\s*\(\s*\)""")
        ) {
            "BuiltInRegistries.ITEM.getKey(output.asItem()).getPath()"
        }
        result = removeSingleItemRecipeWrapper(result)

        if (!maskJavaCommentsAndLiterals(result).contains("Criterion<InventoryChangeTrigger.TriggerInstance> has(ItemLike item)")) {
            val insertAt = result.lastIndexOf("\n}")
            if (insertAt >= 0) {
                val hasMethod = """

	protected static net.minecraft.advancements.Criterion<net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance> has(ItemLike item) {
		return net.minecraft.advancements.CriteriaTriggers.INVENTORY_CHANGED.createCriterion(new net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance(
			java.util.Optional.empty(),
			net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance.Slots.ANY,
			java.util.Collections.singletonList(net.minecraft.advancements.critereon.ItemPredicate.Builder.item().of(item).build())
		));
	}
"""
                result = result.substring(0, insertAt) + hasMethod + result.substring(insertAt)
            }
        }

        if (!usesSymbolOutsideImports(result, "JsonObject")) result = removeImportLine(result, "com.google.gson.JsonObject")
        if (!usesSymbolOutsideImports(result, "RecipeSerializer")) result = removeImportLine(result, "net.minecraft.world.item.crafting.RecipeSerializer")
        if (!usesSymbolOutsideImports(result, "Item")) result = removeImportLine(result, "net.minecraft.world.item.Item")
        if (!usesSymbolOutsideImports(result, "Nullable")) result = removeImportLine(result, "org.jetbrains.annotations.Nullable")
        if (!usesSymbolOutsideImports(result, "Consumer")) result = removeImportLine(result, "java.util.function.Consumer")
        return result
    }

    private fun removeSingleItemRecipeWrapper(source: String): String {
        val classMarker = "public static class Wrapper extends SingleItemRecipeBuilder.Result"
        val executableCode = maskJavaCommentsAndLiterals(source)
        val markerIndex = executableCode.indexOf(classMarker)
        if (markerIndex < 0) return source
        val classLineStart = source.lastIndexOf('\n', markerIndex).let { if (it >= 0) it + 1 else 0 }
        val previousLineEnd = classLineStart - 1
        val previousLineStart = if (previousLineEnd > 0) {
            source.lastIndexOf('\n', previousLineEnd - 1).let { if (it >= 0) it + 1 else 0 }
        } else {
            -1
        }
        val wrapperCommentStart = if (previousLineStart >= 0) {
            val previousLine = source.substring(previousLineStart, previousLineEnd).trim()
            if (previousLine.startsWith("// Wrapper")) previousLineStart else null
        } else {
            null
        }
        val lineStart = wrapperCommentStart ?: classLineStart
        val openBrace = source.indexOf('{', markerIndex)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source
        val end = if (closeBrace + 1 < source.length && source[closeBrace + 1] == '\n') closeBrace + 2 else closeBrace + 1
        return source.removeRange(lineStart, end)
    }

    private fun collectLegacyDyeableLeatherItemClasses(javaFiles: List<Path>): Set<String> =
        javaFiles.mapNotNull { file ->
            val source = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val executableCode = maskJavaCommentsAndLiterals(source)
            Regex(
                """(?s)\bclass\s+([A-Za-z_$][\w$]*)\b[^{;]*\bimplements\b[^{;]*\b(?:net\.minecraft\.world\.item\.)?DyeableLeatherItem\b"""
            ).findAll(executableCode).map { it.groupValues[1] }.toList()
        }.flatten().toSet()

    private fun migrateDyeableLeatherItemColors(
        source: String,
        legacyDyeableLeatherItemClasses: Set<String> = emptySet()
    ): String {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val sourceHasLegacyColorMethods = executableSource.contains("DataComponents.CUSTOM_DATA") &&
            Regex("""\bpublic\s+boolean\s+hasCustomColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(executableSource) &&
            Regex("""\bpublic\s+void\s+setColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(executableSource)
        val sourceHasKnownDyeableColorCallSites = legacyDyeableLeatherItemClasses.any { className ->
            executableSource.contains("instanceof $className") && executableSource.contains(".getColor(")
        }
        if (!executableSource.contains("DyeableLeatherItem") && !sourceHasLegacyColorMethods && !sourceHasKnownDyeableColorCallSites) {
            return source
        }

        var result = source
        result = migrateDyeableLeatherGetColorCallSites(result)
        result = migrateDyeableLeatherInstanceofGetColorCallSites(result, legacyDyeableLeatherItemClasses)
        result = result.replace(Regex("""extends\s+ArmorItem\s+implements\s+DyeableLeatherItem"""), "extends ArmorItem")
        result = result.replace(Regex("""\s+implements\s+DyeableLeatherItem\b"""), "")
        result = result.replace(Regex("""implements\s+DyeableLeatherItem\s*,\s*"""), "implements ")
        result = result.replace(Regex(""",\s*DyeableLeatherItem\b"""), "")
        result = removeImportLine(result, "net.minecraft.nbt.CompoundTag")
        result = removeImportLine(result, "net.minecraft.world.item.DyeableLeatherItem")

        val executableResult = maskJavaCommentsAndLiterals(result)
        val resultHasLegacyColorMethods = executableResult.contains("DataComponents.CUSTOM_DATA") &&
            Regex("""\bpublic\s+boolean\s+hasCustomColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(executableResult) &&
            Regex("""\bpublic\s+void\s+setColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(executableResult)
        if (!resultHasLegacyColorMethods) {
            return result
        }

        if (!executableResult.contains("DEFAULT_COLOR")) {
            val withDefaultColor = insertDyeableDefaultColor(result)
            if (withDefaultColor == result) return source
            result = withDefaultColor
        }

        val firstMethod = Regex("""\bpublic\s+boolean\s+hasCustomColor\s*\(""").find(result) ?: return result
        val insertAt = annotationStartBefore(result, firstMethod.range.first)
        var working = result
        var searchFrom = insertAt
        for (methodName in listOf("hasCustomColor", "getColor", "clearColor", "getColor", "removeColor", "setColor")) {
            val removed = removeFirstJavaMethodByName(working, methodName, searchFrom)
            working = removed.first
            searchFrom = removed.second ?: searchFrom
        }

        val componentMethods = """

	public boolean hasCustomColor(ItemStack stack) {
		return stack.has(DataComponents.DYED_COLOR);
	}

	public int getColor(ItemStack stack) {
		return DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);
	}

	public void clearColor(ItemStack stack) {
		this.removeColor(stack);
	}

	public int getColor(ItemStack stack, int type) {
		return type == 0 ? 0xFFFFFF : DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);
	}

	public void removeColor(ItemStack stack) {
		stack.remove(DataComponents.DYED_COLOR);
	}

	public void setColor(ItemStack stack, int color) {
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color, true));
	}
""".trimEnd()

        result = working.substring(0, insertAt) + componentMethods + working.substring(insertAt)
        if (!usesSymbolOutsideImports(result, "CompoundTag")) result = removeImportLine(result, "net.minecraft.nbt.CompoundTag")
        if (!usesSymbolOutsideImports(result, "CustomData")) result = removeImportLine(result, "net.minecraft.world.item.component.CustomData")
        return result
    }

    private fun migrateDyeableLeatherGetColorCallSites(source: String): String {
        val pattern = Regex(
            """\(\(\s*(?:net\.minecraft\.world\.item\.)?DyeableLeatherItem\s*\)\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getItem\(\)\s*\)\.getColor\(\s*\1\s*\)"""
        )
        return replaceExecutableRegex(source, pattern) { match ->
            val originalMatch = pattern.matchEntire(match.value)
                ?: return@replaceExecutableRegex match.value
            "DyedItemColor.getOrDefault(${originalMatch.groupValues[1]}, DyedItemColor.LEATHER_COLOR)"
        }
    }

    private fun migrateDyeableLeatherInstanceofGetColorCallSites(
        source: String,
        legacyDyeableLeatherItemClasses: Set<String>
    ): String {
        val executableSource = maskJavaCommentsAndLiterals(source)
        if (legacyDyeableLeatherItemClasses.isEmpty() || !executableSource.contains(".getColor(")) return source
        var result = source
        legacyDyeableLeatherItemClasses.forEach { className ->
            val instanceofPattern = Regex(
                """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^)]*\))?)*)\.getItem\(\)\s+instanceof\s+(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(className)}\s+([A-Za-z_$][\w$]*)"""
            )
            var cursor = 0
            while (true) {
                val executableResult = maskJavaCommentsAndLiterals(result)
                val match = instanceofPattern.find(executableResult, cursor) ?: break
                val originalInstanceof = instanceofPattern.matchEntire(result.substring(match.range.first, match.range.last + 1))
                if (originalInstanceof == null) {
                    cursor = match.range.last + 1
                    continue
                }
                val stackExpression = originalInstanceof.groupValues[1]
                val itemVariable = originalInstanceof.groupValues[2]
                val openBrace = executableResult.indexOf('{', match.range.last)
                if (openBrace < 0) {
                    cursor = match.range.last + 1
                    continue
                }
                val closeBrace = findMatchingBrace(result, openBrace)
                if (closeBrace < 0) {
                    cursor = match.range.last + 1
                    continue
                }
                val body = result.substring(openBrace + 1, closeBrace)
                val colorCallPattern = Regex("""\b${Regex.escape(itemVariable)}\.getColor\(\s*${Regex.escape(stackExpression)}\s*\)""")
                val colorMatches = colorCallPattern.findAll(maskJavaCommentsAndLiterals(body)).toList()
                if (colorMatches.isEmpty()) {
                    cursor = closeBrace + 1
                    continue
                }
                var migratedBody = body
                for (colorMatch in colorMatches.asReversed()) {
                    migratedBody = migratedBody.replaceRange(
                        colorMatch.range,
                        "DyedItemColor.getOrDefault($stackExpression, DyedItemColor.LEATHER_COLOR)"
                    )
                }
                result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
                cursor = openBrace + 1 + migratedBody.length
            }
        }
        return result
    }

    private fun insertDyeableDefaultColor(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (executableCode.contains("DEFAULT_COLOR")) return source
        val classMatch = Regex(
            """(?m)^[ \t]*(?:(?:public|protected|private|abstract|final|static)\s+)*class\s+[A-Za-z_$][\w$]*\b[^{;]*\{"""
        ).find(executableCode) ?: return source
        val insertAt = classMatch.range.last + 1
        return source.substring(0, insertAt) +
            "\n\tpublic static final int DEFAULT_COLOR = 0xFFBDCFD9;\n" +
            source.substring(insertAt)
    }

    private fun migrateTierSortingRegistryTiers(
        source: String,
        tierIncorrectTagResources: MutableList<TierIncorrectTagResource>,
        projectDir: Path,
        sourceFile: Path,
        errors: MutableList<String>
    ): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("TierSortingRegistry.registerTier(") &&
            !executableCode.contains("TierSortingRegistry.isCorrectTierForDrops(")) {
            return source
        }

        var result = source
        var changed = false

        if (executableCode.contains("TierSortingRegistry.registerTier(")) {
            val executableResult = maskJavaCommentsAndLiterals(result)
            val marker = "TierSortingRegistry.registerTier"
            val builder = StringBuilder()
            var cursor = 0

            while (true) {
                val markerIndex = executableResult.indexOf(marker, cursor)
                if (markerIndex < 0) break
                val openParen = executableResult.indexOf('(', markerIndex + marker.length)
                if (openParen < 0) break
                val closeParen = findMatchingDelimiter(executableResult, openParen, '(', ')')
                if (closeParen < 0) break

                val inside = result.substring(openParen + 1, closeParen)
                val replacement = migrateTierSortingRegistryCall(inside, tierIncorrectTagResources, projectDir, sourceFile, errors)
                if (replacement == null) {
                    builder.append(result, cursor, closeParen + 1)
                } else {
                    builder.append(result, cursor, markerIndex)
                    builder.append(replacement)
                    changed = true
                }
                cursor = closeParen + 1
            }

            builder.append(result, cursor, result.length)
            result = builder.toString()
        }

        val beforeDropChecks = result
        result = migrateTierSortingRegistryDropChecks(result)
        if (result != beforeDropChecks) {
            changed = true
        }

        if (!changed) return source

        result = removeImportLine(result, "net.neoforged.neoforge.common.TierSortingRegistry")
        if (!usesSymbolOutsideImports(result, "List")) {
            result = removeImportLine(result, "java.util.List")
        }
        if (!usesSymbolOutsideImports(result, "BlockTags")) {
            result = removeImportLine(result, "net.minecraft.tags.BlockTags")
        }
        return result
    }

    private fun migrateTierSortingRegistryDropChecks(source: String): String {
        val marker = "TierSortingRegistry.isCorrectTierForDrops"
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains(marker)) return source

        val builder = StringBuilder()
        var cursor = 0
        while (true) {
            val markerIndex = executableCode.indexOf(marker, cursor)
            if (markerIndex < 0) break
            val openParen = executableCode.indexOf('(', markerIndex + marker.length)
            if (openParen < 0) break
            val closeParen = findMatchingDelimiter(executableCode, openParen, '(', ')')
            if (closeParen < 0) break

            val args = splitTopLevelArguments(source.substring(openParen + 1, closeParen))
            if (args.size != 2) {
                builder.append(source, cursor, closeParen + 1)
            } else {
                builder.append(source, cursor, markerIndex)
                builder.append(tierSortingDropCheckExpression(args[0], args[1]))
            }
            cursor = closeParen + 1
        }
        builder.append(source, cursor, source.length)
        return builder.toString()
    }

    private fun tierSortingDropCheckExpression(tierExpression: String, stateExpression: String): String {
        val state = stateExpression.trim()
        val mineableTag = listOf(
            "MINEABLE_WITH_AXE",
            "MINEABLE_WITH_SHOVEL",
            "MINEABLE_WITH_HOE"
        ).joinToString(" : ") { tag ->
            "$state.is(net.minecraft.tags.BlockTags.$tag) ? net.minecraft.tags.BlockTags.$tag"
        } + " : net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE"
        return "(${tierExpression.trim()}).createToolProperties($mineableTag).isCorrectForDrops($state)"
    }

    private fun migrateTierSortingRegistryCall(
        callInside: String,
        tierIncorrectTagResources: MutableList<TierIncorrectTagResource>,
        projectDir: Path,
        sourceFile: Path,
        errors: MutableList<String>
    ): String? {
        val args = splitTopLevelArguments(callInside)
        if (args.isEmpty()) return null
        val simpleTier = args.first().trim()
        val simpleArgs = simpleTierConstructorArguments(simpleTier) ?: return null
        if (simpleArgs.size !in 6..7) return null

        val oldTagIndex = if (simpleArgs.size == 7) 5 else 0
        val oldNeedsTag = simpleArgs[oldTagIndex].trim()
        val toolName = customToolNameFromNeedsTag(oldNeedsTag)
        val miningLevel = if (simpleArgs.size == 7) {
            simpleArgs[0].trim().toIntOrNull()
        } else {
            miningLevelFromAfterTierList(args.getOrNull(2))
        }
        val incorrectTag = toolName?.let { incorrectToolTagExpression(oldNeedsTag, it) }
            ?: if (simpleArgs.size == 7) incorrectBlockTagForMiningLevel(simpleArgs[0].trim()) else oldNeedsTag
        if (toolName != null && miningLevel != null) {
            val namespace = namespaceFromTagExpression(oldNeedsTag, projectDir, sourceFile)
            if (namespace == null) {
                val relative = projectDir.relativize(sourceFile).toString().replace('\\', '/')
                errors.add("Cannot derive namespace for custom tool tier tag '$oldNeedsTag' in $relative")
                return null
            }
            tierIncorrectTagResources.add(
                TierIncorrectTagResource(
                    namespace = namespace,
                    path = "incorrect_for_${toolName}_tool",
                    vanillaReference = vanillaIncorrectTagForMiningLevel(miningLevel),
                    sourceTagExpression = oldNeedsTag
                )
            )
        }
        val uses = simpleArgs[if (simpleArgs.size == 7) 1 else 1].trim()
        val speed = simpleArgs[if (simpleArgs.size == 7) 2 else 2].trim()
        val attackDamage = simpleArgs[if (simpleArgs.size == 7) 3 else 3].trim()
        val enchantment = simpleArgs[if (simpleArgs.size == 7) 4 else 4].trim()
        val repair = repairIngredientForTool(toolName, simpleArgs[if (simpleArgs.size == 7) 6 else 5].trim())
        return "new SimpleTier($incorrectTag, $uses, $speed, $attackDamage, $enchantment, $repair)"
    }

    private fun simpleTierConstructorArguments(expression: String): List<String>? {
        val match = Regex("""new\s+SimpleTier\s*\(""").find(expression) ?: return null
        val openParen = expression.indexOf('(', match.range.first)
        if (openParen < 0) return null
        val closeParen = findMatchingDelimiter(expression, openParen, '(', ')')
        if (closeParen < 0) return null
        return splitTopLevelArguments(expression.substring(openParen + 1, closeParen))
    }

    private fun customToolNameFromNeedsTag(tagExpression: String): String? =
        Regex("""needs_([a-z0-9_]+)_tool""").find(tagExpression)?.groupValues?.get(1)

    private fun incorrectBlockTagForMiningLevel(levelText: String): String =
        when (levelText.toIntOrNull()) {
            null -> "BlockTags.INCORRECT_FOR_DIAMOND_TOOL"
            0 -> "BlockTags.INCORRECT_FOR_WOODEN_TOOL"
            1 -> "BlockTags.INCORRECT_FOR_STONE_TOOL"
            2 -> "BlockTags.INCORRECT_FOR_IRON_TOOL"
            3 -> "BlockTags.INCORRECT_FOR_DIAMOND_TOOL"
            else -> "BlockTags.INCORRECT_FOR_NETHERITE_TOOL"
        }

    private fun vanillaIncorrectTagForMiningLevel(level: Int): String =
        when (level) {
            0 -> "#minecraft:incorrect_for_wooden_tool"
            1 -> "#minecraft:incorrect_for_stone_tool"
            2 -> "#minecraft:incorrect_for_iron_tool"
            3 -> "#minecraft:incorrect_for_diamond_tool"
            else -> "#minecraft:incorrect_for_netherite_tool"
        }

    private fun miningLevelFromAfterTierList(afterTierExpression: String?): Int? {
        val tiers = afterTierExpression ?: return null
        return Regex("""\bTiers\.(WOOD|STONE|IRON|DIAMOND|NETHERITE)\b""")
            .findAll(tiers)
            .mapNotNull { match ->
                when (match.groupValues[1]) {
                    "WOOD" -> 0
                    "STONE" -> 1
                    "IRON" -> 2
                    "DIAMOND" -> 3
                    "NETHERITE" -> 4
                    else -> null
                }
            }
            .maxOrNull()
    }

    private fun namespaceFromTagExpression(tagExpression: String, projectDir: Path, sourceFile: Path): String? {
        Regex("""ResourceLocation\.fromNamespaceAndPath\(\s*"([a-z0-9_.-]+)"\s*,""")
            .find(tagExpression)?.let { return it.groupValues[1] }
        Regex("""new\s+ResourceLocation\(\s*"([a-z0-9_.-]+)"\s*,""")
            .find(tagExpression)?.let { return it.groupValues[1] }
        val prefixOwner = Regex("""\b([A-Za-z_$][\w$]*)\.prefix\s*\(""")
            .find(tagExpression)
            ?.groupValues
            ?.get(1)
            ?: return null
        return resolvePrefixNamespace(projectDir, sourceFile, prefixOwner)
    }

    private fun resolvePrefixNamespace(projectDir: Path, sourceFile: Path, simpleClassName: String): String? {
        val source = sourceFile.readText()
        val classFile = resolveJavaClassFile(projectDir, sourceFile, source, simpleClassName) ?: return null
        if (!classFile.exists()) return null
        val classSource = classFile.readText()
        val prefixMethod = Regex(
            """(?s)\bprefix\s*\(\s*String\s+[A-Za-z_$][\w$]*\s*\)\s*\{(.*?)\}"""
        ).find(classSource)?.groupValues?.get(1) ?: return null
        val namespaceToken = Regex("""ResourceLocation\.fromNamespaceAndPath\(\s*([A-Za-z_$][\w$]*)\s*,""")
            .find(prefixMethod)
            ?.groupValues
            ?.get(1)
            ?: Regex("""new\s+ResourceLocation\(\s*([A-Za-z_$][\w$]*)\s*,""")
                .find(prefixMethod)
                ?.groupValues
                ?.get(1)
            ?: return null
        return Regex("""\bstatic\s+final\s+String\s+${Regex.escape(namespaceToken)}\s*=\s*"([a-z0-9_.-]+)"""")
            .find(classSource)
            ?.groupValues
            ?.get(1)
    }

    private fun resolveJavaClassFile(
        projectDir: Path,
        sourceFile: Path,
        source: String,
        simpleClassName: String
    ): Path? {
        val imported = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.${Regex.escape(simpleClassName)}\s*;""")
            .find(source)
            ?.let { "${it.groupValues[1]}.$simpleClassName" }
        val fqn = imported ?: run {
            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: return null
            "$packageName.$simpleClassName"
        }
        val relative = fqn.replace('.', '/') + ".java"
        return listOf(
            projectDir.resolve("src/main/java").resolve(relative),
            projectDir.resolve("src/generated/java").resolve(relative),
            sourceFile.parent.resolve("$simpleClassName.java")
        ).firstOrNull { it.exists() }
    }

    private fun incorrectToolTagExpression(oldNeedsTag: String, toolName: String): String =
        oldNeedsTag.replace("needs_${toolName}_tool", "incorrect_for_${toolName}_tool")

    private fun repairIngredientForTool(toolName: String?, original: String): String = original

    private fun ensureTierIncorrectTagResources(
        projectDir: Path,
        specs: List<TierIncorrectTagResource>,
        changes: MutableList<Change>,
        dryRun: Boolean
    ) {
        if (specs.isEmpty()) return
        val resourceDirs = targetResourceDirs(projectDir)
        val emittedTargets = mutableSetOf<Path>()

        for (spec in specs.distinct()) {
            for (resourceDir in resourceDirs) {
                val target = resourceDir
                    .resolve("data")
                    .resolve(spec.namespace)
                    .resolve("tags/block")
                    .resolve("${spec.path}.json")
                if (!emittedTargets.add(target)) continue
                if (target.exists()) continue

                val content = """
{
  "values": [
    "${spec.vanillaReference}"
  ]
}
                """.trimIndent() + "\n"
                changes.add(
                    Change(
                        file = target,
                        line = 1,
                        description = "Generate 1.21 incorrect-block tag for migrated custom tool tier",
                        before = spec.sourceTagExpression,
                        after = "${spec.namespace}:${spec.path} -> ${spec.vanillaReference}",
                        confidence = Confidence.HIGH,
                        ruleId = "tier-incorrect-block-tag-resource"
                    )
                )
                if (!dryRun) {
                    target.parent.createDirectories()
                    target.writeText(content)
                }
            }
        }
    }

    private fun targetResourceDirs(projectDir: Path): List<Path> {
        val generated = projectDir.resolve("src/generated/resources")
        if (generated.exists()) return listOf(generated)
        return listOf(projectDir.resolve("src/main/resources"))
    }

    private data class TierIncorrectTagResource(
        val namespace: String,
        val path: String,
        val vanillaReference: String,
        val sourceTagExpression: String
    )

    private fun migrateEnchantmentCategoryRuntimeChecks(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("EnchantmentCategory") && !executableCode.contains("new Enchantment[0]")) return source
        var result = source
        val enchantmentArrayPattern =
            Regex("""EnchantmentHelper\.getEnchantments\(([^)\r\n]+)\)\.keySet\(\)\.toArray\(new\s+Enchantment\[\s*0\s*]\)""")
        result = replaceExecutableRegex(result, enchantmentArrayPattern) { match ->
            val originalMatch = enchantmentArrayPattern.matchEntire(match.value)
                ?: return@replaceExecutableRegex match.value
            "EnchantmentHelper.getEnchantments(${originalMatch.groupValues[1]}).keySet()"
        }
        val categoryHelperPattern = Regex(
            """(?s)private\s+boolean\s+([A-Za-z_$][\w$]*)\s*\(\s*Enchantment\.\.\.\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*for\s*\(\s*Enchantment\s+([A-Za-z_$][\w$]*)\s*:\s*\2\s*\)\s*\{\s*if\s*\(\s*\3\.category\s*==\s*EnchantmentCategory\.[A-Za-z_$][\w$]*\s*\|\|\s*\3\.canEnchant\(([\s\S]*?)\)\s*\)\s*return\s+true;\s*\}\s*return\s+false;\s*\}"""
        )
        result = replaceExecutableRegex(result, categoryHelperPattern) { match ->
            val originalMatch = categoryHelperPattern.matchEntire(match.value)
                ?: return@replaceExecutableRegex match.value
            val methodName = originalMatch.groupValues[1]
            val itemStackExpression = originalMatch.groupValues[4].trim()
            """
private boolean $methodName(Iterable<net.minecraft.core.Holder<Enchantment>> enchantments) {
		for (net.minecraft.core.Holder<Enchantment> enchantment : enchantments) {
			if ($itemStackExpression.supportsEnchantment(enchantment))
				return true;
		}
		return false;
	}

	private boolean $methodName(net.minecraft.core.Holder<Enchantment> enchantment) {
		return this.$methodName(java.util.List.of(enchantment));
	}
            """.trimIndent()
        }
        return result
    }

    private data class LootCodecField(val key: String, val getter: String, val codecExpression: String)

    private fun collectLegacyLootCodecOwners(javaFiles: List<Path>): Set<String> {
        return javaFiles.mapNotNull { file ->
            val source = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            legacyLootCodecOwner(source)
        }.toSet()
    }

    private fun legacyLootCodecOwner(source: String): String? {
        val inferenceSource = normalizeLegacyLootCodecInferenceSource(source)
        val className = javaTopLevelTypeName(inferenceSource) ?: return null
        return className.takeIf {
            lootConditionCodecFieldSource(inferenceSource, className) != null ||
                lootConditionalFunctionCodecFieldSource(inferenceSource, className) != null
        }
    }

    private fun normalizeLegacyLootCodecInferenceSource(source: String): String =
        source
            .replace("net.minecraftforge.registries.ForgeRegistries.ITEMS", "BuiltInRegistries.ITEM")
            .replace("ForgeRegistries.ITEMS", "BuiltInRegistries.ITEM")
            .replace("net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES", "BuiltInRegistries.ENTITY_TYPE")
            .replace("ForgeRegistries.ENTITY_TYPES", "BuiltInRegistries.ENTITY_TYPE")

    private fun migrateLootSerializerCodecs(source: String, codecOwners: Set<String> = emptySet()): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("LootItemConditionType") &&
            !executableCode.contains("LootItemFunctionType") &&
            !executableCode.contains("Serializer<")) {
            return source
        }

        var result = source
        result = migrateLootConditionSerializerCodec(result)
        result = migrateLootConditionalFunctionSerializerCodec(result)
        result = migrateLootTypeRegistryCodecConstructors(result, codecOwners)
        result = removeLegacyLootSerializerImports(result)
        return result
    }

    private fun migrateLootConditionSerializerCodec(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("implements LootItemCondition") ||
            !executableCode.contains("Serializer<")) {
            return source
        }

        val className = javaTopLevelTypeName(source) ?: return source
        val serializerName = lootConditionSerializerName(source, className) ?: return source
        val codecField = lootConditionCodecFieldSource(source, className) ?: return source

        var result = insertStaticFieldAfterTypeOpen(source, className, codecField)
        result = removeInnerClassByName(result, serializerName)
        return result
    }

    private fun lootConditionCodecFieldSource(source: String, className: String): String? {
        val serializerName = lootConditionSerializerName(source, className) ?: return null
        val serializer = innerClassText(source, serializerName) ?: return null
        return inferLootConditionCodecField(source, serializer, className)
    }

    private fun lootConditionSerializerName(source: String, className: String): String? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        return when {
            Regex("""class\s+ConditionSerializer\s+implements\s+(?:net\.minecraft\.world\.level\.storage\.loot\.)?Serializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(executableCode) -> "ConditionSerializer"
            Regex("""class\s+Serializer\s+implements\s+(?:net\.minecraft\.world\.level\.storage\.loot\.)?Serializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(executableCode) -> "Serializer"
            else -> null
        }
    }

    private fun inferLootConditionCodecField(source: String, serializer: String, className: String): String? {
        val serializerCode = maskJavaComments(serializer)
        val deserializeReturn = Regex(
            """return\s+new\s+${Regex.escape(className)}\s*\(([\s\S]*?)\)\s*;"""
        ).find(serializerCode) ?: return null
        val constructorArgs = splitTopLevelArguments(deserializeReturn.groupValues[1])
        if (constructorArgs.isEmpty()) {
            val singleton = Regex("""public\s+static\s+final\s+${Regex.escape(className)}\s+INSTANCE\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(source))
            val value = if (singleton) "INSTANCE" else "new $className()"
            return """
	public static final MapCodec<$className> CODEC = MapCodec.unit($value);
            """.trimIndent()
        }

        inferWrappedStringLootConditionCodecField(serializerCode, className, constructorArgs)?.let { return it }

        val serializedMembers = serializedLootMembersByKey(serializerCode)
        val fields = constructorArgs.mapNotNull { lootConditionCodecField(it, serializedMembers) }
        if (fields.size != constructorArgs.size) return null

        val fieldLines = fields.joinToString(",\n") { field ->
            "\t\t${field.codecExpression}.forGetter(o -> o.${field.getter})"
        }
        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
$fieldLines
	).apply(instance, $className::new));
        """.trimIndent()
    }

    private fun serializedLootMembersByKey(serializer: String): Map<String, String> {
        val members = linkedMapOf<String, String>()
        Regex(
            """\b(?:json|object)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(serializer).forEach { match ->
            members[match.groupValues[1]] = match.groupValues[2]
        }
        Regex(
            """\b(?:json|object)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$.]*\.serialize\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\s*\)"""
        ).findAll(serializer).forEach { match ->
            members[match.groupValues[1]] = match.groupValues[2]
        }
        Regex(
            """\b(?:json|object)\.add\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*\.serialize\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\s*\)"""
        ).findAll(serializer).forEach { match ->
            members[match.groupValues[1]] = match.groupValues[2]
        }
        return members
    }

    private fun inferWrappedStringLootConditionCodecField(
        serializer: String,
        className: String,
        constructorArgs: List<String>
    ): String? {
        if (constructorArgs.size != 1) return null
        val deserialize = Regex(
            """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.deserialize\s*\(\s*GsonHelper\.getAsString\s*\(\s*[^,]+,\s*"([^"]+)"\s*\)\s*\)"""
        ).find(constructorArgs.single()) ?: return null
        val converter = deserialize.groupValues[1]
        val key = deserialize.groupValues[2]
        val member = Regex(
            """\b(?:json|object)\.addProperty\s*\(\s*"${Regex.escape(key)}"\s*,\s*${Regex.escape(converter)}\.serialize\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\s*\)"""
        ).find(serializer)?.groupValues?.get(1) ?: return null
        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
		com.mojang.serialization.Codec.STRING.fieldOf("$key").forGetter(o -> $converter.serialize(o.$member))
	).apply(instance, value -> new $className($converter.deserialize(value))));
        """.trimIndent()
    }

    private fun lootConditionCodecField(argument: String, serializedMembers: Map<String, String>): LootCodecField? {
        Regex("""GsonHelper\.getAsString\s*\(\s*[^,]+,\s*"([^"]+)"""").find(argument)?.let { match ->
            val key = match.groupValues[1]
            val member = serializedMembers[key] ?: return null
            return LootCodecField(
                key = key,
                getter = member,
                codecExpression = """com.mojang.serialization.Codec.STRING.fieldOf("$key")"""
            )
        }
        Regex("""GsonHelper\.getAsBoolean\s*\(\s*[^,]+,\s*"([^"]+)"\s*,\s*([^)]+)\)""").find(argument)?.let { match ->
            val key = match.groupValues[1]
            val defaultValue = match.groupValues[2].trim()
            val member = serializedMembers[key] ?: return null
            return LootCodecField(
                key = key,
                getter = member,
                codecExpression = """com.mojang.serialization.Codec.BOOL.optionalFieldOf("$key", $defaultValue)"""
            )
        }
        Regex("""GsonHelper\.getAsObject\s*\(\s*[^,]+,\s*"([^"]+)"\s*,\s*[^,]+,\s*LootContext\.EntityTarget\.class\s*\)""")
            .find(argument)?.let { match ->
                val key = match.groupValues[1]
                val member = serializedMembers[key] ?: return null
                return LootCodecField(
                    key = key,
                    getter = member,
                    codecExpression = """LootContext.EntityTarget.CODEC.fieldOf("$key")"""
                )
            }
        return null
    }

    private fun migrateLootConditionalFunctionSerializerCodec(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("extends LootItemConditionalFunction") ||
            !executableCode.contains("LootItemConditionalFunction.Serializer")) {
            return source
        }

        val className = javaTopLevelTypeName(source) ?: return source
        val codecField = lootConditionalFunctionCodecFieldSource(source, className) ?: return source

        var result = insertStaticFieldAfterTypeOpen(source, className, codecField)
        val constructorPattern = Regex("""(protected|public|private)\s+${Regex.escape(className)}\s*\(\s*LootItemCondition\[\]\s+([A-Za-z_$][\w$]*)""")
        result = replaceExecutableRegex(result, constructorPattern) { match ->
            "${match.groupValues[1]} $className(java.util.List<LootItemCondition> ${match.groupValues[2]}"
        }
        val typePattern = Regex("""public\s+LootItemFunctionType\s+getType\s*\(\s*\)""")
        result = replaceExecutableRegex(result, typePattern) { "public LootItemFunctionType<$className> getType()" }
        result = removeInnerClassByName(result, "Serializer")
        return result
    }

    private fun lootConditionalFunctionCodecFieldSource(source: String, className: String): String? {
        val serializer = innerClassText(source, "Serializer") ?: return null
        if (!maskJavaCommentsAndLiterals(serializer).contains("extends LootItemConditionalFunction.Serializer<$className>")) return null
        return inferLootConditionalFunctionCodecField(serializer, className)
    }

    private fun inferLootConditionalFunctionCodecField(serializer: String, className: String): String? {
        val serializerCode = maskJavaComments(serializer)
        if (Regex("""return\s+new\s+${Regex.escape(className)}\s*\(\s*conditions\s*\)\s*;""").containsMatchIn(serializerCode)) {
            return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).apply(instance, $className::new));
            """.trimIndent()
        }

        inferEntityTypeAndIntLootFunctionCodecField(serializerCode, className)?.let { return it }

        if (!serializerCode.contains("GsonHelper.getAsItem") ||
            !serializerCode.contains("\"default\"") ||
            !serializerCode.contains("success")) {
            return null
        }

        val itemMember = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"item"\s*,\s*BuiltInRegistries\.ITEM\.getKey\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).find(serializerCode)?.groupValues?.get(1) ?: return null
        val defaultMembers = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"default"\s*,\s*BuiltInRegistries\.ITEM\.getKey\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(serializerCode).map { it.groupValues[1] }.toList()
        val defaultMember = defaultMembers.lastOrNull() ?: return null
        val successMember = Regex("""if\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)""")
            .find(serializerCode)?.groupValues?.get(1) ?: return null

        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).and(instance.group(
		BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(o -> o.$successMember ? java.util.Optional.of(o.$itemMember) : java.util.Optional.empty()),
		BuiltInRegistries.ITEM.byNameCodec().fieldOf("default").forGetter(o -> o.$defaultMember)
	)).apply(instance, (conditions, item, oldItem) -> new $className(conditions, item.orElse(oldItem), oldItem, item.isPresent())));
        """.trimIndent()
    }

    private fun inferEntityTypeAndIntLootFunctionCodecField(serializer: String, className: String): String? {
        val serializerCode = maskJavaComments(serializer)
        val deserializeReturn = Regex(
            """return\s+new\s+${Regex.escape(className)}\s*\(([\s\S]*?)\)\s*;"""
        ).find(serializerCode) ?: return null
        val constructorArgs = splitTopLevelArguments(deserializeReturn.groupValues[1])
        if (constructorArgs.size < 2 || constructorArgs.first().trim() != "conditions") return null

        val entityLocals = Regex(
            """EntityType\s*<\s*\?\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*EntityType\.byString\s*\(\s*GsonHelper\.getAsString\s*\(\s*[^,]+,\s*"([^"]+)"\s*\)\s*\)[^;]*;"""
        ).findAll(serializerCode).associate { it.groupValues[1] to it.groupValues[2] }
        val intLocals = Regex(
            """int\s+([A-Za-z_$][\w$]*)\s*=\s*GsonHelper\.getAsInt\s*\(\s*[^,]+,\s*"([^"]+)"\s*\)\s*;"""
        ).findAll(serializerCode).associate { it.groupValues[1] to it.groupValues[2] }
        if (entityLocals.isEmpty() && intLocals.isEmpty()) return null

        val entityMembersByKey = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*EntityType\.getKey\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\.toString\s*\(\s*\)\s*\)"""
        ).findAll(serializerCode).associate { it.groupValues[1] to it.groupValues[2] }
        val intMembersByKey = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(serializerCode).associate { it.groupValues[1] to it.groupValues[2] }

        val codecFields = constructorArgs.drop(1).map { argument ->
            val arg = argument.trim()
            entityLocals[arg]?.let { key ->
                val member = entityMembersByKey[key] ?: return null
                """		BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("$key").forGetter(o -> o.$member)"""
            } ?: intLocals[arg]?.let { key ->
                val member = intMembersByKey[key] ?: return null
                """		com.mojang.serialization.Codec.INT.fieldOf("$key").forGetter(o -> o.$member)"""
            } ?: return null
        }
        if (codecFields.isEmpty()) return null

        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).and(instance.group(
${codecFields.joinToString(",\n")}
	)).apply(instance, $className::new));
        """.trimIndent()
    }

    private fun migrateLootTypeRegistryCodecConstructors(source: String, codecOwners: Set<String>): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("LootItemConditionType") && !executableCode.contains("LootItemFunctionType")) return source
        var result = source
        val conditionTypePattern = Regex("""new\s+LootItemConditionType\s*\(\s*new\s+([A-Za-z_$][\w$]*)\.(?:ConditionSerializer|Serializer)\s*\(\s*\)\s*\)""")
        result = replaceExecutableRegex(result, conditionTypePattern) { match ->
                val owner = match.groupValues[1]
                if (lootCodecOwnerIsAvailable(result, owner, codecOwners)) {
                    "new LootItemConditionType($owner.CODEC)"
                } else {
                    match.value
                }
            }
        val functionTypePattern = Regex("""new\s+LootItemFunctionType\s*\(\s*new\s+([A-Za-z_$][\w$]*)\.Serializer\s*\(\s*\)\s*\)""")
        result = replaceExecutableRegex(result, functionTypePattern) { match ->
                val owner = match.groupValues[1]
                if (lootCodecOwnerIsAvailable(result, owner, codecOwners)) {
                    "new LootItemFunctionType<>($owner.CODEC)"
                } else {
                    match.value
                }
            }
        if (maskJavaCommentsAndLiterals(result).contains("new LootItemFunctionType<>(")) {
            val functionRegisterPattern = Regex("""DeferredRegister\s*<\s*LootItemFunctionType\s*>\s+FUNCTIONS""")
            result = replaceExecutableRegex(result, functionRegisterPattern) { "DeferredRegister<LootItemFunctionType<?>> FUNCTIONS" }
        }
        val functionHolderPattern = Regex(
            """DeferredHolder<LootItemFunctionType,\s*LootItemFunctionType>\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.register\s*\(\s*"([^"]+)"\s*,\s*\(\)\s*->\s*new\s+LootItemFunctionType<>\s*\(\s*([A-Za-z_$][\w$]*)\.CODEC\s*\)\s*\)"""
        )
        result = replaceCommentMaskedRegex(result, functionHolderPattern) { match ->
            "DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<${match.groupValues[4]}>> ${match.groupValues[1]} = ${match.groupValues[2]}.register(\"${match.groupValues[3]}\", () -> new LootItemFunctionType<>(${match.groupValues[4]}.CODEC))"
        }
        return result
    }

    private fun lootCodecOwnerIsAvailable(source: String, owner: String, codecOwners: Set<String>): Boolean {
        return owner in codecOwners ||
            Regex("""MapCodec\s*<\s*${Regex.escape(owner)}\s*>\s+CODEC\b""").containsMatchIn(maskJavaCommentsAndLiterals(source))
    }

    private fun migrateItemPropertiesUseDurationCalls(source: String): String {
        if (!source.contains("ItemProperties.register(") || !source.contains(".getUseDuration()")) return source

        val executableCode = maskJavaCommentsAndLiterals(source)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val callToken = "ItemProperties.register("
        val lambdaPattern = Regex(
            """^\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*->"""
        )
        var cursor = 0
        while (cursor < executableCode.length) {
            val callIndex = executableCode.indexOf(callToken, cursor)
            if (callIndex < 0) break
            val openParen = callIndex + "ItemProperties.register".length
            val closeParen = findMatchingDelimiter(executableCode, openParen, '(', ')')
            if (closeParen < 0) {
                cursor = callIndex + callToken.length
                continue
            }

            val args = splitTopLevelArgumentExpressions(
                source.substring(openParen + 1, closeParen),
                baseOffset = openParen + 1
            )
            val lambdaArg = args.getOrNull(2)
            val lambdaMatch = lambdaArg?.let { lambdaPattern.find(it.text) }
            if (lambdaArg == null || lambdaMatch == null) {
                cursor = closeParen + 1
                continue
            }

            val stackParam = lambdaMatch.groupValues[1]
            val entityParam = lambdaMatch.groupValues[3]
            val lambdaBodyStart = lambdaArg.offset + lambdaMatch.range.last + 1
            val lambdaEnd = lambdaArg.offset + lambdaArg.text.length
            val zeroArgUseDuration = Regex("""\b${Regex.escape(stackParam)}\.getUseDuration\s*\(\s*\)""")
            zeroArgUseDuration.findAll(executableCode, lambdaBodyStart)
                .takeWhile { it.range.first < lambdaEnd }
                .filter { it.range.last < lambdaEnd }
                .forEach { match ->
                    replacements += match.range to "$stackParam.getUseDuration($entityParam)"
                }
            cursor = closeParen + 1
        }

        if (replacements.isEmpty()) return source
        var result = source
        replacements
            .distinctBy { it.first }
            .asReversed()
            .forEach { (range, replacement) ->
                result = result.replaceRange(range, replacement)
            }
        return result
    }

    private fun removeLegacyLootSerializerImports(source: String): String {
        var result = source
        result = removeImportLine(result, "net.minecraft.world.level.storage.loot.Serializer")
        val importCandidates = listOf(
            "com.google.gson.JsonDeserializationContext" to "JsonDeserializationContext",
            "com.google.gson.JsonObject" to "JsonObject",
            "com.google.gson.JsonSerializationContext" to "JsonSerializationContext",
            "com.google.gson.JsonSyntaxException" to "JsonSyntaxException",
            "net.minecraft.util.GsonHelper" to "GsonHelper"
        )
        for ((importName, symbol) in importCandidates) {
            if (!usesSymbolOutsideImports(result, symbol)) {
                result = removeImportLine(result, importName)
            }
        }
        return result
    }

    private fun javaTopLevelTypeName(source: String): String? =
        Regex("""(?m)^\s*public\s+(?:final\s+)?(?:class|record)\s+([A-Za-z_$][\w$]*)\b""")
            .find(maskJavaCommentsAndLiterals(source))?.groupValues?.get(1)

    private fun insertStaticFieldAfterTypeOpen(source: String, className: String, fieldSource: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (Regex("""MapCodec<\s*${Regex.escape(className)}\s*>\s+CODEC""").containsMatchIn(executableCode)) return source
        val typeMatch = Regex(
            """public\s+(?:final\s+)?(?:class|record)\s+${Regex.escape(className)}\b[\s\S]*?\{"""
        ).find(executableCode) ?: return source
        val insertAt = typeMatch.range.last + 1
        return source.substring(0, insertAt) + "\n\n" + fieldSource.trimEnd() + "\n" + source.substring(insertAt)
    }

    private fun innerClassText(source: String, simpleName: String): String? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val match = Regex("""(?m)^[ \t]*(?:public|protected|private)?\s*static\s+class\s+${Regex.escape(simpleName)}\b""")
            .find(executableCode) ?: return null
        val openBrace = executableCode.indexOf('{', match.range.last)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return null
        return source.substring(match.range.first, closeBrace + 1)
    }

    private fun removeInnerClassByName(source: String, simpleName: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val match = Regex("""(?m)^[ \t]*(?:public|protected|private)?\s*static\s+class\s+${Regex.escape(simpleName)}\b""")
            .find(executableCode) ?: return source
        val start = source.lastIndexOf('\n', match.range.first).let { if (it >= 0) it else match.range.first }
        val openBrace = executableCode.indexOf('{', match.range.last)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return source
        val end = if (closeBrace + 1 < source.length && source[closeBrace + 1] == '\n') closeBrace + 2 else closeBrace + 1
        return source.removeRange(start, end)
    }

    private fun migrateNeoForgeConditionSerializerCodecs(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("implements ICondition") || !executableCode.contains("IConditionSerializer")) {
            return source
        }
        val className = javaTopLevelTypeName(source) ?: return source
        val serializer = innerClassText(source, "Serializer") ?: return source
        val executableSerializer = maskJavaCommentsAndLiterals(serializer)
        if (!Regex("""implements\s+IConditionSerializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(executableSerializer)) {
            return source
        }

        val codecValue = when {
            Regex("""public\s+static\s+final\s+${Regex.escape(className)}\s+INSTANCE\b""").containsMatchIn(executableCode) -> "INSTANCE"
            Regex("""return\s+new\s+${Regex.escape(className)}\s*\(\s*\)\s*;""").containsMatchIn(executableSerializer) -> "new $className()"
            else -> return source
        }

        var result = insertNeoForgeConditionCodecField(
            source,
            className,
            "public static final MapCodec<$className> CODEC = MapCodec.unit($codecValue);",
            insertAfterInstance = codecValue == "INSTANCE"
        )
        val executableResult = maskJavaCommentsAndLiterals(result)
        if (!executableResult.contains("MapCodec<? extends ICondition> codec()")) {
            result = insertMethodAfterStaticCodec(
                result,
                className,
                """
	@Override
	public MapCodec<? extends ICondition> codec() {
		return CODEC;
	}
                """.trimIndent()
            )
        }
        result = removeFirstJavaMethodByName(result, "getID").first
        result = removeInnerClassByName(result, "Serializer")
        result = replaceUnusedResourceLocationIdConstantWithConditionId(result)
        result = removeImportLine(result, "net.neoforged.neoforge.common.conditions.IConditionSerializer")
        result = removeImportLine(result, "net.minecraftforge.common.crafting.conditions.IConditionSerializer")
        if (!usesSymbolOutsideImports(result, "JsonObject")) {
            result = removeImportLine(result, "com.google.gson.JsonObject")
        }
        if (!usesSymbolOutsideImports(result, "ResourceLocation")) {
            result = removeImportLine(result, "net.minecraft.resources.ResourceLocation")
        }
        return result
    }

    private fun insertNeoForgeConditionCodecField(
        source: String,
        className: String,
        fieldSource: String,
        insertAfterInstance: Boolean
    ): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (Regex("""MapCodec<\s*${Regex.escape(className)}\s*>\s+CODEC""").containsMatchIn(executableCode)) return source
        if (insertAfterInstance) {
            val instanceField = Regex(
                """(?m)^[ \t]*(?:public|protected|private)\s+static\s+final\s+${Regex.escape(className)}\s+INSTANCE\s*=\s*[^;\r\n]+;\s*$"""
            ).find(executableCode)
            if (instanceField != null) {
                val insertAt = instanceField.range.last + 1
                return source.substring(0, insertAt) + "\n" + fieldSource.trimEnd() + "\n" + source.substring(insertAt)
            }
        }
        return insertStaticFieldAfterTypeOpen(source, className, fieldSource)
    }

    private fun replaceUnusedResourceLocationIdConstantWithConditionId(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val match = Regex(
            """(?m)^[ \t]*(?:private|protected|public)\s+static\s+final\s+ResourceLocation\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]+;\s*\r?\n"""
        ).find(executableCode) ?: return source
        val name = match.groupValues[1]
        val declaration = source.substring(match.range.first, match.range.last + 1)
        val withoutField = source.removeRange(match.range)
        val bodyWithoutImports = withoutField.lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
        val executableBodyWithoutImports = maskJavaCommentsAndLiterals(bodyWithoutImports)
        if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(executableBodyWithoutImports)) return source
        val path = inferResourceLocationPath(declaration) ?: return source
        val indent = Regex("""(?m)^([ \t]*)""").find(declaration)?.groupValues?.get(1).orEmpty()
        val replacement = "${indent}public static final String CONDITION_ID = \"$path\";\n"
        return source.replaceRange(match.range, replacement)
    }

    private fun inferResourceLocationPath(declaration: String): String? {
        Regex("""\(\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)""").find(declaration)?.let { return it.groupValues[1] }
        Regex("""\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.prefix\s*\(\s*"([^"]+)"\s*\)""")
            .find(declaration)?.let { return it.groupValues[1] }
        Regex("""ResourceLocation\.parse\s*\(\s*"[^":]+:([^"]+)"\s*\)""").find(declaration)?.let { return it.groupValues[1] }
        Regex("""new\s+ResourceLocation\s*\(\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)""").find(declaration)?.let { return it.groupValues[1] }
        return null
    }

    private fun insertMethodAfterStaticCodec(source: String, className: String, methodSource: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val codecMatch = Regex(
            """public\s+static\s+final\s+MapCodec<\s*${Regex.escape(className)}\s*>\s+CODEC\s*=\s*[\s\S]*?;"""
        ).find(executableCode) ?: return source
        val insertAt = codecMatch.range.last + 1
        return source.substring(0, insertAt) + "\n\n" + methodSource.trimEnd() + "\n" + source.substring(insertAt)
    }

    private enum class LegacyCustomEnchantmentRegistrationStyle {
        DEFERRED_REGISTER,
        REGISTRATE
    }

    private data class LegacyCustomEnchantmentRegistration(
        val file: Path,
        val ownerPackage: String,
        val ownerClass: String,
        val modId: String,
        val fieldName: String,
        val registryName: String,
        val className: String,
        val constructorArgs: List<String>,
        val style: LegacyCustomEnchantmentRegistrationStyle = LegacyCustomEnchantmentRegistrationStyle.DEFERRED_REGISTER,
        val categoryOverride: LegacyExpression? = null,
        val rarityOverride: String? = null,
        val slotOverrides: List<String> = emptyList(),
        val declarationRange: IntRange? = null
    )

    private data class LegacyEnchantmentCategorySpec(
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val itemClassName: String?,
        /** The item class name is written in the declaring source, so it resolves there. */
        val declaringSource: String,
        val itemClassOffset: Int
    )

    private data class RegistryEntryRef(
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val modId: String,
        val path: String
    ) {
        val id: String get() = "$modId:$path"
    }

    private data class LegacyItemTagSpec(
        val namespace: String,
        val path: String,
        val values: List<String>
    )

    private data class LegacyRegistrateRef(
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val modId: String
    )

    private data class LegacyRegistrateFactory(
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val modId: String
    )

    private data class LegacyRegistrateInitializer(
        val file: Path,
        val source: String,
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val initializer: LegacyExpression
    )

    private data class LegacyTypeInfo(
        val name: String,
        val source: String,
        val declaration: JavaTypeDeclaration,
        val directSuperTypes: List<String>
    )

    private data class LegacyItemRegistryIndex(
        val itemIdsByType: Map<String, List<String>>,
        val typeInfos: Map<String, LegacyTypeInfo>
    )

    private data class LegacyJavaContext(
        val packageName: String,
        val typeImports: Map<String, String>,
        val wildcardImports: List<String>,
        val staticFieldImports: Map<String, String>,
        val staticWildcardImports: List<String>
    )

    private data class LegacyReferenceIndex<T>(
        val byExact: Map<String, T>
    )

    private data class LegacyExpression(
        val text: String,
        val offset: Int,
        val executableText: String = text
    )

    private data class LegacySourceSpan(val text: String, val offset: Int)

    private data class LinearIntValue(val base: Int, val perLevelAboveFirst: Int)

    private data class LinearFloatValue(val base: Double, val perLevelAboveFirst: Double)

    private data class LegacyEnchantmentDefinition(
        val registration: LegacyCustomEnchantmentRegistration,
        val supportedItems: String,
        val primaryItems: String?,
        val slots: List<String>,
        val weight: Int,
        val maxLevel: Int,
        val minCost: LinearIntValue,
        val maxCost: LinearIntValue,
        val anvilCost: Int,
        val exclusiveSet: List<String>,
        val effectsJson: String?,
        val itemTags: List<LegacyItemTagSpec>
    )

    private fun migrateLegacyCustomEnchantmentData(
        projectDir: Path,
        javaFiles: List<Path>,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        dryRun: Boolean
    ): List<LegacyCustomEnchantmentRegistration> {
        if (javaFiles.isEmpty()) return emptyList()
        val javaSources = javaFiles.map { it to it.readText() }
        val modIds = customEnchantmentDataStep("detectLegacyJavaModIds") {
            detectLegacyJavaModIds(javaSources)
        }
        val classSources = customEnchantmentDataStep("indexJavaClassSources") {
            indexJavaClassSources(javaSources)
        }
        val registrateRefs = customEnchantmentDataStep("collectLegacyRegistrateRefs") {
            collectLegacyRegistrateRefs(javaSources, modIds)
        }
        val registryEntries = customEnchantmentDataStep("collectLegacyRegistryEntries") {
            collectLegacyRegistryEntries(javaSources, modIds)
        }
        val itemRegistryEntries = customEnchantmentDataStep("collectLegacyItemRegistryEntries") {
            collectLegacyItemRegistryEntries(javaSources, modIds, registrateRefs)
        }
        val categories = customEnchantmentDataStep("collectLegacyEnchantmentCategories") {
            collectLegacyEnchantmentCategories(javaSources, classSources)
        }
        val registrations = customEnchantmentDataStep("collectLegacyCustomEnchantmentRegistrations") {
            collectLegacyCustomEnchantmentRegistrations(
                javaSources,
                modIds,
                classSources,
                registrateRefs,
                errors
            )
        }
        if (registrations.isEmpty()) return emptyList()

        val enchantmentRefs = legacyReferenceIndex(
            registrations,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
        val writtenTags = linkedSetOf<String>()
        val migratedRegistrations = mutableListOf<LegacyCustomEnchantmentRegistration>()
        val migratedDefinitions = mutableListOf<LegacyEnchantmentDefinition>()

        for (registration in registrations) {
            val definition = deriveLegacyEnchantmentDefinition(
                registration = registration,
                classSources = classSources,
                categories = categories,
                registryEntries = registryEntries,
                itemRegistryEntries = itemRegistryEntries,
                enchantmentRefs = enchantmentRefs,
                errors = errors
            ) ?: continue

            val target = projectDir.resolve("src/generated/resources/data/${registration.modId}/enchantment/${registration.registryName}.json")
            changes.add(Change(
                file = target,
                line = 1,
                description = "Create source-derived data-driven custom enchantment '${registration.modId}:${registration.registryName}'",
                before = "legacy Enchantment subclass registration",
                after = "data/${registration.modId}/enchantment/${registration.registryName}.json",
                confidence = Confidence.HIGH,
                ruleId = "text-custom-enchantment-data"
            ))
            if (!dryRun) {
                target.parent.createDirectories()
                target.writeText(legacyEnchantmentJson(definition))
            }
            migratedRegistrations += registration
            migratedDefinitions += definition

            for (tag in definition.itemTags) {
                val tagKey = "${tag.namespace}:${tag.path}"
                if (!writtenTags.add(tagKey)) continue
                val tagTarget = projectDir.resolve("src/generated/resources/data/${tag.namespace}/tags/item/${tag.path}.json")
                changes.add(Change(
                    file = tagTarget,
                    line = 1,
                    description = "Create item tag '$tagKey' for source-derived enchantment item support",
                    before = "legacy EnchantmentCategory predicate",
                    after = "data/${tag.namespace}/tags/item/${tag.path}.json",
                    confidence = Confidence.HIGH,
                    ruleId = "text-custom-enchantment-item-tag"
                ))
                if (!dryRun) {
                    tagTarget.parent.createDirectories()
                    tagTarget.writeText(legacyItemTagJson(tag.values))
                }
            }
        }
        changes += migrateLegacyEnchantmentMarkerInterfaces(
            definitions = migratedDefinitions,
            javaFiles = javaFiles,
            classSources = classSources,
            errors = errors,
            dryRun = dryRun
        )
        return migratedRegistrations
    }

    private fun migrateLegacyEnchantmentMarkerInterfaces(
        definitions: List<LegacyEnchantmentDefinition>,
        javaFiles: List<Path>,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>,
        dryRun: Boolean
    ): List<Change> {
        val markerOwners = definitions.mapNotNull { definition ->
            if (definition.itemTags.isEmpty()) return@mapNotNull null
            val classSource = uniqueLegacyClassSource(definition.registration.className, classSources)?.second
                ?: return@mapNotNull null
            val marker = Regex("""stack\.getItem\(\)\s+instanceof\s+([A-Za-z_$][\w$]*)""")
                .find(maskJavaCommentsAndLiterals(classSource))
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            if (!Regex("""\binterface\s+${Regex.escape(marker)}\b""")
                    .containsMatchIn(maskJavaCommentsAndLiterals(classSource))) {
                return@mapNotNull null
            }
            "${definition.registration.className}.$marker" to marker
        }.distinct()
        if (markerOwners.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        javaFiles.filter { it.exists() }.forEach { file ->
            val original = file.readText()
            var migrated = original
            markerOwners.forEach { (owner, marker) ->
                val importsMarker = Regex(
                    """(?m)^\s*import\s+${Regex.escape(owner)}\s*;\s*$"""
                ).containsMatchIn(migrated)
                if (!importsMarker && !Regex("""\bimplements\b[^\{;]*\b${Regex.escape(marker)}\b""")
                        .containsMatchIn(maskJavaCommentsAndLiterals(migrated))) {
                    return@forEach
                }
                val withoutInterface = removeImplementedInterface(migrated, marker) ?: return@forEach
                val withoutImport = removeImportLine(withoutInterface, owner)
                if (Regex("""\b${Regex.escape(marker)}\b""")
                        .containsMatchIn(maskJavaCommentsAndLiterals(withoutImport))) {
                    errors += "Cannot remove migrated enchantment marker $owner from $file: executable references remain"
                    return@forEach
                }
                migrated = withoutImport
            }
            if (migrated != original) {
                if (!dryRun) file.writeText(migrated)
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Replace custom enchantment marker-interface membership with generated item-tag membership",
                    before = "item class implements custom enchantment marker interface",
                    after = "data/<namespace>/tags/item entry consumed by enchantment supported_items",
                    confidence = Confidence.HIGH,
                    ruleId = "text-enchantment-marker-to-item-tag"
                )
            }
        }
        return changes
    }

    private fun removeImplementedInterface(source: String, marker: String): String? {
        val executable = maskJavaCommentsAndLiterals(source)
        val headerPattern = Regex("""\bclass\s+[A-Za-z_$][\w$]*(?:\s+extends\s+[^\{]+?)?\s+implements\s+([^\{]+)\{""")
        val matches = headerPattern.findAll(executable).toList()
        var result = source
        var changed = false
        matches.asReversed().forEach { match ->
            val interfaces = splitTopLevelArguments(source.substring(match.groups[1]!!.range))
            val retained = interfaces.filterNot { it.trim().substringAfterLast('.') == marker }
            if (retained.size == interfaces.size) return@forEach
            val implementsStart = executable.lastIndexOf("implements", match.groups[1]!!.range.first)
            if (implementsStart < 0) return@forEach
            val replacement = if (retained.isEmpty()) "" else "implements ${retained.joinToString(", ")} "
            result = result.replaceRange(implementsStart, match.groups[1]!!.range.last + 1, replacement)
            changed = true
        }
        return result.takeIf { changed }
    }

    private fun removeMigratedTopLevelCustomEnchantmentClasses(
        registrations: List<LegacyCustomEnchantmentRegistration>,
        javaFiles: List<Path>,
        dryRun: Boolean,
        errors: MutableList<String>
    ): List<Change> {
        if (registrations.isEmpty()) return emptyList()
        val uniqueRegistrations = registrations.distinctBy { it.className }
        val existingSources = javaFiles
            .filter { it.exists() }
            .associateWith { it.readText() }
            .toMutableMap()
        if (existingSources.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        for (registration in uniqueRegistrations) {
            val simpleName = registration.className.substringAfterLast('.')
            val classFile = existingSources.keys.singleOrNull { it.fileName.toString() == "$simpleName.java" }
                ?: continue
            val source = existingSources[classFile] ?: continue
            if (!isTopLevelCustomEnchantmentClass(source, simpleName)) continue

            val retainedShell = retainReferencedNestedEnchantmentInterfaces(
                existingSources,
                classFile,
                registration.className,
                source
            )
            if (retainedShell != null) {
                changes.add(Change(
                    file = classFile,
                    line = 1,
                    description = "Retain referenced nested interfaces while removing migrated top-level Enchantment implementation",
                    before = "top-level class $simpleName extends Enchantment",
                    after = "plain container class with source-declared nested interfaces",
                    confidence = Confidence.HIGH,
                    ruleId = "custom-enchantment-retain-nested-interfaces"
                ))
                if (!dryRun) {
                    classFile.writeText(retainedShell)
                    existingSources[classFile] = retainedShell
                }
                continue
            }
            if (hasExecutableTypeReferenceAfterMigration(existingSources, classFile, simpleName)) {
                if (registration.style == LegacyCustomEnchantmentRegistrationStyle.REGISTRATE) {
                    errors.add(
                        "Cannot remove migrated custom enchantment class ${registration.className}: executable references remain outside source-declared nested interfaces"
                    )
                }
                continue
            }

            changes.add(Change(
                file = classFile,
                line = 1,
                description = "Remove migrated top-level custom Enchantment subclass after source-derived data generation",
                before = "top-level class $simpleName extends Enchantment",
                after = "data-driven enchantment JSON and ResourceKey registration",
                confidence = Confidence.HIGH,
                ruleId = "custom-enchantment-remove-top-level-subclass"
            ))
            if (!dryRun) {
                classFile.deleteIfExists()
                existingSources.remove(classFile)
                removeDeletedCustomEnchantmentImports(
                    registration.className,
                    classFile,
                    existingSources,
                    changes
                )
            }
        }
        return changes
    }

    private fun retainReferencedNestedEnchantmentInterfaces(
        sources: Map<Path, String>,
        classFile: Path,
        qualifiedClassName: String,
        source: String
    ): String? {
        val simpleName = qualifiedClassName.substringAfterLast('.')
        val declarations = findJavaTypeDeclarations(source)
        val outer = declarations.singleOrNull {
            it.kind == "class" &&
                it.name == simpleName &&
                declarations.none { parent ->
                    parent.openBrace < it.openBrace && parent.closeBrace > it.closeBrace
                }
        } ?: return null
        val directNestedInterfaces = declarations.filter { declaration ->
            declaration.kind == "interface" &&
                declaration.openBrace > outer.openBrace &&
                declaration.closeBrace < outer.closeBrace &&
                declarations.none { parent ->
                    parent !== outer &&
                        parent.openBrace < declaration.openBrace &&
                        parent.closeBrace > declaration.closeBrace
                }
        }
        if (directNestedInterfaces.isEmpty()) return null
        val nestedNames = directNestedInterfaces.map { it.name }.toSet()
        var foundNestedReference = false
        for ((file, candidateSource) in sources) {
            if (file == classFile || !file.exists()) continue
            var executable = maskJavaCommentsAndLiterals(candidateSource)
            executable = Regex(
                """(?m)^[ \t]*import\s+${Regex.escape(qualifiedClassName)}\s*;\s*$"""
            ).replace(executable, "")
            for (nestedName in nestedNames) {
                val nestedReference = Regex(
                    """\b${Regex.escape(simpleName)}\s*\.\s*${Regex.escape(nestedName)}\b"""
                )
                if (nestedReference.containsMatchIn(executable)) {
                    foundNestedReference = true
                    executable = nestedReference.replace(executable, "")
                }
            }
            if (Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(executable)) return null
        }
        if (!foundNestedReference) return null

        val outerLineStart = source.lastIndexOf('\n', outer.start).let { if (it < 0) 0 else it + 1 }
        val visibility = if (source.substring(outerLineStart, outer.start).contains("public")) "public " else ""
        val nestedSources = directNestedInterfaces.joinToString("\n\n") { declaration ->
            val start = annotationStartBefore(source, declaration.start)
            source.substring(start, declaration.closeBrace + 1).trimEnd()
        }
        val replacement = buildString {
            append(visibility).append("final class ").append(simpleName).append(" {\n")
            append("\tprivate ").append(simpleName).append("() {\n\t}\n\n")
            append(nestedSources)
            append("\n}")
        }
        return source.replaceRange(outerLineStart, outer.closeBrace + 1, replacement)
    }

    private fun removeDeletedCustomEnchantmentImports(
        qualifiedClassName: String,
        deletedClassFile: Path,
        existingSources: MutableMap<Path, String>,
        changes: MutableList<Change>
    ) {
        for ((file, source) in existingSources.toMap()) {
            if (file == deletedClassFile || !file.exists()) continue
            val migrated = removeImportLine(source, qualifiedClassName)
            if (migrated == source) continue
            changes += Change(
                file = file,
                line = 1,
                description = "Remove import of migrated data-driven custom Enchantment class",
                before = "import $qualifiedClassName;",
                after = "",
                confidence = Confidence.HIGH,
                ruleId = "custom-enchantment-remove-top-level-import"
            )
            file.writeText(migrated)
            existingSources[file] = migrated
        }
    }

    private fun isTopLevelCustomEnchantmentClass(source: String, simpleName: String): Boolean {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val declarations = findJavaTypeDeclarations(executableCode)
        if (declarations.count { it.kind == "class" } != 1) return false
        val declaration = declarations.singleOrNull { it.kind == "class" && it.name == simpleName } ?: return false
        val header = executableCode.substring(declaration.start, declaration.openBrace)
        return Regex(
            """\bclass\s+${Regex.escape(simpleName)}\s+extends\s+(?:net\.minecraft\.world\.item\.enchantment\.)?Enchantment\b"""
        ).containsMatchIn(header)
    }

    private fun hasExecutableTypeReferenceAfterMigration(
        sources: Map<Path, String>,
        classFile: Path,
        simpleName: String
    ): Boolean {
        val typeReference = Regex("""\b${Regex.escape(simpleName)}\b""")
        return sources.any { (file, source) ->
            if (file == classFile || !file.exists()) return@any false
            val executableCode = maskJavaCommentsAndLiterals(source)
            executableCode
                .lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .any { line -> typeReference.containsMatchIn(line) }
        }
    }

    private inline fun <T> customEnchantmentDataStep(step: String, block: () -> T): T {
        return try {
            block()
        } catch (error: StackOverflowError) {
            throw IllegalStateException(
                "Legacy custom enchantment data step '$step' overflowed the Java regex stack",
                error
            )
        }
    }

    private fun legacyJavaPackageName(source: String): String {
        val executableSource = maskJavaCommentsAndLiterals(source)
        return Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(executableSource)
            ?.groupValues
            ?.get(1)
            .orEmpty()
    }

    private fun legacyJavaContext(source: String): LegacyJavaContext {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val typeImports = linkedMapOf<String, String>()
        val wildcardImports = mutableListOf<String>()
        val staticFieldImports = linkedMapOf<String, String>()
        val staticWildcardImports = mutableListOf<String>()
        Regex("""(?m)^\s*import\s+(static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;""")
            .findAll(executableSource)
            .forEach { match ->
                val imported = match.groupValues[2]
                val isStatic = match.groupValues[1].isNotBlank()
                if (imported.endsWith(".*")) {
                    val owner = imported.removeSuffix(".*")
                    if (isStatic) {
                        staticWildcardImports += owner
                    } else {
                        wildcardImports += owner
                    }
                } else if (isStatic) {
                    staticFieldImports[imported.substringAfterLast('.')] = imported
                } else {
                    typeImports[imported.substringAfterLast('.')] = imported
                }
            }
        return LegacyJavaContext(
            packageName = legacyJavaPackageName(source),
            typeImports = typeImports,
            wildcardImports = wildcardImports,
            staticFieldImports = staticFieldImports,
            staticWildcardImports = staticWildcardImports
        )
    }

    private fun <T> legacyReferenceIndex(
        refs: Iterable<T>,
        fieldName: (T) -> String,
        ownerClass: (T) -> String,
        ownerPackage: (T) -> String
    ): LegacyReferenceIndex<T> {
        val byExact = linkedMapOf<String, T>()
        refs.forEach { ref ->
            val field = fieldName(ref)
            val owner = ownerClass(ref)
            val pkg = ownerPackage(ref)
            if (pkg.isNotBlank()) {
                byExact["$pkg.$owner.$field"] = ref
            } else {
                byExact["$owner.$field"] = ref
            }
        }
        return LegacyReferenceIndex(byExact = byExact)
    }

    private fun <T> resolveLegacyReferenceExpression(
        expression: String,
        source: String,
        offset: Int,
        index: LegacyReferenceIndex<T>
    ): T? {
        val ref = expression.trim()
        index.byExact[ref]?.let { return it }
        val field = ref.substringAfterLast('.')
        val owner = ref.substringBeforeLast('.', missingDelimiterValue = "")
        val context = legacyJavaContext(source)

        if (owner.isEmpty()) {
            context.staticFieldImports[field]?.let { imported ->
                index.byExact[imported]?.let { return it }
            }
            context.staticWildcardImports
                .mapNotNull { staticOwner -> index.byExact["$staticOwner.$field"] }
                .distinct()
                .singleOrNull()
                ?.let { return it }
            val ownerClass = javaTypeNameContainingOffset(source, offset) ?: return null
            index.byExact["$ownerClass.$field"]?.let { return it }
            if (context.packageName.isNotBlank()) {
                index.byExact["${context.packageName}.$ownerClass.$field"]?.let { return it }
            }
            return null
        }

        if (owner.contains('.')) {
            return index.byExact[ref]
        }

        context.typeImports[owner]?.let { importedOwner ->
            index.byExact["$importedOwner.$field"]?.let { return it }
        }
        if (context.packageName.isNotBlank()) {
            index.byExact["${context.packageName}.$owner.$field"]?.let { return it }
        }
        context.wildcardImports
            .mapNotNull { packageName -> index.byExact["$packageName.$owner.$field"] }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        return null
    }

    private fun resolveLegacyClassReference(
        typeName: String,
        source: String,
        classSources: Map<String, List<Pair<Path, String>>>
    ): String? {
        val trimmed = typeName.trim()
        val context = legacyJavaContext(source)
        if (trimmed.contains('.')) {
            if (classSources.containsKey(trimmed)) return trimmed
            val head = trimmed.substringBefore('.')
            val suffix = trimmed.substringAfter('.')
            context.typeImports[head]?.let { importedOwner ->
                val importedNested = "$importedOwner.$suffix"
                if (classSources.containsKey(importedNested)) return importedNested
            }
            if (context.packageName.isNotBlank()) {
                val samePackage = "${context.packageName}.$trimmed"
                if (classSources.containsKey(samePackage)) return samePackage
            }
            return null
        }
        context.typeImports[trimmed]?.let { imported ->
            if (classSources.containsKey(imported)) return imported
        }
        val localMatches = findJavaTypeDeclarations(source)
            .filter { declaration -> declaration.kind == "class" && declaration.name == trimmed }
            .map { declaration -> qualifiedLegacyTypeName(source, declaration) }
            .filter { classSources.containsKey(it) }
            .distinct()
        localMatches.singleOrNull()?.let { return it }
        if (context.packageName.isNotBlank()) {
            val samePackage = "${context.packageName}.$trimmed"
            if (classSources.containsKey(samePackage)) return samePackage
        } else if (classSources.containsKey(trimmed)) {
            return trimmed
        }
        val wildcardMatches = context.wildcardImports
            .map { packageName -> "$packageName.$trimmed" }
            .filter { classSources.containsKey(it) }
            .distinct()
        return wildcardMatches.singleOrNull()
    }

    private fun detectLegacyJavaModIds(javaSources: List<Pair<Path, String>>): Map<String, String> {
        val ids = linkedMapOf<String, String>()
        for ((_, source) in javaSources) {
            val code = maskJavaComments(source)
            val executableCode = maskJavaCommentsAndLiterals(source)
            val packageName = legacyJavaPackageName(source)
            Regex("""\bstatic\s+(?:final\s+)?String\s+([A-Za-z_$][\w$]*)\s*=\s*"([^"]+)"""")
                .findAll(code)
                .filter { match ->
                    val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                    executableSegment.contains("static") &&
                        executableSegment.contains("String") &&
                        executableSegment.contains("=")
                }
                .forEach { match ->
                    val ownerClass = javaTypeNameContainingOffset(code, match.range.first)
                        ?: return@forEach
                    ids["$ownerClass.${match.groupValues[1]}"] = match.groupValues[2]
                    if (packageName.isNotBlank()) {
                        ids["$packageName.$ownerClass.${match.groupValues[1]}"] = match.groupValues[2]
                    }
                }
            val typeDeclarations = findJavaTypeDeclarations(code)
            Regex("""@Mod\s*\(\s*"([^"]+)"\s*\)""")
                .findAll(code)
                .forEach { match ->
                    val declaration = typeDeclarations.firstOrNull { declaration ->
                        declaration.kind == "class" &&
                            declaration.start > match.range.last &&
                            executableCode.substring(match.range.last + 1, declaration.start)
                                .none { it == ';' || it == '{' || it == '}' }
                    } ?: return@forEach
                    val executableSegment = executableCode.substring(match.range.first, declaration.openBrace + 1)
                    if (!executableSegment.contains("@Mod") ||
                        !executableSegment.contains("class")) {
                        return@forEach
                    }
                    ids[declaration.name] = match.groupValues[1]
                    if (packageName.isNotBlank()) {
                        ids["$packageName.${declaration.name}"] = match.groupValues[1]
                    }
                }
        }
        return ids
    }

    private fun collectLegacyRegistrateRefs(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>
    ): LegacyReferenceIndex<LegacyRegistrateRef> {
        val initializers = collectLegacyRegistrateInitializers(javaSources)
        val refs = linkedMapOf<String, LegacyRegistrateRef>()

        fun addRef(initializer: LegacyRegistrateInitializer, modId: String): Boolean {
            val exact = if (initializer.ownerPackage.isBlank()) {
                "${initializer.ownerClass}.${initializer.fieldName}"
            } else {
                "${initializer.ownerPackage}.${initializer.ownerClass}.${initializer.fieldName}"
            }
            if (refs.containsKey(exact)) return false
            refs[exact] = LegacyRegistrateRef(
                ownerPackage = initializer.ownerPackage,
                ownerClass = initializer.ownerClass,
                fieldName = initializer.fieldName,
                modId = modId
            )
            return true
        }

        for (initializer in initializers) {
            val executableInitializer = initializer.initializer.executableText
            val createCall = Regex("""\b(?:new\s+)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.create\s*\(""")
                .find(executableInitializer)
                ?: continue
            val openParen = executableInitializer.indexOf('(', createCall.range.first)
            val closeParen = findMatchingDelimiter(executableInitializer, openParen, '(', ')')
            if (closeParen < 0) continue
            val args = splitTopLevelArguments(initializer.initializer.text.substring(openParen + 1, closeParen))
            val modIdExpression = args.firstOrNull() ?: continue
            val modId = resolveLegacyModIdExpression(
                modIdExpression,
                modIds,
                initializer.source,
                initializer.initializer.offset + openParen + 1
            ) ?: continue
            addRef(initializer, modId)
        }

        var changed: Boolean
        do {
            changed = false
            val fieldIndex = legacyReferenceIndex(
                refs.values,
                fieldName = { it.fieldName },
                ownerClass = { it.ownerClass },
                ownerPackage = { it.ownerPackage }
            )
            val factories = collectLegacyRegistrateFactories(javaSources, fieldIndex)
            val factoryIndex = legacyReferenceIndex(
                factories,
                fieldName = { it.fieldName },
                ownerClass = { it.ownerClass },
                ownerPackage = { it.ownerPackage }
            )

            for (initializer in initializers) {
                val exact = if (initializer.ownerPackage.isBlank()) {
                    "${initializer.ownerClass}.${initializer.fieldName}"
                } else {
                    "${initializer.ownerPackage}.${initializer.ownerClass}.${initializer.fieldName}"
                }
                if (refs.containsKey(exact)) continue
                val executableInitializer = initializer.initializer.executableText
                val call = Regex(
                    """^\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\(\s*\)(?:\s*\.[\s\S]*)?$"""
                ).find(executableInitializer) ?: continue
                val factory = resolveLegacyReferenceExpression(
                    call.groupValues[1],
                    initializer.source,
                    initializer.initializer.offset + call.range.first,
                    factoryIndex
                ) ?: continue
                changed = addRef(initializer, factory.modId) || changed
            }
        } while (changed)

        return legacyReferenceIndex(
            refs.values,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
    }

    private fun collectLegacyRegistrateInitializers(
        javaSources: List<Pair<Path, String>>
    ): List<LegacyRegistrateInitializer> {
        val results = mutableListOf<LegacyRegistrateInitializer>()
        val declaration = Regex(
            """(?m)^[ \t]*(?:public|protected|private)?[ \t]*static[ \t]+(?:final[ \t]+)?(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*Registrate(?:\s*<[^;\r\n=]+>)?\s+([A-Za-z_$][\w$]*)\s*="""
        )
        for ((file, source) in javaSources) {
            val executableCode = maskJavaCommentsAndLiterals(source)
            val ownerPackage = legacyJavaPackageName(source)
            for (match in declaration.findAll(executableCode)) {
                val ownerClass = javaTypeNameContainingOffset(source, match.range.first) ?: continue
                val initializerStart = match.range.last + 1
                val statementEnd = findJavaStatementEnd(executableCode, initializerStart)
                if (statementEnd < 0) continue
                val initializer = legacyExpressionFromSourceRange(
                    source,
                    initializerStart,
                    statementEnd
                ) ?: continue
                results += LegacyRegistrateInitializer(
                    file = file,
                    source = source,
                    ownerPackage = ownerPackage,
                    ownerClass = ownerClass,
                    fieldName = match.groupValues[1],
                    initializer = initializer
                )
            }
        }
        return results
    }

    private fun collectLegacyRegistrateFactories(
        javaSources: List<Pair<Path, String>>,
        registrateRefs: LegacyReferenceIndex<LegacyRegistrateRef>
    ): List<LegacyRegistrateFactory> {
        val results = mutableListOf<LegacyRegistrateFactory>()
        val methodPattern = Regex(
            """(?m)^[ \t]*(?:public|protected|private)[ \t]+static[ \t]+(?:final[ \t]+)?(?:<[^>\r\n]+>[ \t]+)?(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*Registrate(?:\s*<[^;\r\n=]+>)?\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*\{"""
        )
        for ((_, source) in javaSources) {
            val executableCode = maskJavaCommentsAndLiterals(source)
            val ownerPackage = legacyJavaPackageName(source)
            for (match in methodPattern.findAll(executableCode)) {
                val openBrace = executableCode.indexOf('{', match.range.first)
                val closeBrace = findMatchingBrace(executableCode, openBrace)
                if (closeBrace < 0) continue
                val returnMatch = Regex(
                    """\breturn\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"""
                ).find(executableCode, openBrace + 1)
                    ?.takeIf { it.range.last < closeBrace }
                    ?: continue
                val registrate = resolveLegacyReferenceExpression(
                    returnMatch.groupValues[1],
                    source,
                    returnMatch.range.first,
                    registrateRefs
                ) ?: continue
                val ownerClass = javaTypeNameContainingOffset(source, match.range.first) ?: continue
                results += LegacyRegistrateFactory(
                    ownerPackage = ownerPackage,
                    ownerClass = ownerClass,
                    fieldName = match.groupValues[1],
                    modId = registrate.modId
                )
            }
        }
        return results
    }

    private data class JavaTypeDeclaration(
        val kind: String,
        val name: String,
        val start: Int,
        val openBrace: Int,
        val closeBrace: Int
    )

    private fun findJavaTypeDeclarations(source: String): List<JavaTypeDeclaration> {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val declarations = mutableListOf<JavaTypeDeclaration>()
        val typePattern = Regex("""\b(class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b""")
        for (match in typePattern.findAll(executableSource)) {
            val openBrace = executableSource.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(executableSource, openBrace) else -1
            if (openBrace >= 0 && closeBrace > openBrace) {
                declarations += JavaTypeDeclaration(
                    kind = match.groupValues[1],
                    name = match.groupValues[2],
                    start = match.range.first,
                    openBrace = openBrace,
                    closeBrace = closeBrace
                )
            }
        }
        return declarations
    }

    private fun javaTypeNameContainingOffset(source: String, offset: Int): String? {
        val executableSource = maskJavaCommentsAndLiterals(source)
        var containingType: JavaTypeDeclaration? = null
        for (declaration in findJavaTypeDeclarations(source)) {
            if (offset in declaration.openBrace..declaration.closeBrace &&
                executableSource.substring(declaration.start, declaration.openBrace + 1).contains(declaration.kind)) {
                if (containingType == null || declaration.openBrace > containingType.openBrace) {
                    containingType = declaration
                }
            }
        }
        return containingType?.name
    }

    private fun indexJavaClassSources(javaSources: List<Pair<Path, String>>): Map<String, List<Pair<Path, String>>> {
        val result = linkedMapOf<String, MutableList<Pair<Path, String>>>()
        for ((file, source) in javaSources) {
            val packageName = legacyJavaPackageName(source)
            val declarations = findJavaTypeDeclarations(source)
            declarations.filter { it.kind == "class" }
                .forEach { declaration ->
                    val owners = declarations
                        .filter { candidate ->
                            candidate.openBrace < declaration.start && candidate.closeBrace > declaration.closeBrace
                        }
                        .sortedBy { it.openBrace }
                        .map { it.name }
                    val className = (owners + declaration.name).joinToString(".")
                    val key = if (packageName.isBlank()) className else "$packageName.$className"
                    result.getOrPut(key) { mutableListOf() }.add(file to source)
                }
        }
        return result
    }

    private fun uniqueLegacyClassSource(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>
    ): Pair<Path, String>? {
        val matches = classSources[className].orEmpty()
        if (matches.map { it.first }.distinct().size != 1) return null
        return matches.first()
    }

    private fun sourceForLegacyClass(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>
    ): Pair<Path, String>? {
        val matches = classSources[className].orEmpty()
        if (matches.isEmpty()) {
            errors.add("Cannot derive custom enchantment data for ${className.substringAfterLast('.')}: class source not found")
            return null
        }
        if (matches.map { it.first }.distinct().size > 1) {
            errors.add("Cannot derive custom enchantment data for ${className.substringAfterLast('.')}: class name is ambiguous")
            return null
        }
        return matches.first()
    }

    private fun collectLegacyCustomEnchantmentRegistrations(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>,
        classSources: Map<String, List<Pair<Path, String>>>,
        registrateRefs: LegacyReferenceIndex<LegacyRegistrateRef>,
        errors: MutableList<String>
    ): List<LegacyCustomEnchantmentRegistration> {
        val id = """[A-Za-z_$][\w$]*"""
        val qualifiedId = """$id(?:\.$id)*"""
        val registerPattern = Regex(
            """(?s)(?:public\s+)?static\s+final\s+DeferredRegister\s*<\s*Enchantment\s*>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        val results = mutableListOf<LegacyCustomEnchantmentRegistration>()

        for ((file, source) in javaSources) {
            val code = maskJavaComments(source)
            val executableCode = maskJavaCommentsAndLiterals(source)
            if (!executableCode.contains("DeferredRegister") ||
                !executableCode.contains(".register(") ||
                !executableCode.contains("Enchantment")) continue
            val ownerPackage = legacyJavaPackageName(source)

            for (registerMatch in registerPattern.findAll(code)) {
                val registerSegment = executableCode.substring(registerMatch.range.first, registerMatch.range.last + 1)
                if (!registerSegment.contains("static") ||
                    !registerSegment.contains("final") ||
                    !registerSegment.contains("DeferredRegister") ||
                    !registerSegment.contains("Enchantment") ||
                    !registerSegment.contains("create")) continue
                val registerField = registerMatch.groupValues[1]
                val modIdExpression = registerMatch.groupValues[2].trim()
                val modId = resolveLegacyModIdExpression(modIdExpression, modIds, code, registerMatch.range.first)
                if (modId == null) {
                    errors.add("Cannot derive custom enchantment data in ${file.fileName}: unresolved mod id expression '$modIdExpression'")
                    continue
                }

                val entryPattern = Regex(
                    """(?s)(?:public\s+)?static\s+final\s+(?:RegistryObject|DeferredHolder)\s*<[^;=]+>\s+($id)\s*=\s*${Regex.escape(registerField)}\.register\(\s*"([^"]+)"\s*,\s*(.*?)\s*\)\s*;"""
                )
                for (entryMatch in entryPattern.findAll(code)) {
                    val entrySegment = executableCode.substring(entryMatch.range.first, entryMatch.range.last + 1)
                    if (!entrySegment.contains("static") ||
                        !entrySegment.contains("final") ||
                        !entrySegment.contains(".register(") ||
                        !entrySegment.contains(registerField) ||
                        !(entrySegment.contains("RegistryObject") || entrySegment.contains("DeferredHolder"))) continue
                    val ownerClass = javaTypeNameContainingOffset(code, entryMatch.range.first)
                    if (ownerClass == null) {
                        errors.add("Cannot derive custom enchantment data for ${entryMatch.groupValues[1]}: declaring Java type is unresolved")
                        continue
                    }
                    val supplier = entryMatch.groupValues[3].trim()
                    val newMatch = Regex("""(?s)(?:\(\)\s*->\s*)?new\s+($qualifiedId)\s*\((.*)\)\s*$""").find(supplier)
                    val methodRefMatch = Regex("""^\s*($qualifiedId)::new\s*$""").find(supplier)
                    val className = newMatch?.groupValues?.get(1)
                        ?: methodRefMatch?.groupValues?.get(1)
                    if (className == null) {
                        errors.add("Cannot derive custom enchantment data for ${entryMatch.groupValues[1]}: unsupported supplier '$supplier'")
                        continue
                    }
                    val resolvedClassName = resolveLegacyClassReference(className, source, classSources)
                    if (resolvedClassName == null) {
                        errors.add("Cannot derive custom enchantment data for ${entryMatch.groupValues[1]}: class reference '$className' is unresolved")
                        continue
                    }
                    val constructorArgs = newMatch?.groupValues?.get(2)?.let { splitTopLevelArguments(it) }.orEmpty()
                    results.add(LegacyCustomEnchantmentRegistration(
                        file = file,
                        ownerPackage = ownerPackage,
                        ownerClass = ownerClass,
                        modId = modId,
                        fieldName = entryMatch.groupValues[1],
                        registryName = entryMatch.groupValues[2],
                        className = resolvedClassName,
                        constructorArgs = constructorArgs,
                        declarationRange = entryMatch.range
                    ))
                }
            }
        }
        results += collectLegacyRegistrateCustomEnchantmentRegistrations(
            javaSources,
            classSources,
            registrateRefs,
            errors
        )
        return results
    }

    private fun collectLegacyRegistrateCustomEnchantmentRegistrations(
        javaSources: List<Pair<Path, String>>,
        classSources: Map<String, List<Pair<Path, String>>>,
        registrateRefs: LegacyReferenceIndex<LegacyRegistrateRef>,
        errors: MutableList<String>
    ): List<LegacyCustomEnchantmentRegistration> {
        val declarationPattern = Regex(
            """(?m)^[ \t]*(?:public|protected|private)?[ \t]*static[ \t]+final[ \t]+[^;\r\n=]+\s+([A-Za-z_$][\w$]*)\s*="""
        )
        val results = mutableListOf<LegacyCustomEnchantmentRegistration>()
        for ((file, source) in javaSources) {
            val executableCode = maskJavaCommentsAndLiterals(source)
            if (!executableCode.contains(".object(") ||
                !executableCode.contains(".enchantment(") ||
                !Regex("""\.register\s*\(\s*\)""").containsMatchIn(executableCode)) {
                continue
            }
            val ownerPackage = legacyJavaPackageName(source)
            for (declaration in declarationPattern.findAll(executableCode)) {
                val initializerStart = declaration.range.last + 1
                val statementEnd = findJavaStatementEnd(executableCode, initializerStart)
                if (statementEnd < 0) continue
                val initializer = maskJavaCommentsAndTextBlocks(
                    source.substring(initializerStart, statementEnd)
                )
                val executableInitializer = executableCode.substring(initializerStart, statementEnd)
                if (!executableInitializer.contains(".object(") ||
                    !executableInitializer.contains(".enchantment(") ||
                    !Regex("""\.register\s*\(\s*\)""").containsMatchIn(executableInitializer)) {
                    continue
                }

                val objectCall = Regex(
                    """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\.object\s*\("""
                ).find(executableInitializer)
                    ?: continue
                val registrate = resolveLegacyReferenceExpression(
                    objectCall.groupValues[1],
                    source,
                    initializerStart + objectCall.range.first,
                    registrateRefs
                )
                if (registrate == null) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: Registrate receiver '${objectCall.groupValues[1]}' is unresolved"
                    )
                    continue
                }
                val objectArgs = legacyCallArguments(
                    initializer,
                    executableInitializer,
                    objectCall.range.first,
                    "object"
                )
                val registryName = objectArgs?.singleOrNull()?.trim()?.let {
                    Regex("^\"([^\"]+)\"$").matchEntire(it)?.groupValues?.get(1)
                }
                if (registryName == null) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: Registrate object name is not a source string literal"
                    )
                    continue
                }

                val enchantmentCall = Regex("""\.enchantment\s*\(""").find(executableInitializer)
                val enchantmentArgs = enchantmentCall?.let {
                    legacyCallArgumentExpressions(
                        initializer,
                        executableInitializer,
                        it.range.first,
                        "enchantment",
                        initializerStart
                    )
                }
                if (enchantmentArgs == null || enchantmentArgs.size != 2) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: Registrate enchantment arguments are not structural"
                    )
                    continue
                }
                val supplier = enchantmentArgs[1].executableText.trim()
                val supplierClass = legacyRegistrateSupplierClass(supplier)
                if (supplierClass == null) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: unsupported Registrate supplier '${enchantmentArgs[1].text.trim()}'"
                    )
                    continue
                }
                val resolvedClassName = resolveLegacyClassReference(supplierClass, source, classSources)
                if (resolvedClassName == null) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: Registrate class reference '$supplierClass' is unresolved"
                    )
                    continue
                }

                val addSlotsCall = Regex("""\.addSlots\s*\(""").find(executableInitializer)
                val slotExpressions = addSlotsCall?.let { call ->
                    legacyCallArgumentExpressions(
                        initializer,
                        executableInitializer,
                        call.range.first,
                        "addSlots",
                        initializerStart
                    )?.map { it.executableText }
                }.orEmpty()
                if (slotExpressions.isEmpty()) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: Registrate equipment slots are unresolved"
                    )
                    continue
                }

                val rarityCall = Regex("""\.rarity\s*\(""").find(executableInitializer)
                val rarityExpression = rarityCall?.let { call ->
                    legacyCallArgumentExpressions(
                        initializer,
                        executableInitializer,
                        call.range.first,
                        "rarity",
                        initializerStart
                    )
                }?.singleOrNull()?.executableText
                if (rarityExpression == null) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: Registrate rarity is unresolved"
                    )
                    continue
                }

                val ownerClass = javaTypeNameContainingOffset(source, declaration.range.first)
                if (ownerClass == null) {
                    errors.add(
                        "Cannot derive custom enchantment data for ${declaration.groupValues[1]}: declaring Java type is unresolved"
                    )
                    continue
                }
                results += LegacyCustomEnchantmentRegistration(
                    file = file,
                    ownerPackage = ownerPackage,
                    ownerClass = ownerClass,
                    modId = registrate.modId,
                    fieldName = declaration.groupValues[1],
                    registryName = registryName,
                    className = resolvedClassName,
                    constructorArgs = emptyList(),
                    style = LegacyCustomEnchantmentRegistrationStyle.REGISTRATE,
                    categoryOverride = enchantmentArgs[0],
                    rarityOverride = rarityExpression,
                    slotOverrides = slotExpressions,
                    declarationRange = declaration.range.first..statementEnd
                )
            }
        }
        return results
    }

    private fun legacyRegistrateSupplierClass(executableSupplier: String): String? {
        val qualifiedId = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""
        Regex("""^($qualifiedId)\s*::\s*new$""")
            .matchEntire(executableSupplier)
            ?.groupValues
            ?.get(1)
            ?.let { return it }

        val lambda = Regex(
            """^\(\s*([^)]*?)\s*\)\s*->\s*new\s+($qualifiedId)\s*\(([\s\S]*)\)\s*$"""
        ).matchEntire(executableSupplier) ?: return null
        val parameters = splitTopLevelArguments(lambda.groupValues[1])
            .mapNotNull {
                Regex("""([A-Za-z_$][\w$]*)\s*$""")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
            }
        val constructorArgs = splitTopLevelArguments(lambda.groupValues[3])
            .map { it.trim() }
        if (parameters.size != 3 || constructorArgs != parameters) return null
        return lambda.groupValues[2]
    }

    private fun legacyCallArguments(
        source: String,
        executableSource: String,
        callStart: Int,
        methodName: String
    ): List<String>? = legacyCallArgumentExpressions(
        source,
        executableSource,
        callStart,
        methodName,
        0
    )?.map { it.text }

    private fun legacyCallArgumentExpressions(
        source: String,
        executableSource: String,
        callStart: Int,
        methodName: String,
        sourceOffset: Int
    ): List<LegacyExpression>? {
        val methodMatch = Regex("""\.?${Regex.escape(methodName)}\s*\(""").find(executableSource, callStart)
            ?: return null
        val openParen = executableSource.indexOf('(', methodMatch.range.first)
        val closeParen = findMatchingDelimiter(executableSource, openParen, '(', ')')
        if (openParen < 0 || closeParen < 0) return null
        return pairedLegacyArgumentExpressions(
            source.substring(openParen + 1, closeParen),
            executableSource.substring(openParen + 1, closeParen),
            sourceOffset + openParen + 1
        )
    }

    private fun legacyExpressionFromSourceRange(
        source: String,
        start: Int,
        endExclusive: Int
    ): LegacyExpression? {
        val literalText = maskJavaCommentsAndTextBlocks(source.substring(start, endExclusive))
        val executableText = maskJavaCommentsAndLiterals(source.substring(start, endExclusive))
        val first = literalText.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return null
        val last = literalText.indexOfLast { !it.isWhitespace() }
        return LegacyExpression(
            text = literalText.substring(first, last + 1),
            offset = start + first,
            executableText = executableText.substring(first, last + 1)
        )
    }

    private fun pairedLegacyArgumentExpressions(
        literalSource: String,
        executableSource: String,
        baseOffset: Int
    ): List<LegacyExpression> {
        require(literalSource.length == executableSource.length)
        return splitTopLevelArgumentExpressions(literalSource, baseOffset).map { expression ->
            val relativeStart = expression.offset - baseOffset
            expression.copy(
                executableText = executableSource.substring(
                    relativeStart,
                    relativeStart + expression.text.length
                )
            )
        }
    }

    private fun resolveLegacyModIdExpression(
        expression: String,
        modIds: Map<String, String>,
        source: String,
        offset: Int
    ): String? {
        val trimmed = expression.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) return trimmed.trim('"')
        modIds[trimmed]?.let { return it }

        if (!Regex("""^[A-Za-z_$][\w$]*$""").matches(trimmed)) return null
        val context = legacyJavaContext(source)
        context.staticFieldImports[trimmed]?.let { imported ->
            modIds[imported]?.let { return it }
        }
        context.staticWildcardImports
            .mapNotNull { importedOwner -> modIds["$importedOwner.$trimmed"] }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        val ownerClass = javaTypeNameContainingOffset(source, offset) ?: return null
        modIds["$ownerClass.$trimmed"]?.let { return it }
        if (context.packageName.isNotBlank()) {
            modIds["${context.packageName}.$ownerClass.$trimmed"]?.let { return it }
        }
        return null
    }

    private fun collectLegacyEnchantmentCategories(
        javaSources: List<Pair<Path, String>>,
        classSources: Map<String, List<Pair<Path, String>>>
    ): LegacyReferenceIndex<LegacyEnchantmentCategorySpec> {
        val id = """[A-Za-z_$][\w$]*"""
        val categoryPattern = Regex(
            """(?s)(?:public|private|protected)?\s*static\s+final\s+EnchantmentCategory\s+($id)\s*=\s*EnchantmentCategory\.create\(\s*[^,]+,\s*$id\s*->\s*[^;]*?\binstanceof\s+($id)\b[^;]*?\)\s*;"""
        )
        val categories = mutableListOf<LegacyEnchantmentCategorySpec>()
        for ((_, source) in javaSources) {
            val code = maskJavaComments(source)
            val executableCode = maskJavaCommentsAndLiterals(source)
            val ownerPackage = legacyJavaPackageName(source)
            for (match in categoryPattern.findAll(code)) {
                val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                if (!executableSegment.contains("static") ||
                    !executableSegment.contains("final") ||
                    !executableSegment.contains("EnchantmentCategory") ||
                    !executableSegment.contains("create") ||
                    !executableSegment.contains("instanceof")) continue
                val ownerClass = javaTypeNameContainingOffset(code, match.range.first)
                    ?: continue
                val itemClassName = canonicalLegacyTypeReference(
                    typeName = match.groupValues[2],
                    source = source,
                    classSources = classSources
                )
                val spec = LegacyEnchantmentCategorySpec(
                    ownerPackage = ownerPackage,
                    ownerClass = ownerClass,
                    fieldName = match.groupValues[1],
                    itemClassName = itemClassName ?: match.groupValues[2],
                    declaringSource = source,
                    itemClassOffset = match.groups[2]!!.range.first
                )
                categories += spec
            }
        }
        return legacyReferenceIndex(
            categories,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
    }

    private fun collectLegacyRegistryEntries(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>
    ): LegacyReferenceIndex<RegistryEntryRef> {
        val id = """[A-Za-z_$][\w$]*"""
        val registerPattern = Regex(
            """(?s)(?:public\s+)?static\s+final\s+DeferredRegister\s*<[^>]+>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        val entries = mutableListOf<RegistryEntryRef>()
        for ((_, source) in javaSources) {
            val code = maskJavaComments(source)
            val executableCode = maskJavaCommentsAndLiterals(source)
            val ownerPackage = legacyJavaPackageName(source)
            for (registerMatch in registerPattern.findAll(code)) {
                val registerSegment = executableCode.substring(registerMatch.range.first, registerMatch.range.last + 1)
                if (!registerSegment.contains("static") ||
                    !registerSegment.contains("final") ||
                    !registerSegment.contains("DeferredRegister") ||
                    !registerSegment.contains("create")) continue
                val registerField = registerMatch.groupValues[1]
                val modId = resolveLegacyModIdExpression(
                    expression = registerMatch.groupValues[2],
                    modIds = modIds,
                    source = code,
                    offset = registerMatch.range.first
                ) ?: continue
                val entryPattern = Regex(
                    """(?s)(?:public\s+)?static\s+final\s+(?:RegistryObject|DeferredHolder|DeferredItem)\s*<[^;=]+>\s+($id)\s*=\s*${Regex.escape(registerField)}\.register\(\s*"([^"]+)""""
                )
                for (entryMatch in entryPattern.findAll(code)) {
                    val entrySegment = executableCode.substring(entryMatch.range.first, entryMatch.range.last + 1)
                    if (!entrySegment.contains("static") ||
                        !entrySegment.contains("final") ||
                        !entrySegment.contains(".register(") ||
                        !entrySegment.contains(registerField) ||
                        !(entrySegment.contains("RegistryObject") ||
                            entrySegment.contains("DeferredHolder") ||
                            entrySegment.contains("DeferredItem"))) continue
                    val ownerClass = javaTypeNameContainingOffset(code, entryMatch.range.first)
                        ?: continue
                    val ref = RegistryEntryRef(
                        ownerPackage = ownerPackage,
                        ownerClass = ownerClass,
                        fieldName = entryMatch.groupValues[1],
                        modId = modId,
                        path = entryMatch.groupValues[2]
                    )
                    entries += ref
                }
            }
        }
        return legacyReferenceIndex(
            entries,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
    }

    private fun collectLegacyItemRegistryEntries(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>,
        registrateRefs: LegacyReferenceIndex<LegacyRegistrateRef>
    ): LegacyItemRegistryIndex {
        val typeInfos = indexLegacyTypeInfos(javaSources)
        val id = """[A-Za-z_$][\w$]*"""
        val registerPattern = Regex(
            """(?s)DeferredRegister\s*<\s*Item\s*>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        val results = linkedMapOf<String, MutableList<String>>()

        for ((_, source) in javaSources) {
            val code = maskJavaComments(source)
            val literalCode = maskJavaCommentsAndTextBlocks(source)
            val executableCode = maskJavaCommentsAndLiterals(source)
            for (registerMatch in registerPattern.findAll(code)) {
                val registerSegment = executableCode.substring(registerMatch.range.first, registerMatch.range.last + 1)
                if (!registerSegment.contains("DeferredRegister") ||
                    !registerSegment.contains("Item") ||
                    !registerSegment.contains("create")) continue
                val registerField = registerMatch.groupValues[1]
                val modId = resolveLegacyModIdExpression(
                    expression = registerMatch.groupValues[2],
                    modIds = modIds,
                    source = code,
                    offset = registerMatch.range.first
                ) ?: continue
                val entryPattern = Regex(
                    """(?s)${Regex.escape(registerField)}\.register\(\s*"([^"]+)"\s*,\s*(?:\(\)\s*->\s*)?new\s+($id)\s*\("""
                )
                for (entryMatch in entryPattern.findAll(code)) {
                    val entrySegment = executableCode.substring(entryMatch.range.first, entryMatch.range.last + 1)
                    if (!entrySegment.contains(registerField) ||
                        !entrySegment.contains(".register(") ||
                        !entrySegment.contains("new")) continue
                    val resolvedClass = resolveLegacyTypeReference(
                        entryMatch.groupValues[2],
                        source,
                        entryMatch.range.first,
                        typeInfos
                    ) ?: legacyVisibleTypeIdentity(
                        entryMatch.groupValues[2],
                        source
                    ) ?: continue
                    results.getOrPut(resolvedClass) { mutableListOf() }.add("$modId:${entryMatch.groupValues[1]}")
                }
            }

            val itemCallPattern = Regex(
                """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\.item\s*\("""
            )
            for (itemCall in itemCallPattern.findAll(executableCode)) {
                val registrate = resolveLegacyReferenceExpression(
                    itemCall.groupValues[1],
                    source,
                    itemCall.range.first,
                    registrateRefs
                ) ?: continue
                val openParen = executableCode.indexOf('(', itemCall.range.first)
                val closeParen = findMatchingDelimiter(executableCode, openParen, '(', ')')
                if (openParen < 0 || closeParen < 0) continue
                val chainEnd = findFluentChainTerminator(executableCode, closeParen + 1)
                if (chainEnd <= closeParen ||
                    !Regex("""\.register\s*\(\s*\)""")
                        .containsMatchIn(executableCode.substring(closeParen + 1, chainEnd))) {
                    continue
                }
                val args = pairedLegacyArgumentExpressions(
                    literalCode.substring(openParen + 1, closeParen),
                    executableCode.substring(openParen + 1, closeParen),
                    openParen + 1
                )
                if (args.size < 2) continue
                val itemPath = Regex("^\"([^\"]+)\"$").matchEntire(args[0].text.trim())
                    ?.groupValues
                    ?.get(1)
                    ?: continue
                val factory = args[1].executableText.trim()
                val classReference = Regex(
                    """^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::new$"""
                ).matchEntire(factory)?.groupValues?.get(1)
                    ?: Regex(
                        """^(?:[A-Za-z_$][\w$]*\s*->|\([^)]*\)\s*->)\s*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\("""
                    ).find(factory)?.groupValues?.get(1)
                    ?: continue
                val resolvedClass = resolveLegacyTypeReference(
                    classReference,
                    source,
                    itemCall.range.first,
                    typeInfos
                ) ?: continue
                results.getOrPut(resolvedClass) { mutableListOf() }.add("${registrate.modId}:$itemPath")
            }
        }
        return LegacyItemRegistryIndex(
            itemIdsByType = results.mapValues { (_, values) -> values.distinct() },
            typeInfos = typeInfos
        )
    }

    private fun findFluentChainTerminator(executableSource: String, start: Int): Int {
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        for (index in start until executableSource.length) {
            when (executableSource[index]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth-- else return -1
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                ',', ';' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) return index
            }
        }
        return -1
    }

    private fun legacyVisibleTypeIdentity(typeName: String, source: String): String? {
        val raw = typeName.trim().substringBefore('<')
        if (!Regex("""^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$""").matches(raw)) return null
        val context = legacyJavaContext(source)
        val segments = raw.split('.')
        val first = segments.first()
        context.typeImports[first]?.let { imported ->
            return (listOf(imported) + segments.drop(1)).joinToString(".")
        }
        if (raw.contains('.')) return raw
        return if (context.packageName.isBlank()) raw else "${context.packageName}.$raw"
    }

    private fun indexLegacyTypeInfos(
        javaSources: List<Pair<Path, String>>
    ): Map<String, LegacyTypeInfo> {
        data class PendingType(
            val name: String,
            val source: String,
            val declaration: JavaTypeDeclaration,
            val rawSuperTypes: List<String>
        )

        val pending = mutableListOf<PendingType>()
        for ((_, source) in javaSources) {
            val packageName = legacyJavaPackageName(source)
            val declarations = findJavaTypeDeclarations(source)
            for (declaration in declarations) {
                val parents = declarations
                    .filter {
                        it.openBrace < declaration.openBrace &&
                            it.closeBrace > declaration.closeBrace
                    }
                    .sortedBy { it.openBrace }
                val ownerPath = (parents.map { it.name } + declaration.name).joinToString(".")
                val name = if (packageName.isBlank()) ownerPath else "$packageName.$ownerPath"
                val header = maskJavaCommentsAndLiterals(
                    source.substring(declaration.start, declaration.openBrace)
                )
                val rawSuperTypes = buildList {
                    Regex("""\bextends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
                        .find(header)
                        ?.groupValues
                        ?.get(1)
                        ?.let { add(it) }
                    Regex("""\bimplements\s+([\s\S]+)$""")
                        .find(header)
                        ?.groupValues
                        ?.get(1)
                        ?.let { implemented ->
                            splitTopLevelArguments(implemented).forEach { type ->
                                Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""")
                                    .find(type)
                                    ?.value
                                    ?.let { add(it) }
                            }
                        }
                    if (declaration.kind == "interface") {
                        Regex("""\bextends\s+([\s\S]+)$""")
                            .find(header)
                            ?.groupValues
                            ?.get(1)
                            ?.let { extended ->
                                splitTopLevelArguments(extended).forEach { type ->
                                    Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""")
                                        .find(type)
                                        ?.value
                                        ?.let { add(it) }
                                }
                            }
                    }
                }.distinct()
                pending += PendingType(name, source, declaration, rawSuperTypes)
            }
        }

        val names = pending.associateBy { it.name }
        return pending.associate { type ->
            type.name to LegacyTypeInfo(
                name = type.name,
                source = type.source,
                declaration = type.declaration,
                directSuperTypes = type.rawSuperTypes.mapNotNull { raw ->
                    resolveLegacyTypeReference(raw, type.source, type.declaration.start, names.keys)
                }
            )
        }
    }

    private fun resolveLegacyTypeReference(
        typeName: String,
        source: String,
        offset: Int,
        typeInfos: Map<String, LegacyTypeInfo>
    ): String? = resolveLegacyTypeReference(typeName, source, offset, typeInfos.keys)

    private fun resolveLegacyTypeReference(
        typeName: String,
        source: String,
        offset: Int,
        knownTypes: Set<String>
    ): String? {
        val raw = typeName.trim().substringBefore('<')
        knownTypes.singleOrNull { it == raw }?.let { return it }
        val context = legacyJavaContext(source)
        val segments = raw.split('.')
        val first = segments.firstOrNull() ?: return null
        context.typeImports[first]?.let { imported ->
            val candidate = (listOf(imported) + segments.drop(1)).joinToString(".")
            if (candidate in knownTypes) return candidate
        }
        val ownerClass = javaTypeNameContainingOffset(source, offset)
        if (context.packageName.isNotBlank()) {
            val samePackage = "${context.packageName}.$raw"
            if (samePackage in knownTypes) return samePackage
            if (ownerClass != null) {
                val nested = "${context.packageName}.$ownerClass.$raw"
                if (nested in knownTypes) return nested
            }
        } else {
            if (raw in knownTypes) return raw
            if (ownerClass != null && "$ownerClass.$raw" in knownTypes) return "$ownerClass.$raw"
        }
        val wildcardMatches = context.wildcardImports
            .map { "$it.$raw" }
            .filter { it in knownTypes }
            .distinct()
        return wildcardMatches.singleOrNull()
    }

    private fun legacyRegisteredItemIdsAssignableTo(
        itemClassName: String,
        source: String,
        offset: Int,
        registry: LegacyItemRegistryIndex
    ): List<String>? {
        val targetType = resolveLegacyTypeReference(
            itemClassName,
            source,
            offset,
            registry.typeInfos
        ) ?: legacyVisibleTypeIdentity(itemClassName, source)
        ?: return null
        if (targetType !in registry.typeInfos) {
            return registry.itemIdsByType[targetType]?.distinct()
        }
        val memo = mutableMapOf<String, Boolean>()
        fun isAssignable(typeName: String, visiting: MutableSet<String>): Boolean {
            memo[typeName]?.let { return it }
            if (typeName == targetType) return true
            if (!visiting.add(typeName)) return false
            val result = registry.typeInfos[typeName]
                ?.directSuperTypes
                .orEmpty()
                .any { isAssignable(it, visiting) }
            visiting.remove(typeName)
            memo[typeName] = result
            return result
        }
        return registry.itemIdsByType
            .filterKeys { isAssignable(it, mutableSetOf()) }
            .values
            .flatten()
            .distinct()
    }

    private fun canonicalLegacyTypeReference(
        typeName: String,
        source: String,
        classSources: Map<String, List<Pair<Path, String>>>
    ): String? {
        val type = typeName.trim().removeSuffix("[]").substringBefore('<').trim()
        if (!Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").matches(type)) return null
        val context = legacyJavaContext(source)
        val declarations = findJavaTypeDeclarations(source)

        if (type.contains('.')) {
            val head = type.substringBefore('.')
            val suffix = type.substringAfter('.', "")
            context.typeImports[head]?.let { imported -> return "$imported.$suffix" }
            if (head.firstOrNull()?.isLowerCase() == true) return type
            if (context.packageName.isNotBlank()) return "${context.packageName}.$type"
            return type
        }

        context.typeImports[type]?.let { return it }
        declarations
            .filter { it.name == type }
            .map { qualifiedLegacyTypeName(source, it, declarations) }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        val wildcardMatches = context.wildcardImports
            .map { packageName -> "$packageName.$type" }
            .filter { classSources.containsKey(it) }
            .distinct()
        if (wildcardMatches.size == 1) return wildcardMatches.single()
        if (context.packageName.isNotBlank()) return "${context.packageName}.$type"
        return type
    }

    private fun qualifiedLegacyTypeName(
        source: String,
        declaration: JavaTypeDeclaration,
        declarations: List<JavaTypeDeclaration> = findJavaTypeDeclarations(source)
    ): String {
        val owners = declarations
            .filter { candidate ->
                candidate.openBrace < declaration.start && candidate.closeBrace > declaration.closeBrace
            }
            .sortedBy { it.openBrace }
            .map { it.name }
        val localName = (owners + declaration.name).joinToString(".")
        val packageName = legacyJavaPackageName(source)
        return if (packageName.isBlank()) localName else "$packageName.$localName"
    }

    private fun deriveLegacyEnchantmentDefinition(
        registration: LegacyCustomEnchantmentRegistration,
        classSources: Map<String, List<Pair<Path, String>>>,
        categories: LegacyReferenceIndex<LegacyEnchantmentCategorySpec>,
        registryEntries: LegacyReferenceIndex<RegistryEntryRef>,
        itemRegistryEntries: LegacyItemRegistryIndex,
        enchantmentRefs: LegacyReferenceIndex<LegacyCustomEnchantmentRegistration>,
        errors: MutableList<String>
    ): LegacyEnchantmentDefinition? {
        val (_, source) = sourceForLegacyClass(registration.className, classSources, errors) ?: return null
        val superArgs = extractLegacyConstructorSuperArgs(source, registration.className)
        if (superArgs == null || superArgs.size < 3) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: constructor super arguments are not structural")
            return null
        }
        if (registration.style == LegacyCustomEnchantmentRegistrationStyle.REGISTRATE &&
            !hasDirectRegistrateConstructorForwarding(source, registration.className)) {
            errors.add(
                "Cannot derive custom enchantment data for ${registration.className}: Registrate constructor does not directly forward rarity, category, and slots to super"
            )
            return null
        }

        val constructorBindings = if (registration.style == LegacyCustomEnchantmentRegistrationStyle.DEFERRED_REGISTER) {
            legacyConstructorArgumentBindings(
                source,
                registration.className,
                registration.constructorArgs
            )
        } else {
            emptyMap()
        }
        val rarityExpression = registration.rarityOverride
            ?: constructorBindings[superArgs[0].text.trim()]
            ?: superArgs[0].text
        val rarity = resolveLegacyRarity(
            registration.constructorArgs,
            rarityExpression
        )
        if (rarity == null) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: rarity is unresolved")
            return null
        }

        val slots = parseLegacyEquipmentSlots(
            if (registration.slotOverrides.isNotEmpty()) {
                registration.slotOverrides.joinToString(", ")
            } else {
                constructorBindings[superArgs[2].text.trim()] ?: superArgs[2].text
            }
        )
        if (slots.isEmpty()) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: equipment slots are unresolved")
            return null
        }

        val boundCategory = constructorBindings[superArgs[1].text.trim()]
        val categoryExpression = registration.categoryOverride
            ?: boundCategory?.let {
                LegacyExpression(
                    text = it,
                    offset = registration.declarationRange?.first ?: 0,
                    executableText = maskJavaCommentsAndLiterals(it)
                )
            }
            ?: superArgs[1]
        val categoryReferenceSource = if (registration.categoryOverride != null || boundCategory != null) {
            registration.file.readText()
        } else {
            source
        }
        val itemTarget = deriveLegacyEnchantmentItemTarget(
            registration = registration,
            source = source,
            categoryExpression = categoryExpression,
            categoryReferenceSource = categoryReferenceSource,
            categories = categories,
            itemRegistryEntries = itemRegistryEntries,
            slots = slots,
            errors = errors
        ) ?: return null

        val maxLevel = parseLegacyIntReturnMethod(source, "getMaxLevel") ?: 1
        val minCost = resolveLegacyEnchantmentCost(
            className = registration.className,
            classSources = classSources,
            methodName = "getMinCost",
            inheritedCost = LinearIntValue(11, 10),
            minCost = null,
            registration = registration,
            errors = errors
        ) ?: return null
        val maxCost = resolveLegacyEnchantmentCost(
            className = registration.className,
            classSources = classSources,
            methodName = "getMaxCost",
            inheritedCost = LinearIntValue(minCost.base + 5, minCost.perLevelAboveFirst),
            minCost = minCost,
            registration = registration,
            errors = errors
        ) ?: return null
        val treasureOnly = legacyClassChainAny(registration.className, classSources, errors, registration) {
            legacyBooleanReturn(it, "isTreasureOnly") == true
        }
        val tradeable = legacyClassChainFirstBoolean(registration.className, classSources, errors, registration, "isTradeable")
        val discoverable = legacyClassChainFirstBoolean(registration.className, classSources, errors, registration, "isDiscoverable")
        val lootOnly = treasureOnly && tradeable == false && discoverable == false
        val weight = if (lootOnly) 1 else legacyRarityWeight(rarity)
        val anvilCost = if (lootOnly) 8 else legacyRarityAnvilCost(rarity)
        val effectErrorsBefore = errors.size
        val effectsJson = deriveLegacyEnchantmentEffects(source, registryEntries, errors, registration)
        if (errors.size > effectErrorsBefore) return null
        val exclusiveSet = deriveLegacyExclusiveSet(source, enchantmentRefs, errors, registration) ?: return null

        return LegacyEnchantmentDefinition(
            registration = registration,
            supportedItems = itemTarget.supportedItems,
            primaryItems = itemTarget.primaryItems,
            slots = itemTarget.slotGroups,
            weight = weight,
            maxLevel = maxLevel,
            minCost = minCost,
            maxCost = maxCost,
            anvilCost = anvilCost,
            exclusiveSet = exclusiveSet,
            effectsJson = effectsJson,
            itemTags = itemTarget.itemTags
        )
    }

    private data class LegacyItemTarget(
        val supportedItems: String,
        val primaryItems: String?,
        val slotGroups: List<String>,
        val itemTags: List<LegacyItemTagSpec>
    )

    private fun deriveLegacyEnchantmentItemTarget(
        registration: LegacyCustomEnchantmentRegistration,
        source: String,
        categoryExpression: LegacyExpression,
        categoryReferenceSource: String,
        categories: LegacyReferenceIndex<LegacyEnchantmentCategorySpec>,
        itemRegistryEntries: LegacyItemRegistryIndex,
        slots: List<String>,
        errors: MutableList<String>
    ): LegacyItemTarget? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val canEnchantItemClass = Regex("""stack\.getItem\(\)\s+instanceof\s+([A-Za-z_$][\w$]*)""")
            .find(executableCode)
            ?.groupValues
            ?.get(1)
        if (canEnchantItemClass == "ArmorItem") {
            return armorLegacyItemTarget(slots)
        }
        if (canEnchantItemClass != null) {
            return customItemLegacyTarget(
                registration,
                canEnchantItemClass,
                source,
                executableCode.indexOf(canEnchantItemClass),
                itemRegistryEntries,
                slots,
                errors
            )
        }

        val category = categoryExpression.executableText.trim()
        legacyVanillaEnchantmentCategoryTarget(category, slots)?.let { return it }

        val customCategory = resolveLegacyReferenceExpression(
            category,
            categoryReferenceSource,
            categoryExpression.offset,
            categories
        )
        if (customCategory?.itemClassName != null) {
            return customItemLegacyTarget(
                registration,
                customCategory.itemClassName,
                customCategory.declaringSource,
                customCategory.itemClassOffset,
                itemRegistryEntries,
                slots,
                errors
            )
        }

        errors.add("Cannot derive custom enchantment data for ${registration.className}: item support expression '${categoryExpression.text}' is unresolved")
        return null
    }

    private fun legacyVanillaEnchantmentCategoryTarget(category: String, slots: List<String>): LegacyItemTarget? {
        val categoryName = Regex("""(?:^|\.)EnchantmentCategory\.([A-Z_]+)$""")
            .find(category)
            ?.groupValues
            ?.get(1)
            ?: return null
        if (categoryName == "ARMOR") return armorLegacyItemTarget(slots)
        val supportedItems = when (categoryName) {
            "ARMOR_FEET" -> "#minecraft:enchantable/foot_armor"
            "ARMOR_LEGS" -> "#minecraft:enchantable/leg_armor"
            "ARMOR_CHEST" -> "#minecraft:enchantable/chest_armor"
            "ARMOR_HEAD" -> "#minecraft:enchantable/head_armor"
            "WEAPON" -> "#minecraft:enchantable/weapon"
            "DIGGER" -> "#minecraft:enchantable/mining"
            "FISHING_ROD" -> "#minecraft:enchantable/fishing"
            "TRIDENT" -> "#minecraft:enchantable/trident"
            "BREAKABLE" -> "#minecraft:enchantable/durability"
            "BOW" -> "#minecraft:enchantable/bow"
            "WEARABLE" -> "#minecraft:enchantable/equippable"
            "CROSSBOW" -> "#minecraft:enchantable/crossbow"
            "VANISHABLE" -> "#minecraft:enchantable/vanishing"
            else -> return null
        }
        return LegacyItemTarget(
            supportedItems = supportedItems,
            primaryItems = null,
            slotGroups = slotGroupsForLegacySlots(slots, customHand = false),
            itemTags = emptyList()
        )
    }

    private fun armorLegacyItemTarget(slots: List<String>): LegacyItemTarget {
        val normalized = slots.toSet()
        val allArmor = setOf("HEAD", "CHEST", "LEGS", "FEET")
        return if (normalized.containsAll(allArmor)) {
            LegacyItemTarget(
                supportedItems = "#minecraft:enchantable/armor",
                primaryItems = "#minecraft:enchantable/chest_armor",
                slotGroups = listOf("armor"),
                itemTags = emptyList()
            )
        } else {
            val slot = when {
                "FEET" in normalized -> "feet"
                "LEGS" in normalized -> "legs"
                "CHEST" in normalized -> "chest"
                "HEAD" in normalized -> "head"
                else -> "armor"
            }
            val tag = when (slot) {
                "feet" -> "#minecraft:enchantable/foot_armor"
                "legs" -> "#minecraft:enchantable/leg_armor"
                "chest" -> "#minecraft:enchantable/chest_armor"
                "head" -> "#minecraft:enchantable/head_armor"
                else -> "#minecraft:enchantable/armor"
            }
            LegacyItemTarget(tag, null, listOf(slot), emptyList())
        }
    }

    private fun customItemLegacyTarget(
        registration: LegacyCustomEnchantmentRegistration,
        itemClassName: String,
        itemClassSource: String,
        itemClassOffset: Int,
        itemRegistryEntries: LegacyItemRegistryIndex,
        slots: List<String>,
        errors: MutableList<String>
    ): LegacyItemTarget? {
        val itemIds = legacyRegisteredItemIdsAssignableTo(
            itemClassName,
            itemClassSource,
            itemClassOffset,
            itemRegistryEntries
        ).orEmpty()
            .filter { it.substringBefore(':') == registration.modId }
            .distinct()
        if (itemIds.isEmpty()) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: item class '$itemClassName' has no source registry entry")
            return null
        }
        val tagPath = if (itemIds.size == 1) {
            "enchantable/${itemIds.single().substringAfter(':')}"
        } else {
            "enchantable/${registration.registryName}"
        }
        val tag = LegacyItemTagSpec(registration.modId, tagPath, itemIds)
        return LegacyItemTarget(
            supportedItems = "#${registration.modId}:$tagPath",
            primaryItems = null,
            slotGroups = slotGroupsForLegacySlots(slots, customHand = true),
            itemTags = listOf(tag)
        )
    }

    private fun slotGroupsForLegacySlots(slots: List<String>, customHand: Boolean): List<String> {
        val normalized = slots.toSet()
        return when {
            normalized.containsAll(setOf("HEAD", "CHEST", "LEGS", "FEET")) -> listOf("armor")
            normalized == setOf("MAINHAND") -> listOf(if (customHand) "hand" else "mainhand")
            normalized == setOf("OFFHAND") -> listOf("offhand")
            normalized == setOf("MAINHAND", "OFFHAND") -> listOf("hand")
            normalized == setOf("FEET") -> listOf("feet")
            normalized == setOf("LEGS") -> listOf("legs")
            normalized == setOf("CHEST") -> listOf("chest")
            normalized == setOf("HEAD") -> listOf("head")
            else -> slots.map { it.lowercase() }
        }
    }

    private fun extractLegacyConstructorSuperArgs(source: String, className: String): List<LegacyExpression>? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val constructor = Regex("""(?s)\b${Regex.escape(className.substringAfterLast('.'))}\s*\([^)]*\)\s*\{""")
            .find(executableCode)
            ?: return null
        val openBrace = executableCode.indexOf('{', constructor.range.last)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return null
        val body = source.substring(openBrace + 1, closeBrace)
        val executableBody = executableCode.substring(openBrace + 1, closeBrace)
        val superCall = Regex("""\bsuper\s*\(""").find(executableBody) ?: return null
        val openParen = executableBody.indexOf('(', superCall.range.first)
        val closeParen = findMatchingDelimiter(executableBody, openParen, '(', ')')
        if (closeParen < 0) return null
        return splitTopLevelArgumentExpressions(
            body.substring(openParen + 1, closeParen),
            baseOffset = openBrace + 1 + openParen + 1
        )
    }

    private fun hasDirectRegistrateConstructorForwarding(source: String, className: String): Boolean {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val simpleName = className.substringAfterLast('.')
        val declaration = findJavaTypeDeclarations(executableCode).singleOrNull {
            it.kind == "class" && it.name == simpleName
        } ?: return false
        val header = executableCode.substring(declaration.start, declaration.openBrace)
        if (!Regex(
                """\bclass\s+${Regex.escape(simpleName)}\s+extends\s+(?:net\.minecraft\.world\.item\.enchantment\.)?Enchantment\b"""
            ).containsMatchIn(header)) {
            return false
        }
        val constructors = Regex(
            """(?m)^[ \t]*(?:(?:public|protected|private)[ \t]+)?${Regex.escape(simpleName)}\s*\(([^)]*)\)\s*\{"""
        ).findAll(executableCode)
        return constructors.any { constructor ->
            val parameters = splitTopLevelArguments(constructor.groupValues[1])
            if (parameters.size != 3) return@any false
            val parameterNames = parameters.mapNotNull { parameter ->
                Regex("""([A-Za-z_$][\w$]*)\s*$""")
                    .find(parameter)
                    ?.groupValues
                    ?.get(1)
            }
            if (parameterNames.size != 3) return@any false
            if (!Regex("""\bRarity\b""").containsMatchIn(parameters[0]) ||
                !Regex("""\bEnchantmentCategory\b""").containsMatchIn(parameters[1]) ||
                !Regex("""\bEquipmentSlot\b""").containsMatchIn(parameters[2]) ||
                !(parameters[2].contains("[]") || parameters[2].contains("..."))) {
                return@any false
            }

            val openBrace = executableCode.indexOf('{', constructor.range.last)
            val closeBrace = findMatchingBrace(executableCode, openBrace)
            if (openBrace < 0 || closeBrace < 0) return@any false
            val body = executableCode.substring(openBrace + 1, closeBrace)
            val superCall = Regex("""^\s*super\s*\(""").find(body) ?: return@any false
            val openParen = body.indexOf('(', superCall.range.first)
            val closeParen = findMatchingDelimiter(body, openParen, '(', ')')
            if (openParen < 0 || closeParen < 0) return@any false
            val superArgs = splitTopLevelArguments(body.substring(openParen + 1, closeParen))
                .map { it.trim() }
            superArgs == parameterNames
        }
    }

    private fun legacyConstructorArgumentBindings(
        source: String,
        className: String,
        constructorArgs: List<String>
    ): Map<String, String> {
        if (constructorArgs.isEmpty()) return emptyMap()
        val executable = maskJavaCommentsAndLiterals(source)
        val constructorName = Regex.escape(className.substringAfterLast('.'))
        val declaration = Regex("""\b$constructorName\s*\(""").find(executable) ?: return emptyMap()
        val openParen = executable.indexOf('(', declaration.range.first)
        val closeParen = findMatchingDelimiter(executable, openParen, '(', ')')
        if (openParen < 0 || closeParen <= openParen) return emptyMap()
        val parameters = splitTopLevelArguments(source.substring(openParen + 1, closeParen))
        if (parameters.size != constructorArgs.size) return emptyMap()
        val parameterNames = parameters.map { parameter ->
            Regex("""([A-Za-z_$][\w$]*)\s*$""").find(parameter.trim())?.groupValues?.get(1)
                ?: return emptyMap()
        }
        return parameterNames.zip(constructorArgs).toMap()
    }

    private fun resolveLegacyRarity(constructorArgs: List<String>, rarityExpression: String): String? {
        Regex("""(?:Enchantment\.)?Rarity\.([A-Z_]+)""").find(rarityExpression)?.let { return it.groupValues[1] }
        constructorArgs.forEach { arg ->
            Regex("""(?:Enchantment\.)?Rarity\.([A-Z_]+)""").find(arg)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun parseLegacyEquipmentSlots(expression: String): List<String> =
        Regex("""EquipmentSlot\.([A-Z_]+)""").findAll(expression).map { it.groupValues[1] }.toList()

    private fun parseLegacyIntReturnMethod(source: String, methodName: String): Int? {
        val body = legacyMethodBody(source, methodName) ?: return null
        val executableBody = maskJavaCommentsAndLiterals(body)
        return Regex("""return\s+(-?\d+)\s*;""").find(executableBody)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun legacyEnchantmentOwnerBody(
        source: String,
        className: String,
        registration: LegacyCustomEnchantmentRegistration,
        errors: MutableList<String>
    ): String? {
        val simpleName = className.substringAfterLast('.')
        val declarations = findJavaTypeDeclarations(source).filter {
            it.kind == "class" && it.name == simpleName
        }
        if (declarations.size != 1) {
            errors.add(
                "${registration.file}: custom enchantment '${registration.registryName}' requires exactly one " +
                    "class declaration for '$simpleName', found ${declarations.size}"
            )
            return null
        }
        val declaration = declarations.single()
        return source.substring(declaration.openBrace + 1, declaration.closeBrace)
    }

    private fun legacyEnchantmentBaseClass(source: String, className: String): String? {
        val simpleName = className.substringAfterLast('.')
        val executableSource = maskJavaCommentsAndLiterals(source)
        val declaration = findJavaTypeDeclarations(source).singleOrNull {
            it.kind == "class" && it.name == simpleName
        } ?: return null
        val header = executableSource.substring(declaration.start, declaration.openBrace)
        return Regex("""\bextends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
            .find(header)
            ?.groupValues
            ?.get(1)
    }

    private data class LegacyEnchantmentCostMethod(
        val parameterName: String,
        val expression: String?
    )

    private fun resolveLegacyEnchantmentCost(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        methodName: String,
        inheritedCost: LinearIntValue,
        minCost: LinearIntValue?,
        registration: LegacyCustomEnchantmentRegistration,
        errors: MutableList<String>
    ): LinearIntValue? {
        val visited = linkedSetOf<String>()
        fun resolve(currentClassName: String): LinearIntValue? {
            if (!visited.add(currentClassName)) {
                errors.add(
                    "${registration.file}: custom enchantment '${registration.registryName}' has a cyclic " +
                        "cost inheritance chain at '$currentClassName'"
                )
                return null
            }
            val matches = classSources[currentClassName].orEmpty()
            if (matches.map { it.first }.distinct().size != 1) {
                errors.add(
                    "${registration.file}: custom enchantment '${registration.registryName}' requires one source " +
                        "for cost owner '$currentClassName', found ${matches.map { it.first }.distinct().size}"
                )
                return null
            }
            val source = matches.first().second
            legacyEnchantmentOwnerBody(source, currentClassName, registration, errors) ?: return null
            val methods = legacyEnchantmentCostMethods(source, currentClassName, methodName)
            if (methods == null) {
                errors.add(
                    "${registration.file}: custom enchantment '${registration.registryName}' cannot parse " +
                        "$methodName(int) declarations in '$currentClassName'"
                )
                return null
            }
            if (methods.size > 1) {
                errors.add(
                    "${registration.file}: custom enchantment '${registration.registryName}' has ${methods.size} " +
                        "$methodName(int level) overrides in '$currentClassName'; refusing ambiguous cost ownership"
                )
                return null
            }
            if (methods.size == 1) {
                val method = methods.single()
                val parsed = method.expression?.let {
                    parseLegacyCostMethodBody(it, method.parameterName, methodName, minCost) {
                        // super.getMinCost binds statically to the parent's implementation.
                        val base = legacyEnchantmentBaseClass(source, currentClassName)
                        when (base) {
                            null -> null
                            "net.minecraft.world.item.enchantment.Enchantment" ->
                                LinearIntValue(11, 10)
                            else -> resolveLegacyClassReference(base, source, classSources)
                                ?.let { parent ->
                                    resolveLegacyEnchantmentCost(
                                        parent,
                                        classSources,
                                        "getMinCost",
                                        LinearIntValue(11, 10),
                                        null,
                                        registration,
                                        errors
                                    )
                                }
                                ?: if (base == "Enchantment") LinearIntValue(11, 10) else null
                        }
                    }
                }
                return parsed ?: run {
                    errors.add(
                        "${registration.file}: custom enchantment '${registration.registryName}' has an unsupported " +
                            "$methodName(int level) override in '$currentClassName'; refusing to guess a 1.21 linear cost"
                    )
                    null
                }
            }

            val baseClass = legacyEnchantmentBaseClass(source, currentClassName)
            if (baseClass == null) {
                errors.add(
                    "${registration.file}: custom enchantment '${registration.registryName}' has no resolvable " +
                        "cost parent for '$currentClassName'"
                )
                return null
            }
            if (baseClass == "net.minecraft.world.item.enchantment.Enchantment") return inheritedCost
            val resolvedBase = resolveLegacyClassReference(baseClass, source, classSources)
            if (resolvedBase != null) return resolve(resolvedBase)
            if (baseClass == "Enchantment") return inheritedCost
            errors.add(
                "${registration.file}: custom enchantment '${registration.registryName}' has unresolved cost " +
                    "parent '$baseClass' for '$currentClassName'; refusing to assume vanilla defaults"
            )
            return null
        }
        return resolve(className)
    }

    private fun legacyEnchantmentCostMethods(
        source: String,
        className: String,
        methodName: String
    ): List<LegacyEnchantmentCostMethod>? {
        return try {
            val simpleName = className.substringAfterLast('.')
            val parsed = localJavaParser().parse(source)
            if (!parsed.isSuccessful) return null
            val unit = parsed.result.orElse(null) ?: return null
            val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
            val owners = unit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { declaration ->
                    if (declaration.isInterface || declaration.nameAsString != simpleName) {
                        return@filter false
                    }
                    val ownerNames = mutableListOf<String>()
                    var current: com.github.javaparser.ast.Node? = declaration
                    while (current != null) {
                        if (current is ClassOrInterfaceDeclaration) {
                            ownerNames += current.nameAsString
                        }
                        current = current.parentNode.orElse(null)
                    }
                    val relativeName = ownerNames.asReversed().joinToString(".")
                    val qualifiedName = if (packageName.isBlank()) {
                        relativeName
                    } else {
                        "$packageName.$relativeName"
                    }
                    qualifiedName == className
                }
            if (owners.size != 1) return null
            owners.single().getMethodsByName(methodName)
                .filter { method ->
                    method.parameters.size == 1 &&
                        method.getParameter(0).type.isPrimitiveType &&
                        method.getParameter(0).type.asPrimitiveType().type ==
                        PrimitiveType.Primitive.INT
                }
                .map { method ->
                    LegacyEnchantmentCostMethod(
                        parameterName = method.getParameter(0).nameAsString,
                        expression = if (method.isPublic &&
                            method.type.isPrimitiveType &&
                            method.type.asPrimitiveType().type == PrimitiveType.Primitive.INT
                        ) {
                            val statement = method.body.orElse(null)
                                ?.statements
                                ?.singleOrNull() as? ReturnStmt
                            statement?.expression
                                ?.orElse(null)
                                ?.toString()
                                ?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    )
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun localJavaParser(): JavaParser =
        JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )

    private fun parseLegacyCostMethodBody(
        expression: String,
        parameterName: String,
        methodName: String,
        minCost: LinearIntValue?,
        superMinCost: () -> LinearIntValue? = { null }
    ): LinearIntValue? {
        val parameterReference = Regex(
            """(?<!\p{javaJavaIdentifierPart})(?<!\.)${Regex.escape(parameterName)}(?!\p{javaJavaIdentifierPart})"""
        )
        val normalizedExpression = parameterReference.replace(expression, "level")
        if (methodName == "getMaxCost" && minCost != null) {
            Regex("""(?:this\.)?getMinCost\(\s*level\s*\)\s*\+\s*(\d+)""")
                .matchEntire(normalizedExpression)?.let {
                val add = it.groupValues[1].toInt()
                return LinearIntValue(minCost.base + add, minCost.perLevelAboveFirst)
            }
        }
        if (methodName == "getMaxCost") {
            Regex("""super\.getMinCost\(\s*level\s*\)\s*\+\s*(\d+)""")
                .matchEntire(normalizedExpression)?.let {
                val parent = superMinCost() ?: return null
                return LinearIntValue(
                    parent.base + it.groupValues[1].toInt(),
                    parent.perLevelAboveFirst
                )
            }
        }
        Regex("""(-?\d+)\s*\+\s*\(\s*level\s*-\s*1\s*\)\s*\*\s*(-?\d+)""")
            .matchEntire(normalizedExpression)?.let {
            return LinearIntValue(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("""\(\s*level\s*-\s*1\s*\)\s*\*\s*(-?\d+)\s*\+\s*(-?\d+)""")
            .matchEntire(normalizedExpression)?.let {
            return LinearIntValue(it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        Regex("""level\s*\*\s*(-?\d+)""").matchEntire(normalizedExpression)?.let {
            val value = it.groupValues[1].toInt()
            return LinearIntValue(value, value)
        }
        Regex("""-?\d+""").matchEntire(normalizedExpression)?.let {
            return LinearIntValue(normalizedExpression.toInt(), 0)
        }
        return null
    }

    private fun legacyMethodBody(source: String, methodName: String): String? {
        return legacyMethodBodySpan(source, methodName)?.text
    }

    private fun legacyMethodBodySpan(source: String, methodName: String): LegacySourceSpan? {
        val declaration = findJavaMethodDeclaration(source, methodName) ?: return null
        val executableCode = maskJavaCommentsAndLiterals(source)
        val openBrace = executableCode.indexOf('{', declaration.range.last)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return null
        return LegacySourceSpan(
            text = source.substring(openBrace + 1, closeBrace),
            offset = openBrace + 1
        )
    }

    private fun legacyBooleanReturn(source: String, methodName: String): Boolean? {
        val body = legacyMethodBody(source, methodName) ?: return null
        val executableBody = maskJavaCommentsAndLiterals(body)
        return when (Regex("""return\s+(true|false)\s*;""").find(executableBody)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun legacyClassChainAny(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration,
        predicate: (String) -> Boolean
    ): Boolean {
        val visited = mutableSetOf<String>()
        fun walk(name: String): Boolean {
            if (!visited.add(name)) return false
            val matches = classSources[name].orEmpty()
            if (matches.map { it.first }.distinct().size > 1) {
                errors.add("Cannot derive custom enchantment data for ${registration.className}: class chain source '$name' is ambiguous")
                return false
            }
            val source = uniqueLegacyClassSource(name, classSources)?.second ?: return false
            if (predicate(source)) return true
            val executableSource = maskJavaCommentsAndLiterals(source)
            val base = Regex("""\bclass\s+${Regex.escape(name.substringAfterLast('.'))}\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
                .find(executableSource)
                ?.groupValues
                ?.get(1)
                ?: return false
            return walk(resolveLegacyClassReference(base, source, classSources) ?: return false)
        }
        return walk(className)
    }

    private fun legacyClassChainFirstBoolean(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration,
        methodName: String
    ): Boolean? {
        val visited = mutableSetOf<String>()
        fun walk(name: String): Boolean? {
            if (!visited.add(name)) return null
            val matches = classSources[name].orEmpty()
            if (matches.map { it.first }.distinct().size > 1) {
                errors.add("Cannot derive custom enchantment data for ${registration.className}: class chain source '$name' is ambiguous")
                return null
            }
            val source = uniqueLegacyClassSource(name, classSources)?.second ?: return null
            legacyBooleanReturn(source, methodName)?.let { return it }
            val executableSource = maskJavaCommentsAndLiterals(source)
            val base = Regex("""\bclass\s+${Regex.escape(name.substringAfterLast('.'))}\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
                .find(executableSource)
                ?.groupValues
                ?.get(1)
                ?: return null
            return walk(resolveLegacyClassReference(base, source, classSources) ?: return null)
        }
        return walk(className)
    }

    private fun legacyRarityWeight(rarity: String): Int = when (rarity) {
        "COMMON" -> 10
        "UNCOMMON" -> 5
        "RARE" -> 2
        "VERY_RARE" -> 1
        else -> 1
    }

    private fun legacyRarityAnvilCost(rarity: String): Int = when (rarity) {
        "COMMON" -> 1
        "UNCOMMON" -> 2
        "RARE" -> 4
        "VERY_RARE" -> 8
        else -> 1
    }

    private fun deriveLegacyExclusiveSet(
        source: String,
        enchantmentRefs: LegacyReferenceIndex<LegacyCustomEnchantmentRegistration>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration
    ): List<String>? {
        val bodySpan = legacyMethodBodySpan(source, "checkCompatibility") ?: return emptyList()
        val body = bodySpan.text
        val executableBody = maskJavaCommentsAndLiterals(body)
        val results = linkedSetOf<String>()
        var unresolved = false
        Regex("""other\s*!=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.get\(\)""")
            .findAll(executableBody)
            .forEach { match ->
                val expression = match.groupValues[1]
                val ref = resolveLegacyReferenceExpression(
                    expression,
                    source,
                    bodySpan.offset + match.range.first,
                    enchantmentRefs
                )
                if (ref == null) {
                    unresolved = true
                    errors.add("Cannot derive custom enchantment data for ${registration.className}: exclusive enchantment reference '$expression' is unresolved")
                } else {
                    results.add("${ref.modId}:${ref.registryName}")
                }
            }
        Regex("""other\s*!=\s*Enchantments\.([A-Z0-9_]+)""")
            .findAll(executableBody)
            .forEach { match -> results.add("minecraft:${match.groupValues[1].lowercase()}") }
        if (unresolved) return null
        return results.toList()
    }

    private fun deriveLegacyEnchantmentEffects(
        source: String,
        registryEntries: LegacyReferenceIndex<RegistryEntryRef>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration
    ): String? {
        val effects = linkedMapOf<String, MutableList<String>>()
        derivePostHurtIgniteEffect(source)?.let { effects.getOrPut("minecraft:post_attack") { mutableListOf() }.add(it) }
        derivePostHurtMobEffect(source, registryEntries, errors, registration)?.let { effects.getOrPut("minecraft:post_attack") { mutableListOf() }.add(it) }
        deriveDamageBonusEffect(source)?.let { effects.getOrPut("minecraft:damage") { mutableListOf() }.add(it) }

        val hasPostHurt = legacyMethodBody(source, "doPostHurt") != null
        val hasDamageBonus = legacyMethodBody(source, "getDamageBonus") != null
        if (hasPostHurt && !effects.containsKey("minecraft:post_attack")) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: doPostHurt body has no supported effect shape")
        }
        if (hasDamageBonus && !effects.containsKey("minecraft:damage")) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: getDamageBonus body has no supported value effect shape")
        }
        if (effects.isEmpty()) return null

        return buildString {
            append("{\n")
            effects.entries.forEachIndexed { index, (component, entries) ->
                if (index > 0) append(",\n")
                append("    \"").append(component).append("\": [\n")
                entries.forEachIndexed { entryIndex, entry ->
                    if (entryIndex > 0) append(",\n")
                    append(indentJson(entry, "      "))
                }
                append("\n    ]")
            }
            append("\n  }")
        }
    }

    private fun derivePostHurtIgniteEffect(source: String): String? {
        val body = legacyMethodBody(source, "doPostHurt") ?: return null
        val executableBody = maskJavaCommentsAndLiterals(body)
        if (!executableBody.contains("setSecondsOnFire(")) return null
        val duration = Regex("""setSecondsOnFire\(\s*(\d+)\s*\+\s*\([^)]*nextInt\(\s*level\s*\)\s*\*\s*(\d+)\s*\)""")
            .find(executableBody)
            ?.let { LinearFloatValue(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            ?: Regex("""setSecondsOnFire\(\s*(\d+)""").find(executableBody)?.let { LinearFloatValue(it.groupValues[1].toDouble(), 0.0) }
            ?: return null
        val chance = legacyRandomChancePerLevel(source) ?: return null
        return """
{
  "affected": "attacker",
  "effect": {
    "type": "minecraft:ignite",
    "duration": ${legacyLevelBasedValueJson(duration)}
  },
  "enchanted": "victim",
  "requirements": ${legacyRandomChanceJson(chance)}
}
""".trimIndent()
    }

    private fun derivePostHurtMobEffect(
        source: String,
        registryEntries: LegacyReferenceIndex<RegistryEntryRef>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration
    ): String? {
        val body = legacyMethodBody(source, "doPostHurt") ?: return null
        val helperCall = legacyMethodCalls(body)
            .firstOrNull { call ->
                call.executableArguments.size >= 4 &&
                    call.executableArguments[3].contains("shouldHit") &&
                    legacyMethodBody(source, call.name)
                        ?.let { maskJavaCommentsAndLiterals(it).contains("new MobEffectInstance(") } == true
            }
            ?: return null
        val helperName = helperCall.name
        val helperBodySpan = legacyMethodBodySpan(source, helperName) ?: return null
        val helperBody = helperBodySpan.text
        val executableHelperBody = maskJavaCommentsAndLiterals(helperBody)
        val effectRef = Regex("""new\s+MobEffectInstance\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.get\(\)""")
            .find(executableHelperBody)
            ?: return null
        val effectId = resolveLegacyReferenceExpression(
            effectRef.groupValues[1],
            source,
            helperBodySpan.offset + effectRef.range.first,
            registryEntries
        )?.id
            ?: run {
                errors.add("Cannot derive custom enchantment data for ${registration.className}: mob effect reference '${effectRef.groupValues[1]}' is unresolved")
                return null
            }
        val durationTicks = helperCall.executableArguments[1].trim().toDoubleOrNull() ?: return null
        val amplifier = parseLegacyAmplifierExpression(helperCall.executableArguments[2].trim()) ?: return null
        val chance = legacyRandomChancePerLevel(source) ?: return null
        val durationSeconds = LinearFloatValue(durationTicks / 20.0, 0.0)
        return """
{
  "affected": "attacker",
  "effect": {
    "type": "minecraft:apply_mob_effect",
    "to_apply": "$effectId",
    "min_duration": ${legacyLevelBasedValueJson(durationSeconds)},
    "max_duration": ${legacyLevelBasedValueJson(durationSeconds)},
    "min_amplifier": ${legacyLevelBasedValueJson(amplifier)},
    "max_amplifier": ${legacyLevelBasedValueJson(amplifier)}
  },
  "enchanted": "victim",
  "requirements": ${legacyRandomChanceJson(chance)}
}
""".trimIndent()
    }

    private data class LegacyMethodCall(
        val name: String,
        val executableArguments: List<String>
    )

    private fun legacyMethodCalls(source: String): List<LegacyMethodCall> {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val calls = mutableListOf<LegacyMethodCall>()
        val pattern = Regex("""\b([A-Za-z_$][\w$]*)\s*\(""")
        for (match in pattern.findAll(executableSource)) {
            val name = match.groupValues[1]
            if (name in setOf("if", "for", "while", "switch", "catch", "new", "return", "super", "this")) continue
            val openParen = executableSource.indexOf('(', match.range.first)
            if (openParen < 0) continue
            val closeParen = findMatchingDelimiter(executableSource, openParen, '(', ')')
            if (closeParen < 0) continue
            calls.add(
                LegacyMethodCall(
                    name = name,
                    executableArguments = splitTopLevelArguments(executableSource.substring(openParen + 1, closeParen))
                )
            )
        }
        return calls
    }

    private fun parseLegacyAmplifierExpression(expression: String): LinearFloatValue? {
        Regex("""level\s*-\s*(\d+)""").find(expression)?.let {
            val subtract = it.groupValues[1].toDouble()
            return LinearFloatValue(1.0 - subtract, 1.0)
        }
        Regex("""level""").find(expression)?.let {
            return LinearFloatValue(1.0, 1.0)
        }
        expression.toDoubleOrNull()?.let { return LinearFloatValue(it, 0.0) }
        return null
    }

    private fun deriveDamageBonusEffect(source: String): String? {
        val body = legacyMethodBody(source, "getDamageBonus") ?: return null
        val executableBody = maskJavaCommentsAndLiterals(body)
        val expression = Regex("""return\s+([^;]+);""").find(executableBody)?.groupValues?.get(1)?.trim() ?: return null
        val value = Regex("""(-?)\s*level\s*\*\s*([0-9]+(?:\.[0-9]+)?)[Ff]?""").find(expression)?.let {
            val sign = if (it.groupValues[1].contains("-")) -1.0 else 1.0
            it.groupValues[2].toDouble() * sign
        } ?: return null
        return """
{
  "effect": {
    "type": "minecraft:add",
    "value": ${legacyLevelBasedValueJson(LinearFloatValue(value, value))}
  }
}
""".trimIndent()
    }

    private fun legacyRandomChancePerLevel(source: String): LinearFloatValue? {
        val executableSource = maskJavaCommentsAndLiterals(source)
        Regex("""nextFloat\(\)\s*<\s*([0-9]+(?:\.[0-9]+)?)F?\s*\*\s*\(float\)\s*level""")
            .find(executableSource)
            ?.let {
                val value = it.groupValues[1].toDouble()
                return LinearFloatValue(value, value)
            }
        Regex("""nextFloat\(\)\s*<\s*([0-9]+(?:\.[0-9]+)?)F?\s*\*\s*level""")
            .find(executableSource)
            ?.let {
                val value = it.groupValues[1].toDouble()
                return LinearFloatValue(value, value)
            }
        return null
    }

    private fun legacyRandomChanceJson(chance: LinearFloatValue): String = """
{
  "chance": {
    "type": "minecraft:enchantment_level",
    "amount": ${legacyLevelBasedValueJson(chance)}
  },
  "condition": "minecraft:random_chance"
}
""".trimIndent()

    private fun legacyLevelBasedValueJson(value: LinearFloatValue): String {
        if (value.perLevelAboveFirst == 0.0) return formatJsonNumber(value.base)
        return """{
      "type": "minecraft:linear",
      "base": ${formatJsonNumber(value.base)},
      "per_level_above_first": ${formatJsonNumber(value.perLevelAboveFirst)}
    }"""
    }

    private fun legacyEnchantmentJson(definition: LegacyEnchantmentDefinition): String {
        val fields = mutableListOf<String>()
        fields.add("""  "anvil_cost": ${definition.anvilCost}""")
        fields.add("""  "description": {
    "translate": "enchantment.${definition.registration.modId}.${definition.registration.registryName}"
  }""")
        definition.effectsJson?.let { fields.add("""  "effects": $it""") }
        if (definition.exclusiveSet.isNotEmpty()) {
            fields.add("""  "exclusive_set": [
${definition.exclusiveSet.joinToString(",\n") { """    "$it"""" }}
  ]""")
        }
        fields.add("""  "max_cost": {
    "base": ${definition.maxCost.base},
    "per_level_above_first": ${definition.maxCost.perLevelAboveFirst}
  }""")
        fields.add("""  "max_level": ${definition.maxLevel}""")
        fields.add("""  "min_cost": {
    "base": ${definition.minCost.base},
    "per_level_above_first": ${definition.minCost.perLevelAboveFirst}
  }""")
        definition.primaryItems?.let { fields.add("""  "primary_items": "$it"""") }
        fields.add("""  "slots": [
${definition.slots.joinToString(",\n") { """    "$it"""" }}
  ]""")
        fields.add("""  "supported_items": "${definition.supportedItems}"""")
        fields.add("""  "weight": ${definition.weight}""")
        return "{\n" + fields.joinToString(",\n") + "\n}\n"
    }

    private fun legacyItemTagJson(values: List<String>): String =
        "{\n  \"values\": [\n" +
            values.joinToString(",\n") { "    \"$it\"" } +
            "\n  ]\n}\n"

    private fun indentJson(source: String, indent: String): String =
        source.lines().joinToString("\n") { line -> if (line.isBlank()) line else indent + line }

    private fun formatJsonNumber(value: Double): String {
        val text = if (value == value.toLong().toDouble()) "${value.toLong()}.0" else value.toString()
        return if (text == "-0.0") "0.0" else text
    }

    private fun migrateAllCustomEnchantmentResourceKeys(
        source: String,
        registrations: List<LegacyCustomEnchantmentRegistration>
    ): String {
        val deferredMigrated = migrateCustomEnchantmentResourceKeys(source)
        return migrateRegistrateCustomEnchantmentResourceKeys(deferredMigrated, registrations)
    }

    private fun migrateCustomEnchantmentResourceKeys(source: String): String {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("DeferredRegister") ||
            !executableCode.contains("DeferredHolder") ||
            !executableCode.contains("Enchantment") ||
            !executableCode.contains(".register(")) {
            return source
        }

        val id = """[A-Za-z_$][\w$]*"""
        val registerPattern = Regex(
            """public\s+static\s+final\s+DeferredRegister\s*<\s*Enchantment\s*>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        )
        val registerMatch = registerPattern.find(code)
            ?.takeIf { match ->
                val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                executableSegment.contains("static") &&
                    executableSegment.contains("final") &&
                    executableSegment.contains("DeferredRegister") &&
                    executableSegment.contains("Enchantment") &&
                    executableSegment.contains("create")
            }
            ?: return source
        val registerField = registerMatch.groupValues[1]
        val modIdExpression = registerMatch.groupValues[2]

        val entryPattern = Regex(
            """public\s+static\s+final\s+DeferredHolder\s*<\s*Enchantment\s*,\s*Enchantment\s*>\s+($id)\s*=\s*${Regex.escape(registerField)}\.register\(\s*"([^"]+)"\s*,\s*(?:$id(?:\.$id)*::new|\(\)\s*->\s*new\s+$id(?:\.$id)*\([^)]*\))\s*\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        )
        val entryMatches = entryPattern.findAll(code)
            .filter { match ->
                val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                    executableSegment.contains("static") &&
                    executableSegment.contains("final") &&
                    executableSegment.contains("DeferredHolder") &&
                    executableSegment.contains("Enchantment") &&
                    executableSegment.contains("$registerField.register(")
            }
            .toList()
        val entries = entryMatches.map { it.groupValues[1] to it.groupValues[2] }
        if (entries.isEmpty()) return source

        val helper = """
private static net.minecraft.core.Holder<Enchantment> holder(net.minecraft.world.level.Level level, net.minecraft.resources.ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
    }
        """.trimIndent()

        val replacements = mutableListOf(registerMatch.range to helper)
        for (match in entryMatches) {
            val name = match.groupValues[1]
            val registryName = match.groupValues[2]
            replacements += match.range to """
public static final net.minecraft.resources.ResourceKey<Enchantment> $name =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($modIdExpression, "$registryName"));
            """.trimIndent()
        }
        var result = source
        for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
            result = result.replaceRange(range, replacement)
        }
        val keys = entries.joinToString(", ") { it.first }
        if (keys.isNotBlank() &&
            !Regex("""\bENCHANTMENTS\s*=\s*java\.util\.List\.of\(""").containsMatchIn(maskJavaCommentsAndLiterals(result))) {
            val listField = """

public static final java.util.List<net.minecraft.resources.ResourceKey<Enchantment>> ENCHANTMENTS =
            java.util.List.of($keys);
            """.trimEnd()
            val insertAt = maskJavaCommentsAndLiterals(result).lastIndexOf('}')
            if (insertAt >= 0) {
                result = result.substring(0, insertAt).trimEnd() + "\n" + listField + "\n" + result.substring(insertAt)
            }
        }

        for ((name, _) in entries) {
            result = replaceExecutableRegex(
                result,
                Regex("""stack\.getEnchantmentLevel\(\s*${Regex.escape(name)}\.get\(\)\s*\)""")
            ) { "stack.getEnchantmentLevel(holder(level, $name))" }
            result = replaceExecutableRegex(
                result,
                Regex("""stack\.enchant\(\s*${Regex.escape(name)}\.get\(\)\s*,""")
            ) { "stack.enchant(holder(level, $name)," }
            result = replaceExecutableRegex(result, Regex(
                """public\s+static\s+boolean\s+([A-Za-z_$][\w$]*)\s*\(\s*Enchantment\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+\2\s*==\s*${Regex.escape(name)}\.get\(\)\s*;\s*\}"""
            )) { match ->
                val methodName = match.groupValues[1]
                val paramName = match.groupValues[2]
                """
public static boolean $methodName(net.minecraft.core.Holder<Enchantment> $paramName) {
        return $paramName.is($name);
    }
                """.trimIndent()
            }
        }

        return result
    }

    private fun migrateRegistrateCustomEnchantmentResourceKeys(
        source: String,
        registrations: List<LegacyCustomEnchantmentRegistration>
    ): String {
        val registrateEntries = registrations
            .filter { it.style == LegacyCustomEnchantmentRegistrationStyle.REGISTRATE }
        if (registrateEntries.isEmpty()) return source

        var result = source
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val literalCode = maskJavaCommentsAndTextBlocks(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val declarationPattern = Regex(
            """(?m)^[ \t]*(?:public|protected|private)?[ \t]*static[ \t]+final[ \t]+[^;\r\n=]+\s+([A-Za-z_$][\w$]*)\s*="""
        )
        for (declaration in declarationPattern.findAll(executableCode)) {
            val ownerClass = javaTypeNameContainingOffset(source, declaration.range.first) ?: continue
            val registration = registrateEntries.singleOrNull {
                it.fieldName == declaration.groupValues[1] &&
                    it.ownerClass == ownerClass
            } ?: continue
            val initializerStart = declaration.range.last + 1
            val statementEnd = findJavaStatementEnd(executableCode, initializerStart)
            if (statementEnd < 0) continue
            val literalInitializer = literalCode.substring(initializerStart, statementEnd)
            val executableInitializer = executableCode.substring(initializerStart, statementEnd)
            val objectCall = Regex("""\.object\s*\(""").find(executableInitializer) ?: continue
            val objectName = legacyCallArguments(
                literalInitializer,
                executableInitializer,
                objectCall.range.first,
                "object"
            )?.singleOrNull()?.trim()?.let {
                Regex("^\"([^\"]+)\"$").matchEntire(it)?.groupValues?.get(1)
            }
            if (objectName != registration.registryName ||
                !executableInitializer.contains(".enchantment(") ||
                !Regex("""\.register\s*\(\s*\)""").containsMatchIn(executableInitializer)) {
                continue
            }
            val replacement = """
public static final net.minecraft.resources.ResourceKey<Enchantment> ${registration.fieldName} =
        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("${registration.modId}", "${registration.registryName}"));
            """.trimIndent()
            replacements += declaration.range.first..statementEnd to replacement
        }
        if (replacements.size != registrateEntries.size) return source
        for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
            result = result.replaceRange(range, replacement)
        }

        val keys = registrateEntries.joinToString(", ") { it.fieldName }
        if (!Regex("""\bENCHANTMENTS\s*=\s*java\.util\.List\.of\(""")
                .containsMatchIn(maskJavaCommentsAndLiterals(result))) {
            val listField = """

public static final java.util.List<net.minecraft.resources.ResourceKey<Enchantment>> ENCHANTMENTS =
        java.util.List.of($keys);
            """.trimEnd()
            val insertAt = maskJavaCommentsAndLiterals(result).lastIndexOf('}')
            if (insertAt >= 0) {
                result = result.substring(0, insertAt).trimEnd() + "\n" + listField + "\n" + result.substring(insertAt)
            }
        }

        val registrateFieldNames = collectRegistrateFieldNames(result)
        for (fieldName in registrateFieldNames) {
            val withoutDeclaration = removeUnusedRegistrateField(result, fieldName)
            if (withoutDeclaration != null) result = withoutDeclaration
        }
        val withoutLegacyRarityImport = removeImportLine(
            result,
            "net.minecraft.world.item.enchantment.Enchantment.Rarity"
        )
        if (!Regex("""\bRarity\b""").containsMatchIn(maskJavaCommentsAndLiterals(withoutLegacyRarityImport))) {
            result = withoutLegacyRarityImport
        }
        return result
    }

    private fun collectRegistrateFieldNames(source: String): List<String> {
        val executableCode = maskJavaCommentsAndLiterals(source)
        return Regex(
            """(?m)^[ \t]*(?:public|protected|private)?[ \t]*static[ \t]+(?:final[ \t]+)?(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*Registrate(?:\s*<[^;\r\n=]+>)?\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(executableCode).map { it.groupValues[1] }.toList()
    }

    private fun removeUnusedRegistrateField(source: String, fieldName: String): String? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val references = Regex("""\b${Regex.escape(fieldName)}\b""").findAll(executableCode).toList()
        if (references.size != 1) return null
        val declaration = Regex(
            """(?m)^[ \t]*(?:public|protected|private)?[ \t]*static[ \t]+(?:final[ \t]+)?(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*Registrate(?:\s*<[^;\r\n=]+>)?\s+${Regex.escape(fieldName)}\s*="""
        ).find(executableCode) ?: return null
        val statementEnd = findJavaStatementEnd(executableCode, declaration.range.last + 1)
        if (statementEnd < 0) return null
        var end = statementEnd + 1
        if (source.getOrNull(end) == '\r') end++
        if (source.getOrNull(end) == '\n') end++
        return source.removeRange(declaration.range.first, end)
    }

    private fun migrateParticleNetworkCodecs(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("ParticleOptions") ||
            !executableCode.contains("getDeserializer().fromNetwork") ||
            !executableCode.contains("writeToNetwork")) {
            return source
        }

        var result = source
        val afterReadMigration = replaceExecutableRegex(
            result,
            Regex("""return\s+particleType\.getDeserializer\(\)\.fromNetwork\(\s*particleType\s*,\s*buf\s*\)\s*;"""),
        ) { "return particleType.streamCodec().decode(buf);" }
        if (afterReadMigration == result) return source
        result = afterReadMigration
        result = replaceExecutableRegex(
            result,
            Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.particleOptions\.writeToNetwork\(\s*buf\s*\)\s*;""")
        ) { match -> "writeParticle(${match.groupValues[1]}.particleOptions, buf);" }

        result = replaceExecutableRegex(result, Regex("""\bFriendlyByteBuf\b""")) { "RegistryFriendlyByteBuf" }

        if (!maskJavaCommentsAndLiterals(result).contains("void writeParticle(")) {
            val readParticlePattern = Regex(
                """(?s)(private\s+<T\s+extends\s+ParticleOptions>\s+T\s+readParticle\s*\(\s*ParticleType<T>\s+particleType\s*,\s*RegistryFriendlyByteBuf\s+buf\s*\)\s*\{\s*return\s+particleType\.streamCodec\(\)\.decode\(buf\);\s*\})"""
            )
            result = replaceExecutableRegex(result, readParticlePattern) { match ->
                match.value + """


	private <T extends ParticleOptions> void writeParticle(T particleOptions, RegistryFriendlyByteBuf buf) {
		@SuppressWarnings("unchecked")
		ParticleType<T> particleType = (ParticleType<T>) particleOptions.getType();
		particleType.streamCodec().encode(buf, particleOptions);
	}"""
            }
        }

        result = removeImportLine(result, "net.minecraft.network.FriendlyByteBuf")
        return result
    }

    private fun migrateJadeTooltipElementHelper(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains(".getElementHelper")) return source
        if (!executableCode.contains("import snownee.jade.api.ITooltip;") &&
            !executableCode.contains("snownee.jade.api.ITooltip")) {
            return source
        }

        val identifier = """[A-Za-z_$][\w$]*"""
        val tooltipVariables = Regex("""\b(?:snownee\.jade\.api\.)?ITooltip\s+($identifier)\b""")
            .findAll(executableCode)
            .map { it.groupValues[1] }
            .toSet()
        if (tooltipVariables.isEmpty()) return source

        var result = source
        for (variable in tooltipVariables) {
            result = replaceExecutableRegex(
                result,
                Regex("""\b${Regex.escape(variable)}\s*\.\s*getElementHelper\s*\(\s*\)""")
            ) { "IElementHelper.get()" }
        }
        return result
    }

    private data class NetworkHooksOpenScreenMigration(
        val content: String,
        val changes: List<Change>,
        val errors: List<String>
    )

    private fun migrateNetworkHooksOpenScreen(source: String, file: Path, projectDir: Path): NetworkHooksOpenScreenMigration {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("NetworkHooks.openScreen")) {
            return NetworkHooksOpenScreenMigration(source, emptyList(), emptyList())
        }

        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val out = StringBuilder(source.length)
        var cursor = 0
        var searchFrom = 0
        val callName = "NetworkHooks.openScreen"

        while (true) {
            val callStart = executableCode.indexOf(callName, searchFrom)
            if (callStart < 0) break
            val openParen = executableCode.indexOf('(', callStart + callName.length)
            if (openParen < 0) {
                errors.add("Cannot safely migrate NetworkHooks.openScreen in ${file}:${source.lineNumberAt(callStart)}: missing argument list")
                break
            }
            val closeParen = findMatchingDelimiter(executableCode, openParen, '(', ')')
            if (closeParen < 0) {
                errors.add("Cannot safely migrate NetworkHooks.openScreen in ${file}:${source.lineNumberAt(callStart)}: unbalanced argument list")
                break
            }

            val args = splitTopLevelArguments(source.substring(openParen + 1, closeParen))
            val replacement = when (args.size) {
                2 -> "(${args[0]}).openMenu(${args[1]})"
                3 -> {
                    val extraData = args[2]
                    when {
                        isNetworkHooksExtraDataWriter(extraData, source) ->
                            "(${args[0]}).openMenu(${args[1]}, $extraData)"
                        // IPlayerExtension#openMenu(MenuProvider, BlockPos) writes the position itself
                        isNetworkHooksBlockPosExtra(extraData, source, file, projectDir) ->
                            "(${args[0]}).openMenu(${args[1]}, $extraData)"
                        else -> null
                    }
                }
                else -> null
            }

            if (replacement == null) {
                errors.add(
                    "Cannot safely migrate NetworkHooks.openScreen in ${file}:${source.lineNumberAt(callStart)}: " +
                        "expected 2 args or a statically identifiable Consumer/FriendlyByteBuf writer or BlockPos third arg"
                )
                out.append(source, cursor, closeParen + 1)
            } else {
                out.append(source, cursor, callStart)
                out.append(replacement)
                changes.add(Change(
                    file = file,
                    line = source.lineNumberAt(callStart),
                    description = "Migrate NetworkHooks.openScreen with balanced argument parsing",
                    before = source.substring(callStart, closeParen + 1),
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "networkhooks-openscreen-balanced"
                ))
            }

            cursor = closeParen + 1
            searchFrom = closeParen + 1
        }

        out.append(source, cursor, source.length)
        return NetworkHooksOpenScreenMigration(out.toString(), changes, errors)
    }

    private fun migrateLegacyGuiOverlayRenderContext(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!Regex("""\bimplements\s+IGuiOverlay\b""").containsMatchIn(executableCode)) return source

        val signature = Regex(
            """public\s+void\s+render\(\s*(?:ForgeGui|ExtendedGui|Gui)\s+([A-Za-z_$][\w$]*)\s*,\s*GuiGraphics\s+([A-Za-z_$][\w$]*)\s*,\s*float\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
        )
        val matches = signature.findAll(executableCode).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            val openBrace = match.range.last
            val closeBrace = findMatchingBrace(executableCode, openBrace)
            if (closeBrace <= openBrace) continue
            val guiParameter = match.groupValues[1]
            val graphicsParameter = match.groupValues[2]
            val bodyStart = openBrace + 1
            val body = result.substring(bodyStart, closeBrace)
            val migratedBody = replaceExecutableRegex(
                body,
                Regex("""\b${Regex.escape(guiParameter)}\b""")
            ) { "net.minecraft.client.Minecraft.getInstance().gui" }
            val migratedSignature =
                "public void render(GuiGraphics $graphicsParameter, DeltaTracker deltaTracker) {"
            result = result.substring(0, match.range.first) +
                migratedSignature +
                migratedBody +
                result.substring(closeBrace)
        }
        return result
    }

    private fun migrateUseWithoutItemRemovedHandReferences(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val methodPattern = Regex(
            """\buseWithoutItem\s*\(([^)]*)\)\s*(?:throws\s+[^{]+)?\{"""
        )
        val matches = methodPattern.findAll(executableCode).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            val parameters = match.groupValues[1]
            if (Regex("""\b(?:hand|handIn)\b""").containsMatchIn(parameters)) continue
            val openBrace = match.range.last
            val closeBrace = findMatchingBrace(executableCode, openBrace)
            if (closeBrace <= openBrace) continue
            val bodyStart = openBrace + 1
            val body = result.substring(bodyStart, closeBrace)
            var migratedBody = replaceExecutableRegex(
                body,
                Regex("""\b([A-Za-z_$][\w$]*)\.getItemInHand\(\s*(?:hand|handIn)\s*\)""")
            ) { matchBody -> "${matchBody.groupValues[1]}.getMainHandItem()" }
            migratedBody = replaceExecutableRegex(
                migratedBody,
                Regex("""\b(?:hand|handIn)\b""")
            ) { "InteractionHand.MAIN_HAND" }
            if (migratedBody != body) {
                result = result.substring(0, bodyStart) + migratedBody + result.substring(closeBrace)
            }
        }
        return result
    }

    private fun migrateLegacyGuiOverlayType(source: String): String {
        val legacyOwners = listOf(
            "net.minecraftforge.client.gui.overlay.IGuiOverlay",
            "net.neoforged.neoforge.client.gui.overlay.IGuiOverlay"
        )
        val executable = maskJavaCommentsAndLiterals(source)
        val importedOwner = legacyOwners.firstOrNull { owner ->
            Regex("""(?m)^\s*import\s+${Regex.escape(owner)}\s*;""").containsMatchIn(executable)
        } ?: return source

        var result = replaceExecutableRegex(
            source,
            Regex("""(?m)^([ \t]*)import\s+${Regex.escape(importedOwner)}\s*;""")
        ) { match -> "${match.groupValues[1]}import net.minecraft.client.gui.LayeredDraw;" }
        result = replaceExecutableRegex(result, Regex("""\bIGuiOverlay\b""")) { "LayeredDraw.Layer" }
        return result
    }

    private fun migrateInventoryRecipeHolderInterface(source: String): String {
        val oldImport = "import net.minecraft.world.inventory.RecipeHolder;"
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains(oldImport)) return source
        var result = replaceExecutableRegex(
            source,
            Regex("""(?m)^([ \t]*)import[ \t]+net\.minecraft\.world\.inventory\.RecipeHolder;[ \t]*(\r?\n)?""")
        ) { match ->
            "${match.groupValues[1]}import net.minecraft.world.inventory.RecipeCraftingHolder;${match.groupValues[2]}"
        }
        result = replaceExecutableRegex(result, Regex("""(implements\s+[^{;\r\n]*?)\bRecipeHolder\b""")) { match ->
            "${match.groupValues[1]}RecipeCraftingHolder"
        }
        return result
    }

    private fun migrateModelRenderPackedColorText(source: String): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("renderToBuffer(") && !executableCode.contains(".render(")) return source

        var result = replaceExecutableRegex(
            source,
            Regex("""public\s+void\s+renderToBuffer\(\s*PoseStack\s+(\w+)\s*,\s*VertexConsumer\s+(\w+)\s*,\s*int\s+(\w+)\s*,\s*int\s+(\w+)\s*,\s*float\s+\w+\s*,\s*float\s+\w+\s*,\s*float\s+\w+\s*,\s*float\s+\w+\s*\)""")
        ) { match ->
            "public void renderToBuffer(PoseStack ${match.groupValues[1]}, VertexConsumer ${match.groupValues[2]}, int ${match.groupValues[3]}, int ${match.groupValues[4]}, int color)"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""(\w+)\.render\(([^;]*?packedOverlay)\s*,\s*red\s*,\s*green\s*,\s*blue\s*,\s*alpha\s*\)""")
        ) { match ->
            "${match.groupValues[1]}.render(${match.groupValues[2]}, color)"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""(\w+)\.renderToBuffer\(([^;]*?)\s*,\s*([A-Za-z0-9_\.]+)\s*,\s*([A-Za-z0-9_\.]+)\s*,\s*([A-Za-z0-9_\.]+)\s*,\s*([A-Za-z0-9_\.]+)\s*\)""")
        ) { match ->
            "${match.groupValues[1]}.renderToBuffer(${match.groupValues[2]}, FastColor.ARGB32.colorFromFloat(${match.groupValues[6]}, ${match.groupValues[3]}, ${match.groupValues[4]}, ${match.groupValues[5]}))"
        }
        return result
    }

    private fun migrateRemovedTagManagerAccess(source: String): String {
        return LegacyTagManagerMigration().migrate(source)
    }

    private fun isNetworkHooksExtraDataWriter(expression: String, source: String): Boolean {
        val trimmed = stripOuterParentheses(expression.trim())
        if (trimmed.contains("->") || trimmed.contains("::")) return true
        if (trimmed.startsWith("new Consumer") || trimmed.startsWith("new java.util.function.Consumer")) return true
        val identifier = Regex("""^[A-Za-z_$][\w$]*$""").matchEntire(trimmed)?.value ?: return false
        val executableCode = maskJavaCommentsAndLiterals(source)
        return Regex("""\b(?:java\.util\.function\.)?Consumer\s*<[^;=]+>\s+${Regex.escape(identifier)}\b""")
            .containsMatchIn(executableCode)
    }

    private fun isNetworkHooksBlockPosExtra(
        expression: String,
        source: String,
        file: Path,
        projectDir: Path
    ): Boolean {
        val trimmed = stripOuterParentheses(expression.trim())
        if (trimmed.startsWith("new BlockPos(") ||
            trimmed.startsWith("new net.minecraft.core.BlockPos(") ||
            trimmed.startsWith("BlockPos.") ||
            trimmed.startsWith("net.minecraft.core.BlockPos.") ||
            trimmed.endsWith(".getBlockPos()")) {
            return true
        }
        val executableCode = maskJavaCommentsAndLiterals(source)
        val identifier = Regex("""^[A-Za-z_$][\w$]*$""").matchEntire(trimmed)?.value
        if (identifier != null &&
            Regex("""\b(?:net\.minecraft\.core\.)?BlockPos\s+${Regex.escape(identifier)}\b""")
                .containsMatchIn(executableCode)) {
            return true
        }
        // BlockEntity#worldPosition is the block entity's BlockPos (protected final in 1.21.1)
        if (trimmed == "worldPosition" || trimmed == "this.worldPosition") {
            if (Regex("""\bextends\s+[\w.]*BlockEntity\b""").containsMatchIn(executableCode)) {
                return true
            }
            return enclosingTypeInheritsMinecraftBlockEntity(file, projectDir)
        }
        return false
    }

    private fun enclosingTypeInheritsMinecraftBlockEntity(file: Path, projectDir: Path): Boolean {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!file.startsWith(sourceRoot) || !sourceRoot.exists()) return false

        val sourceCache = mutableMapOf<Path, String>()
        val visited = mutableSetOf<Path>()
        var currentFile = file
        repeat(32) {
            if (!visited.add(currentFile)) return false
            val currentSource = sourceCache.getOrPut(currentFile) { currentFile.readText() }
            val executableCode = maskJavaCommentsAndLiterals(currentSource)
            val superclass = Regex(
                """\b(?:class|record)\s+[A-Za-z_$][\w$]*(?:\s*<[^>{}]*>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)"""
            ).find(executableCode)?.groupValues?.get(1) ?: return false
            if (superclass == "BlockEntity" || superclass == "net.minecraft.world.level.block.entity.BlockEntity") {
                return true
            }

            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
                .find(executableCode)?.groupValues?.get(1).orEmpty()
            val imports = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$.]*)\s*;""")
                .findAll(executableCode)
                .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
            val superclassFqn = when {
                '.' in superclass -> superclass
                superclass in imports -> imports.getValue(superclass)
                packageName.isNotEmpty() -> "$packageName.$superclass"
                else -> return false
            }
            if (superclassFqn == "net.minecraft.world.level.block.entity.BlockEntity") return true

            val candidate = sourceRoot.resolve(superclassFqn.replace('.', '/') + ".java")
            if (!candidate.exists()) return false
            currentFile = candidate
        }
        return false
    }

    private fun stripOuterParentheses(expression: String): String {
        var result = expression
        while (result.startsWith("(") && result.endsWith(")")) {
            val closeParen = findMatchingDelimiter(result, 0, '(', ')')
            if (closeParen != result.lastIndex) break
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun removeImportLine(source: String, importName: String): String =
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n?""").replace(source, "")

    private fun usesSymbolOutsideImports(source: String, symbol: String): Boolean =
        source.lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .any { Regex("""\b${Regex.escape(symbol)}\b""").containsMatchIn(it) }

    private fun replaceFirstJavaMethodByName(source: String, methodName: String, replacement: String): String {
        val declaration = findJavaMethodDeclaration(source, methodName)
            ?: return source
        val start = annotationStartBefore(source, declaration.range.first)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val openBrace = executableCode.indexOf('{', declaration.range.last)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return source
        var end = closeBrace + 1
        if (end < source.length && source[end] == '\r') end++
        if (end < source.length && source[end] == '\n') end++
        return source.substring(0, start) + replacement + "\n" + source.substring(end)
    }

    private fun removeFirstJavaMethodByName(source: String, methodName: String, searchFrom: Int = 0): Pair<String, Int?> {
        val declaration = findJavaMethodDeclaration(source, methodName, searchFrom)
            ?: return source to null
        val start = annotationStartBefore(source, declaration.range.first)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val openBrace = executableCode.indexOf('{', declaration.range.last)
        if (openBrace < 0) return source to null
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) return source to null
        var end = closeBrace + 1
        if (end < source.length && source[end] == '\r') end++
        if (end < source.length && source[end] == '\n') end++
        return source.removeRange(start, end) to start
    }

    private fun findJavaMethodDeclaration(source: String, methodName: String, searchFrom: Int = 0): MatchResult? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        return Regex(
            """(?m)^[ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:<[^>\r\n]+>\s+)?[\w.$<>\[\], ?]+\s+${Regex.escape(methodName)}\s*\("""
        ).find(executableCode, searchFrom)
    }

    private fun annotationStartBefore(source: String, declarationIndex: Int): Int {
        val lineStart = source.lastIndexOf('\n', declarationIndex).let { if (it < 0) 0 else it + 1 }
        val annotations = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
            .findAll(source.substring(0, lineStart))
            .lastOrNull()
        return annotations?.range?.first ?: lineStart
    }

    private fun splitTopLevelArguments(source: String): List<String> {
        return splitTopLevelArgumentExpressions(source, baseOffset = 0).map { it.text }
    }

    private fun splitTopLevelArgumentExpressions(source: String, baseOffset: Int): List<LegacyExpression> {
        val args = mutableListOf<LegacyExpression>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var inChar = false
        var escaped = false

        fun addArgument(end: Int) {
            var trimmedStart = start
            var trimmedEnd = end
            while (trimmedStart < trimmedEnd && source[trimmedStart].isWhitespace()) trimmedStart++
            while (trimmedEnd > trimmedStart && source[trimmedEnd - 1].isWhitespace()) trimmedEnd--
            if (trimmedStart < trimmedEnd) {
                args += LegacyExpression(
                    text = source.substring(trimmedStart, trimmedEnd),
                    offset = baseOffset + trimmedStart
                )
            }
        }

        for (i in source.indices) {
            val c = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            if (inChar) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '\'') {
                    inChar = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '\'' -> inChar = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    addArgument(i)
                    start = i + 1
                }
            }
        }
        addArgument(source.length)
        return args
    }

    private fun findJavaStatementEnd(source: String, startIndex: Int): Int {
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        for (index in startIndex until source.length) {
            when (source[index]) {
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> {
                    if (braceDepth == 0) return -1
                    braceDepth--
                }
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ';' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) return index
            }
        }
        return -1
    }

    private fun findMatchingDelimiter(source: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var inString = false
        var inChar = false
        var escaped = false
        var inLineComment = false
        var inBlockComment = false
        for (i in openIndex until source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') inBlockComment = false
                continue
            }
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            if (inChar) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '\'') {
                    inChar = false
                }
                continue
            }
            if (c == '/' && next == '/') {
                inLineComment = true
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                continue
            }
            if (c == '"') {
                inString = true
                continue
            }
            if (c == '\'') {
                inChar = true
                continue
            }
            if (c == openChar) depth++
            if (c == closeChar) {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun findMatchingBrace(source: String, openBrace: Int): Int {
        var depth = 0
        var inString = false
        var inChar = false
        var escaped = false
        var inLineComment = false
        var inBlockComment = false
        var index = openBrace
        while (index < source.length) {
            val c = source[index]
            val next = source.getOrNull(index + 1)
            if (!inString && !inChar && !inLineComment && !inBlockComment &&
                c == '"' && next == '"' && source.getOrNull(index + 2) == '"'
            ) {
                index = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                continue
            }
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                index++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    index += 2
                    continue
                }
                index++
                continue
            }
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                index++
                continue
            }
            if (inChar) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '\'') {
                    inChar = false
                }
                index++
                continue
            }
            if (c == '/' && next == '/') {
                inLineComment = true
                index += 2
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                index += 2
                continue
            }
            if (c == '"') {
                inString = true
                index++
                continue
            }
            if (c == '\'') {
                inChar = true
                index++
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return index
            }
            index++
        }
        return -1
    }

    private fun maskJavaComments(source: String): String {
        val result = StringBuilder(source.length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTextBlock = false
        var inChar = false
        var escaped = false
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            val nextTwo = source.getOrNull(index + 2)

            when {
                inLineComment -> {
                    if (ch == '\r' || ch == '\n') {
                        inLineComment = false
                        result.append(ch)
                    } else {
                        result.append(' ')
                    }
                }
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        result.append("  ")
                        index++
                        inBlockComment = false
                    } else {
                        result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    }
                }
                inTextBlock -> {
                    result.append(ch)
                    if (ch == '"' && next == '"' && nextTwo == '"') {
                        result.append("  ")
                        index += 2
                        inTextBlock = false
                    }
                }
                inString -> {
                    result.append(ch)
                    if (ch == '"' && !escaped) inString = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                inChar -> {
                    result.append(ch)
                    if (ch == '\'' && !escaped) inChar = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                ch == '/' && next == '/' -> {
                    result.append("  ")
                    index++
                    inLineComment = true
                }
                ch == '/' && next == '*' -> {
                    result.append("  ")
                    index++
                    inBlockComment = true
                }
                ch == '"' && next == '"' && nextTwo == '"' -> {
                    result.append("\"\"\"")
                    index += 2
                    inTextBlock = true
                    escaped = false
                }
                ch == '"' -> {
                    result.append(ch)
                    inString = true
                    escaped = false
                }
                ch == '\'' -> {
                    result.append(ch)
                    inChar = true
                    escaped = false
                }
                else -> result.append(ch)
            }
            index++
        }
        return result.toString()
    }

    private fun maskJavaCommentsAndTextBlocks(source: String): String {
        val result = StringBuilder(source.length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTextBlock = false
        var inChar = false
        var escaped = false
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            val nextTwo = source.getOrNull(index + 2)

            when {
                inLineComment -> {
                    if (ch == '\r' || ch == '\n') {
                        inLineComment = false
                        result.append(ch)
                    } else {
                        result.append(' ')
                    }
                }
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        result.append("  ")
                        index++
                        inBlockComment = false
                    } else {
                        result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    }
                }
                inTextBlock -> {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    if (ch == '"' && next == '"' && nextTwo == '"') {
                        result.append("  ")
                        index += 2
                        inTextBlock = false
                    }
                }
                inString -> {
                    result.append(ch)
                    if (ch == '"' && !escaped) inString = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                inChar -> {
                    result.append(ch)
                    if (ch == '\'' && !escaped) inChar = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                ch == '/' && next == '/' -> {
                    result.append("  ")
                    index++
                    inLineComment = true
                }
                ch == '/' && next == '*' -> {
                    result.append("  ")
                    index++
                    inBlockComment = true
                }
                ch == '"' && next == '"' && nextTwo == '"' -> {
                    result.append("   ")
                    index += 2
                    inTextBlock = true
                    escaped = false
                }
                ch == '"' -> {
                    result.append(ch)
                    inString = true
                    escaped = false
                }
                ch == '\'' -> {
                    result.append(ch)
                    inChar = true
                    escaped = false
                }
                else -> result.append(ch)
            }
            index++
        }
        return result.toString()
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val result = StringBuilder(source.length)
        var index = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTextBlock = false
        var inChar = false
        var escaped = false
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            val nextTwo = source.getOrNull(index + 2)

            when {
                inLineComment -> {
                    if (ch == '\r' || ch == '\n') {
                        inLineComment = false
                        result.append(ch)
                    } else {
                        result.append(' ')
                    }
                }
                inBlockComment -> {
                    if (ch == '*' && next == '/') {
                        result.append("  ")
                        index++
                        inBlockComment = false
                    } else {
                        result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    }
                }
                inTextBlock -> {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    if (ch == '"' && next == '"' && nextTwo == '"') {
                        result.append("  ")
                        index += 2
                        inTextBlock = false
                    }
                }
                inString -> {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    if (ch == '"' && !escaped) inString = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                inChar -> {
                    result.append(if (ch == '\r' || ch == '\n') ch else ' ')
                    if (ch == '\'' && !escaped) inChar = false
                    escaped = ch == '\\' && !escaped
                    if (ch != '\\') escaped = false
                }
                ch == '/' && next == '/' -> {
                    result.append("  ")
                    index++
                    inLineComment = true
                }
                ch == '/' && next == '*' -> {
                    result.append("  ")
                    index++
                    inBlockComment = true
                }
                ch == '"' && next == '"' && nextTwo == '"' -> {
                    result.append("   ")
                    index += 2
                    inTextBlock = true
                    escaped = false
                }
                ch == '"' -> {
                    result.append(' ')
                    inString = true
                    escaped = false
                }
                ch == '\'' -> {
                    result.append(' ')
                    inChar = true
                    escaped = false
                }
                else -> result.append(ch)
            }
            index++
        }
        return result.toString()
    }

    private fun replaceExecutableRegex(
        source: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val matches = pattern.findAll(executableCode).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            val originalValue = source.substring(match.range.first, match.range.last + 1)
            val sourceMatch = pattern.find(source, match.range.first)
                ?.takeIf { it.range == match.range }
            val groupValues = sourceMatch?.groupValues ?: match.groupValues
            result = result.replaceRange(
                match.range,
                replacement(ExecutableRegexMatch(originalValue, groupValues))
            )
        }
        return result
    }

    private fun replaceCommentMaskedRegex(
        source: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String {
        val code = maskJavaComments(source)
        val matches = pattern.findAll(code).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            val originalValue = source.substring(match.range.first, match.range.last + 1)
            val sourceMatch = pattern.find(source, match.range.first)
                ?.takeIf { it.range == match.range }
            val groupValues = sourceMatch?.groupValues ?: match.groupValues
            result = result.replaceRange(
                match.range,
                replacement(ExecutableRegexMatch(originalValue, groupValues))
            )
        }
        return result
    }

    private data class ExecutableRegexMatch(
        val value: String,
        val groupValues: List<String>
    )

    private data class TextRuleReplacement(
        val range: IntRange,
        val replacement: String
    )

    private data class TextRuleResult(
        val content: String,
        val changes: List<TextRuleReplacement>
    )

    private fun replaceTextRuleInExecutableJava(input: TextRuleSource, rule: TextReplacement, pattern: Regex?): TextRuleResult {
        val source = input.source
        val executableOnlyCode = input.executableOnlyCode
        val executableCode = if (rule.matchesJavaStringLiteralArguments()) {
            input.withStringArguments
        } else {
            executableOnlyCode
        }
        fun startsInExecutableCode(range: IntRange): Boolean {
            val start = firstNonWhitespaceInRange(executableCode, range) ?: return false
            return executableOnlyCode.getOrNull(start)?.isWhitespace() == false
        }
        val replacements = if (rule.isRegex) {
            val compiledPattern = requireNotNull(pattern)
            compiledPattern.findAll(executableCode)
                .filter { startsInExecutableCode(it.range) }
                .mapNotNull { match ->
                    val original = source.substring(match.range.first, match.range.last + 1)
                    val migrated = compiledPattern.replace(original, rule.replacement)
                    if (migrated == original) null else TextRuleReplacement(match.range, migrated)
                }
                .toList()
        } else {
            val found = mutableListOf<TextRuleReplacement>()
            var index = executableCode.indexOf(rule.pattern)
            while (index >= 0) {
                val end = index + rule.pattern.length
                val range = index until end
                if (startsInExecutableCode(range)) {
                    val original = source.substring(index, end)
                    val migrated = original.replace(rule.pattern, rule.replacement)
                    if (migrated != original) {
                        found += TextRuleReplacement(range, migrated)
                    }
                }
                index = executableCode.indexOf(rule.pattern, end)
            }
            found
        }
        if (replacements.isEmpty()) return TextRuleResult(source, emptyList())

        var result = source
        for (replacement in replacements.asReversed()) {
            result = result.replaceRange(replacement.range, replacement.replacement)
        }
        return TextRuleResult(result, replacements)
    }

    private fun TextReplacement.matchesJavaStringLiteralArguments(): Boolean =
        isRegex && pattern.contains('"')

    private fun firstNonWhitespaceInRange(source: String, range: IntRange): Int? {
        val start = range.first.coerceAtLeast(0)
        val end = range.last.coerceAtMost(source.lastIndex)
        if (start > end) return null
        for (index in start..end) {
            if (!source[index].isWhitespace()) return index
        }
        return null
    }

    private fun removeLegacyPrivateStaticEnchantmentSubclasses(source: String): String {
        if (!source.contains("Enchantment")) return source
        val executableCode = maskJavaCommentsAndLiterals(source)
        val headerPattern = Regex(
            """(?m)^[ \t]*private\s+static\s+(?:final\s+|abstract\s+)?class\s+[A-Za-z_$][\w$]*\s+extends\s+(?:[A-Za-z_$][\w$]*\.)*Enchantment\s*\{"""
        )
        val removals = mutableListOf<IntRange>()
        var searchStart = 0
        while (searchStart < executableCode.length) {
            val match = headerPattern.find(executableCode, searchStart) ?: break
            val openBrace = match.range.last
            val closeBrace = findMatchingBrace(source, openBrace)
            if (closeBrace >= 0) {
                removals += match.range.first until endOfLine(source, closeBrace)
                searchStart = closeBrace + 1
            } else {
                searchStart = match.range.last + 1
            }
        }
        if (removals.isEmpty()) return source

        var result = source
        for (range in removals.asReversed()) {
            result = result.removeRange(range)
        }
        return result
    }

    private fun endOfLine(source: String, index: Int): Int {
        var end = (index + 1).coerceAtMost(source.length)
        if (source.getOrNull(end) == '\r' && source.getOrNull(end + 1) == '\n') {
            return end + 2
        }
        if (source.getOrNull(end) == '\n') {
            return end + 1
        }
        return end
    }

    private data class ParticleField(
        val type: String,
        val name: String
    )

    private fun dedupeImports(content: String): String {
        val seen = mutableSetOf<String>()
        return content.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("import ") && !seen.add(trimmed)
            }
            .joinToString("\n")
    }

    private fun String.lineNumberAt(offset: Int): Int =
        take(offset.coerceIn(0, length)).count { it == '\n' } + 1

    private fun String.lineAt(offset: Int): String {
        val bounded = offset.coerceIn(0, length)
        val start = lastIndexOf('\n', (bounded - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = indexOf('\n', bounded).let { if (it < 0) length else it }
        return substring(start, end).removeSuffix("\r")
    }

    /**
     * Build the complete rule list by combining explicit text-replacements.json rules
     * with auto-generated regex rules from class-renames.json.
     * Explicit rules come first (they include ordered package renames),
     * then class rename rules are appended.
     */
    private fun buildRuleList(): List<TextReplacement> {
        val explicitRules = mappingDb.getTextReplacements()
        val classRenameRules = mappingDb.getAllClassMappings()
            .filter { (forge, mapping) ->
                // Skip entries already covered by explicit text rules
                explicitRules.none { it.pattern.contains(forge) }
            }
            .map { (forge, mapping) ->
                TextReplacement(
                    id = "cls-auto-$forge",
                    pattern = "\\b${Regex.escape(forge)}\\b",
                    replacement = mapping.neoForgeClass,
                    description = "Class rename: $forge -> ${mapping.neoForgeClass}",
                    isRegex = true
                )
            }
        return explicitRules + classRenameRules
    }

    private fun migrateForgeInternalNameDescriptors(source: String): String {
        if (!source.contains("Lnet/minecraftforge/")) return source
        return listOf(
            "Lnet/minecraftforge/fml/" to "Lnet/neoforged/fml/",
            "Lnet/minecraftforge/eventbus/" to "Lnet/neoforged/bus/",
            "Lnet/minecraftforge/api/distmarker/" to "Lnet/neoforged/api/distmarker/",
            "Lnet/minecraftforge/forgespi/" to "Lnet/neoforged/neoforgespi/",
            "Lnet/minecraftforge/accesstransformer/" to "Lnet/neoforged/accesstransformer/",
            "Lnet/minecraftforge/coremod/" to "Lnet/neoforged/coremod/",
            "Lnet/minecraftforge/jarjar/" to "Lnet/neoforged/jarjar/",
            "Lnet/minecraftforge/" to "Lnet/neoforged/neoforge/"
        ).fold(source) { current, (oldOwner, newOwner) ->
            current.replace(oldOwner, newOwner)
        }
    }

    private fun migrateTestSourceForgeAssertions(source: String): String {
        return source
            .replace("net.minecraftforge.fml.", "net.neoforged.fml.")
            .replace("net.minecraftforge.eventbus.", "net.neoforged.bus.")
            .replace("net.minecraftforge.forgespi.", "net.neoforged.neoforgespi.")
            .replace("net.minecraftforge.fluids.", "net.neoforged.neoforge.fluids.")
            .replace("net.minecraftforge.common.", "net.neoforged.neoforge.common.")
            .replace("net.minecraftforge.", "net.neoforged.neoforge.")
            .replace("MinecraftForge", "NeoForge")
    }

    private fun findJavaFiles(projectDir: Path): List<Path> {
        return Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/") ||
                    rel.startsWith("src/references/") || rel.contains("/src/references/")
            }}
            .toList()
    }
}
