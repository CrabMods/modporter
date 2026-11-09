package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

// Structural migration rules, part 03 of 10, in pipeline order.
// Rules are `internal fun StructuralRefactorPass.<name>` extensions; a rule is followed by the helpers it uses.
// Entry points called from processFiles: migrateCommonNeoForge121Apis, migrateLegacyRarityEnumExtensions, migrateLegacyRecipeBookCategoryEnumExtensions, migrateLegacyRecipeBookTypeEnumExtensions, migrateCustomRecipeBookCategoryFinders, migrateLegacyMobCategoryEnumExtensions, migrateLegacyPathTypeCreateConstants, migrateLegacyImageButtonConstructors, migrateLegacyStateSwitchingButtonTextures, migrateCuriosClientApi, migrateLegacyGrassColorModifierEnumExtensions, migrateDeferredRegisterListenerReferences, registerMissingDeferredRegisterFields.

internal fun StructuralRefactorPass.migrateCommonNeoForge121Apis(
    projectDir: Path,
    dryRun: Boolean,
    errorSink: MutableCollection<String>? = null
): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .filter { !it.toString().replace('\\', '/').contains("/com/modporter/generated/") }
        .toList()
    if (!dryRun) {
        val initialInheritanceIndex = collectJavaInheritanceIndex(javaFiles)
        javaFiles.forEach { javaFile ->
            val source = javaFile.readText()
            val migrated = migrateBlockEntitySerializationDeclarationSource(source, initialInheritanceIndex)
            if (migrated != source) {
                javaFile.writeText(migrated)
                changes += Change(
                    file = javaFile,
                    line = 1,
                    description = "Migrate BlockEntity serialization declarations before provider propagation",
                    before = "legacy BlockEntity serialization declaration",
                    after = "provider-aware NeoForge 1.21 declaration",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-blockentity-provider-contract"
                )
            }
            val providerInput = javaFile.readText()
            val componentMigrated = migrateLegacyComponentSerializationSource(
                providerInput,
                initialInheritanceIndex
            )
            if (componentMigrated != providerInput) {
                javaFile.writeText(componentMigrated)
                changes += Change(
                    file = javaFile,
                    line = 1,
                    description = "Migrate component JSON serialization before provider propagation",
                    before = "Component.Serializer legacy serialization",
                    after = "provider overload or ComponentSerialization codec",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-component-serialization-pre-provider"
                )
            }
        }
        changes.addAll(
            LegacyNbtProviderMethodMigration().migrate(
                projectDir,
                dryRun = false,
                allowContextProviderExpressions = false
            )
        )
    }
    val javaSourceTypes = collectJavaSourceTypes(javaFiles)
    val recordComponents = collectJavaRecordComponents(srcDir)
    val mapCodecConstantOwners = collectMapCodecConstantOwners(srcDir)
    val mapCodecRequiredOwners = collectMapCodecRequiredOwners(srcDir)
    val codecConstantOwners = collectCodecConstantOwners(srcDir)
    val spriteSourceProviderClasses = collectSpriteSourceProviderClasses(srcDir)
    val finalMapDecorationClasses = collectFinalMapDecorationSubclassClasses(srcDir)
    val deferredHolderRegistryBaseHints = collectDeferredHolderRegistryBaseHints(srcDir)
    val deferredRegisterOwners = collectDeferredRegisterOwners(srcDir)
    val lootTableProviderClasses = collectLootTableProviderClasses(srcDir)
    val lootTableProviderFactoryMethods = collectLootTableProviderFactoryMethods(srcDir)
    val globalLootModifierProviderClasses = collectGlobalLootModifierProviderClasses(srcDir)
    val recipeProviderClasses = collectRecipeProviderClasses(srcDir)
    val recipeSerializerFactoryHints = collectRecipeSerializerFactoryHints(srcDir)
    val recipeTypeHints = collectRecipeTypeHints(srcDir)
    val holderValueParameterHints = collectHolderValueParameterHints(srcDir)
    val criterionInstanceFactoryHints = collectCriterionInstanceFactoryHints(srcDir)
    val staticRegistryFieldHints = collectStaticRegistryFieldHints(srcDir)
    val legacyItemStackPredicateOverrides = collectLegacyItemStackPredicateOverrideMethods(javaFiles)
    val legacyBannerPatternFactories = collectLegacyBannerPatternLayerFactories(javaFiles)
    val legacyBannerItemFactories = collectLegacyBannerItemStackFactories(javaFiles)
    val legacyRegistryBackedMethods = collectLegacyRegistryBackedMethods(javaFiles)
    val attributeModifierIdReferences = collectAttributeModifierIdReferences(javaFiles)
    val attributeModifierMethods = collectAttributeModifierMethods(javaFiles)
    val genericMethodReturnTypes = collectGenericMethodReturnTypes(javaFiles)
    val javaMethodReturnTypes = collectJavaMethodReturnTypes(javaFiles)
    val attributeModifierIdMethodArguments = collectAttributeModifierIdMethodArguments(
        javaFiles,
        attributeModifierMethods,
        genericMethodReturnTypes
    )
    val attributeHolderAccessHints = collectAttributeHolderAccessHints(srcDir)
    val optionalCompoundRecipeTagOwners = collectLegacyOptionalCompoundRecipeTagOwners(javaFiles)
    val legacyCookingRecipeClasses = collectLegacyCookingRecipeClasses(javaFiles)
    val javaInheritanceIndex = collectJavaInheritanceIndex(javaFiles)
    val javaProjectTypeIndex = JavaProjectTypeIndex.build(srcDir)
    val recipeImplementationClasses = collectJavaRecipeImplementationClasses(javaFiles, javaInheritanceIndex)
    val customRecipeInputTypes = collectCustomRecipeInputTypes(javaFiles)
    val inheritedRecipeStateAccessors = collectInheritedRecipeStateAccessors(javaFiles)
    val holderLookupCompoundTagMethods = collectHolderLookupCompoundTagMethods(javaFiles)
    val legacyPlacementBanBaseClasses = collectLegacyPlacementBanBaseClasses(javaFiles)
    val legacyPlacementBanBuilderClasses = collectLegacyPlacementBanBuilderClasses(javaFiles)
    val legacyPlacementBanDisplayClasses = collectLegacyPlacementBanDisplayClasses(javaFiles, legacyPlacementBanBaseClasses)
    val savedDataClassNames = collectJavaClassNamesExtending(javaFiles, setOf("SavedData"), javaInheritanceIndex)
    val modId = detectModId(projectDir) ?: projectMetadataModId(projectDir)
    val mainClass = detectModMainClass(projectDir)
    val mainText = mainClass?.readText().orEmpty()
    val deferredHolderFields = collectDeferredHolderFields(srcDir)
    val gameEventDeferredHolderFields = collectDeferredHolderFieldsOf(srcDir, "GameEvent")
    val mobEffectDeferredHolderFields = collectDeferredHolderFieldsOf(srcDir, "MobEffect")
    val fluidDeferredHolderFields = collectDeferredHolderFieldsOf(srcDir, "Fluid")
    val fluidTupleDeferredHolderFields = collectDeferredHolderTupleFieldsOf(srcDir, "Fluid")
    val soundEventSupplierConstructors = collectSoundEventSupplierConstructors(javaFiles)
    val placementModifierTypeHolderFields = collectPlacementModifierTypeRegistryFields(javaFiles)
    val legacyLootTableResourceLocationReferences = collectLegacyLootTableResourceLocationReferences(javaFiles)
    val inbtSerializableTypeFqns = collectProjectINBTSerializableTypes(javaFiles)
    val providerAwareNbtMethods = collectProviderAwareNbtMethodContracts(
        javaFiles,
        inbtSerializableTypeFqns,
        javaInheritanceIndex
    )
    val providerAwareMethodShapes = providerAwareNbtMethods.mapTo(linkedSetOf()) { method ->
        Triple(method.owner, method.methodName, method.parameterCount)
    }
    projectNbtHandlerTypeNames = collectJavaClassNamesExtending(javaFiles, setOf("ItemStackHandler"), javaInheritanceIndex) +
        inbtSerializableTypeFqns.map { it.substringAfterLast('.') }
    projectLevelCarriers = ProjectLevelCarriers.collect(javaFiles)
    projectNbtHandlerFieldNames = javaFiles.flatMap { collectItemStackHandlerValueNames(runCatching { it.readText() }.getOrDefault("")) }.toSet()
    projectItemStackFields = ProjectItemStackFields.collect(javaFiles)
    projectCreativeTabHolders = CreativeTabHolderArguments.collect(javaFiles)
    projectFrameTimeMethods = MinecraftFrameTimeCalls.projectDeclared(javaFiles)
    projectRegistryElements = RegistryElementTypes.collect(javaFiles)
    projectRecipeInputPositions = RecipeInputBounds.collect(javaFiles)
    projectEnumRecipeTypeInputs = EnumRecipeTypeInputs.collect(javaFiles)
    projectValueFacts = ProjectValueFacts.collect(this, javaFiles)
    changes.addAll(
        LegacyBlockStateUseCalls.migrate(
            this,
            projectDir,
            {
                detectRequiredGeneratedCompatPackage(
                    projectDir,
                    "legacy BlockState use dispatch"
                )
            },
            dryRun
        )
    )
    val itemStackNbtMethods = collectItemStackNbtMethodsNeedingHolderLookup(
        javaFiles,
        javaInheritanceIndex,
        javaProjectTypeIndex
    )
        .filterNot { method ->
            Triple(method.owner, method.methodName, method.parameterCount) in providerAwareMethodShapes
        }
        .toSet() + providerAwareNbtMethods
    val itemStackNbtConstructors = collectItemStackNbtConstructorsNeedingHolderLookup(javaFiles, itemStackNbtMethods, javaInheritanceIndex)
    val constructorFactoryMethods = collectHolderLookupConstructorFactoryMethods(javaFiles, itemStackNbtConstructors, javaInheritanceIndex)
    val savedDataHolderLookupMethods = collectSavedDataHolderLookupMethods(javaFiles, savedDataClassNames)
    val existingHolderLookupNbtMethods = collectExistingHolderLookupNbtMethods(javaFiles)
    val effectiveItemStackNbtMethods = itemStackNbtMethods +
        constructorFactoryMethods +
        savedDataHolderLookupMethods +
        existingHolderLookupNbtMethods
    val entityCapabilityTypes = collectEntityCapabilityValueTypes(javaFiles)
    val entityDataValueTypes = mergeEntityDataValueTypes(
        entityCapabilityTypes,
        collectEntityAttachmentValueTypes(javaFiles)
    )
    val cachedRenderBoundsFamilies = javaFiles.mapNotNull { legacyCachedRenderBoundsType(it.readText()) }.distinct()
    require(cachedRenderBoundsFamilies.size <= 1) {
        "More than one cached BlockEntity render-bounds family requires an explicit renderer bridge"
    }
    val cachedRenderBoundsType = cachedRenderBoundsFamilies.singleOrNull()

    javaFiles.forEach { javaFile ->
        val fileChangeStart = changes.size
        try {
            val original = javaFile.readText()
            var text = original

            fun exactHook(ruleId: String, description: String, rewrite: (String) -> String) {
                val migrated = rewrite(text)
                if (migrated == text) return
                changes.add(Change(javaFile, 0, description, "legacy source hook", "source-proven target hook",
                    Confidence.HIGH, ruleId))
                text = migrated
            }
            exactHook("struct-warning-screen-footer-layout", "Preserve warning-screen footer actions in the target layout", this::migrateWarningScreenFooterLayout)
            exactHook("struct-gui-overlay-layer", "Preserve the legacy overlay callback through a target GUI layer", this::migrateLegacyGuiOverlayLayer)
            exactHook("struct-neoforge-subscriber-bus", "Use the target game event bus for proven subscriber annotations", this::migrateNeoForgeSubscriberBus)
            exactHook("struct-transient-step-height", "Preserve temporary entity step height through the target attribute", this::migrateTransientStepHeightOverride)
            if (cachedRenderBoundsType != null) {
                exactHook("struct-cached-render-bounds-base", "Preserve the cached block-entity render bounds fallback", this::migrateLegacyCachedRenderBoundsBase)
                exactHook("struct-cached-render-bounds-bridge", "Connect cached block-entity bounds to target renderer culling") {
                    migrateLegacyCachedRenderBoundsBridge(it, cachedRenderBoundsType)
                }
            }

            val nbtSerializableMigrated = migrateINBTSerializableHolderLookupSource(text, inbtSerializableTypeFqns)
            if (nbtSerializableMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate INBTSerializable methods to HolderLookup.Provider signatures",
                    before = "serializeNBT()/deserializeNBT(CompoundTag)",
                    after = "serializeNBT(HolderLookup.Provider)/deserializeNBT(HolderLookup.Provider, CompoundTag)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-inbtserializable-holderlookup"
                ))
                text = nbtSerializableMigrated
            }

            val stagedProviderContracts = migrateProjectItemStackNbtMethods(
                migrateProjectItemStackNbtMethods(
                    text,
                    effectiveItemStackNbtMethods,
                    javaInheritanceIndex,
                    entityDataValueTypes,
                    javaMethodReturnTypes
                ),
                effectiveItemStackNbtMethods,
                javaInheritanceIndex,
                entityDataValueTypes,
                javaMethodReturnTypes
            )
            if (stagedProviderContracts != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Stage provider-aware project NBT contracts before BlockEntity call migration",
                    before = "legacy project NBT declaration or call",
                    after = "provider-aware declaration and exact call",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-project-nbt-provider-contract-stage"
                ))
                text = stagedProviderContracts
            }

            val tickMigrated = migrateLegacyTickEventSource(text)
            if (tickMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy Forge tick events to split NeoForge 1.21 tick events",
                    before = "(LivingTickEvent or TickEvent.Phase)",
                    after = "(EntityTickEvent.Pre or typed Pre/Post wrappers without legacy phase fields)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-tick-events"
                ))
                text = tickMigrated
            }

            val blockEntityMigrated = migrateBlockEntitySerializationSource(text, javaInheritanceIndex)
            if (blockEntityMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate BlockEntity serialization/update hooks to HolderLookup.Provider signatures",
                    before = "(legacy BlockEntity NBT/update signatures)",
                    after = "(NeoForge 1.21 BlockEntity NBT/update signatures)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-blockentity-holderlookup"
                ))
                text = blockEntityMigrated
            }

            val entitySaveDataMigrated = migrateEntityAdditionalSaveDataHolderLookupSource(
                text,
                javaInheritanceIndex,
                holderLookupCompoundTagMethods
            )
            if (entitySaveDataMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Entity save-data methods' provider-aware CompoundTag delegate calls",
                    before = "provider-aware CompoundTag delegate called with one argument",
                    after = "provider-aware CompoundTag delegate called with Entity registry access provider",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-entity-save-data-holderlookup"
                ))
                text = entitySaveDataMigrated
            }

            val baseContainerMigrated = migrateBaseContainerBlockEntityItemsSource(text)
            if (baseContainerMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Add BaseContainerBlockEntity item-list accessors required by 1.21",
                    before = "items field without getItems()/setItems()",
                    after = "getItems()/setItems(NonNullList<ItemStack>)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-base-container-items-accessors"
                ))
                text = baseContainerMigrated
            }

            val eventBusSubscriberStaticMigrated = migrateEventBusSubscriberStaticMethodsSource(text)
            if (eventBusSubscriberStaticMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Make @EventBusSubscriber @SubscribeEvent methods static for NeoForge auto-registration",
                    before = "@EventBusSubscriber class with instance @SubscribeEvent methods",
                    after = "@SubscribeEvent methods declared static",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-eventbussubscriber-static-methods"
                ))
                text = eventBusSubscriberStaticMigrated
            }

            val customNameSetterMigrated = migrateBaseContainerCustomNameSetterSource(text)
            if (customNameSetterMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Add component-backed custom name setter for container block entities",
                    before = "legacy setCustomName(Component) call target without 1.21 setter",
                    after = "setCustomName(Component) applies DataComponents.CUSTOM_NAME",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-blockentity-custom-name-component"
                ))
                text = customNameSetterMigrated
            }

            val transparentBlockMigrated = migrateTransparentBlockBeaconSource(text)
            if (transparentBlockMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate AbstractGlassBlock beacon color override to 1.21 TransparentBlock API",
                    before = "AbstractGlassBlock + float[] getBeaconColorMultiplier",
                    after = "TransparentBlock + Integer getBeaconColorMultiplier",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-transparent-block-beacon-color"
                ))
                text = transparentBlockMigrated
            }

            val plantApiMigrated = if (legacyPlantRuntimeOwned) text else migrateLegacyPlantApiSource(text)
            if (plantApiMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate removed PlantType/IPlantable overrides to 1.21 plant support APIs",
                    before = "PlantType/IPlantable custom plant API",
                    after = "BlockState plant + TriState or vanilla block mechanics",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-plant-api"
                ))
                text = plantApiMigrated
            }

            val tooltipMigrated = migrateTooltipContextImportsSource(text)
            if (tooltipMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Add Item import for Item.TooltipContext signatures",
                    before = "Item.TooltipContext without Item import",
                    after = "import net.minecraft.world.item.Item;",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-tooltip-context-import"
                ))
                text = tooltipMigrated
            }

            val jsonReloadMigrated = migrateRemovedJsonReloadDeserializersSource(text)
            if (jsonReloadMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate removed loot Deserializers Gson factory in JSON reload listeners",
                    before = "Deserializers.createFunctionSerializer().create()",
                    after = "new GsonBuilder().create()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-json-reload-deserializers"
                ))
                text = jsonReloadMigrated
            }

            val configMigrated = migrateModLoadingContextRegisterConfigSource(text)
            if (configMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate ModLoadingContext config registration to constructor ModContainer",
                    before = "ModLoadingContext.get().registerConfig(...)",
                    after = "modContainer.registerConfig(...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-modloadingcontext-register-config"
                ))
                text = configMigrated
            }

            val mobEffectMigrated = migrateMobEffectHolderCallsSource(text, mobEffectDeferredHolderFields)
            if (mobEffectMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate MobEffect arguments to Holder<MobEffect> API shapes",
                    before = "MobEffect or DeferredHolder#get() passed to 1.21 Holder APIs",
                    after = "Holder<MobEffect> or source-proven registry holder argument",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-mobeffect-holder-direct"
                ))
                text = mobEffectMigrated
            }

            val customRecipeInputMigrated = migrateCustomRecipeInputTypeSource(text, customRecipeInputTypes)
            if (customRecipeInputMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Make project recipe input container implement RecipeInput",
                    before = "Recipe<ProjectContainer> where ProjectContainer is not a RecipeInput",
                    after = "ProjectContainer implements RecipeInput with size()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-custom-recipe-input-type"
                ))
                text = customRecipeInputMigrated
            }

            val holderAccessorMigrated = migrateHolderValueAccessorsSource(text)
            if (holderAccessorMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Holder-returning accessors to value() before vanilla object methods",
                    before = "holder.getDescriptionId() / clientPlayer.getModelName()",
                    after = "holder.value().getDescriptionId() / clientPlayer.getSkin().model().id()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-holder-value-accessors"
                ))
                text = holderAccessorMigrated
            }

            val abstractClientPlayerSkinWrapMigrated = migrateLegacyAbstractClientPlayerCapeMixinToSkinWrapSource(text)
            if (abstractClientPlayerSkinWrapMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate removed AbstractClientPlayer cape hooks to PlayerSkin wrapping",
                    before = "isCapeLoaded()/getCloakTextureLocation() mixin injections",
                    after = "getSkin() WrapMethod returning a PlayerSkin with the migrated cape texture",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-abstract-client-player-skin-wrap"
                ))
                text = abstractClientPlayerSkinWrapMigrated
            }

            val screenBackgroundMigrated = migrateScreenBackgroundRenderedEventSource(text)
            if (screenBackgroundMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy screen background event overrides to the 1.21 renderBackground signature",
                    before = "renderBackground(GuiGraphics) posting ScreenEvent.BackgroundRendered",
                    after = "renderBackground(GuiGraphics, int, int, float) with no background draw",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-screen-background-rendered-event"
                ))
                text = screenBackgroundMigrated
            }

            val legacyHeartTypeSheetMigrated = migrateLegacyHeartTypeSheetCoordinatesSource(text)
            if (legacyHeartTypeSheetMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy Gui.HeartType sheet coordinates to an explicit 1.20-compatible coordinate helper",
                    before = "Gui.HeartType#getX(halfHeart, blinking) in GuiGraphics.blit(...)",
                    after = "modporterLegacyHeartTypeX(heartType, halfHeart, blinking)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-hearttype-legacy-sheet-coordinates"
                ))
                text = legacyHeartTypeSheetMigrated
            }

            val projectilePortalMigrated = migrateLegacyProjectilePortalBranchSource(text)
            if (projectilePortalMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Remove legacy copied projectile portal branch that calls removed 1.20 portal APIs",
                    before = "handleInsidePortal(...) and TheEndGatewayBlockEntity.teleportEntity(...) inside projectile hit scan",
                    after = "Entity block-inside portal processing via checkInsideBlocks()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-projectile-legacy-portal-branch"
                ))
                text = projectilePortalMigrated
            }

            val coloredLayerMigrated = migrateColoredCutoutModelCopyLayerRenderSource(text)
            if (coloredLayerMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate RenderLayer colored cutout helper RGB float arguments to packed ARGB color",
                    before = "coloredCutoutModelCopyLayerRender(..., red, green, blue)",
                    after = "coloredCutoutModelCopyLayerRender(..., FastColor.ARGB32.colorFromFloat(1.0F, red, green, blue))",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-colored-cutout-layer-packed-color"
                ))
                text = coloredLayerMigrated
            }

            val shearableSignatureMigrated = migrateLegacyShearableSignaturesSource(text)
            if (shearableSignatureMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy NeoForge shearable method signatures to 1.21 IShearable",
                    before = "onSheared(..., int fortune) / isShearable(ItemStack, Level, BlockPos)",
                    after = "onSheared(..., BlockPos) / isShearable(Player, ItemStack, Level, BlockPos)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-shearable-signatures"
                ))
                text = shearableSignatureMigrated
            }

            val explosionSignatureMigrated = migrateLegacyIgnoreExplosionSignatureSource(text)
            if (explosionSignatureMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Entity ignoreExplosion override to the 1.21 Explosion-aware signature",
                    before = "ignoreExplosion()",
                    after = "ignoreExplosion(Explosion explosion)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-ignore-explosion-signature"
                ))
                text = explosionSignatureMigrated
            }

            val dimensionEffectsMigrated = migrateDimensionSpecialEffectsCloudSignatureSource(text)
            if (dimensionEffectsMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate DimensionSpecialEffects cloud renderer override to the 1.21 model/projection matrix signature",
                    before = "renderClouds(..., Matrix4f projectionMatrix)",
                    after = "renderClouds(..., Matrix4f modelViewMatrix, Matrix4f projectionMatrix)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-dimension-effects-cloud-signature"
                ))
                text = dimensionEffectsMigrated
            }

            val customDataMigrated = migrateCustomDataComponentsSource(text)
            if (customDataMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy ItemStack/FluidStack child NBT helpers to CustomData components",
                    before = "getTagElement/removeTagKey/getChildTag/getOrCreateChildTag",
                    after = "CustomData component read/write helpers",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-custom-data-components"
                ))
                text = customDataMigrated
            }

            val recordAccessorMigrated = migrateRecordComponentFieldAccessSource(text, recordComponents)
            if (recordAccessorMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Java record component field reads to accessor methods",
                    before = "recordValue.component",
                    after = "recordValue.component()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-record-component-accessors"
                ))
                text = recordAccessorMigrated
            }

            val recordCodecMigrated = migrateRecordCodecBuilderWitnessSource(text)
            if (recordCodecMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Add record codec builder type witness for Java record codecs",
                    before = "RecordCodecBuilder.mapCodec(...)",
                    after = "RecordCodecBuilder.<RecordType>mapCodec(...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-record-codec-builder-witness"
                ))
                text = recordCodecMigrated
            }

            val brewingMigrated = migrateBrewingRecipeRegistrationSource(text)
            if (brewingMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate brewing recipe registration to RegisterBrewingRecipesEvent builder",
                    before = "BrewingRecipeRegistry.addRecipe during common setup",
                    after = "RegisterBrewingRecipesEvent#getBuilder().addRecipe",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-brewing-recipes-event"
                ))
                text = brewingMigrated
            }

            val predicateOverrideMigrated = migrateLegacyRegistryBackedItemStackPredicateOverrides(
                text,
                legacyItemStackPredicateOverrides,
                javaSourceTypes
            )
            if (predicateOverrideMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate item-stack predicate override registries to registry-aware predicates",
                    before = "Predicate<ItemStack> override registry",
                    after = "Function<RegistryAccess, Predicate<ItemStack>> override registry",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-registry-backed-itemstack-predicate"
                ))
                text = predicateOverrideMigrated
            }

            val bannerPatternMigrated = migrateLegacyBannerPatternLayerSource(
                text,
                legacyBannerPatternFactories,
                legacyBannerItemFactories
            )
            if (bannerPatternMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy banner pattern builders to 1.21 banner pattern components",
                    before = "BannerPattern.Builder#toListTag + BlockEntityTag Patterns",
                    after = "HolderGetter-backed BannerPatternLayers + DataComponents.BANNER_PATTERNS",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-banner-pattern-components"
                ))
                text = bannerPatternMigrated
            }

            val blockLootMigrated = migrateLegacyLootEnchantmentHolderCalls(
                migrateLegacyBlockLootSubProviderSource(text)
            )
            if (blockLootMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate block loot providers to HolderLookup-aware 1.21 constructors and enchantment holders",
                    before = "BlockLootSubProvider(...); Enchantments.FORTUNE in loot functions",
                    after = "BlockLootSubProvider(..., registries); registry lookup enchantment holder",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-block-loot-provider-121"
                ))
                text = blockLootMigrated
            }

            val soundEventSupplierMigrated = migrateLegacySoundEventSupplierLambdas(
                text,
                soundEventSupplierConstructors
            )
            if (soundEventSupplierMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate vanilla SoundEvents holder constants in legacy SoundEvent supplier constructor slots",
                    before = "() -> SoundEvents.X passed to Supplier<SoundEvent> constructor parameters",
                    after = "() -> SoundEvents.X.value() in proven legacy SoundEvent supplier slots",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-soundevent-supplier-lambdas"
                ))
                text = soundEventSupplierMigrated
            }

            exactHook(
                "struct-armor-holder-boundaries",
                "Migrate proven legacy armor sound suppliers and ArmorTrim mixin signatures to holder APIs",
                this::migrateArmorHolderBoundaries
            )
            exactHook(
                "struct-registry-source-boundaries",
                "Migrate registry receivers and generic positions from their source declarations",
                this::migrateRegistrySourceBoundaries
            )

            val vanilla121Migrated = migrateVanilla121ApiSource(
                text,
                mapCodecConstantOwners,
                mapCodecRequiredOwners,
                codecConstantOwners,
                spriteSourceProviderClasses,
                finalMapDecorationClasses,
                lootTableProviderClasses,
                lootTableProviderFactoryMethods,
                globalLootModifierProviderClasses,
                recipeProviderClasses,
                recipeSerializerFactoryHints,
                recipeTypeHints,
                holderValueParameterHints,
                criterionInstanceFactoryHints,
                staticRegistryFieldHints,
                legacyRegistryBackedMethods,
                attributeModifierIdReferences,
                attributeModifierMethods,
                genericMethodReturnTypes,
                javaMethodReturnTypes,
                attributeModifierIdMethodArguments,
                attributeHolderAccessHints,
                optionalCompoundRecipeTagOwners,
                javaInheritanceIndex,
                recipeImplementationClasses,
                legacyPlacementBanBaseClasses,
                legacyPlacementBanBuilderClasses,
                legacyPlacementBanDisplayClasses,
                modId.orEmpty(),
                { detectRequiredGeneratedCompatPackage(projectDir, "common 1.21 LazyOptional import bridge") },
                savedDataClassNames,
                legacyLootTableResourceLocationReferences,
                attributeModifierNamespaceExpression = explicitModIdReferenceForGeneratedClass(
                    mainClass,
                    mainText,
                    packageNameOf(text),
                    metadataModId = null
                ),
                itemStackNbtMethods = effectiveItemStackNbtMethods,
                itemStackNbtConstructors = itemStackNbtConstructors,
                mobEffectDeferredHolderFields = mobEffectDeferredHolderFields,
                entityCapabilityTypes = entityCapabilityTypes,
                projectModIdExpression = explicitModIdReferenceForGeneratedClass(
                    mainClass,
                    mainText,
                    packageNameOf(text),
                    metadataModId = null
                )
            )
            if (vanilla121Migrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate common Minecraft 1.21 API signature changes",
                    before = "legacy reach/gravity/attribute/particle/trade/skinning APIs",
                    after = "Minecraft 1.21 API equivalents",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-vanilla-121-api"
                ))
                text = vanilla121Migrated
            }

            exactHook(
                "struct-legacy-compiler-source-boundaries",
                "Preserve source predicate, tier, holder and GameTest provider boundaries",
                this::migrateLegacyCompilerSourceBoundaries
            )
            exactHook(
                "struct-stained-glass-ingredient-boundaries",
                "Preserve legacy stained glass recipe membership as native tag intersections",
                this::migrateStainedGlassIngredientBoundaries
            )

            exactHook("struct-legacy-entity-interaction-hooks", "Preserve source living interaction cancellation and damage/drop contracts", this::migrateLegacyEntityInteractionHooks)
            exactHook("struct-legacy-size-eye-height", "Preserve source entity eye-height changes in the target dimensions", this::migrateLegacyEntitySizeEyeHeight)
            exactHook("struct-legacy-deployer-interaction-hooks", "Preserve proven deployer interaction cancellation contracts", this::migrateLegacyDeployerInteractionHooks)

            val fluidTypeValueMigrated = migrateNeoForgeFluidTypeValueContexts(text)
            if (fluidTypeValueMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate NeoForge fluid type holders to value contexts",
                    before = "NeoForgeMod.*_TYPE holder used as a FluidType",
                    after = "NeoForgeMod.*_TYPE.value() in FluidType API contexts",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-vanilla-121-api"
                ))
                text = fluidTypeValueMigrated
            }

            val placementModifierHolderAccessMigrated = migratePlacementModifierTypeDeferredHolderReferences(
                text,
                placementModifierTypeHolderFields
            )
            if (placementModifierHolderAccessMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Dereference migrated PlacementModifierType DeferredHolder fields at type-return sites",
                    before = "return PlacementRegistry.CONFIG;",
                    after = "return PlacementRegistry.CONFIG.get();",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-placementmodifier-deferredholder-access"
                ))
                text = placementModifierHolderAccessMigrated
            }

            val verifiedCompatMigrated = migrateVerifiedCompat121ApiSource(text)
            if (verifiedCompatMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate verified third-party compat APIs to their NeoForge 1.21 surfaces",
                    before = "Forge-era compat event/effect accessors",
                    after = "NeoForge 1.21 compat event hooks and Holder effect constants",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-verified-compat-121-api"
                ))
                text = verifiedCompatMigrated
            }

            val recipeSerializerMigrated = migrateCustomRecipeSerializer121Source(text)
            if (recipeSerializerMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate custom recipe serializer to MapCodec and StreamCodec",
                    before = "fromJson/fromNetwork/toNetwork serializer methods",
                    after = "codec()/streamCodec() serializer methods",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-recipe-codec-121"
                ))
                text = recipeSerializerMigrated
            }

            // Structured serializers consume their original packet statements first.
            exactHook("struct-recipe-value-codecs", "Serialize source-proven ingredient and fluid values through target codecs") {
                migrateProvenRecipeValueSerialization(it)
            }

            val cookingRecipeConstructorMigrated = migrateLegacyCookingRecipeConstructorsSource(text, legacyCookingRecipeClasses)
            if (cookingRecipeConstructorMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy AbstractCookingRecipe constructors away from recipe ids",
                    before = "AbstractCookingRecipe subclass constructors with ResourceLocation recipe id",
                    after = "idless 1.21 cooking recipe constructors and SingleRecipeInput methods",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-cooking-recipe-idless-constructor"
                ))
                text = cookingRecipeConstructorMigrated
            }

            val inheritedRecipeStateMigrated = migrateInheritedRecipeStateAccessSource(
                text,
                inheritedRecipeStateAccessors,
                javaInheritanceIndex
            )
            if (inheritedRecipeStateMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate inherited recipe state reads to proven accessors and AbstractCookingRecipe state",
                    before = "serializer lambdas read private inherited fields or constructor-forwarded state fields",
                    after = "serializer lambdas call existing accessors or AbstractCookingRecipe cooking-time state",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-inherited-recipe-state-access"
                ))
                text = inheritedRecipeStateMigrated
            }

            val deferredHolderMigrated = migrateDeferredHolderGenericsSource(text, deferredHolderRegistryBaseHints)
            if (deferredHolderMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate DeferredHolder declarations to registry-base/concrete generic order",
                    before = "DeferredHolder<concrete, concrete>",
                    after = "DeferredHolder<registry base, concrete>",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-deferredholder-generics"
                ))
                text = deferredHolderMigrated
            }

            val mapEntryValueMigrated = migrateMapEntryValueGetCalls(text)
            if (mapEntryValueMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Map.Entry value reads away from legacy delegate get calls",
                    before = "entry.get()",
                    after = "entry.getValue()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-map-entry-value-get"
                ))
                text = mapEntryValueMigrated
            }

            val fluidHolderConstructorMigrated = migrateFluidHolderConstructorArgumentsSource(
                text,
                fluidDeferredHolderFields,
                fluidTupleDeferredHolderFields
            )
            if (fluidHolderConstructorMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Unwrap proven DeferredHolder fluid arguments for constructors that require Fluid values",
                    before = "new BucketItem(DeferredHolder<Fluid, ...>) / new LiquidBlock(DeferredHolder<Fluid, ...>)",
                    after = "new BucketItem(holder.get()) / new LiquidBlock(holder.get())",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-fluid-holder-constructor-args"
                ))
                text = fluidHolderConstructorMigrated
            }

            val deferredHolderAccessMigrated = migrateDeferredHolderGetValueCalls(text, deferredHolderFields)
            if (deferredHolderAccessMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate proven DeferredHolder getValue() calls to get()",
                    before = "DeferredHolder field .getValue()",
                    after = "DeferredHolder field .get()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-deferredholder-getvalue"
                ))
                text = deferredHolderAccessMigrated
            }

            val gameEventHolderMigrated = migrateDeferredHolderGameEventArguments(text, gameEventDeferredHolderFields)
            if (gameEventHolderMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Pass proven GameEvent DeferredHolder fields directly to 1.21 gameEvent APIs",
                    before = "gameEvent(..., DeferredHolder<GameEvent, ?>.get(), ...)",
                    after = "gameEvent(..., DeferredHolder<GameEvent, ?>, ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-gameevent-deferredholder-argument"
                ))
                text = gameEventHolderMigrated
            }

            val deferredRegisterListenerMigrated = migrateDeferredRegisterListenerReferencesSource(text, deferredRegisterOwners)
            if (deferredRegisterListenerMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Register DeferredRegister fields directly on the mod event bus",
                    before = "modBus.addListener(RegistryClass::register)",
                    after = "RegistryClass.DEFERRED_REGISTER.register(modBus)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-deferredregister-listener"
                ))
                text = deferredRegisterListenerMigrated
            }

            val finalContainerScopeMigrated = migrateContainerSizeCallsForDeclaredContainerScopes(text)
            if (finalContainerScopeMigrated != text) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Keep Container size calls on the Container API within declared method scopes",
                    before = "Container variable .size()",
                    after = "Container variable .getContainerSize()",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-container-scope-size"
                ))
                text = finalContainerScopeMigrated
            }

            if (text != original && !dryRun) {
                javaFile.writeText(text)
            }
        } catch (e: Exception) {
            // The source write is deferred until every rewrite succeeds; discard its staged receipts too.
            changes.subList(fileChangeStart, changes.size).clear()
            if (errorSink == null) throw e
            val relativeFile = projectDir.relativize(javaFile).toString().replace('\\', '/')
            errorSink.add("Common NeoForge 1.21 API migration error in $relativeFile: ${e.message}")
        }
    }

    if (!dryRun) {
        changes.addAll(migrateDatagenLookupProviderCallers(javaFiles, dryRun))
        changes.addAll(migrateProjectNestedHolderLookupProviderCallSites(javaFiles, javaInheritanceIndex))
        changes.addAll(migrateProjectHolderLookupForwarderMethods(javaFiles, effectiveItemStackNbtMethods, javaInheritanceIndex))
        changes.addAll(migrateProjectMobEffectHolderArgumentCallSites(srcDir, javaFiles, dryRun))
        changes.addAll(migrateMobEffectIdentityFlows(javaFiles, dryRun))
    }

    val resourceKeyLootTableOwners = collectResourceKeyLootTableFieldOwners(srcDir)
    val resourceKeyLootTableFields = collectResourceKeyLootTableFields(srcDir)
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val original = javaFile.readText()
            val migrated = migrateLootTableResourceKeyReferenceCreates(
                original,
                resourceKeyLootTableOwners,
                resourceKeyLootTableFields
            )
            if (migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Remove redundant ResourceKey.create wrappers around loot table ResourceKey expressions",
                    before = "ResourceKey.create(Registries.LOOT_TABLE, ResourceKey<LootTable>)",
                    after = "ResourceKey<LootTable>",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-loot-table-resourcekey-reference"
                ))
                if (!dryRun) {
                    javaFile.writeText(migrated)
                }
            }
        }

    if (!dryRun) {
        changes.addAll(LegacyNbtProviderMethodMigration().migrate(projectDir, dryRun = false))
    }
    return changes
}

internal fun StructuralRefactorPass.collectResourceKeyLootTableFieldOwners(srcDir: Path): Set<String> {
    val owners = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("ResourceKey<LootTable>") || !source.contains("lootTable")) return@forEach
            val className = classNameOfJavaSource(source) ?: return@forEach
            if (Regex("""\bResourceKey\s*<\s*LootTable\s*>\s+lootTable\b""").containsMatchIn(source)) {
                owners += className
            }
    }
    return owners
}

internal fun StructuralRefactorPass.collectResourceKeyLootTableFields(srcDir: Path): Set<String> {
    val fields = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("ResourceKey<LootTable>")) return@forEach
            val className = classNameOfJavaSource(source) ?: return@forEach
            val packageName = packageNameOf(source)
            Regex(
                """(?m)\b(?:public|protected|private)\s+static\s+(?:final\s+)?ResourceKey\s*<\s*LootTable\s*>\s+([A-Z][A-Z0-9_]*)\b"""
            ).findAll(source).forEach { match ->
                fields += "$className.${match.groupValues[1]}"
                if (packageName != null) {
                    fields += "$packageName.$className.${match.groupValues[1]}"
                }
            }
        }
    return fields
}

internal fun StructuralRefactorPass.collectMapCodecConstantOwners(srcDir: Path): Set<String> {
    val owners = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            owners += collectMapCodecConstantOwnersFromSource(javaFile.readText())
        }
    return owners
}

internal fun StructuralRefactorPass.collectMapCodecConstantOwnersFromSource(source: String): Set<String> {
    if (!source.contains("MapCodec") || !source.contains("CODEC")) return emptySet()
    return Regex(
        """\b(?:com\.mojang\.serialization\.)?MapCodec\s*<\s*(?:\?\s+extends\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s+CODEC\b"""
    ).findAll(source)
        .map { it.groupValues[1].substringAfterLast('.') }
        .toSet()
}

internal fun StructuralRefactorPass.collectMapCodecRequiredOwners(srcDir: Path): Set<String> {
    val owners = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            owners += collectMapCodecRequiredOwnersFromSource(javaFile.readText())
        }
    return owners
}

internal fun StructuralRefactorPass.collectMapCodecRequiredOwnersFromSource(source: String): Set<String> {
    if (!source.contains(".CODEC") || !source.contains(".register(")) return emptySet()
    val mapCodecRegistrySurface = source.contains("StructureProcessorType") ||
        source.contains("PlacementModifierType") ||
        source.contains("StructurePlacementType") ||
        source.contains("StructureType") ||
        source.contains("TreeDecoratorType")
    if (!mapCodecRegistrySurface) return emptySet()
    val owners = linkedSetOf<String>()
    Regex(
        """(?s)\.register\s*\((?:(?!;).)*?\(\)\s*->\s*(?:\(\)\s*->\s*)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.CODEC"""
    ).findAll(source)
        .mapTo(owners) { it.groupValues[1].substringAfterLast('.') }
    Regex(
        """(?s)\bregister\s*\((?:(?!;).)*?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.CODEC"""
    ).findAll(source)
        .mapTo(owners) { it.groupValues[1].substringAfterLast('.') }
    Regex(
        """\bnew\s+(?:PlacementModifierType|StructureProcessorType|StructurePlacementType|StructureType|TreeDecoratorType)\s*<[^>]*>\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.CODEC"""
    ).findAll(source)
        .mapTo(owners) { it.groupValues[1].substringAfterLast('.') }
    return owners
}

internal fun StructuralRefactorPass.collectCodecConstantOwners(srcDir: Path): Set<String> {
    val owners = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            owners += collectCodecConstantOwnersFromSource(javaFile.readText())
        }
    return owners
}

internal fun StructuralRefactorPass.collectCodecConstantOwnersFromSource(source: String): Set<String> {
    if (!source.contains("Codec") || !source.contains("CODEC")) return emptySet()
    return Regex(
        """\b(?<!Map)(?:com\.mojang\.serialization\.)?Codec\s*<\s*(?:\?\s+extends\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s+CODEC\b"""
    ).findAll(source)
        .map { it.groupValues[1].substringAfterLast('.') }
        .toSet()
}

internal data class LegacyItemStackPredicateOverrideMethod(
    val packageName: String,
    val ownerClass: String,
    val methodName: String
) {
    val qualifiedOwner: String = if (packageName.isBlank()) ownerClass else "$packageName.$ownerClass"
}

internal fun StructuralRefactorPass.collectLegacyItemStackPredicateOverrideMethods(javaFiles: List<Path>): Set<LegacyItemStackPredicateOverrideMethod> =
    javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("Predicate<ItemStack>") ||
            !executableCode.contains("Map<Predicate<ItemStack>") ||
            !executableCode.contains(".putIfAbsent(")) {
            return@mapNotNull null
        }
        val ownerClass = classNameOfJavaSource(executableCode) ?: return@mapNotNull null
        val packageName = packageNameOf(executableCode)
        val methodPattern = Regex(
            """(?m)\bstatic\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*Predicate\s*<\s*ItemStack\s*>\s+[A-Za-z_$][\w$]*\s*,"""
        )
        methodPattern.findAll(executableCode)
            .map { LegacyItemStackPredicateOverrideMethod(packageName, ownerClass, it.groupValues[1]) }
            .toList()
    }.flatten().toSet()

internal fun StructuralRefactorPass.migrateLegacyRegistryBackedItemStackPredicateOverrides(
    source: String,
    methods: Set<LegacyItemStackPredicateOverrideMethod>,
    javaTypes: List<JavaSourceType>
): String {
    val methodAccesses = legacyItemStackPredicateOverrideMethodAccesses(methods, javaTypes)
    val migratableMethods = methodAccesses.keys
    val requiredContainerTypes = methodAccesses.values.map { it.containerTypeFqn }.toSet()
    val currentTypeFqn = currentJavaSourceTypeFqn(source)
    var result = if (currentTypeFqn in requiredContainerTypes) {
        migratePlayerBackedSimpleContainerFieldVisibility(source)
    } else {
        source
    }
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Predicate<ItemStack>") &&
        migratableMethods.none { executableCode.contains("${it.methodName}(") }) {
        return result
    }
    result = migrateLegacyItemStackPredicateOverrideDefinitions(result, javaTypes)
    result = migrateLegacyItemStackPredicateOverrideCallSites(result, migratableMethods)
    if (result == source) return source
    if (result.contains("Function<RegistryAccess, Predicate<ItemStack>>")) {
        result = addImportIfMissing(result, "java.util.function.Function")
        result = addImportIfMissing(result, "net.minecraft.core.RegistryAccess")
    }
    if (result.contains("Optional<String>")) {
        result = addImportIfMissing(result, "java.util.Optional")
    }
    return result
}

internal data class ItemStackPredicateRegistryAccess(
    val expression: String,
    val containerTypeFqn: String
)

internal fun StructuralRefactorPass.migrateLegacyItemStackPredicateOverrideDefinitions(source: String, javaTypes: List<JavaSourceType>): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Map<Predicate<ItemStack>") ||
        !executableCode.contains("Predicate<ItemStack>") ||
        !executableCode.contains(".putIfAbsent(")) {
        return source
    }
    val registryAccess = inferItemStackPredicateOverrideRegistryAccess(source, javaTypes)
        ?: return source
    var result = source
    val fieldNames = Regex("""Map\s*<\s*Predicate\s*<\s*ItemStack\s*>\s*,\s*String\s*>\s+([A-Za-z_$][\w$]*)""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (fieldNames.isEmpty()) return source

    result = replaceExecutableRegex(
        result,
        Regex("""Map\s*<\s*Predicate\s*<\s*ItemStack\s*>\s*,\s*String\s*>""")
    ) { "Map<Function<RegistryAccess, Predicate<ItemStack>>, String>" }
    result = replaceExecutableRegex(
        result,
        Regex("""Predicate\s*<\s*ItemStack\s*>\s+([A-Za-z_$][\w$]*)(?=\s*,\s*String\s+[A-Za-z_$][\w$]*\s*\))""")
    ) { match -> "Function<RegistryAccess, Predicate<ItemStack>> ${match.groupValues[1]}" }

    for (fieldName in fieldNames) {
        val loopPattern = Regex(
            """(?ms)^([ \t]*)for\s*\(\s*Predicate\s*<\s*ItemStack\s*>\s+([A-Za-z_$][\w$]*)\s*:\s*${Regex.escape(fieldName)}\.keySet\(\)\s*\)\s*\{\s*if\s*\(\s*\2\.test\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\)\s*\{\s*return\s+${Regex.escape(fieldName)}\.get\(\s*\2\s*\)\s*;\s*}\s*}\s*return\s+([^;\r\n]+);\s*$"""
        )
        val beforeLoopRewrite = result
        result = replaceExecutableRegex(result, loopPattern) { match ->
            val indent = match.groupValues[1]
            val stack = match.groupValues[3]
            val originalLoop = beforeLoopRewrite.substring(match.range.first, match.range.last + 1)
            val fallback = Regex("""(?s).*}\s*return\s+([^;\r\n]+);\s*$""")
                .find(originalLoop)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: match.groupValues[4].trim()
            "${indent}Optional<String> key = $fieldName.entrySet().stream().filter(e -> e.getKey().apply($registryAccess).test($stack)).findAny().map(Map.Entry::getValue);\n" +
                "${indent}return key.orElseGet(() -> $fallback);"
        }
    }

    result = migratePlayerBackedSimpleContainerFieldVisibility(result)
    return result
}

internal fun StructuralRefactorPass.legacyItemStackPredicateOverrideMethodAccesses(
    methods: Set<LegacyItemStackPredicateOverrideMethod>,
    javaTypes: List<JavaSourceType>
): Map<LegacyItemStackPredicateOverrideMethod, ItemStackPredicateRegistryAccess> =
    methods.mapNotNull { method ->
        val access = legacyItemStackPredicateOverrideMethodAccess(method, javaTypes) ?: return@mapNotNull null
        method to access
    }.toMap()

internal fun StructuralRefactorPass.legacyItemStackPredicateOverrideMethodAccess(
    method: LegacyItemStackPredicateOverrideMethod,
    javaTypes: List<JavaSourceType>
): ItemStackPredicateRegistryAccess? {
    val owner = javaTypes.singleOrNull { it.fqn == method.qualifiedOwner } ?: return null
    val executableCode = maskJavaCommentsAndLiterals(owner.source)
    if (!executableCode.contains("Map<Predicate<ItemStack>") ||
        !executableCode.contains("Predicate<ItemStack>") ||
        !executableCode.contains(".putIfAbsent(")) {
        return null
    }
    return inferItemStackPredicateOverrideRegistryAccessShape(owner.source, javaTypes)
}

internal fun StructuralRefactorPass.inferItemStackPredicateOverrideRegistryAccess(source: String, javaTypes: List<JavaSourceType>): String? =
    inferItemStackPredicateOverrideRegistryAccessShape(source, javaTypes)?.expression

internal fun StructuralRefactorPass.inferItemStackPredicateOverrideRegistryAccessShape(
    source: String,
    javaTypes: List<JavaSourceType>
): ItemStackPredicateRegistryAccess? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val packageName = packageNameOf(executableCode)
    val imports = javaNonStaticImports(executableCode)
    val wildcardImports = javaNonStaticWildcardImports(executableCode)
    val knownTypes = javaTypes.map { it.fqn }.toSet()
    val typeByFqn = javaTypes.associateBy { it.fqn }
    val inventoryFields = Regex(
        """(?m)^[ \t]*private\s+final\s+([A-Za-z_$][\w$]*(?:Inventory|Container))\s+([A-Za-z_$][\w$]*)\s*;"""
    ).findAll(executableCode)
        .mapNotNull { match ->
            val typeName = match.groupValues[1]
            val resolvedType = resolveKnownJavaTypeReference(
                typeName,
                packageName,
                imports,
                wildcardImports,
                knownTypes
            ) ?: return@mapNotNull null
            val type = typeByFqn[resolvedType] ?: return@mapNotNull null
            if (!isPlayerBackedSimpleContainerType(type)) return@mapNotNull null
            match.groupValues[2] to resolvedType
        }
        .distinct()
        .toList()
    val (fieldName, containerTypeFqn) = inventoryFields.singleOrNull() ?: return null
    return ItemStackPredicateRegistryAccess(
        expression = "this.$fieldName.player.registryAccess()",
        containerTypeFqn = containerTypeFqn
    )
}

internal fun StructuralRefactorPass.currentJavaSourceTypeFqn(source: String): String? {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val className = javaTopLevelTypeName(executableCode) ?: return null
    val packageName = packageNameOf(executableCode)
    return if (packageName.isBlank()) className else "$packageName.$className"
}

internal fun StructuralRefactorPass.isPlayerBackedSimpleContainerType(type: JavaSourceType): Boolean {
    val executableCode = maskJavaCommentsAndLiterals(type.source)
    val imports = javaNonStaticImports(executableCode)
    val wildcardImports = javaNonStaticWildcardImports(executableCode)
    val extendsSimpleContainer = extractJavaTopLevelSuperTypes(executableCode).any { superType ->
        resolveKnownJavaTypeReference(
            superType,
            type.packageName,
            imports,
            wildcardImports,
            setOf("net.minecraft.world.SimpleContainer")
        ) == "net.minecraft.world.SimpleContainer"
    }
    if (!extendsSimpleContainer) return false

    val hasAccessibleOrMigratablePlayerField = Regex(
        """(?m)^[ \t]*(?:private|public)\s+final\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s+player\s*;"""
    ).findAll(executableCode).any { match ->
        resolveKnownJavaTypeReference(
            match.groupValues[1],
            type.packageName,
            imports,
            wildcardImports,
            setOf("net.minecraft.world.entity.player.Player")
        ) == "net.minecraft.world.entity.player.Player"
    }
    if (!hasAccessibleOrMigratablePlayerField) return false
    return Regex("""\bthis\.player\s*=\s*player\s*;""").containsMatchIn(executableCode)
}

internal fun StructuralRefactorPass.migratePlayerBackedSimpleContainerFieldVisibility(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!Regex("""(?m)^[ \t]*private\s+final\s+(?:net\.minecraft\.world\.entity\.player\.)?Player\s+player\s*;""").containsMatchIn(executableCode) ||
        !executableCode.contains("this.player = player") ||
        !Regex("""extends\s+(?:net\.minecraft\.world\.)?SimpleContainer\b""").containsMatchIn(executableCode)) {
        return source
    }
    return replaceExecutableRegex(
        source,
        Regex("""(?m)^([ \t]*)private\s+final\s+((?:net\.minecraft\.world\.entity\.player\.)?Player)\s+player\s*;"""),
    ) { match -> "${match.groupValues[1]}public final ${match.groupValues[2]} player;" }
}

internal fun StructuralRefactorPass.migrateLegacyItemStackPredicateOverrideCallSites(
    source: String,
    methods: Set<LegacyItemStackPredicateOverrideMethod>
): String {
    if (methods.isEmpty()) return source
    var result = source
    methods.forEach { method ->
        result = rewriteExecutableJavaCall(result, method.methodName) { receiver, args ->
            if (args.size < 2) return@rewriteExecutableJavaCall null
            val receiverText = receiver.trim()
            val matchesOwner = receiverText == method.ownerClass || receiverText == method.qualifiedOwner ||
                receiverText.endsWith(".${method.ownerClass}")
            if (!matchesOwner || !isLegacyItemStackPredicateLambda(args[0])) return@rewriteExecutableJavaCall null
            val migratedArgs = args.toMutableList()
            migratedArgs[0] = "registryAccess -> ${args[0].trim()}"
            "$receiverText.${method.methodName}(${migratedArgs.joinToString(", ") { it.trim() }})"
        }
        result = rewriteUnqualifiedLegacyItemStackPredicateOverrideCallSites(result, method)
    }
    return result
}

internal fun StructuralRefactorPass.rewriteUnqualifiedLegacyItemStackPredicateOverrideCallSites(
    source: String,
    method: LegacyItemStackPredicateOverrideMethod
): String {
    var result = source
    var cursor = 0
    val token = "${method.methodName}("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0) {
            val prev = executableCode[tokenIndex - 1]
            if (prev.isLetterOrDigit() || prev == '_' || prev == '$' || prev == '.') {
                cursor = tokenIndex + token.length
                continue
            }
        }
        val openParen = tokenIndex + method.methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (looksLikeJavaMethodDeclaration(executableCode, tokenIndex, closeParen)) {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))
        if (args.size < 2 || !isLegacyItemStackPredicateLambda(args[0])) {
            cursor = closeParen + 1
            continue
        }
        val migratedArgs = args.toMutableList()
        migratedArgs[0] = "registryAccess -> ${args[0].trim()}"
        val replacement = "${method.methodName}(${migratedArgs.joinToString(", ") { it.trim() }})"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.isLegacyItemStackPredicateLambda(expression: String): Boolean {
    val trimmed = expression.trim()
    if (!trimmed.contains("->")) return false
    if (Regex("""^\s*(?:\(?\s*)?registryAccess\s*(?:\)?\s*)?->""").containsMatchIn(trimmed)) return false
    return Regex("""^\s*(?:\(?\s*)?[A-Za-z_$][\w$]*\s*(?:\)?\s*)?->""").containsMatchIn(trimmed)
}

internal data class LegacyBannerPatternLayerFactory(
    val packageName: String,
    val ownerClass: String,
    val fieldName: String
) {
    val qualifiedOwner: String = if (packageName.isBlank()) ownerClass else "$packageName.$ownerClass"
}

internal data class LegacyBannerItemStackFactory(
    val packageName: String,
    val ownerClass: String,
    val methodName: String
) {
    val qualifiedOwner: String = if (packageName.isBlank()) ownerClass else "$packageName.$ownerClass"
}

internal fun StructuralRefactorPass.collectLegacyBannerPatternLayerFactories(javaFiles: List<Path>): Set<LegacyBannerPatternLayerFactory> =
    javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("BannerPattern.Builder") || !executableCode.contains("new BannerPattern.Builder()")) {
            return@mapNotNull null
        }
        val ownerClass = classNameOfJavaSource(executableCode) ?: return@mapNotNull null
        val packageName = packageNameOf(executableCode)
        Regex("""(?m)\bstatic\s+final\s+BannerPattern\.Builder\s+([A-Za-z_$][\w$]*)\s*=""")
            .findAll(executableCode)
            .map { LegacyBannerPatternLayerFactory(packageName, ownerClass, it.groupValues[1]) }
            .toList()
    }.flatten().toSet()

internal fun StructuralRefactorPass.collectLegacyBannerItemStackFactories(javaFiles: List<Path>): Set<LegacyBannerItemStackFactory> =
    javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val code = maskJavaComments(source)
        if (!code.contains(".toListTag()") ||
            !code.contains("BlockItem.setBlockEntityData") ||
            !code.contains("BlockEntityType.BANNER")) {
            return@mapNotNull null
        }
        val ownerClass = classNameOfJavaSource(code) ?: return@mapNotNull null
        val packageName = packageNameOf(code)
        javaMethodRanges(code)
            .mapNotNull { method ->
                val methodText = code.substring(method.range)
                if (methodText.contains(".toListTag()") &&
                    methodText.contains("BlockItem.setBlockEntityData") &&
                    methodText.contains("BlockEntityType.BANNER") &&
                    Regex("""\bItemStack\s+${Regex.escape(method.name)}\s*\(""").containsMatchIn(method.header)) {
                    LegacyBannerItemStackFactory(packageName, ownerClass, method.name)
                } else {
                    null
                }
            }
    }.flatten().toSet()

internal fun StructuralRefactorPass.migrateLegacyBannerPatternLayerSource(
    source: String,
    patternFactories: Set<LegacyBannerPatternLayerFactory>,
    itemFactories: Set<LegacyBannerItemStackFactory>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BannerPattern") &&
        !executableCode.contains(".toListTag()") &&
        itemFactories.none { executableCode.contains("${it.methodName}()") }) {
        return source
    }

    var result = source
    result = migrateLegacyBannerPatternFactoryDefinitions(result)
    result = migrateLegacyBannerItemStackFactoryDefinitions(result, patternFactories)
    result = migrateLegacyBannerItemStackFactoryCalls(result, itemFactories)

    if (result == source) return source

    if (result.contains("HolderGetter<BannerPattern>")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderGetter")
        result = addImportIfMissing(result, "net.minecraft.world.level.block.entity.BannerPattern")
    }
    if (result.contains("BannerPatternLayers")) {
        result = addImportIfMissing(result, "net.minecraft.world.level.block.entity.BannerPatternLayers")
    }
    if (result.contains("DataComponents.BANNER_PATTERNS")) {
        result = addImportIfMissing(result, "net.minecraft.core.component.DataComponents")
    }
    if (result.contains("Registries.BANNER_PATTERN")) {
        result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    }
    result = removeUnusedSimpleImport(result, "net.minecraft.nbt.CompoundTag", "CompoundTag")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.level.block.entity.BlockEntityType", "BlockEntityType")
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBannerPatternFactoryDefinitions(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("BannerPattern.Builder") || !executableCode.contains("new BannerPattern.Builder()")) return source
    val pattern = Regex(
        """(?ms)^([ \t]*)(public|protected|private)?([ \t]+static[ \t]+final[ \t]+)BannerPattern\.Builder[ \t]+([A-Za-z_$][\w$]*)[ \t]*=[ \t]*new[ \t]+BannerPattern\.Builder[ \t]*\([ \t]*\)(.*?);[ \t]*$"""
    )
    return replaceExecutableRegex(source, pattern) { match ->
        val initializerTail = match.groupValues[5]
        val entries = legacyBannerPatternEntries(initializerTail)
        if (entries.isEmpty()) return@replaceExecutableRegex match.value
        val indent = match.groupValues[1]
        val visibility = match.groupValues[2].ifBlank { "public" }
        val fieldName = match.groupValues[4]
        buildString {
            append(indent)
            append(visibility)
            append(" static BannerPatternLayers ")
            append(fieldName)
            append("(HolderGetter<BannerPattern> patternRegistry) {\n")
            append(indent)
            append("    return new BannerPatternLayers.Builder()\n")
            entries.forEach { entry ->
                append(indent)
                append("        .add(patternRegistry.getOrThrow(")
                append(entry.first)
                append("), ")
                append(entry.second)
                append(")\n")
            }
            append(indent)
            append("        .build();\n")
            append(indent)
            append("}")
        }
    }
}

internal fun StructuralRefactorPass.migrateLegacyBannerItemStackFactoryDefinitions(
    source: String,
    patternFactories: Set<LegacyBannerPatternLayerFactory>
): String {
    val code = maskJavaComments(source)
    if (!code.contains(".toListTag()") ||
        !code.contains("BlockItem.setBlockEntityData") ||
        !code.contains("BlockEntityType.BANNER")) {
        return source
    }
    var result = source
    val methods = javaMethodRanges(code)
        .filter { method ->
            val methodText = code.substring(method.range)
            methodText.contains(".toListTag()") &&
                methodText.contains("BlockItem.setBlockEntityData") &&
                methodText.contains("BlockEntityType.BANNER")
        }
        .toList()
    for (method in methods.asReversed()) {
        val originalMethod = result.substring(method.range)
        val migratedMethod = migrateLegacyBannerItemStackFactoryMethod(originalMethod, patternFactories)
        if (migratedMethod != originalMethod) {
            result = result.substring(0, method.range.first) + migratedMethod + result.substring(method.range.last + 1)
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyBannerItemStackFactoryMethod(
    methodText: String,
    patternFactories: Set<LegacyBannerPatternLayerFactory>
): String {
    var migrated = ensureBannerPatternRegistryParameter(methodText)
    val nbtPattern = Regex(
        """(?m)^([ \t]*)CompoundTag[ \t]+([A-Za-z_$][\w$]*)[ \t]*=[ \t]*new[ \t]+CompoundTag\([ \t]*\);[ \t]*\r?\n\1\2\.put\([ \t]*"Patterns"[ \t]*,[ \t]*(.+?)\.toListTag\([ \t]*\)[ \t]*\);[ \t]*\r?\n\1BlockItem\.setBlockEntityData\([ \t]*([^,\r\n]+?)[ \t]*,[ \t]*BlockEntityType\.BANNER[ \t]*,[ \t]*\2[ \t]*\);[ \t]*$"""
    )
    migrated = nbtPattern.replace(migrated) { match ->
        val indent = match.groupValues[1]
        val legacyExpression = match.groupValues[3].trim()
        val stackExpression = match.groupValues[4].trim()
        val layersExpression = migrateLegacyBannerLayersExpression(legacyExpression, patternFactories)
        "$indent$stackExpression.set(DataComponents.BANNER_PATTERNS, $layersExpression);"
    }
    return migrated
}

internal fun StructuralRefactorPass.ensureBannerPatternRegistryParameter(methodText: String): String {
    if (methodText.contains("HolderGetter<BannerPattern>")) return methodText
    val declaration = Regex(
        """(?m)^([ \t]*(?:@\w+(?:\([^)]*\))?\s*\r?\n[ \t]*)*(?:public|protected|private)\s+(?:static\s+)?[\w<>\[\].?,\s]+\s+[A-Za-z_$][\w$]*\s*)\(([^)]*)\)"""
    ).find(methodText) ?: return methodText
    val existingParams = declaration.groupValues[2].trim()
    val newParams = if (existingParams.isBlank()) {
        "HolderGetter<BannerPattern> patternRegistry"
    } else {
        "$existingParams, HolderGetter<BannerPattern> patternRegistry"
    }
    return methodText.substring(0, declaration.range.first) +
        declaration.groupValues[1] +
        "($newParams)" +
        methodText.substring(declaration.range.last + 1)
}

internal fun StructuralRefactorPass.migrateLegacyBannerLayersExpression(
    legacyExpression: String,
    patternFactories: Set<LegacyBannerPatternLayerFactory>
): String {
    val trimmed = legacyExpression.trim()
    val field = patternFactories.firstOrNull { factory ->
        trimmed == factory.fieldName ||
            trimmed == "${factory.ownerClass}.${factory.fieldName}" ||
            trimmed == "${factory.qualifiedOwner}.${factory.fieldName}"
    }
    if (field != null) {
        return if (trimmed == field.fieldName) {
            "${field.fieldName}(patternRegistry)"
        } else {
            "${trimmed.substringBeforeLast('.')}.${field.fieldName}(patternRegistry)"
        }
    }
    return "$trimmed.build()"
}

internal fun StructuralRefactorPass.migrateLegacyBannerItemStackFactoryCalls(
    source: String,
    itemFactories: Set<LegacyBannerItemStackFactory>
): String {
    if (itemFactories.isEmpty()) return source
    var result = source
    itemFactories.forEach { factory ->
        result = rewriteQualifiedLegacyBannerItemStackFactoryCalls(result, factory)
        result = rewriteUnqualifiedLegacyBannerItemStackFactoryCalls(result, factory)
    }
    return result
}

internal fun StructuralRefactorPass.rewriteQualifiedLegacyBannerItemStackFactoryCalls(
    source: String,
    factory: LegacyBannerItemStackFactory
): String =
    rewriteExecutableJavaCallWithOffset(source, factory.methodName) { receiver, args, callOffset ->
        if (args.isNotEmpty()) return@rewriteExecutableJavaCallWithOffset null
        val receiverText = receiver.trim()
        val matchesFactory = receiverText == factory.ownerClass || receiverText == factory.qualifiedOwner ||
            receiverText.endsWith(".${factory.ownerClass}")
        if (!matchesFactory) return@rewriteExecutableJavaCallWithOffset null
        val lookup = inferBannerPatternRegistryLookupExpression(source, callOffset) ?: return@rewriteExecutableJavaCallWithOffset null
        "$receiverText.${factory.methodName}($lookup)"
    }

internal fun StructuralRefactorPass.rewriteUnqualifiedLegacyBannerItemStackFactoryCalls(
    source: String,
    factory: LegacyBannerItemStackFactory
): String {
    var result = source
    var cursor = 0
    val token = "${factory.methodName}("
    while (true) {
        val executableCode = maskJavaCommentsAndLiterals(result)
        val tokenIndex = executableCode.indexOf(token, cursor)
        if (tokenIndex < 0) break
        if (tokenIndex > 0) {
            val prev = executableCode[tokenIndex - 1]
            if (prev.isLetterOrDigit() || prev == '_' || prev == '$' || prev == '.') {
                cursor = tokenIndex + token.length
                continue
            }
        }
        val openParen = tokenIndex + factory.methodName.length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + token.length
            continue
        }
        if (result.substring(openParen + 1, closeParen).trim().isNotEmpty()) {
            cursor = closeParen + 1
            continue
        }
        if (looksLikeJavaMethodDeclaration(executableCode, tokenIndex, closeParen)) {
            cursor = closeParen + 1
            continue
        }
        val lookup = inferBannerPatternRegistryLookupExpression(result, tokenIndex)
        if (lookup == null) {
            cursor = closeParen + 1
            continue
        }
        val replacement = "${factory.methodName}($lookup)"
        result = result.substring(0, tokenIndex) + replacement + result.substring(closeParen + 1)
        cursor = tokenIndex + replacement.length
    }
    return result
}

internal fun StructuralRefactorPass.looksLikeJavaMethodDeclaration(source: String, methodNameIndex: Int, closeParen: Int): Boolean {
    val next = source.drop(closeParen + 1).dropWhile { it.isWhitespace() }.firstOrNull()
    if (next != '{') return false
    val lineStart = source.lastIndexOf('\n', methodNameIndex).let { if (it < 0) 0 else it + 1 }
    val prefix = source.substring(lineStart, methodNameIndex)
    return Regex("""\b(?:public|protected|private|default)\b""").containsMatchIn(prefix) ||
        Regex("""\bItemStack\s+$""").containsMatchIn(prefix)
}

/** `Type name(...);` (an abstract or interface method): a return type before the name and no body after it. */
internal fun isBodilessJavaMethodDeclaration(executableCode: String, nameIndex: Int, closeParen: Int): Boolean {
    if (!Regex("""^\s*(?:throws\s+[\w$.,\s]+?)?;""").containsMatchIn(executableCode.substring(closeParen + 1))) return false
    val before = executableCode.substring(maxOf(0, nameIndex - 200), nameIndex)
    val word = Regex("""([\w$]+|[>\]])\s*$""").find(before)?.groupValues?.get(1) ?: return false
    return word !in setOf("return", "new", "throw", "else", "case", "yield", "assert", "do")
}

internal fun StructuralRefactorPass.inferBannerPatternRegistryLookupExpression(source: String, offset: Int): String? {
    inferCreativeTabBannerPatternLookup(source, offset)?.let { return it }
    val method = javaMethodRanges(source).firstOrNull { offset in it.range } ?: return null
    return inferBannerPatternLookupFromScope(source.substring(method.range.first, offset), method.header)
}

internal fun StructuralRefactorPass.inferCreativeTabBannerPatternLookup(source: String, offset: Int): String? {
    val pattern = Regex(
        """\.displayItems\s*\(\s*\(\s*(?:CreativeModeTab\.ItemDisplayParameters\s+)?([A-Za-z_$][\w$]*)\s*,\s*(?:CreativeModeTab\.Output\s+)?([A-Za-z_$][\w$]*)\s*\)\s*->\s*\{"""
    )
    return pattern.findAll(source)
        .mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace >= 0 && closeBrace >= offset && offset > openBrace) {
                "${match.groupValues[1]}.holders().lookupOrThrow(Registries.BANNER_PATTERN)"
            } else {
                null
            }
        }
        .lastOrNull()
}

internal fun StructuralRefactorPass.inferBannerPatternLookupFromScope(scopePrefix: String, methodHeader: String?): String? {
    val combined = listOfNotNull(methodHeader, scopePrefix).joinToString("\n")
    Regex("""HolderGetter\s*<\s*BannerPattern\s*>\s+([A-Za-z_$][\w$]*)""")
        .find(combined)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    Regex("""HolderLookup\.Provider\s+([A-Za-z_$][\w$]*)""")
        .find(combined)
        ?.groupValues
        ?.get(1)
        ?.let { return "$it.lookupOrThrow(Registries.BANNER_PATTERN)" }
    Regex("""RegistryAccess\s+([A-Za-z_$][\w$]*)""")
        .find(combined)
        ?.groupValues
        ?.get(1)
        ?.let { return "$it.lookupOrThrow(Registries.BANNER_PATTERN)" }
    Regex("""\b(registryAccess)\s*->""")
        .find(combined)
        ?.groupValues
        ?.get(1)
        ?.let { return "$it.registryOrThrow(Registries.BANNER_PATTERN).asLookup()" }
    Regex("""(?:ServerLevel|Level|LevelAccessor|LevelReader|WorldGenLevel|ServerLevelAccessor)\s+([A-Za-z_$][\w$]*)""")
        .find(combined)
        ?.groupValues
        ?.get(1)
        ?.let { return "$it.holderLookup(Registries.BANNER_PATTERN)" }
    return null
}

internal fun StructuralRefactorPass.legacyBannerPatternEntries(initializerTail: String): List<Pair<String, String>> {
    val entries = mutableListOf<Pair<String, String>>()
    var cursor = 0
    val token = ".addPattern("
    while (true) {
        val tokenIndex = initializerTail.indexOf(token, cursor)
        if (tokenIndex < 0) break
        val openParen = tokenIndex + ".addPattern".length
        val closeParen = findMatchingParen(initializerTail, openParen)
        if (closeParen < 0) break
        val args = splitTopLevelJavaArgs(initializerTail.substring(openParen + 1, closeParen))
        if (args.size == 2) {
            entries += args[0].trim() to args[1].trim()
        }
        cursor = closeParen + 1
    }
    return entries
}

internal data class JavaMethodRange(val name: String, val header: String, val range: IntRange)

internal fun StructuralRefactorPass.javaMethodRanges(source: String): List<JavaMethodRange> {
    val pattern = Regex(
        """(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:public|protected|private)\s+(?:static\s+)?(?:<[^>{};]+>\s*)?[\w<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"""
    )
    val executableSource = maskJavaCommentsAndLiterals(source)
    return pattern.findAll(executableSource).mapNotNull { match ->
        val openBrace = executableSource.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableSource, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@mapNotNull null
        JavaMethodRange(match.groupValues[1], source.substring(match.range), match.range.first..closeBrace)
    }.toList()
}

internal fun StructuralRefactorPass.removeUnusedSimpleImport(source: String, importName: String, simpleName: String): String {
    val without = removeImport(source, importName)
    return if (Regex("""(?<![.\w$])${Regex.escape(simpleName)}(?![\w$])""").containsMatchIn(without)) {
        source
    } else {
        without
    }
}

internal fun StructuralRefactorPass.collectLegacyRegistryBackedMethods(javaFiles: List<Path>): Set<LegacyRegistryBackedMethod> {
    val methods = linkedSetOf<LegacyRegistryBackedMethod>()
    val methodPattern = Regex(
        """(?m)(?:^|\r?\n)[ \t]*(?:(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*)(?:(?:public|protected|private|static|final|synchronized)\s+)+[A-Za-z_$][\w$<>\[\].?,\s]*\s+([A-Za-z_$][\w$]*)\s*\([^()]*\)\s*\{"""
    )
    for (javaFile in javaFiles) {
        val source = javaFile.readText()
        if (!source.contains("BuiltInRegistries.PAINTING_VARIANT.tags().getTag(")) continue
        val owner = javaTopLevelTypeName(source) ?: continue
        methodPattern.findAll(source).forEach { match ->
            val openBrace = source.indexOf('{', match.range.last - 1)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (closeBrace <= openBrace) return@forEach
            val methodText = source.substring(match.range.first, closeBrace + 1)
            if (methodText.contains("BuiltInRegistries.PAINTING_VARIANT.tags().getTag(")) {
                methods += LegacyRegistryBackedMethod(owner, match.groupValues[1])
            }
        }
    }
    return methods
}

internal fun StructuralRefactorPass.collectAttributeModifierIdReferences(javaFiles: List<Path>): Set<String> {
    val references = linkedSetOf<String>()
    val id = """[A-Za-z_$][\w$]*"""
    val qualifiedModifierId = """(?:$id\.)+[A-Z0-9_]*MODIFIER[A-Z0-9_]*"""

    fun normalizedQualifiedId(expression: String): String {
        val parts = expression.split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts.last()}"
        } else {
            expression
        }
    }

    fun directAttributeModifierUse(source: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("""new\s+AttributeModifier\s*\(\s*$escaped\b""").containsMatchIn(source) ||
            Regex("""\.(?:getModifier|hasModifier|removeModifier)\(\s*$escaped\s*\)""").containsMatchIn(source) ||
            Regex("""\.(?:getModifier|hasModifier|removeModifier)\(\s*[^,()]+,\s*$escaped\s*\)""").containsMatchIn(source) ||
            Regex("""\.addAttributeModifier\([^;\r\n]*\b$escaped\b""").containsMatchIn(source)
    }

    for (javaFile in javaFiles) {
        val source = javaFile.readText()
        if (!source.contains("AttributeModifier") &&
            !source.contains(".getModifier(") &&
            !source.contains(".hasModifier(") &&
            !source.contains(".removeModifier(") &&
            !source.contains(".addAttributeModifier(")) {
            continue
        }

        Regex("""new\s+AttributeModifier\s*\(\s*($qualifiedModifierId)\b""")
            .findAll(source)
            .forEach { references += normalizedQualifiedId(it.groupValues[1]) }
        Regex("""\.(?:getModifier|hasModifier|removeModifier)\(\s*($qualifiedModifierId)\s*\)""")
            .findAll(source)
            .forEach { references += normalizedQualifiedId(it.groupValues[1]) }
        Regex("""\.addAttributeModifier\([^;\r\n]*\b($qualifiedModifierId)\b""")
            .findAll(source)
            .forEach { references += normalizedQualifiedId(it.groupValues[1]) }

        Regex(
            """\b(?:final\s+)?(?:(?:java\.util\.)?UUID|(?:net\.minecraft\.resources\.)?ResourceLocation|var)\s+($id)\s*=\s*($qualifiedModifierId)\s*;"""
        ).findAll(source).forEach { match ->
            val alias = match.groupValues[1]
            val target = match.groupValues[2]
            if (directAttributeModifierUse(source, alias)) {
                references += normalizedQualifiedId(target)
            }
        }
    }

    return references
}

internal fun StructuralRefactorPass.collectAttributeModifierMethods(javaFiles: List<Path>): Map<String, Set<String>> {
    val id = """[A-Za-z_$][\w$]*"""
    val declaredMethods = linkedMapOf<String, MutableSet<String>>()
    val parents = linkedMapOf<String, MutableSet<String>>()

    fun simpleTypeName(expression: String): String =
        expression
            .replace(Regex("""<[^<>]*>"""), "")
            .trim()
            .removeSuffix("[]")
            .substringAfterLast('.')

    fun parentNames(declarationTail: String): Set<String> {
        val result = linkedSetOf<String>()
        Regex("""\bextends\s+([^{}]*?)(?=\bimplements\b|$)""")
            .find(declarationTail)
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { result += simpleTypeName(it) }
        Regex("""\bimplements\s+([^{}]*)""")
            .find(declarationTail)
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { result += simpleTypeName(it) }
        return result
    }

    for (javaFile in javaFiles) {
        val source = javaFile.readText()
        val owner = javaTopLevelTypeName(source) ?: continue
        val declaration = Regex("""(?s)\b(?:class|interface|record|enum)\s+${Regex.escape(owner)}\b([^{}]*)\{""")
            .find(source)
        if (declaration != null) {
            parents.getOrPut(owner) { linkedSetOf() }.addAll(parentNames(declaration.groupValues[1]))
        }
        Regex(
            """(?m)\b(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)*(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?AttributeModifier\s+($id)\s*\("""
        ).findAll(source).forEach { match ->
            declaredMethods.getOrPut(owner) { linkedSetOf() } += match.groupValues[1]
        }
    }

    val resolved = linkedMapOf<String, MutableSet<String>>()
    fun methodsFor(typeName: String, seen: Set<String> = emptySet()): Set<String> {
        if (typeName in seen) return emptySet()
        resolved[typeName]?.let { return it }
        val methods = linkedSetOf<String>()
        methods += declaredMethods[typeName].orEmpty()
        parents[typeName].orEmpty().forEach { parent ->
            methods += methodsFor(parent, seen + typeName)
        }
        resolved[typeName] = methods
        return methods
    }

    (declaredMethods.keys + parents.keys).forEach { methodsFor(it) }
    return resolved.mapValues { it.value.toSet() }
}

internal data class AttributeModifierIdMethodArgument(
    val owner: String,
    val methodName: String,
    val parameterIndex: Int
)

internal fun StructuralRefactorPass.collectAttributeModifierIdMethodArguments(
    javaFiles: List<Path>,
    attributeModifierMethods: Map<String, Set<String>>,
    genericMethodReturnTypes: Map<String, String>
): Set<AttributeModifierIdMethodArgument> {
    val hints = linkedSetOf<AttributeModifierIdMethodArgument>()
    val id = """[A-Za-z_$][\w$]*"""
    val callPattern = Regex("""(?:(($id(?:\.$id)*)|this)\s*\.\s*)?($id)\s*\(""")
    val ignoredNames = setOf("if", "for", "while", "switch", "catch", "new", "return", "throw", "super", "this")

    fun methodsForType(typeName: String, typeParameterBounds: Map<String, Set<String>>): Set<String> {
        val simple = typeName.substringAfterLast('.')
        val methods = linkedSetOf<String>()
        methods += attributeModifierMethods[simple].orEmpty()
        typeParameterBounds[simple].orEmpty().forEach { bound ->
            methods += attributeModifierMethods[bound].orEmpty()
        }
        return methods
    }

    fun isAttributeModifierExpression(
        expression: String,
        scope: String,
        ownerName: String,
        variableTypes: Map<String, String>,
        typeParameterBounds: Map<String, Set<String>>
    ): Boolean {
        val trimmed = expression.trim()
        val directType = variableTypes[trimmed]
        if (directType == "AttributeModifier" || "AttributeModifier" in typeParameterBounds[directType].orEmpty()) {
            return true
        }
        val methodCall = Regex("""(?s)^(?:(.+)\.)?($id)\s*\(.*\)$""").matchEntire(trimmed)
            ?: return false
        val receiver = methodCall.groupValues[1].trim()
        val methodName = methodCall.groupValues[2]
        if (receiver.isBlank() || receiver == "this") {
            return methodName in attributeModifierMethods[ownerName].orEmpty()
        }
        val receiverType = variableTypes[receiver]
        if (receiverType != null && methodName in methodsForType(receiverType, typeParameterBounds)) {
            return true
        }
        return methodName in attributeModifierMethods[receiver.substringAfterLast('.')].orEmpty()
    }

    fun attributeModifierIdReceiver(argument: String): String? {
        val trimmed = argument.trim()
        return when {
            trimmed.endsWith(".id()") -> trimmed.removeSuffix(".id()").trim()
            trimmed.endsWith(".getId()") -> trimmed.removeSuffix(".getId()").trim()
            else -> null
        }.takeIf { !it.isNullOrBlank() }
    }

    fun isAttributeModifierIdArgument(
        argument: String,
        scope: String,
        ownerName: String,
        variableTypes: Map<String, String>,
        typeParameterBounds: Map<String, Set<String>>
    ): Boolean {
        val receiver = attributeModifierIdReceiver(argument) ?: return false
        return isAttributeModifierExpression(receiver, scope, ownerName, variableTypes, typeParameterBounds)
    }

    fun callOwner(
        receiver: String,
        ownerName: String,
        variableTypes: Map<String, String>
    ): String? {
        val trimmed = receiver.trim()
        if (trimmed.isBlank() || trimmed == "this") return ownerName
        if (trimmed == "super") return null
        variableTypes[trimmed]?.let { return it.substringAfterLast('.') }
        val simple = trimmed.substringAfterLast('.')
        return if (simple.firstOrNull()?.isUpperCase() == true) simple else null
    }

    for (javaFile in javaFiles) {
        val source = javaFile.readText()
        if ((!source.contains(".id()") && !source.contains(".getId()")) || !source.contains("AttributeModifier")) continue
        val ownerName = javaTopLevelTypeName(source) ?: continue
        callPattern.findAll(source).forEach { match ->
            val methodName = match.groupValues[3]
            if (methodName in ignoredNames) return@forEach
            val openParen = match.range.last
            val closeParen = findMatchingParen(source, openParen)
            if (closeParen < 0) return@forEach
            val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
            if (args.none { it.contains(".id()") || it.contains(".getId()") }) return@forEach
            val scope = enclosingMethodText(source, match.range.first) ?: source
            val variableTypes = javaLocalVariableTypes(scope, genericMethodReturnTypes)
            val typeParameterBounds = javaTypeParameterBounds(scope)
            val receiver = match.groupValues[1]
            val targetOwner = callOwner(receiver, ownerName, variableTypes) ?: return@forEach
            args.forEachIndexed { index, argument ->
                if (isAttributeModifierIdArgument(argument, scope, ownerName, variableTypes, typeParameterBounds)) {
                    hints += AttributeModifierIdMethodArgument(targetOwner, methodName, index)
                }
            }
        }
    }
    return hints
}

internal fun StructuralRefactorPass.collectGenericMethodReturnTypes(javaFiles: List<Path>): Map<String, String> {
    val id = """[A-Za-z_$][\w$]*"""
    val result = linkedMapOf<String, String>()
    fun simpleTypeName(type: String): String =
        type.trim()
            .replace(Regex("""<[^<>]*>"""), "")
            .substringAfterLast('.')

    for (javaFile in javaFiles) {
        val source = javaFile.readText()
        val owner = javaTopLevelTypeName(source) ?: continue
        Regex(
            """(?m)\b(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)*(?:$id\.)*$id\s*<\s*($id(?:\.$id)*)\s*>\s+($id)\s*\("""
        ).findAll(source).forEach { match ->
            result["$owner.${match.groupValues[2]}"] = simpleTypeName(match.groupValues[1])
        }
    }
    return result
}

internal fun StructuralRefactorPass.collectJavaMethodReturnTypes(javaFiles: List<Path>): Map<String, String> {
    val id = """[A-Za-z_$][\w$]*"""
    val result = linkedMapOf<String, String>()
    fun simpleTypeName(type: String): String =
        type.trim()
            .replace(Regex("""<[^<>]*>"""), "")
            .removeSuffix("[]")
            .substringAfterLast('.')
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|static|final|default|abstract|synchronized)\s+)+((?:$id\.)*$id(?:\s*<[^;=(){}]*>)?(?:\s*\[\])?)\s+($id)\s*\("""
    )
    for (javaFile in javaFiles) {
        val text = javaFile.readText()
        val source = maskJavaCommentsAndLiterals(text)
        val owner = javaTopLevelTypeName(source) ?: continue
        val typeBlocks = javaTypeBlocks(text, source)
        methodPattern.findAll(source).forEach { match ->
            val returnType = simpleTypeName(match.groupValues[1])
            if (returnType !in setOf("void", "boolean", "byte", "short", "int", "long", "float", "double", "char")) {
                result["$owner.${match.groupValues[2]}"] = returnType
                // and under the (nested) type that declares it
                typeBlocks.filter { match.range.first in it.bodyStart..it.end }.minByOrNull { it.end - it.bodyStart }
                    ?.takeIf { it.name != owner }
                    ?.let { result["${it.name}.${match.groupValues[2]}"] = returnType }
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.javaTopLevelTypeName(source: String): String? = classNameOfJavaSource(source)

internal fun StructuralRefactorPass.javaClassNames(source: String): List<String> {
    return Regex("""\b(?:class|record|enum)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(source)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
}

internal fun StructuralRefactorPass.isKnownRegistryBackedMethodCall(
    source: String,
    receiver: String,
    methodName: String,
    legacyRegistryBackedMethods: Set<LegacyRegistryBackedMethod>
): Boolean {
    val simpleReceiver = receiver.substringAfterLast('.')
    if (legacyRegistryBackedMethods.any { it.owner == simpleReceiver && it.name == methodName }) {
        return true
    }
    val currentOwner = javaTopLevelTypeName(source)
    if (currentOwner != null &&
        simpleReceiver in setOf("this", currentOwner) &&
        legacyRegistryBackedMethods.any { it.owner == currentOwner && it.name == methodName }) {
        return true
    }
    return currentOwner != null &&
        simpleReceiver in setOf("this", currentOwner) &&
        Regex("""\b${Regex.escape(methodName)}\s*\(\s*RegistryAccess\s+registryAccess\b""")
            .containsMatchIn(source)
}

internal fun StructuralRefactorPass.collectSpriteSourceProviderClasses(srcDir: Path): Set<String> {
    val classes = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("SpriteSourceProvider")) return@forEach
            Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?SpriteSourceProvider\b""")
                .findAll(source)
                .forEach { classes.add(it.groupValues[1]) }
        }
    return classes
}

internal data class LegacyRarityExtension(
    val sourceFile: Path,
    val packageName: String,
    val rarityName: String,
    val serializedName: String,
    val colorName: String,
    val enumName: String,
    val methodName: String
)

internal data class LegacyGrassColorModifierExtension(
    val sourceFile: Path,
    val packageName: String,
    val sourceClassName: String,
    val imports: List<String>,
    val serializedName: String,
    val enumName: String,
    val methodName: String,
    val delegateExpression: String
)

internal data class LegacyRecipeBookCategoryExtension(
    val sourceFile: Path,
    val packageName: String,
    val imports: List<String>,
    val enumName: String,
    val methodName: String,
    val iconExpressions: List<String>
)

internal data class LegacyRecipeBookTypeExtension(
    val enumName: String
)

internal data class CustomRecipeBookCategoryRecipe(
    val simpleName: String,
    val qualifiedName: String
)

internal data class CustomRecipeBookTypeField(
    val ownerQualifiedName: String,
    val fieldName: String,
    val recipeClassQualifiedName: String
) {
    val expression: String = "$ownerQualifiedName.$fieldName.get()"
}

internal data class CustomRecipeBookCategoryFinder(
    val recipeTypeExpression: String,
    val recipeClassQualifiedName: String
)

internal data class LegacyMobCategoryExtension(
    val sourceFile: Path,
    val packageName: String,
    val enumName: String,
    val methodName: String,
    val oldSerializedName: String,
    val serializedName: String,
    val maxInstances: String,
    val friendly: String,
    val persistent: String,
    val despawnDistance: String
)

internal data class LegacyPathTypeConstant(
    val sourceFile: Path,
    val packageName: String,
    val ownerClass: String,
    val fieldName: String,
    val malusExpression: String,
    val declarationText: String
) {
    val ownerFqn: String = if (packageName.isBlank()) ownerClass else "$packageName.$ownerClass"
}

internal data class LegacyRegistryBackedMethod(
    val owner: String,
    val name: String
)

internal fun StructuralRefactorPass.enumExtensionNamePart(value: String): String? =
    value.uppercase().replace(Regex("""[^A-Z0-9_]+"""), "_").trim('_').takeIf { it.isNotBlank() }

internal fun StructuralRefactorPass.prefixedEnumExtensionName(modId: String, legacyName: String): String? {
    val enumPrefix = enumExtensionNamePart(modId) ?: return null
    val rawEnumName = enumExtensionNamePart(legacyName) ?: return null
    return if (rawEnumName.startsWith("${enumPrefix}_")) rawEnumName else "${enumPrefix}_$rawEnumName"
}

internal fun StructuralRefactorPass.legacyRaritySerializedName(modId: String, legacyName: String): String? {
    val namespace = modId.lowercase().replace(Regex("""[^a-z0-9_.-]+"""), "_").trim('_', '.', '-')
        .takeIf { it.isNotBlank() }
        ?: return null
    val trimmed = legacyName.trim()
    if (Regex("""^[a-z0-9_.-]+:[a-z0-9_./-]+$""").matches(trimmed)) return trimmed
    val namespaceDotPrefix = "$namespace."
    if (trimmed.startsWith(namespaceDotPrefix)) {
        val path = trimmed.removePrefix(namespaceDotPrefix)
            .lowercase()
            .replace(Regex("""[^a-z0-9_./-]+"""), "_")
            .trim('_', '.', '/', '-')
        if (path.isNotBlank()) return "$namespace:$path"
    }
    val path = trimmed.lowercase()
        .replace(Regex("""[^a-z0-9_./-]+"""), "_")
        .trim('_', '.', '/', '-')
        .takeIf { it.isNotBlank() }
        ?: return null
    return "$namespace:$path"
}

internal fun StructuralRefactorPass.migrateLegacyRarityEnumExtensions(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val modId = detectModId(projectDir) ?: projectMetadataModId(projectDir) ?: return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val extensions = linkedMapOf<String, LegacyRarityExtension>()
    val changes = mutableListOf<Change>()

    javaFiles.forEach { file ->
        val original = file.readText()
        if (!original.contains("Rarity.create(")) return@forEach
        val packageName = packageNameOf(original)
        var migrated = original
        val replacements = mutableListOf<Pair<String, String>>()
        var cursor = 0
        while (true) {
            val match = Regex("""Rarity\.create\s*\(""").find(original, cursor) ?: break
            val openParen = original.indexOf('(', match.range.last - 1)
            val closeParen = if (openParen >= 0) findMatchingParen(original, openParen) else -1
            if (closeParen < 0) {
                cursor = match.range.last + 1
                continue
            }
            val args = splitTopLevelJavaArgs(original.substring(openParen + 1, closeParen))
            val rarityName = args.getOrNull(0)?.let { javaStringLiteralValue(it.trim()) }
            val colorName = args.getOrNull(1)
                ?.trim()
                ?.let { Regex("""(?:net\.minecraft\.)?ChatFormatting\.([A-Z0-9_]+)""").matchEntire(it)?.groupValues?.get(1) }
            if (args.size == 2 && rarityName != null && colorName != null) {
                val enumName = prefixedEnumExtensionName(modId, rarityName)
                val serializedName = legacyRaritySerializedName(modId, rarityName)
                if (enumName == null || serializedName == null) {
                    cursor = closeParen + 1
                    continue
                }
                val methodName = "Rarity_${enumName}".replace(Regex("""[^A-Za-z0-9_]+"""), "_")
                extensions[enumName] = LegacyRarityExtension(
                    sourceFile = file,
                    packageName = packageName,
                    rarityName = rarityName,
                    serializedName = serializedName,
                    colorName = colorName,
                    enumName = enumName,
                    methodName = methodName
                )
                replacements += original.substring(match.range.first, closeParen + 1) to """Rarity.valueOf("$enumName")"""
            }
            cursor = closeParen + 1
        }
        replacements.forEach { (before, after) ->
            migrated = migrated.replace(before, after)
        }
        if (!migrated.contains("ChatFormatting.")) {
            migrated = removeImport(migrated, "net.minecraft.ChatFormatting")
        }
        if (migrated != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Migrate legacy Rarity.create() to NeoForge enum extension value lookup",
                before = "Rarity.create(name, ChatFormatting.COLOR)",
                after = "Rarity.valueOf(generated enum extension name)",
                confidence = Confidence.HIGH,
                ruleId = "struct-rarity-enum-extension"
            ))
            if (!dryRun) file.writeText(migrated)
        }
    }

    if (extensions.isEmpty()) return changes
    val packageName = extensions.values.first().packageName
    val helperClass = "NeoForgeEnumExtensions"
    val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("$helperClass.java")
    val helperSource = rarityEnumExtensionHelperSource(packageName, helperClass, extensions.values.toList())
    changes.add(Change(
        file = helperFile,
        line = 0,
        description = "Generate NeoForge enum extension parameter helper for legacy custom rarities",
        before = "(missing)",
        after = "$helperClass.java",
        confidence = Confidence.HIGH,
        ruleId = "struct-rarity-enum-extension-helper"
    ))
    if (!dryRun) {
        helperFile.parent.createDirectories()
        val merged = if (helperFile.exists()) {
            mergeRarityEnumExtensionHelperMethods(helperFile.readText(), extensions.values.toList(), helperSource)
        } else {
            helperSource
        }
        helperFile.writeText(merged)
    }

    val resourcesDir = projectDir.resolve("src/main/resources")
    val metaInf = resourcesDir.resolve("META-INF")
    val enumExtensionFile = metaInf.resolve("enumextensions.json")
    val helperInternalName = if (packageName.isBlank()) helperClass else "${packageName.replace('.', '/')}/$helperClass"
    val enumEntries = extensions.values.map { rarityEnumExtensionJsonEntry(helperInternalName, it) }
    changes.add(Change(
        file = enumExtensionFile,
        line = 0,
        description = "Declare NeoForge enum extension metadata for legacy custom rarities",
        before = "(missing enum extension entries)",
        after = "META-INF/enumextensions.json",
        confidence = Confidence.HIGH,
        ruleId = "struct-rarity-enum-extension-json"
    ))
    if (!dryRun) {
        metaInf.createDirectories()
        mergeEnumExtensionEntries(enumExtensionFile, enumEntries)
        ensureEnumExtensionsTomlEntry(metaInf)
    }
    return changes
}

internal fun StructuralRefactorPass.rarityEnumExtensionHelperSource(
    packageName: String,
    helperClass: String,
    extensions: List<LegacyRarityExtension>
): String {
    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    val methods = extensions.joinToString("\n\n") { extension ->
        rarityEnumExtensionMethod(extension)
    }
    return """${packageLine}import net.minecraft.ChatFormatting;

@SuppressWarnings("unused")
public final class $helperClass {
	private $helperClass() {
	}

$methods
}
"""
}

internal fun StructuralRefactorPass.rarityEnumExtensionMethod(extension: LegacyRarityExtension): String =
    """
	public static Object ${extension.methodName}(int idx, Class<?> type) {
		return switch (idx) {
			case 0 -> -1;
			case 1 -> "${extension.serializedName}";
			case 2 -> ChatFormatting.${extension.colorName};
			default -> throw new IllegalArgumentException("Unexpected parameter index: " + idx);
		};
	}
        """.trimEnd()

internal fun StructuralRefactorPass.mergeRarityEnumExtensionHelperMethods(
    existing: String,
    extensions: List<LegacyRarityExtension>,
    freshSource: String
): String {
    var result = addImportIfMissing(existing, "net.minecraft.ChatFormatting")
    val missingMethods = extensions
        .filterNot { result.contains(" ${it.methodName}(int idx, Class<?> type)") }
        .joinToString("\n\n") { rarityEnumExtensionMethod(it) }
    if (missingMethods.isBlank()) return result
    val insertIndex = result.lastIndexOf('}')
    return if (insertIndex >= 0) {
        result.substring(0, insertIndex).trimEnd() + "\n\n" + missingMethods + "\n" + result.substring(insertIndex)
    } else {
        result + "\n\n" + freshSource.substringAfter("{").substringBeforeLast("}").trim()
    }
}

internal fun StructuralRefactorPass.rarityEnumExtensionJsonEntry(helperInternalName: String, extension: LegacyRarityExtension): String {
    return """    {
      "enum": "net/minecraft/world/item/Rarity",
      "name": "${extension.enumName}",
      "constructor": "(ILjava/lang/String;Lnet/minecraft/ChatFormatting;)V",
      "parameters": {
        "class": "$helperInternalName",
        "method": "${extension.methodName}"
      }
    }"""
}

internal fun StructuralRefactorPass.ensureEnumExtensionsTomlEntry(metaInf: Path) {
    listOf(metaInf.resolve("neoforge.mods.toml"), metaInf.resolve("mods.toml"))
        .firstOrNull { it.exists() }
        ?.let { toml ->
            val content = toml.readText()
            val insert = "enumExtensions=\"META-INF/enumextensions.json\""
            val enumEntryPattern = Regex("""(?m)^\s*enumExtensions\s*=\s*"[^"]+"\s*\r?\n?""")
            val existingEntry = enumEntryPattern.find(content)
            if (existingEntry != null && tomlKeyIsInFirstModsTable(content, existingEntry.range.first)) return

            val withoutMisplacedEntry = enumEntryPattern.replace(content, "")
            val modsTable = Regex("""(?m)^\s*\[\[mods]]\s*$""").find(withoutMisplacedEntry)
            val migrated = if (modsTable != null) {
                withoutMisplacedEntry.substring(0, modsTable.range.last + 1) +
                    "\n$insert" +
                    withoutMisplacedEntry.substring(modsTable.range.last + 1)
            } else if (withoutMisplacedEntry.contains("modLoader")) {
                val match = Regex("""(?m)^modLoader\s*=\s*"[^"]*"\s*$""").find(withoutMisplacedEntry)
                if (match != null) {
                    withoutMisplacedEntry.substring(0, match.range.last + 1) +
                        "\n$insert" +
                        withoutMisplacedEntry.substring(match.range.last + 1)
                } else {
                    insert + "\n" + withoutMisplacedEntry
                }
            } else {
                insert + "\n" + withoutMisplacedEntry
            }
            toml.writeText(migrated)
        }
}

internal fun StructuralRefactorPass.tomlKeyIsInFirstModsTable(content: String, offset: Int): Boolean {
    val priorHeader = Regex("""(?m)^\s*(\[\[[^\]\r\n]+]]|\[[^\]\r\n]+])""")
        .findAll(content.substring(0, offset))
        .lastOrNull()
        ?.value
        ?.trim()
    return priorHeader == "[[mods]]"
}

internal fun StructuralRefactorPass.migrateLegacyRecipeBookCategoryEnumExtensions(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val modId = detectModId(projectDir) ?: projectMetadataModId(projectDir) ?: return changes
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val extensions = mutableListOf<LegacyRecipeBookCategoryExtension>()

    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        if (!original.contains("RecipeBookCategories.create(")) return@forEach
        val packageName = packageNameOf(original)
        var migrated = original
        var cursor = 0
        var changed = false
        while (true) {
            val match = Regex("""RecipeBookCategories\.create\s*\(""").find(original, cursor) ?: break
            val openParen = original.indexOf('(', match.range.last - 1)
            val closeParen = if (openParen >= 0) findMatchingParen(original, openParen) else -1
            if (closeParen < 0) {
                cursor = match.range.last + 1
                continue
            }
            val args = splitTopLevelJavaArgs(original.substring(openParen + 1, closeParen))
            if (args.size < 2) {
                cursor = closeParen + 1
                continue
            }
            val categoryName = Regex(""""((?:\\.|[^"\\])*)"""")
                .matchEntire(args[0].trim())
                ?.groupValues
                ?.get(1)
            if (categoryName == null) {
                cursor = closeParen + 1
                continue
            }
            val enumName = prefixedEnumExtensionName(modId, categoryName)
            if (enumName == null) {
                cursor = closeParen + 1
                continue
            }
            val methodName = "RecipeBookCategory_${enumName}".replace(Regex("""[^A-Za-z0-9_]+"""), "_")
            val iconExpressions = args.drop(1).map { it.trim() }.filter { it.isNotBlank() }
            if (iconExpressions.isEmpty()) {
                cursor = closeParen + 1
                continue
            }
            val callText = original.substring(match.range.first, closeParen + 1)
            migrated = migrated.replace(callText, """RecipeBookCategories.valueOf("$enumName")""")
            extensions += LegacyRecipeBookCategoryExtension(
                sourceFile = javaFile,
                packageName = packageName,
                imports = importsUsedByExpressions(original, iconExpressions),
                enumName = enumName,
                methodName = methodName,
                iconExpressions = iconExpressions
            )
            changed = true
            cursor = closeParen + 1
        }
        if (changed && migrated != original) {
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Migrate legacy RecipeBookCategories.create() to NeoForge enum extension lookup",
                before = "RecipeBookCategories.create(name, icons...)",
                after = "RecipeBookCategories.valueOf(generated enum extension name)",
                confidence = Confidence.HIGH,
                ruleId = "struct-recipebook-category-enum-extension"
            ))
            if (!dryRun) javaFile.writeText(migrated)
        }
    }

    if (extensions.isEmpty()) return changes
    extensions.groupBy { it.packageName }.forEach { (packageName, packageExtensions) ->
        val helperClass = "NeoForgeEnumExtensions"
        val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("$helperClass.java")
        val helperSource = recipeBookEnumExtensionHelperSource(packageName, helperClass, packageExtensions)
        changes.add(Change(
            file = helperFile,
            line = 0,
            description = "Generate NeoForge enum extension parameter helper for legacy recipe book categories",
            before = "(missing recipe book enum extension helper)",
            after = "$helperClass.java",
            confidence = Confidence.HIGH,
            ruleId = "struct-recipebook-category-enum-extension-helper"
        ))
        if (!dryRun) {
            helperFile.parent.createDirectories()
            val merged = if (helperFile.exists()) {
                mergeRecipeBookEnumExtensionHelperMethods(helperFile.readText(), packageExtensions, helperSource)
            } else {
                helperSource
            }
            helperFile.writeText(merged)
        }
    }

    val resourcesDir = projectDir.resolve("src/main/resources")
    val metaInf = resourcesDir.resolve("META-INF")
    val enumExtensionFile = metaInf.resolve("enumextensions.json")
    val entries = extensions.map { recipeBookEnumExtensionJsonEntry(it) }
    changes.add(Change(
        file = enumExtensionFile,
        line = 0,
        description = "Declare NeoForge enum extension metadata for legacy recipe book categories",
        before = "(missing recipe book enum extension entries)",
        after = "META-INF/enumextensions.json",
        confidence = Confidence.HIGH,
        ruleId = "struct-recipebook-category-enum-extension-json"
    ))
    if (!dryRun) {
        metaInf.createDirectories()
        mergeEnumExtensionEntries(enumExtensionFile, entries)
        ensureEnumExtensionsTomlEntry(metaInf)
    }
    return changes
}

internal fun StructuralRefactorPass.migrateLegacyRecipeBookTypeEnumExtensions(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val modId = detectModId(projectDir) ?: projectMetadataModId(projectDir) ?: return changes
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val extensions = linkedMapOf<String, LegacyRecipeBookTypeExtension>()

    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        if (!original.contains("RecipeBookType.create(")) return@forEach
        var migrated = original
        val replacements = mutableListOf<Pair<String, String>>()
        var cursor = 0
        while (true) {
            val match = Regex("""RecipeBookType\.create\s*\(""").find(original, cursor) ?: break
            val openParen = original.indexOf('(', match.range.last - 1)
            val closeParen = if (openParen >= 0) findMatchingParen(original, openParen) else -1
            if (closeParen < 0) {
                cursor = match.range.last + 1
                continue
            }
            val args = splitTopLevelJavaArgs(original.substring(openParen + 1, closeParen))
            val typeName = if (args.size == 1) javaStringLiteralValue(args[0].trim()) else null
            if (typeName != null) {
                val enumName = prefixedEnumExtensionName(modId, typeName)
                if (enumName == null) {
                    cursor = closeParen + 1
                    continue
                }
                extensions.putIfAbsent(enumName, LegacyRecipeBookTypeExtension(enumName))
                replacements += original.substring(match.range.first, closeParen + 1) to """RecipeBookType.valueOf("$enumName")"""
            }
            cursor = closeParen + 1
        }
        if (replacements.isNotEmpty()) {
            replacements.forEach { (before, after) -> migrated = migrated.replace(before, after) }
            if (migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy RecipeBookType.create() to NeoForge enum extension lookup",
                    before = "RecipeBookType.create(name)",
                    after = "RecipeBookType.valueOf(generated enum extension name)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-recipebook-type-enum-extension"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }
    }

    if (extensions.isEmpty()) return changes
    val resourcesDir = projectDir.resolve("src/main/resources")
    val metaInf = resourcesDir.resolve("META-INF")
    val enumExtensionFile = metaInf.resolve("enumextensions.json")
    val entries = extensions.values.map { recipeBookTypeEnumExtensionJsonEntry(it) }
    changes.add(Change(
        file = enumExtensionFile,
        line = 0,
        description = "Declare NeoForge enum extension metadata for legacy recipe book types",
        before = "(missing recipe book type enum extension entries)",
        after = "META-INF/enumextensions.json",
        confidence = Confidence.HIGH,
        ruleId = "struct-recipebook-type-enum-extension-json"
    ))
    if (!dryRun) {
        metaInf.createDirectories()
        mergeEnumExtensionEntries(enumExtensionFile, entries)
        ensureEnumExtensionsTomlEntry(metaInf)
    }
    return changes
}

internal fun StructuralRefactorPass.migrateCustomRecipeBookCategoryFinders(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    if (javaFiles.isEmpty()) return emptyList()

    val recipeClasses = collectCustomCraftingBookCategoryRecipes(javaFiles)
    if (recipeClasses.isEmpty()) return emptyList()
    val recipeClassNames = recipeClasses.associateBy { it.qualifiedName }
    val existingSources = javaFiles.associateWith { it.readText() }
    val existingExecutable = existingSources.values.joinToString("\n") { maskJavaCommentsAndLiterals(it) }
    val finders = collectCustomRecipeTypeFields(javaFiles)
        .filter { it.recipeClassQualifiedName in recipeClassNames }
        .filterNot { field -> customRecipeBookFinderAlreadyRegistered(existingExecutable, field) }
        .map { field ->
            CustomRecipeBookCategoryFinder(
                recipeTypeExpression = field.expression,
                recipeClassQualifiedName = field.recipeClassQualifiedName
            )
        }
        .distinctBy { it.recipeTypeExpression }
        .sortedBy { it.recipeTypeExpression }

    if (finders.isEmpty()) return emptyList()

    val mainFile = detectModMainClass(projectDir)
        ?: error("Cannot register custom recipe book category finders: expected exactly one @Mod main class")
    val mainOriginal = mainFile.readText()
    val modBusVar = detectModBusVariable(mainOriginal)
        ?: error("Cannot derive mod event bus variable for custom recipe book category finders")
    val compatPackage = detectRequiredGeneratedCompatPackage(projectDir, "custom recipe book category finders")
    val className = "ModRecipeBookCategories"
    val compatFile = srcDir.resolve(compatPackage.replace('.', '/')).resolve("$className.java")
    val generatedSource = customRecipeBookCategoryFinderSource(compatPackage, className, finders)
    val changes = mutableListOf<Change>()
    if (!compatFile.exists() || compatFile.readText() != generatedSource) {
        changes.add(Change(
            file = compatFile,
            line = 0,
            description = "Generate NeoForge recipe book category finders for custom CraftingBookCategory recipes",
            before = "(missing RegisterRecipeBookCategoriesEvent finder)",
            after = "$className.register(IEventBus)",
            confidence = Confidence.HIGH,
            ruleId = "struct-custom-recipebook-category-finder"
        ))
        if (!dryRun) {
            compatFile.parent.createDirectories()
            compatFile.writeText(generatedSource)
        }
    }

    val registrationCall = "$compatPackage.$className.register($modBusVar);"
    if (!mainOriginal.contains(registrationCall)) {
        val indent = mainConstructorIndent(mainOriginal, modBusVar)
        val registration = "${indent}if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {\n" +
            "$indent    $registrationCall\n" +
            "$indent}"
        val mainMigrated = insertModBusListener(mainOriginal, modBusVar, registration, className)
        if (mainMigrated != mainOriginal) {
            changes.add(Change(
                file = mainFile,
                line = 0,
                description = "Register generated custom recipe book category finder on the client mod bus",
                before = "(missing client RegisterRecipeBookCategoriesEvent listener)",
                after = registrationCall,
                confidence = Confidence.HIGH,
                ruleId = "struct-custom-recipebook-category-finder-registration"
            ))
            if (!dryRun) mainFile.writeText(mainMigrated)
        }
    }

    return changes
}

internal fun StructuralRefactorPass.collectCustomCraftingBookCategoryRecipes(javaFiles: List<Path>): List<CustomRecipeBookCategoryRecipe> =
    javaFiles.mapNotNull { javaFile ->
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("CraftingBookCategory") || !executableCode.contains("Recipe<")) return@mapNotNull null
        val className = javaTopLevelTypeName(source) ?: return@mapNotNull null
        val typeStart = findTypeDeclarationStart(executableCode, className) ?: return@mapNotNull null
        val typeOpen = executableCode.indexOf('{', typeStart)
        if (typeOpen < 0) return@mapNotNull null
        val header = executableCode.substring(typeStart, typeOpen)
        if (!Regex("""\bimplements\b[^{;]*\bRecipe\s*<""").containsMatchIn(header)) return@mapNotNull null
        if (Regex("""\bimplements\b[^{;]*\bCraftingRecipe\b""").containsMatchIn(header)) return@mapNotNull null
        val categoryMethod = Regex(
            """(?m)\bpublic\s+(?:net\.minecraft\.world\.item\.crafting\.)?CraftingBookCategory\s+category\s*\(\s*\)"""
        ).containsMatchIn(executableCode)
        if (!categoryMethod) return@mapNotNull null
        val packageName = packageNameOf(source)
        val qualifiedName = if (packageName.isBlank()) className else "$packageName.$className"
        CustomRecipeBookCategoryRecipe(className, qualifiedName)
    }

internal fun StructuralRefactorPass.collectCustomRecipeTypeFields(javaFiles: List<Path>): List<CustomRecipeBookTypeField> =
    javaFiles.flatMap { javaFile ->
        val source = javaFile.readText()
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!executableCode.contains("RecipeType<")) return@flatMap emptyList()
        val packageName = packageNameOf(source)
        val imports = javaNonStaticImports(source)
        val owner = javaTopLevelTypeName(source) ?: return@flatMap emptyList()
        val ownerQualifiedName = if (packageName.isBlank()) owner else "$packageName.$owner"
        val fields = mutableListOf<CustomRecipeBookTypeField>()
        Regex(
            """\bDeferredHolder\s*<\s*RecipeType\s*<\s*\?\s*>\s*,\s*RecipeType\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
        ).findAll(executableCode).forEach { match ->
            fields += CustomRecipeBookTypeField(
                ownerQualifiedName = ownerQualifiedName,
                fieldName = match.groupValues[2],
                recipeClassQualifiedName = resolveJavaTypeName(match.groupValues[1], packageName, imports)
            )
        }
        Regex(
            """\b(?:RegistryObject|Supplier)\s*<\s*RecipeType\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
        ).findAll(executableCode).forEach { match ->
            fields += CustomRecipeBookTypeField(
                ownerQualifiedName = ownerQualifiedName,
                fieldName = match.groupValues[2],
                recipeClassQualifiedName = resolveJavaTypeName(match.groupValues[1], packageName, imports)
            )
        }
        fields
    }

internal fun StructuralRefactorPass.customRecipeBookFinderAlreadyRegistered(
    executableCode: String,
    field: CustomRecipeBookTypeField
): Boolean {
    if (!executableCode.contains("registerRecipeCategoryFinder(")) return false
    val normalized = normalizeRecipeTypeExpression(executableCode)
    val ownerSimpleName = field.ownerQualifiedName.substringAfterLast('.')
    val candidates = listOf(
        field.expression,
        "$ownerSimpleName.${field.fieldName}.get()",
        "${field.fieldName}.get()"
    ).map(::normalizeRecipeTypeExpression)
    return candidates.any { normalized.contains("registerRecipeCategoryFinder($it,") }
}

internal fun StructuralRefactorPass.customRecipeBookCategoryFinderSource(
    packageName: String,
    className: String,
    finders: List<CustomRecipeBookCategoryFinder>
): String {
    val registrations = finders.joinToString("\n") { finder ->
        """
        event.registerRecipeCategoryFinder(${finder.recipeTypeExpression}, recipe -> {
            if (recipe.value() instanceof ${finder.recipeClassQualifiedName} value) {
                return fromCraftingCategory(value.category());
            }
            throw new IllegalStateException("Recipe type ${finder.recipeTypeExpression} produced " + recipe.value().getClass().getName() + " instead of ${finder.recipeClassQualifiedName}");
        });
            """.trimEnd()
    }
    return """package $packageName;

import net.minecraft.client.RecipeBookCategories;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent;

public final class $className {
    private $className() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener($className::registerRecipeCategories);
    }

    public static void registerRecipeCategories(RegisterRecipeBookCategoriesEvent event) {
$registrations
    }

    private static RecipeBookCategories fromCraftingCategory(CraftingBookCategory category) {
        return switch (category) {
            case BUILDING -> RecipeBookCategories.CRAFTING_BUILDING_BLOCKS;
            case EQUIPMENT -> RecipeBookCategories.CRAFTING_EQUIPMENT;
            case REDSTONE -> RecipeBookCategories.CRAFTING_REDSTONE;
            case MISC -> RecipeBookCategories.CRAFTING_MISC;
        };
    }
}
"""
}

internal fun StructuralRefactorPass.recipeBookTypeEnumExtensionJsonEntry(extension: LegacyRecipeBookTypeExtension): String {
    return """    {
      "enum": "net/minecraft/world/inventory/RecipeBookType",
      "name": "${extension.enumName}",
      "constructor": "()V",
      "parameters": [ ]
    }"""
}

internal fun StructuralRefactorPass.importsUsedByExpressions(source: String, expressions: List<String>): List<String> {
    val joined = expressions.joinToString("\n")
    val imports = Regex("""(?m)^\s*import\s+([^;]+);\s*$""")
        .findAll(source)
        .map { it.groupValues[1].trim() }
        .filter { importName ->
            val simpleName = importName.substringAfterLast('.').substringBefore('<')
            simpleName == "*" || Regex("""(?<![\w$])${Regex.escape(simpleName)}(?![\w$])""").containsMatchIn(joined)
        }
        .filterNot { it == "net.minecraft.client.RecipeBookCategories" }
        .distinct()
        .toMutableList()
    listOf(
        "java.util.List",
        "java.util.function.Supplier",
        "net.minecraft.world.item.ItemStack"
    ).forEach { required ->
        if (imports.none { it == required }) imports += required
    }
    return imports
}

internal fun StructuralRefactorPass.recipeBookEnumExtensionHelperSource(
    packageName: String,
    helperClass: String,
    extensions: List<LegacyRecipeBookCategoryExtension>
): String {
    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    val imports = extensions.flatMap { it.imports }
        .distinct()
        .sorted()
        .joinToString("\n") { "import $it;" }
    val importBlock = if (imports.isBlank()) "" else "$imports\n\n"
    val methods = extensions.joinToString("\n\n") { recipeBookEnumExtensionMethod(it) }
    return """${packageLine}${importBlock}@SuppressWarnings("unused")
public final class $helperClass {
	private $helperClass() {
	}

$methods
}
"""
}

internal fun StructuralRefactorPass.recipeBookEnumExtensionMethod(extension: LegacyRecipeBookCategoryExtension): String {
    val icons = extension.iconExpressions.joinToString(", ")
    return """
	public static Object ${extension.methodName}(int idx, Class<?> type) {
		return switch (idx) {
			case 0 -> (Supplier<List<ItemStack>>) () -> List.of($icons);
			default -> throw new IllegalArgumentException("Unexpected parameter index: " + idx);
		};
	}
        """.trimEnd()
}

internal fun StructuralRefactorPass.mergeRecipeBookEnumExtensionHelperMethods(
    existing: String,
    extensions: List<LegacyRecipeBookCategoryExtension>,
    freshSource: String
): String {
    var result = existing
    Regex("""(?m)^\s*import\s+[^;]+;\s*$""")
        .findAll(freshSource)
        .map { it.value.trim().removePrefix("import ").removeSuffix(";") }
        .forEach { importName ->
            result = addImportIfMissing(result, importName)
        }
    val missingMethods = extensions
        .filterNot { result.contains(" ${it.methodName}(int idx, Class<?> type)") }
        .joinToString("\n\n") { recipeBookEnumExtensionMethod(it) }
    if (missingMethods.isBlank()) return result
    val insertIndex = result.lastIndexOf('}')
    return if (insertIndex >= 0) {
        result.substring(0, insertIndex).trimEnd() + "\n\n" + missingMethods + "\n" + result.substring(insertIndex)
    } else {
        result + "\n\n" + missingMethods
    }
}

internal fun StructuralRefactorPass.recipeBookEnumExtensionJsonEntry(extension: LegacyRecipeBookCategoryExtension): String {
    val helperInternalName = if (extension.packageName.isBlank()) {
        "NeoForgeEnumExtensions"
    } else {
        "${extension.packageName.replace('.', '/')}/NeoForgeEnumExtensions"
    }
    return """    {
      "enum": "net/minecraft/client/RecipeBookCategories",
      "name": "${extension.enumName}",
      "constructor": "(Ljava/util/function/Supplier;)V",
      "parameters": {
        "class": "$helperInternalName",
        "method": "${extension.methodName}"
      }
    }"""
}

internal fun StructuralRefactorPass.migrateLegacyMobCategoryEnumExtensions(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val modId = detectModId(projectDir) ?: projectMetadataModId(projectDir) ?: return changes
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val extensions = linkedMapOf<String, LegacyMobCategoryExtension>()

    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        if (!original.contains("MobCategory.create(")) return@forEach
        val packageName = packageNameOf(original)
        var migrated = original
        val replacements = mutableListOf<Pair<String, String>>()
        var cursor = 0
        while (true) {
            val match = Regex("""MobCategory\.create\s*\(""").find(original, cursor) ?: break
            val openParen = original.indexOf('(', match.range.last - 1)
            val closeParen = if (openParen >= 0) findMatchingParen(original, openParen) else -1
            if (closeParen < 0) {
                cursor = match.range.last + 1
                continue
            }
            val args = splitTopLevelJavaArgs(original.substring(openParen + 1, closeParen))
            val extension = legacyMobCategoryExtension(args, modId, packageName, javaFile)
            if (extension != null) {
                extensions.putIfAbsent(extension.enumName, extension)
                val callText = original.substring(match.range.first, closeParen + 1)
                replacements += callText to """MobCategory.valueOf("${extension.enumName}")"""
            }
            cursor = closeParen + 1
        }
        if (replacements.isNotEmpty()) {
            replacements.forEach { (before, after) -> migrated = migrated.replace(before, after) }
            if (migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy MobCategory.create() to NeoForge enum extension lookup",
                    before = "MobCategory.create(name, serializedName, max, friendly, persistent, despawnDistance)",
                    after = "MobCategory.valueOf(generated enum extension name)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-mobcategory-enum-extension"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }
    }

    if (extensions.isEmpty()) return changes
    extensions.values.groupBy { it.packageName }.forEach { (packageName, packageExtensions) ->
        val helperClass = "NeoForgeEnumExtensions"
        val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("$helperClass.java")
        val helperSource = mobCategoryEnumExtensionHelperSource(packageName, helperClass, packageExtensions)
        changes.add(Change(
            file = helperFile,
            line = 0,
            description = "Generate NeoForge enum extension parameter helper for legacy mob categories",
            before = "(missing mob category enum extension helper)",
            after = "$helperClass.java",
            confidence = Confidence.HIGH,
            ruleId = "struct-mobcategory-enum-extension-helper"
        ))
        if (!dryRun) {
            helperFile.parent.createDirectories()
            val merged = if (helperFile.exists()) {
                mergeMobCategoryEnumExtensionHelperMethods(helperFile.readText(), packageExtensions)
            } else {
                helperSource
            }
            helperFile.writeText(merged)
        }
    }

    val resourcesDir = projectDir.resolve("src/main/resources")
    val metaInf = resourcesDir.resolve("META-INF")
    val enumExtensionFile = metaInf.resolve("enumextensions.json")
    val entries = extensions.values.map { mobCategoryEnumExtensionJsonEntry(it) }
    changes.add(Change(
        file = enumExtensionFile,
        line = 0,
        description = "Declare NeoForge enum extension metadata for legacy mob categories",
        before = "(missing mob category enum extension entries)",
        after = "META-INF/enumextensions.json",
        confidence = Confidence.HIGH,
        ruleId = "struct-mobcategory-enum-extension-json"
    ))
    if (!dryRun) {
        metaInf.createDirectories()
        mergeEnumExtensionEntries(enumExtensionFile, entries)
        ensureEnumExtensionsTomlEntry(metaInf)
    }

    changes.addAll(migrateMobCategoryDataResourceKeys(projectDir, extensions.values, dryRun))
    return changes
}

internal fun StructuralRefactorPass.legacyMobCategoryExtension(
    args: List<String>,
    modId: String,
    packageName: String,
    sourceFile: Path
): LegacyMobCategoryExtension? {
    if (args.size != 6) return null
    val enumNameArgument = javaStringLiteralValue(args[0].trim()) ?: return null
    val serializedName = javaStringLiteralValue(args[1].trim()) ?: return null
    val maxInstances = args[2].trim()
    val friendly = args[3].trim()
    val persistent = args[4].trim()
    val despawnDistance = args[5].trim()
    if (!isJavaIntegerLiteral(maxInstances) ||
        friendly !in setOf("true", "false") ||
        persistent !in setOf("true", "false") ||
        !isJavaIntegerLiteral(despawnDistance)) {
        return null
    }
    val enumName = prefixedEnumExtensionName(modId, enumNameArgument) ?: return null
    val namespacedSerializedName = enumExtensionNameParameter(modId, serializedName) ?: return null
    val methodName = "MobCategory_${enumName}".replace(Regex("""[^A-Za-z0-9_]+"""), "_")
    return LegacyMobCategoryExtension(
        sourceFile = sourceFile,
        packageName = packageName,
        enumName = enumName,
        methodName = methodName,
        oldSerializedName = serializedName,
        serializedName = namespacedSerializedName,
        maxInstances = maxInstances,
        friendly = friendly,
        persistent = persistent,
        despawnDistance = despawnDistance
    )
}

internal fun StructuralRefactorPass.enumExtensionNameParameter(modId: String, legacyName: String): String? {
    val namespace = modId.lowercase().replace(Regex("""[^a-z0-9_.-]+"""), "_").trim('_', '.', '-')
        .takeIf { it.isNotBlank() }
        ?: return null
    val rawPath = legacyName.substringAfter(':')
    val normalizedPath = rawPath
        .lowercase()
        .replace(Regex("""[^a-z0-9_./-]+"""), "_")
        .trim('_', '.', '/', '-')
        .removePrefix("${namespace}_")
        .removePrefix("${namespace}.")
        .removePrefix("${namespace}/")
        .trim('_', '.', '/', '-')
        .takeIf { it.isNotBlank() }
        ?: return null
    return "$namespace:$normalizedPath"
}

internal fun StructuralRefactorPass.javaStringLiteralValue(expression: String): String? {
    val match = Regex("""^"((?:\\.|[^"\\])*)"$""").matchEntire(expression.trim()) ?: return null
    return match.groupValues[1]
}

internal fun StructuralRefactorPass.isJavaIntegerLiteral(expression: String): Boolean =
    Regex("""^[+-]?(?:0|[1-9][0-9]*)(?:[lL])?$""").matches(expression.trim())

internal fun StructuralRefactorPass.mobCategoryEnumExtensionHelperSource(
    packageName: String,
    helperClass: String,
    extensions: List<LegacyMobCategoryExtension>
): String {
    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    val methods = extensions.joinToString("\n\n") { mobCategoryEnumExtensionMethod(it) }
    return """${packageLine}@SuppressWarnings("unused")
public final class $helperClass {
	private $helperClass() {
	}

$methods
}
"""
}

internal fun StructuralRefactorPass.mobCategoryEnumExtensionMethod(extension: LegacyMobCategoryExtension): String {
    return """
	public static Object ${extension.methodName}(int idx, Class<?> type) {
		return switch (idx) {
			case 0 -> "${extension.serializedName}";
			case 1 -> ${extension.maxInstances};
			case 2 -> ${extension.friendly};
			case 3 -> ${extension.persistent};
			case 4 -> ${extension.despawnDistance};
			default -> throw new IllegalArgumentException("Unexpected parameter index: " + idx);
		};
	}
        """.trimEnd()
}

internal fun StructuralRefactorPass.mergeMobCategoryEnumExtensionHelperMethods(
    existing: String,
    extensions: List<LegacyMobCategoryExtension>
): String {
    val missingMethods = extensions
        .filterNot { existing.contains(" ${it.methodName}(int idx, Class<?> type)") }
        .joinToString("\n\n") { mobCategoryEnumExtensionMethod(it) }
    if (missingMethods.isBlank()) return existing
    val insertIndex = existing.lastIndexOf('}')
    return if (insertIndex >= 0) {
        existing.substring(0, insertIndex).trimEnd() + "\n\n" + missingMethods + "\n" + existing.substring(insertIndex)
    } else {
        existing + "\n\n" + missingMethods
    }
}

internal fun StructuralRefactorPass.mobCategoryEnumExtensionJsonEntry(extension: LegacyMobCategoryExtension): String {
    val helperInternalName = if (extension.packageName.isBlank()) {
        "NeoForgeEnumExtensions"
    } else {
        "${extension.packageName.replace('.', '/')}/NeoForgeEnumExtensions"
    }
    return """    {
      "enum": "net/minecraft/world/entity/MobCategory",
      "name": "${extension.enumName}",
      "constructor": "(Ljava/lang/String;IZZI)V",
      "parameters": {
        "class": "$helperInternalName",
        "method": "${extension.methodName}"
      }
    }"""
}

internal fun StructuralRefactorPass.migrateMobCategoryDataResourceKeys(
    projectDir: Path,
    extensions: Collection<LegacyMobCategoryExtension>,
    dryRun: Boolean
): List<Change> {
    val renames = linkedMapOf<String, String>()
    extensions.forEach { extension ->
        if (extension.oldSerializedName != extension.serializedName) {
            renames.putIfAbsent(extension.oldSerializedName, extension.serializedName)
        }
    }
    if (renames.isEmpty()) return emptyList()

    val resourceDirs = listOf(
        projectDir.resolve("src/main/resources"),
        projectDir.resolve("src/generated/resources")
    ).filter { it.exists() }
    if (resourceDirs.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    resourceDirs.forEach { resourceDir ->
        val jsonFiles = Files.walk(resourceDir).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .filter {
                    val filePath = it.toString().replace('\\', '/')
                    filePath.contains("/data/") && filePath.contains("/worldgen/")
                }
                .toList()
        }
        for (jsonFile in jsonFiles) {
            val original = jsonFile.readText()
            if (renames.keys.none { key -> original.contains("\"$key\"") }) continue
            val root = parseStructuralResourceJson(original) ?: continue
            val migrated = migrateMobCategoryDataKeys(root, renames, currentKey = null)
            if (migrated.conflicts.isNotEmpty()) {
                throw IllegalStateException(
                    "Ambiguous MobCategory data key migration in $jsonFile: " +
                        migrated.conflicts.joinToString("; ")
                )
            }
            if (!migrated.changed) continue

            changes.add(Change(
                file = jsonFile,
                line = 0,
                description = "Rewrite MobCategory enum extension data keys to namespaced serialized names",
                before = renames.keys.joinToString(limit = 4),
                after = renames.values.joinToString(limit = 4),
                confidence = Confidence.HIGH,
                ruleId = "struct-mobcategory-data-keys"
            ))
            if (!dryRun) {
                jsonFile.writeText(
                    STRUCTURAL_RESOURCE_JSON.encodeToString(JsonElement.serializer(), migrated.element) + "\n"
                )
            }
        }
    }
    return changes
}

internal fun StructuralRefactorPass.parseStructuralResourceJson(content: String): JsonElement? =
    try {
        STRUCTURAL_RESOURCE_JSON.parseToJsonElement(content)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun StructuralRefactorPass.migrateMobCategoryDataKeys(
    element: JsonElement,
    renames: Map<String, String>,
    currentKey: String?
): MobCategoryDataKeyMigration {
    return when (element) {
        is JsonArray -> {
            var changed = false
            val conflicts = mutableListOf<String>()
            val values = element.map { child ->
                val migrated = migrateMobCategoryDataKeys(child, renames, currentKey = null)
                changed = changed || migrated.changed
                conflicts.addAll(migrated.conflicts)
                migrated.element
            }
            MobCategoryDataKeyMigration(JsonArray(values), changed, conflicts)
        }
        is JsonObject -> {
            if (currentKey == "spawners" || currentKey == "spawn_overrides") {
                return migrateMobCategoryCategoryKeyObject(element, renames)
            }

            var changed = false
            val conflicts = mutableListOf<String>()
            val entries = linkedMapOf<String, JsonElement>()
            for ((key, value) in element) {
                val migrated = migrateMobCategoryDataKeys(value, renames, currentKey = key)
                entries[key] = migrated.element
                changed = changed || migrated.changed
                conflicts.addAll(migrated.conflicts)
            }
            MobCategoryDataKeyMigration(JsonObject(entries), changed, conflicts)
        }
        else -> MobCategoryDataKeyMigration(element, changed = false)
    }
}

internal fun StructuralRefactorPass.migrateMobCategoryCategoryKeyObject(
    element: JsonObject,
    renames: Map<String, String>
): MobCategoryDataKeyMigration {
    var changed = false
    val conflicts = mutableListOf<String>()
    val entries = linkedMapOf<String, JsonElement>()
    for ((key, value) in element) {
        val migrated = migrateMobCategoryDataKeys(value, renames, currentKey = null)
        val targetKey = renames[key] ?: key
        if (targetKey != key) {
            changed = true
            if (entries.containsKey(targetKey)) {
                conflicts += "'$key' maps to existing '$targetKey'"
                entries[key] = migrated.element
                continue
            }
        }
        entries[targetKey] = migrated.element
        changed = changed || migrated.changed
        conflicts.addAll(migrated.conflicts)
    }
    return MobCategoryDataKeyMigration(JsonObject(entries), changed, conflicts)
}

internal data class MobCategoryDataKeyMigration(
    val element: JsonElement,
    val changed: Boolean,
    val conflicts: List<String> = emptyList()
)

internal fun StructuralRefactorPass.migrateLegacyPathTypeCreateConstants(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val sources = javaFiles.associateWith { it.readText() }
    val constants = javaFiles.flatMap { file -> collectLegacyPathTypeConstants(file, sources.getValue(file)) }
    if (constants.isEmpty()) return emptyList()

    val migratable = constants
        .filter { isNegativeOneFloatLiteral(it.malusExpression) }
        .filter { allPathTypeConstantReferencesAreProvenBlockedConsumers(sources, it) }
    if (migratable.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    val migratedSources = sources.toMutableMap()
    migratable.forEach { constant ->
        val referencePattern = legacyPathTypeConstantReferencePattern(constant)
        migratedSources.keys.forEach { javaFile ->
            val original = migratedSources.getValue(javaFile)
            if (!referencePattern.containsMatchIn(original)) return@forEach
            var migrated = referencePattern.replace(original, "PathType.BLOCKED")
            val withoutOwnerImport = removeImport(migrated, constant.ownerFqn)
            if (!hasSimpleTypeReference(withoutOwnerImport, constant.ownerClass)) {
                migrated = withoutOwnerImport
            }
            if (Regex("""\bPathType\b""").containsMatchIn(migrated)) {
                migrated = addImportIfMissing(migrated, "net.minecraft.world.level.pathfinder.PathType")
            }
            if (migrated != original) {
                migratedSources[javaFile] = migrated
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Inline removed legacy PathType.create constant into proven blocked path type consumers",
                    before = "${constant.ownerClass}.${constant.fieldName}",
                    after = "PathType.BLOCKED",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-path-type-create-blocked-consumer"
                ))
            }
        }
    }

    migratable.groupBy { it.sourceFile }.forEach { (sourceFile, constantsForFile) ->
        var migrated = migratedSources.getValue(sourceFile)
        val original = migrated
        constantsForFile.forEach { constant ->
            migrated = migrated.replace(constant.declarationText, "")
        }
        if (!migrated.contains("PathType.create(")) {
            val withoutPathTypeImport = removeImport(migrated, "net.minecraft.world.level.pathfinder.PathType")
            if (!hasSimpleTypeReference(withoutPathTypeImport, "PathType")) {
                migrated = withoutPathTypeImport
            }
        }
        migrated = migrated.replace(Regex("""\n{3,}"""), "\n\n")
        if (migrated != original) {
            migratedSources[sourceFile] = migrated
            changes.add(Change(
                file = sourceFile,
                line = 0,
                description = "Remove legacy PathType.create constant after all proven blocked consumers were migrated",
                before = "PathType.create(name, -1.0F)",
                after = "(removed obsolete path type constant)",
                confidence = Confidence.HIGH,
                ruleId = "struct-path-type-create-remove-constant"
            ))
        }
    }

    if (!dryRun) {
        migratedSources.forEach { (file, migrated) ->
            if (migrated != sources.getValue(file)) {
                file.writeText(migrated)
            }
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectLegacyPathTypeConstants(file: Path, source: String): List<LegacyPathTypeConstant> {
    if (!source.contains("PathType.create(")) return emptyList()
    val ownerClass = classNameOfJavaSource(source) ?: return emptyList()
    val packageName = packageNameOf(source)
    val constants = mutableListOf<LegacyPathTypeConstant>()
    val pattern = Regex(
        """(?m)^[ \t]*(?:(?:public|protected|private)\s+)?static\s+final\s+PathType\s+([A-Za-z_$][\w$]*)\s*=\s*PathType\.create\s*\("""
    )
    var cursor = 0
    while (true) {
        val match = pattern.find(source, cursor) ?: break
        val openParen = source.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val semicolon = source.indexOf(';', closeParen)
        if (semicolon < 0) {
            cursor = closeParen + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == 2 && javaStringLiteralValue(args[0].trim()) != null) {
            val declarationEnd = if (semicolon + 1 < source.length && source[semicolon + 1] == '\n') semicolon + 2 else semicolon + 1
            constants += LegacyPathTypeConstant(
                sourceFile = file,
                packageName = packageName,
                ownerClass = ownerClass,
                fieldName = match.groupValues[1],
                malusExpression = args[1].trim(),
                declarationText = source.substring(match.range.first, declarationEnd)
            )
        }
        cursor = semicolon + 1
    }
    return constants
}

internal fun StructuralRefactorPass.isNegativeOneFloatLiteral(expression: String): Boolean {
    val normalized = expression.trim().removeSuffix("F").removeSuffix("f")
    return normalized.toFloatOrNull() == -1.0f
}

internal fun StructuralRefactorPass.allPathTypeConstantReferencesAreProvenBlockedConsumers(
    sources: Map<Path, String>,
    constant: LegacyPathTypeConstant
): Boolean {
    val referencePattern = legacyPathTypeConstantReferencePattern(constant)
    var references = 0
    var allowed = 0
    sources.values.forEach { source ->
        val refs = referencePattern.findAll(source).toList()
        if (refs.isEmpty()) return@forEach
        references += refs.size
        val allowedRanges = mutableListOf<IntRange>()
        legacyPathTypeConstantReturnPattern(constant).findAll(source).forEach { match ->
            val method = enclosingMethodText(source, match.range.first) ?: return false
            if (!Regex("""\bPathType\s+getBlockPathType\s*\(""").containsMatchIn(method)) return false
            allowedRanges += match.range
        }
        legacyPathTypeConstantSetMalusPattern(constant).findAll(source).forEach { match ->
            if (isNegativeOneFloatLiteral(match.groupValues[1])) {
                allowedRanges += match.range
            }
        }
        refs.forEach { ref ->
            if (allowedRanges.any { ref.range.first >= it.first && ref.range.last <= it.last }) {
                allowed++
            }
        }
    }
    return references > 0 && references == allowed
}

internal fun StructuralRefactorPass.legacyPathTypeConstantReferencePattern(constant: LegacyPathTypeConstant): Regex =
    Regex("""(?<![\w$])${Regex.escape(constant.ownerClass)}\.${Regex.escape(constant.fieldName)}(?![\w$])""")

internal fun StructuralRefactorPass.legacyPathTypeConstantReturnPattern(constant: LegacyPathTypeConstant): Regex =
    Regex("""return\s+${Regex.escape(constant.ownerClass)}\.${Regex.escape(constant.fieldName)}\s*;""")

internal fun StructuralRefactorPass.legacyPathTypeConstantSetMalusPattern(constant: LegacyPathTypeConstant): Regex =
    Regex("""setPathfindingMalus\s*\(\s*${Regex.escape(constant.ownerClass)}\.${Regex.escape(constant.fieldName)}\s*,\s*([^,)]+)\s*\)""")

internal fun StructuralRefactorPass.migrateLegacyImageButtonConstructors(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val helperPackages = mutableSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .filter { it.fileName.toString() != "LegacyImageButton.java" }
        .toList()
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("ImageButton")) return@forEach
            val packageName = packageNameOf(original)
            var migrated = rewriteLegacyImageButtonObjectCreations(original)
            migrated = rewriteLegacyImageButtonSuperclasses(migrated)
            if (migrated != original) {
                helperPackages += packageName
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy ImageButton UV constructors to generated compatibility widget",
                    before = "ImageButton(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, ...)",
                    after = "LegacyImageButton(old UV constructor shape)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-imagebutton-constructors"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }

    helperPackages.forEach { packageName ->
        val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("LegacyImageButton.java")
        changes.add(Change(
            file = helperFile,
            line = 0,
            description = "Generate compatibility ImageButton preserving legacy UV spritesheet rendering",
            before = "(missing LegacyImageButton)",
            after = "LegacyImageButton.java",
            confidence = Confidence.HIGH,
            ruleId = "struct-legacy-imagebutton-helper"
        ))
        if (!dryRun && !helperFile.exists()) {
            helperFile.parent.createDirectories()
            helperFile.writeText(legacyImageButtonSource(packageName))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.rewriteLegacyImageButtonObjectCreations(source: String): String {
    var result = source
    var cursor = 0
    val replacements = mutableListOf<Pair<String, String>>()
    while (true) {
        val match = Regex("""new\s+ImageButton\s*\(""").find(source, cursor) ?: break
        val openParen = source.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (isLegacyImageButtonConstructorArgs(args)) {
            val call = source.substring(match.range.first, closeParen + 1)
            replacements += call to call.replaceFirst(Regex("""new\s+ImageButton"""), "new LegacyImageButton")
        }
        cursor = closeParen + 1
    }
    replacements.forEach { (before, after) ->
        result = result.replace(before, after)
    }
    return result
}

internal fun StructuralRefactorPass.rewriteLegacyImageButtonSuperclasses(source: String): String {
    if (!Regex("""extends\s+ImageButton\b""").containsMatchIn(source)) return source
    var hasLegacySuperCall = false
    var cursor = 0
    while (true) {
        val match = Regex("""\bsuper\s*\(""").find(source, cursor) ?: break
        val openParen = source.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (isLegacyImageButtonConstructorArgs(args)) {
            hasLegacySuperCall = true
            break
        }
        cursor = closeParen + 1
    }
    return if (hasLegacySuperCall) {
        source.replace(Regex("""extends\s+ImageButton\b"""), "extends LegacyImageButton")
    } else {
        source
    }
}

internal fun StructuralRefactorPass.isLegacyImageButtonConstructorArgs(args: List<String>): Boolean {
    if (args.size !in setOf(9, 11, 12)) return false
    val textureArgIndex = 7
    val textureArg = args.getOrNull(textureArgIndex)?.trim().orEmpty()
    if (textureArg.isBlank()) return false
    if (args[4].contains("WidgetSprites")) return false
    return true
}

internal fun StructuralRefactorPass.legacyImageButtonSource(packageName: String): String {
    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    return """${packageLine}import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class LegacyImageButton extends ImageButton {
	protected final int xTexStart;
	protected final int yTexStart;
	protected final int yDiffTex;
	protected final ResourceLocation resourceLocation;
	protected final int textureWidth;
	protected final int textureHeight;

	public LegacyImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, OnPress onPress) {
		this(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, 256, 256, onPress, CommonComponents.EMPTY);
	}

	public LegacyImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, int textureWidth, int textureHeight, OnPress onPress) {
		this(x, y, width, height, xTexStart, yTexStart, yDiffTex, texture, textureWidth, textureHeight, onPress, CommonComponents.EMPTY);
	}

	public LegacyImageButton(int x, int y, int width, int height, int xTexStart, int yTexStart, int yDiffTex, ResourceLocation texture, int textureWidth, int textureHeight, OnPress onPress, Component message) {
		super(x, y, width, height, new WidgetSprites(texture, texture), onPress, message);
		this.xTexStart = xTexStart;
		this.yTexStart = yTexStart;
		this.yDiffTex = yDiffTex;
		this.resourceLocation = texture;
		this.textureWidth = textureWidth;
		this.textureHeight = textureHeight;
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		int v = this.yTexStart;
		if (this.isHoveredOrFocused()) {
			v += this.yDiffTex;
		}
		guiGraphics.blit(this.resourceLocation, this.getX(), this.getY(), (float) this.xTexStart, (float) v, this.width, this.height, this.textureWidth, this.textureHeight);
	}
}
"""
}

internal fun StructuralRefactorPass.migrateLegacyStateSwitchingButtonTextures(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val helperPackages = mutableSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .filter { it.fileName.toString() != "LegacyStateSwitchingButton.java" }
        .toList()
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("StateSwitchingButton") && !original.contains("initTextureValues(")) return@forEach
            if (!containsLegacyStateSwitchingButtonInitCall(original)) return@forEach
            val packageName = packageNameOf(original)
            var migrated = rewriteLegacyStateSwitchingButtonSuperclasses(original)
            migrated = rewriteLegacyStateSwitchingButtonInitCalls(migrated)
            migrated = rewriteLegacyStateSwitchingButtonObjectCreations(migrated)
            if (migrated != original) {
                helperPackages += packageName
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy StateSwitchingButton UV textures to generated compatibility widget",
                    before = "StateSwitchingButton.initTextureValues(xTexStart, yTexStart, yDiffTex, xDiffTex, texture)",
                    after = "LegacyStateSwitchingButton(old UV texture shape)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-legacy-state-switching-button-textures"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }

    helperPackages.forEach { packageName ->
        val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("LegacyStateSwitchingButton.java")
        changes.add(Change(
            file = helperFile,
            line = 0,
            description = "Generate compatibility StateSwitchingButton preserving legacy UV spritesheet rendering",
            before = "(missing LegacyStateSwitchingButton)",
            after = "LegacyStateSwitchingButton.java",
            confidence = Confidence.HIGH,
            ruleId = "struct-legacy-state-switching-button-helper"
        ))
        if (!dryRun && !helperFile.exists()) {
            helperFile.parent.createDirectories()
            helperFile.writeText(legacyStateSwitchingButtonSource(packageName))
        }
    }
    return changes
}

internal fun StructuralRefactorPass.containsLegacyStateSwitchingButtonInitCall(source: String): Boolean {
    val executableCode = maskJavaCommentsAndLiterals(source)
    var cursor = 0
    while (true) {
        val tokenIndex = executableCode.indexOf("initTextureValues(", cursor)
        if (tokenIndex < 0) return false
        if (tokenIndex > 0 && (executableCode[tokenIndex - 1].isLetterOrDigit() || executableCode[tokenIndex - 1] == '_' || executableCode[tokenIndex - 1] == '$')) {
            cursor = tokenIndex + "initTextureValues(".length
            continue
        }
        val openParen = tokenIndex + "initTextureValues".length
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) {
            cursor = tokenIndex + "initTextureValues(".length
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (isLegacyStateSwitchingButtonInitArgs(args)) return true
        cursor = closeParen + 1
    }
}

internal fun StructuralRefactorPass.rewriteLegacyStateSwitchingButtonSuperclasses(source: String): String {
    if (!Regex("""extends\s+StateSwitchingButton\b""").containsMatchIn(maskJavaCommentsAndLiterals(source))) return source
    return if (containsLegacyStateSwitchingButtonInitCall(source)) {
        replaceExecutableRegex(source, Regex("""extends\s+StateSwitchingButton\b""")) { "extends LegacyStateSwitchingButton" }
    } else {
        source
    }
}

internal fun StructuralRefactorPass.rewriteLegacyStateSwitchingButtonInitCalls(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    return rewriteExecutableJavaCall(source, "initTextureValues") { receiver, args ->
        if (!isLegacyStateSwitchingButtonInitArgs(args)) return@rewriteExecutableJavaCall null
        if (receiver == "this" || receiver == "super" || executableCode.contains("extends LegacyStateSwitchingButton")) {
            return@rewriteExecutableJavaCall null
        }
        val target = legacyStateSwitchingButtonAssignmentTarget(receiver) ?: return@rewriteExecutableJavaCall null
        "$target = LegacyStateSwitchingButton.replace($receiver, ${args.joinToString(", ") { it.trim() }})"
    }
}

internal fun StructuralRefactorPass.rewriteLegacyStateSwitchingButtonObjectCreations(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    var cursor = 0
    val replacements = mutableListOf<Triple<Int, Int, String>>()
    while (true) {
        val match = Regex("""new\s+StateSwitchingButton\s*\(""").find(executableCode, cursor) ?: break
        val openParen = executableCode.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(executableCode, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == 5) {
            val call = source.substring(match.range.first, closeParen + 1)
            val replacement = call.replaceFirst(Regex("""new\s+StateSwitchingButton"""), "new LegacyStateSwitchingButton")
            replacements += Triple(match.range.first, closeParen + 1, replacement)
        }
        cursor = closeParen + 1
    }
    var result = source
    replacements.asReversed().forEach { (start, end, replacement) ->
        result = result.substring(0, start) + replacement + result.substring(end)
    }
    return result
}

internal fun StructuralRefactorPass.legacyStateSwitchingButtonAssignmentTarget(receiver: String): String? {
    val trimmed = receiver.trim()
    if (trimmed == "this" || trimmed == "super") return null
    val assignable = Regex("""(?:this|[A-Za-z_$][\w$]*)(?:\.[A-Za-z_$][\w$]*)*""")
    return trimmed.takeIf { assignable.matches(it) }
}

internal fun StructuralRefactorPass.isLegacyStateSwitchingButtonInitArgs(args: List<String>): Boolean {
    if (args.size != 5) return false
    if (args.any { it.contains("WidgetSprites") }) return false
    return args.take(4).all { it.trim().isNotBlank() } && args[4].trim().isNotBlank()
}

internal fun StructuralRefactorPass.legacyStateSwitchingButtonSource(packageName: String): String {
    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    return """${packageLine}import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.resources.ResourceLocation;

public class LegacyStateSwitchingButton extends StateSwitchingButton {
	protected int xTexStart;
	protected int yTexStart;
	protected int yDiffTex;
	protected int xDiffTex;
	protected ResourceLocation resourceLocation;

	public LegacyStateSwitchingButton(int x, int y, int width, int height, boolean initialState) {
		super(x, y, width, height, initialState);
	}

	public static LegacyStateSwitchingButton replace(StateSwitchingButton button, int xTexStart, int yTexStart, int yDiffTex, int xDiffTex, ResourceLocation texture) {
		LegacyStateSwitchingButton legacy = button instanceof LegacyStateSwitchingButton existing
			? existing
			: new LegacyStateSwitchingButton(button.getX(), button.getY(), button.getWidth(), button.getHeight(), button.isStateTriggered());
		legacy.active = button.active;
		legacy.visible = button.visible;
		legacy.setTooltip(button.getTooltip());
		legacy.initTextureValues(xTexStart, yTexStart, yDiffTex, xDiffTex, texture);
		return legacy;
	}

	public void initTextureValues(int xTexStart, int yTexStart, int yDiffTex, int xDiffTex, ResourceLocation texture) {
		super.initTextureValues(new WidgetSprites(texture, texture, texture, texture));
		this.xTexStart = xTexStart;
		this.yTexStart = yTexStart;
		this.yDiffTex = yDiffTex;
		this.xDiffTex = xDiffTex;
		this.resourceLocation = texture;
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (this.resourceLocation == null) {
			super.renderWidget(guiGraphics, mouseX, mouseY, partialTicks);
			return;
		}
		int u = this.xTexStart;
		if (this.isStateTriggered()) {
			u += this.xDiffTex;
		}
		int v = this.yTexStart;
		if (this.isHoveredOrFocused()) {
			v += this.yDiffTex;
		}
		guiGraphics.blit(this.resourceLocation, this.getX(), this.getY(), (float) u, (float) v, this.width, this.height, 256, 256);
	}
}
"""
}

internal fun StructuralRefactorPass.migrateCuriosClientApi(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("Curios") && !original.contains("RenderButton")) return@forEach
            var migrated = original
            if (migrated.contains("Curios.MODID")) {
                migrated = migrated.replace("Curios.MODID", "CuriosApi.MODID")
                migrated = addImportIfMissing(migrated, "top.theillusivec4.curios.api.CuriosApi")
            }
            if (migrated.contains("import top.theillusivec4.curios.client.gui.RenderButton;")) {
                migrated = rewriteCuriosRenderButtonConstructors(migrated)
                migrated = rewriteCuriosRenderButtonAnonymousRenderMethods(migrated)
            }
            if (migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate Curios client API constants and RenderButton constructor shape",
                    before = "Curios.MODID / RenderButton(..., yDiffTex, texture, onPress)",
                    after = "CuriosApi.MODID / RenderButton(..., texture, onPress)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-curios-client-api"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }
    return changes
}

internal fun StructuralRefactorPass.rewriteCuriosRenderButtonConstructors(source: String): String {
    var result = source
    var cursor = 0
    val replacements = mutableListOf<Pair<String, String>>()
    while (true) {
        val match = Regex("""new\s+RenderButton\s*\(""").find(source, cursor) ?: break
        val openParen = source.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val args = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
        if (args.size == 10) {
            val migratedArgs = args.filterIndexed { index, _ -> index != 7 }
            val call = source.substring(match.range.first, closeParen + 1)
            replacements += call to "new RenderButton(${migratedArgs.joinToString(", ")})"
        }
        cursor = closeParen + 1
    }
    replacements.forEach { (before, after) ->
        result = result.replace(before, after)
    }
    return result
}

internal fun StructuralRefactorPass.rewriteCuriosRenderButtonAnonymousRenderMethods(source: String): String {
    val replacements = mutableListOf<Triple<Int, Int, String>>()
    var cursor = 0
    while (true) {
        val match = Regex("""new\s+RenderButton\s*\(""").find(source, cursor) ?: break
        val openParen = source.indexOf('(', match.range.last - 1)
        val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
        if (closeParen < 0) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = firstNonWhitespaceIndex(source, closeParen + 1)
        if (openBrace < 0 || source[openBrace] != '{') {
            cursor = closeParen + 1
            continue
        }
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) {
            cursor = closeParen + 1
            continue
        }
        val body = source.substring(openBrace, closeBrace + 1)
        val migratedBody = Regex("""(\bpublic\s+void\s+)render(\s*\(\s*GuiGraphics\b)""")
            .replace(body, "$1renderWidget$2")
        if (migratedBody != body) {
            replacements += Triple(openBrace, closeBrace + 1, migratedBody)
        }
        cursor = closeBrace + 1
    }
    if (replacements.isEmpty()) return source
    var result = source
    replacements.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
        result = result.substring(0, start) + replacement + result.substring(end)
    }
    return result
}

internal fun StructuralRefactorPass.firstNonWhitespaceIndex(source: String, start: Int): Int {
    var index = start
    while (index < source.length && source[index].isWhitespace()) {
        index++
    }
    return if (index < source.length) index else -1
}

internal fun StructuralRefactorPass.migrateLegacyGrassColorModifierEnumExtensions(projectDir: Path, dryRun: Boolean): List<Change> {
    val changes = mutableListOf<Change>()
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return changes
    val modId = detectModId(projectDir) ?: return changes
    val extensions = mutableListOf<LegacyGrassColorModifierExtension>()

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val original = javaFile.readText()
            if (!original.contains("GrassColorModifier.create(") || !original.contains("GrassColorModifier")) return@forEach
            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .find(original)
                ?.groupValues
                ?.get(1)
                ?: ""
            val className = Regex("""\b(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_$][\w$]*)\b""")
                .find(original)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            val imports = Regex("""(?m)^\s*import\s+[^;]+;\s*$""")
                .findAll(original)
                .map { it.value.trim() }
                .filterNot { it.contains("BiomeSpecialEffects.GrassColorModifier") }
                .distinct()
                .toList()
            val localStaticMethods = Regex(
                """(?m)\b(?:public|protected|private)\s+static\s+[\w<>\[\].?,\s]+\s+([A-Za-z_$][\w$]*)\s*\("""
            ).findAll(original)
                .map { it.groupValues[1] }
                .toSet()

            var migrated = original
            var cursor = 0
            var changed = false
            val fieldPattern = Regex("""\bGrassColorModifier\s+([A-Za-z_$][\w$]*)\s*=\s*make\s*\(""")
            while (true) {
                val match = fieldPattern.find(original, cursor) ?: break
                val openParen = original.indexOf('(', match.range.last - 1)
                val closeParen = if (openParen >= 0) findMatchingParen(original, openParen) else -1
                if (closeParen < 0) {
                    cursor = match.range.last + 1
                    continue
                }
                val args = splitTopLevelJavaArgs(original.substring(openParen + 1, closeParen))
                if (args.size != 2) {
                    cursor = closeParen + 1
                    continue
                }
                val id = Regex("""^"([^"]+)"""").find(args[0].trim())?.groupValues?.get(1)
                if (id == null) {
                    cursor = closeParen + 1
                    continue
                }
                val fieldName = match.groupValues[1]
                val enumName = "${modId}_${fieldName}"
                    .uppercase()
                    .replace(Regex("""[^A-Z0-9_]+"""), "_")
                val methodName = "GrassColorModifier_${fieldName}"
                    .replace(Regex("""[^A-Za-z0-9_]+"""), "_")
                val delegate = qualifyLocalStaticMethodCalls(args[1].trim(), className, localStaticMethods)
                val callText = original.substring(original.indexOf("make", match.range.first), closeParen + 1)
                migrated = migrated.replace(callText, """GrassColorModifier.valueOf("$enumName")""")
                extensions += LegacyGrassColorModifierExtension(
                    sourceFile = javaFile,
                    packageName = packageName,
                    sourceClassName = className,
                    imports = imports,
                    serializedName = "$modId:$id",
                    enumName = enumName,
                    methodName = methodName,
                    delegateExpression = delegate
                )
                changed = true
                cursor = closeParen + 1
            }

            val makeMethod = javaDeclaredMethodText(migrated, "make")
            if (makeMethod != null &&
                makeMethod.contains("GrassColorModifier.create(") &&
                !Regex("""=\s*make\s*\(""").containsMatchIn(migrated)) {
                migrated = migrated.replace(makeMethod, "").replace(Regex("""\n{3,}"""), "\n\n")
            }

            if (changed && migrated != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 0,
                    description = "Migrate legacy GrassColorModifier.create() to NeoForge enum extension value lookup",
                    before = "GrassColorModifier.create(name, name, delegate)",
                    after = "GrassColorModifier.valueOf(generated enum extension name)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-grass-color-enum-extension"
                ))
                if (!dryRun) javaFile.writeText(migrated)
            }
        }

    if (extensions.isEmpty()) return changes
    extensions.groupBy { it.packageName }.forEach { (packageName, packageExtensions) ->
        val helperClass = "NeoForgeEnumExtensions"
        val helperFile = srcDir.resolve(packageName.replace('.', '/')).resolve("$helperClass.java")
        val helperSource = grassColorEnumExtensionHelperSource(packageName, helperClass, packageExtensions)
        changes.add(Change(
            file = helperFile,
            line = 0,
            description = "Generate NeoForge enum extension parameter helper for legacy grass color modifiers",
            before = "(missing grass color enum extension helper)",
            after = "$helperClass.java",
            confidence = Confidence.HIGH,
            ruleId = "struct-grass-color-enum-extension-helper"
        ))
        if (!dryRun) {
            helperFile.parent.createDirectories()
            val merged = if (helperFile.exists()) {
                mergeEnumExtensionHelperMethods(helperFile.readText(), packageExtensions, helperSource)
            } else {
                helperSource
            }
            helperFile.writeText(merged)
        }
    }

    val resourcesDir = projectDir.resolve("src/main/resources")
    val metaInf = resourcesDir.resolve("META-INF")
    val enumExtensionFile = metaInf.resolve("enumextensions.json")
    val entries = extensions.map { grassColorEnumExtensionJsonEntry(it) }
    changes.add(Change(
        file = enumExtensionFile,
        line = 0,
        description = "Declare NeoForge enum extension metadata for legacy grass color modifiers",
        before = "(missing grass color enum extension entries)",
        after = "META-INF/enumextensions.json",
        confidence = Confidence.HIGH,
        ruleId = "struct-grass-color-enum-extension-json"
    ))
    if (!dryRun) {
        metaInf.createDirectories()
        mergeEnumExtensionEntries(enumExtensionFile, entries)
        ensureEnumExtensionsTomlEntry(metaInf)
    }
    return changes
}

internal fun StructuralRefactorPass.qualifyLocalStaticMethodCalls(
    expression: String,
    className: String,
    localStaticMethods: Set<String>
): String {
    var result = expression
    localStaticMethods
        .filterNot { it == "make" }
        .forEach { method ->
            result = Regex("""(?<![\w$.])${Regex.escape(method)}\s*\(""")
                .replace(result, "$className.$method(")
        }
    return result
}

internal fun StructuralRefactorPass.grassColorEnumExtensionHelperSource(
    packageName: String,
    helperClass: String,
    extensions: List<LegacyGrassColorModifierExtension>
): String {
    val packageLine = if (packageName.isBlank()) "" else "package $packageName;\n\n"
    val imports = (extensions.flatMap { it.imports } + "import net.minecraft.world.level.biome.BiomeSpecialEffects.GrassColorModifier;")
        .distinct()
        .joinToString("\n")
    val importBlock = if (imports.isBlank()) "" else "$imports\n\n"
    val methods = extensions.joinToString("\n\n") { grassColorEnumExtensionMethod(it) }
    return """${packageLine}${importBlock}@SuppressWarnings("unused")
public final class $helperClass {
	private $helperClass() {
	}

$methods
}
"""
}

internal fun StructuralRefactorPass.grassColorEnumExtensionMethod(extension: LegacyGrassColorModifierExtension): String {
    return """
	public static Object ${extension.methodName}(int idx, Class<?> type) {
		return switch (idx) {
			case 0 -> "${extension.serializedName}";
			case 1 -> (GrassColorModifier.ColorModifier) (${extension.delegateExpression});
			default -> throw new IllegalArgumentException("Unexpected parameter index: " + idx);
		};
	}
        """.trimEnd()
}

internal fun StructuralRefactorPass.mergeEnumExtensionHelperMethods(
    existing: String,
    extensions: List<LegacyGrassColorModifierExtension>,
    freshSource: String
): String {
    var result = existing
    Regex("""(?m)^\s*import\s+[^;]+;\s*$""")
        .findAll(freshSource)
        .map { it.value.trim().removePrefix("import ").removeSuffix(";") }
        .forEach { importName ->
            result = addImportIfMissing(result, importName)
        }
    val missingMethods = extensions
        .filterNot { result.contains(" ${it.methodName}(int idx, Class<?> type)") }
        .joinToString("\n\n") { grassColorEnumExtensionMethod(it) }
    if (missingMethods.isBlank()) return result
    val insertIndex = result.lastIndexOf('}')
    return if (insertIndex >= 0) {
        result.substring(0, insertIndex).trimEnd() + "\n\n" + missingMethods + "\n" + result.substring(insertIndex)
    } else {
        result + "\n\n" + missingMethods
    }
}

internal fun StructuralRefactorPass.grassColorEnumExtensionJsonEntry(extension: LegacyGrassColorModifierExtension): String {
    val helperInternalName = if (extension.packageName.isBlank()) {
        "NeoForgeEnumExtensions"
    } else {
        "${extension.packageName.replace('.', '/')}/NeoForgeEnumExtensions"
    }
    return """    {
      "enum": "net/minecraft/world/level/biome/BiomeSpecialEffects${'$'}GrassColorModifier",
      "name": "${extension.enumName}",
      "constructor": "(Ljava/lang/String;Lnet/minecraft/world/level/biome/BiomeSpecialEffects${'$'}GrassColorModifier${'$'}ColorModifier;)V",
      "parameters": {
        "class": "$helperInternalName",
        "method": "${extension.methodName}"
      }
    }"""
}

internal fun StructuralRefactorPass.mergeEnumExtensionEntries(enumExtensionFile: Path, entries: List<String>) {
    val newEntries = entries.filter { entry ->
        val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(entry)?.groupValues?.get(1)
        name == null || !enumExtensionFile.exists() || !enumExtensionFile.readText().contains(""""name": "$name"""")
    }
    if (newEntries.isEmpty()) return
    if (!enumExtensionFile.exists()) {
        enumExtensionFile.writeText(enumExtensionsJson(newEntries))
        return
    }
    val content = enumExtensionFile.readText()
    if (!content.contains(""""entries"""")) {
        enumExtensionFile.writeText(enumExtensionsJson(newEntries))
        return
    }
    val insertIndex = content.lastIndexOf("\n  ]")
    if (insertIndex < 0) {
        enumExtensionFile.writeText(enumExtensionsJson(newEntries))
        return
    }
    val prefix = content.substring(0, insertIndex).trimEnd()
    val separator = if (prefix.endsWith("[")) "\n" else ",\n"
    enumExtensionFile.writeText(prefix + separator + newEntries.joinToString(",\n") + content.substring(insertIndex))
}

internal fun StructuralRefactorPass.enumExtensionsJson(entries: List<String>): String {
    return """{
  "entries": [
${entries.joinToString(",\n")}
  ]
}
"""
}

internal data class DeferredRegisterOwner(val simpleName: String, val fieldName: String)

internal data class DeferredRegisterField(
    val ownerSimpleName: String,
    val ownerQualifiedName: String,
    val fieldName: String,
    val ownerFile: Path? = null
) {
    val qualifiedReference: String
        get() = if (ownerQualifiedName.isBlank()) fieldName else "$ownerQualifiedName.$fieldName"
}

internal fun StructuralRefactorPass.migrateDeferredRegisterListenerReferences(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val javaFiles = walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .toList()
    val owners = collectDeferredRegisterOwners(srcDir)
    if (owners.isEmpty()) return emptyList()

    val changes = mutableListOf<Change>()
    javaFiles.forEach { file ->
        val original = file.readText()
        val executableOriginal = maskJavaCommentsAndLiterals(original)
        if (!executableOriginal.contains("::register") || !executableOriginal.contains(".addListener(")) return@forEach
        val migrated = migrateDeferredRegisterListenerReferencesSource(original, owners)
        if (migrated != original) {
            changes.add(Change(
                file = file,
                line = 0,
                description = "Register DeferredRegister field directly on the mod event bus",
                before = "modBus.addListener(RegistryClass::register)",
                after = "RegistryClass.DEFERRED_REGISTER.register(modBus)",
                confidence = Confidence.HIGH,
                ruleId = "struct-deferredregister-listener"
            ))
            if (!dryRun) file.writeText(migrated)
        }
    }
    return changes
}

internal fun StructuralRefactorPass.migrateDeferredRegisterListenerReferencesSource(
    source: String,
    owners: Map<String, DeferredRegisterOwner>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (owners.isEmpty() || !executableCode.contains("::register") || !executableCode.contains(".addListener(")) return source
    val listenerPattern = Regex(
        """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.addListener\(\s*((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)::register\s*\)\s*;\s*$"""
    )
    return replaceExecutableRegex(source, listenerPattern) { match ->
        val target = match.groupValues[3]
        val simpleName = target.substringAfterLast('.')
        val owner = owners[simpleName] ?: return@replaceExecutableRegex match.value
        "${match.groupValues[1]}$target.${owner.fieldName}.register(${match.groupValues[2]});"
    }
}

internal fun StructuralRefactorPass.registerMissingDeferredRegisterFields(projectDir: Path, dryRun: Boolean): List<Change> {
    val srcDir = projectDir.resolve("src/main/java")
    if (!srcDir.exists()) return emptyList()
    val mainClass = detectModMainClass(projectDir) ?: return emptyList()
    val mainSource = mainClass.readText()
    val mainName = classNameOfJavaSource(mainSource) ?: return emptyList()
    val mainPackage = packageNameOf(mainSource)
    val mainQualifiedName = if (mainPackage.isBlank()) mainName else "$mainPackage.$mainName"
    val fields = collectDeferredRegisterFields(srcDir)
        .filterNot { it.ownerQualifiedName == mainQualifiedName }
    if (fields.isEmpty()) return emptyList()

    val original = mainClass.readText()
    val migrated = addMissingDeferredRegistersToConstructorArray(original, fields)
    if (migrated == original) return emptyList()
    val executableOriginal = maskJavaCommentsAndLiterals(original)
    val executableMigrated = maskJavaCommentsAndLiterals(migrated)
    val missing = fields.filter { field ->
        hasDeferredRegisterArray(original) &&
            !deferredRegisterReferencePattern(field).containsMatchIn(executableOriginal) &&
            deferredRegisterReferencePattern(field).containsMatchIn(executableMigrated)
    }
    if (missing.isEmpty()) return emptyList()
    val changes = listOf(Change(
        file = mainClass,
        line = 0,
        description = "Register DeferredRegister fields discovered outside the main mod class on the existing mod event bus array",
        before = "DeferredRegister<?>[] registers missing ${missing.joinToString { it.qualifiedReference }}",
        after = "DeferredRegister<?>[] registers includes ${missing.joinToString { it.qualifiedReference }}",
        confidence = Confidence.HIGH,
        ruleId = "struct-deferredregister-array-completeness"
    ))
    if (!dryRun) mainClass.writeText(migrated)
    return changes
}

internal fun StructuralRefactorPass.addMissingDeferredRegistersToConstructorArray(
    source: String,
    fields: List<DeferredRegisterField>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (fields.isEmpty() || !hasDeferredRegisterArray(source)) return source
    val arrayMatch = Regex("""DeferredRegister\s*<\s*\?\s*>\s*\[\]\s+[A-Za-z_$][\w$]*\s*=\s*\{""")
        .find(executableCode)
        ?: return source
    val openBrace = executableCode.indexOf('{', arrayMatch.range.first)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
    if (openBrace < 0 || closeBrace <= openBrace) return source

    val missing = fields
        .filterNot {
            deferredRegisterReferencePattern(it).containsMatchIn(executableCode) ||
                deferredRegisterFieldRegisteredViaOwnerMethod(source, it)
        }
        .map { it.qualifiedReference }
    if (missing.isEmpty()) return source

    val inside = source.substring(openBrace + 1, closeBrace)
    if (!inside.contains('\n')) {
        val prefix = if (inside.trim().isEmpty()) " " else ", "
        val insertion = prefix + missing.joinToString(", ") + " "
        return source.substring(0, closeBrace).trimEnd() + insertion + source.substring(closeBrace)
    }

    val insertion = run {
        val closeIndent = source.lineStartIndent(closeBrace)
        val entryIndent = Regex("""\r?\n([ \t]*)""").find(inside)?.groupValues?.get(1) ?: "$closeIndent    "
        val prefix = if (inside.trim().isEmpty()) "\n" else ",\n"
        prefix + missing.joinToString(",\n") { "$entryIndent$it" } + "\n$closeIndent"
    }
    return source.substring(0, closeBrace) + insertion + source.substring(closeBrace)
}

internal fun StructuralRefactorPass.hasDeferredRegisterArray(source: String): Boolean {
    val executableCode = maskJavaCommentsAndLiterals(source)
    return Regex("""DeferredRegister\s*<\s*\?\s*>\s*\[\]\s+[A-Za-z_$][\w$]*\s*=""")
        .containsMatchIn(executableCode)
}

internal fun StructuralRefactorPass.deferredRegisterReferencePattern(field: DeferredRegisterField): Regex {
    val simple = Regex.escape(field.ownerSimpleName)
    val qualified = Regex.escape(field.ownerQualifiedName)
    val owner = if (field.ownerQualifiedName.isBlank()) simple else "(?:$simple|$qualified)"
    return Regex("""\b$owner\.${Regex.escape(field.fieldName)}\b""")
}

internal fun StructuralRefactorPass.deferredRegisterFieldRegisteredViaOwnerMethod(
    mainSource: String,
    field: DeferredRegisterField
): Boolean {
    val ownerSource = field.ownerFile?.takeIf { it.exists() }?.readText() ?: return false
    val executableOwnerSource = maskJavaCommentsAndLiterals(ownerSource)
    val executableMainSource = maskJavaCommentsAndLiterals(mainSource)
    val owner = if (field.ownerQualifiedName.isBlank()) {
        Regex.escape(field.ownerSimpleName)
    } else {
        "(?:${Regex.escape(field.ownerSimpleName)}|${Regex.escape(field.ownerQualifiedName)})"
    }
    val busType = """(?:IEventBus|net\.neoforged\.bus\.api\.IEventBus)"""
    val methodPattern = Regex(
        """(?s)\b(?:public|protected|private)?\s*static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*$busType\s+([A-Za-z_$][\w$]*)\s*\)\s*\{(.*?)\n\s*\}"""
    )
    return methodPattern.findAll(executableOwnerSource).any { match ->
        val methodName = match.groupValues[1]
        val busName = match.groupValues[2]
        val body = match.groupValues[3]
        val registersField = Regex("""\b${Regex.escape(field.fieldName)}\.register\s*\(\s*${Regex.escape(busName)}\s*\)""")
            .containsMatchIn(body)
        registersField && Regex("""\b$owner\.${Regex.escape(methodName)}\s*\(""").containsMatchIn(executableMainSource)
    }
}

internal fun String.lineStartIndent(index: Int): String {
    val lineStart = lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
    return substring(lineStart, index).takeWhile { it == ' ' || it == '\t' }
}

internal fun StructuralRefactorPass.collectJavaRecordComponents(srcDir: Path): Map<String, Set<String>> {
    val recordComponentSets = linkedMapOf<String, MutableSet<Set<String>>>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            collectJavaRecordComponentsFromSource(source).forEach { (recordName, components) ->
                recordComponentSets.getOrPut(recordName) { linkedSetOf() } += components
            }
        }
    return recordComponentSets
        .filterValues { it.size == 1 }
        .mapValues { (_, componentSets) -> componentSets.single() }
}

internal fun StructuralRefactorPass.collectJavaRecordComponentsFromSource(source: String): Map<String, Set<String>> {
    val records = linkedMapOf<String, Set<String>>()
    val executableCode = maskJavaCommentsAndLiterals(source)
    Regex("""\brecord\s+([A-Za-z_$][\w$]*)\s*\(""").findAll(executableCode).forEach { match ->
        val openParen = match.range.last
        val closeParen = findMatchingParen(executableCode, openParen)
        if (closeParen < 0) return@forEach
        val components = splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))
            .mapNotNull { component ->
                Regex("""([A-Za-z_$][\w$]*)\s*(?:\[\s*\])?\s*$""")
                    .find(component.trim())
                    ?.groupValues
                    ?.get(1)
            }
            .filter { it.isNotBlank() }
            .toSet()
        if (components.isNotEmpty()) {
            records[match.groupValues[1]] = components
        }
    }
    return records
}

internal fun StructuralRefactorPass.collectFinalMapDecorationSubclassClasses(srcDir: Path): Set<String> {
    val classes = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            val executableCode = maskJavaCommentsAndLiterals(source)
            if (!executableCode.contains("extends MapDecoration")) return@forEach
            Regex("""\b(?:class\s+)?([A-Za-z_$][\w$]*)\s+extends\s+MapDecoration\b""")
                .findAll(executableCode)
                .forEach { classes.add(it.groupValues[1]) }
        }
    return classes
}

internal fun StructuralRefactorPass.collectLootTableProviderClasses(srcDir: Path): Set<String> {
    val classes = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("LootTableProvider")) return@forEach
            Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+LootTableProvider\b""")
                .findAll(source)
                .forEach { classes.add(it.groupValues[1]) }
    }
    return classes
}

internal fun StructuralRefactorPass.collectLootTableProviderFactoryMethods(srcDir: Path): Map<String, Set<String>> {
    val factories = linkedMapOf<String, MutableSet<String>>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("static LootTableProvider") || !source.contains("new LootTableProvider(")) return@forEach
            val owner = classNameOfJavaSource(source) ?: return@forEach
            Regex("""\b(?:public|protected|private)\s+static\s+LootTableProvider\s+([A-Za-z_$][\w$]*)\s*\(""")
                .findAll(source)
                .forEach { match -> factories.getOrPut(owner) { linkedSetOf() }.add(match.groupValues[1]) }
        }
    return factories.mapValues { it.value.toSet() }
}

internal data class RecipeProviderClassHints(
    val all: Set<String>,
    val concrete: Set<String>
)

internal data class RecipeSerializerFactoryHints(
    val fieldToFactory: Map<String, String>,
    val fieldToRecipeClass: Map<String, String>,
    val fieldToFactoryInterface: Map<String, RecipeFactoryInterfaceHint> = emptyMap()
)

internal data class RecipeFactoryInterfaceHint(
    val type: String,
    val params: List<JavaParameterInfo>
)

internal data class RecipeTypeHints(
    val expressionToRecipeClass: Map<String, String>
)

internal data class HolderValueParameterHints(
    val soundEventParameters: Map<String, Set<Int>>,
    val potionParameters: Map<String, Set<Int>>,
    val mobEffectHolderParameters: Map<String, Set<Int>> = emptyMap(),
    val mobEffectDeferredHolderFields: Set<String> = emptySet()
)

internal data class CriterionInstanceFactoryHints(
    val emptyInstanceFactories: Map<String, String>,
    val triggerHoldersByTriggerClass: Map<String, String> = emptyMap()
)

internal data class StaticRegistryFieldHints(
    val qualifiedFields: Set<String>,
    val uniqueSimpleFields: Set<String>
) {
    fun contains(expression: String): Boolean {
        val normalized = expression.trim()
        return normalized in qualifiedFields || normalized in uniqueSimpleFields
    }
}

internal data class AttributeHolderAccessHints(
    val qualifiedFields: Set<String>
)

internal fun StructuralRefactorPass.declaredAttributeHolderFieldNames(source: String): Set<String> {
    val attributeType = """(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?Attribute"""
    val holderType = """(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*$attributeType\s*,\s*[^>\r\n]+>"""
    val registryObjectType = """(?:net\.minecraftforge\.registries\.)?RegistryObject\s*<\s*$attributeType\s*>"""
    return Regex(
        """(?m)\b(?:(?:public|protected|private|static|final|transient|volatile)\s+)*(?:$holderType|$registryObjectType)\s+([A-Za-z_$][\w$]*)\s*="""
    ).findAll(source).map { it.groupValues[1] }.toSet()
}

internal fun StructuralRefactorPass.collectAttributeHolderAccessHints(srcDir: Path): AttributeHolderAccessHints {
    val qualifiedFields = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("Attribute") ||
                (!source.contains("DeferredHolder") && !source.contains("RegistryObject"))) {
                return@forEach
            }
            val owner = classNameOfJavaSource(source) ?: return@forEach
            val packageName = packageNameOf(source)
            declaredAttributeHolderFieldNames(source).forEach { field ->
                qualifiedFields += "$owner.$field"
                if (packageName != null) {
                    qualifiedFields += "$packageName.$owner.$field"
                }
            }
        }
    return AttributeHolderAccessHints(qualifiedFields = qualifiedFields)
}

internal fun StructuralRefactorPass.collectStaticRegistryFieldHints(srcDir: Path): StaticRegistryFieldHints {
    val qualifiedFields = linkedSetOf<String>()
    val simpleCounts = linkedMapOf<String, Int>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("Registry<")) return@forEach
            val owner = classNameOfJavaSource(source) ?: return@forEach
            val packageName = packageNameOf(source)
            Regex(
                """\bstatic\s+(?:final\s+)?Registry\s*<[^>\r\n]+>\s+([A-Za-z_$][\w$]*)\s*="""
            ).findAll(source).forEach { match ->
                val field = match.groupValues[1]
                qualifiedFields += "$owner.$field"
                if (packageName != null) {
                    qualifiedFields += "$packageName.$owner.$field"
                }
                simpleCounts[field] = (simpleCounts[field] ?: 0) + 1
            }
            Regex(
                """\bstatic\s+(?:final\s+)?(?:java\.util\.function\.)?Supplier\s*<\s*Registry\s*<[^>\r\n]+>\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]+?\.makeRegistry\s*\("""
            ).findAll(source).forEach { match ->
                val field = match.groupValues[1]
                qualifiedFields += "$owner.$field"
                if (packageName != null) {
                    qualifiedFields += "$packageName.$owner.$field"
                }
                simpleCounts[field] = (simpleCounts[field] ?: 0) + 1
            }
        }
    return StaticRegistryFieldHints(
        qualifiedFields = qualifiedFields,
        uniqueSimpleFields = simpleCounts.filterValues { it == 1 }.keys
    )
}

internal fun StructuralRefactorPass.collectRecipeProviderClasses(srcDir: Path): RecipeProviderClassHints {
    data class ProviderType(val name: String, val qualified: String, val parent: String?, val namespace: String,
        val owners: List<String>, val imports: Map<String, String>, val wildcards: List<String>, val abstract: Boolean)
    val declarations = mutableListOf<ProviderType>()
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("class")) return@forEach
            val unit = parser.parse(source).result.orElse(null) ?: return@forEach
            val namespace = unit.packageDeclaration.map { it.nameAsString }.orElse("")
            val imports = unit.imports.filter { !it.isStatic && !it.isAsterisk }.associate { it.name.identifier to it.nameAsString }
            val wildcards = unit.imports.filter { !it.isStatic && it.isAsterisk }.map { it.nameAsString }
            for (type in unit.findAll(ClassOrInterfaceDeclaration::class.java)) {
                if (type.parentNode.orElse(null) !is CompilationUnit && type.parentNode.orElse(null) !is com.github.javaparser.ast.body.TypeDeclaration<*>) continue
                val owners = mutableListOf<String>()
                var parent = type.parentNode.orElse(null)
                while (parent != null) {
                    if (parent is com.github.javaparser.ast.body.TypeDeclaration<*>) owners += parent.nameAsString
                    parent = parent.parentNode.orElse(null)
                }
                val enclosing = owners.asReversed()
                declarations += ProviderType(type.nameAsString, (listOf(namespace) + enclosing + type.nameAsString).filter { it.isNotBlank() }.joinToString("."),
                    type.extendedTypes.singleOrNull()?.nameWithScope, namespace, enclosing, imports, wildcards, type.isAbstract || type.isInterface)
            }
        }
    // These are actual platform/library provider bases, not a class-name suffix convention.
    // javap nitrogen_internals 1.21.1-1.1.25-neoforge: NitrogenRecipeProvider(PackOutput,
    // CompletableFuture<HolderLookup.Provider>, String); its 1.20.1 build took (PackOutput, String).
    val roots = setOf(
        "net.minecraft.data.recipes.RecipeProvider",
        "cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider",
        "com.aetherteam.nitrogen.data.providers.NitrogenRecipeProvider"
    )
    val byQualified = declarations.groupBy { it.qualified }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    val ambiguous = declarations.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    fun parentOf(type: ProviderType): String? {
        val parent = type.parent ?: return null
        val candidates = mutableSetOf(parent)
        val first = parent.substringBefore('.')
        type.imports[first]?.let { candidates += it + parent.removePrefix(first) }
        for (count in 0..type.owners.size) candidates += (listOf(type.namespace) + type.owners.take(count) + parent).filter { it.isNotBlank() }.joinToString(".")
        type.wildcards.forEach { candidates += "$it.$parent" }
        return candidates.filter { it in roots || it in byQualified }.singleOrNull()
    }
    fun resolves(type: ProviderType, seen: Set<String> = emptySet()): Boolean {
        if (type.qualified in seen) return false
        val parent = parentOf(type) ?: return false
        return parent in roots || byQualified[parent]?.let { resolves(it, seen + type.qualified) } == true
    }
    val providers = declarations.filter { it.name !in ambiguous && resolves(it) }
    return RecipeProviderClassHints(
        all = providers.mapTo(linkedSetOf()) { it.name },
        concrete = providers.filter { !it.abstract }.mapTo(linkedSetOf()) { it.name }
    )
}

internal fun StructuralRefactorPass.collectRecipeSerializerFactoryHints(srcDir: Path): RecipeSerializerFactoryHints {
    val packageByClass = linkedMapOf<String, String>()
    val factoryInterfaceBySerializerClass = linkedMapOf<String, RecipeFactoryInterfaceHint>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
            Regex("""\b(?:class|record|enum)\s+([A-Za-z_$][\w$]*)\b""")
                .findAll(source)
                .forEach { match ->
                    if (packageName != null) {
                        packageByClass.putIfAbsent(match.groupValues[1], "$packageName.${match.groupValues[1]}")
                    }
                }
            collectRecipeSerializerFactoryInterface(source, packageName)?.let { (serializerClass, hint) ->
                factoryInterfaceBySerializerClass[serializerClass] = hint
            }
        }

    val fieldToFactory = linkedMapOf<String, String>()
    val fieldToRecipeClass = linkedMapOf<String, String>()
    val fieldToFactoryInterface = linkedMapOf<String, RecipeFactoryInterfaceHint>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("RecipeSerializer<") && !source.contains("DeferredHolder")) return@forEach
            val owner = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\b""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            Regex(
                """\bDeferredHolder\s*<\s*RecipeSerializer\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*,\s*RecipeSerializer\s*<\s*\1\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
            ).findAll(source).forEach { match ->
                val recipeClass = match.groupValues[1].substringAfterLast('.')
                val factoryClass = packageByClass[recipeClass] ?: match.groupValues[1]
                fieldToFactory["$owner.${match.groupValues[2]}"] = "$factoryClass::new"
                fieldToRecipeClass["$owner.${match.groupValues[2]}"] = factoryClass
            }
            Regex(
                """\bDeferredHolder\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*,\s*\1\s*<\s*\2\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
            ).findAll(source).forEach { match ->
                val serializerClass = match.groupValues[1].substringAfterLast('.')
                val recipeClass = match.groupValues[2].substringAfterLast('.')
                val factoryClass = packageByClass[recipeClass] ?: match.groupValues[2]
                val field = "$owner.${match.groupValues[3]}"
                fieldToFactory[field] = "$factoryClass::new"
                fieldToRecipeClass[field] = factoryClass
                factoryInterfaceBySerializerClass[serializerClass]?.let { fieldToFactoryInterface[field] = it }
            }
            Regex(
                """\bDeferredHolder\s*<\s*RecipeSerializer\s*<\s*\?\s*>\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
            ).findAll(source).forEach { match ->
                val serializerClass = match.groupValues[1].substringAfterLast('.')
                val recipeClass = match.groupValues[2].substringAfterLast('.')
                val factoryClass = packageByClass[recipeClass] ?: match.groupValues[2]
                val field = "$owner.${match.groupValues[3]}"
                fieldToFactory[field] = "$factoryClass::new"
                fieldToRecipeClass[field] = factoryClass
                factoryInterfaceBySerializerClass[serializerClass]?.let { fieldToFactoryInterface[field] = it }
            }
            Regex(
                """\bDeferredHolder\s*<\s*RecipeSerializer\s*<\s*\?\s*>\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*Recipe)\.Serializer\s*>\s+([A-Z][A-Z0-9_]*)\b"""
            ).findAll(source).forEach { match ->
                val recipeClass = match.groupValues[1].substringAfterLast('.')
                val factoryClass = packageByClass[recipeClass] ?: match.groupValues[1]
                val field = "$owner.${match.groupValues[2]}"
                fieldToFactory[field] = "$factoryClass::new"
                fieldToRecipeClass[field] = factoryClass
            }
        }

    return RecipeSerializerFactoryHints(fieldToFactory, fieldToRecipeClass, fieldToFactoryInterface)
}

internal fun StructuralRefactorPass.collectRecipeSerializerFactoryInterface(
    source: String,
    packageName: String?
): Pair<String, RecipeFactoryInterfaceHint>? {
    if (!source.contains("implements RecipeSerializer<")) return null
    val serializerClass = javaTopLevelTypeName(source) ?: return null
    val constructor = Regex(
        """(?s)\bpublic\s+${Regex.escape(serializerClass)}\s*\(([^)]*)\)"""
    ).find(source) ?: return null
    val firstParam = parseJavaParameters(constructor.groupValues[1]).firstOrNull() ?: return null
    val rawFactoryType = firstParam.type
    if (!rawFactoryType.contains("$serializerClass.")) return null
    val nestedFactory = rawFactoryType
        .substringAfter("$serializerClass.")
        .substringBefore('<')
        .trim()
    if (nestedFactory.isBlank()) return null
    val factoryBody = Regex(
        """(?s)\binterface\s+${Regex.escape(nestedFactory)}\b[^{]*\{(.*?)\}"""
    ).find(source)?.groupValues?.get(1) ?: return null
    val createMethod = Regex("""\b[A-Za-z_$][\w$]*(?:\s*<[^;()]+>)?\s+create\s*\(([^)]*)\)\s*;""")
        .find(factoryBody)
        ?: return null
    val params = parseJavaParameters(createMethod.groupValues[1])
    if (params.isEmpty()) return null
    val qualifiedSerializer = if (packageName == null) serializerClass else "$packageName.$serializerClass"
    return serializerClass to RecipeFactoryInterfaceHint(
        type = "$qualifiedSerializer.$nestedFactory<?>",
        params = params
    )
}

internal fun StructuralRefactorPass.collectRecipeTypeHints(srcDir: Path): RecipeTypeHints {
    val qualified = linkedMapOf<String, String>()
    val simpleCandidates = linkedMapOf<String, MutableSet<String>>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("RecipeType<")) return@forEach
            val packageName = packageNameOf(source)
            val imports = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .findAll(source)
                .associate { match -> match.groupValues[1].substringAfterLast('.') to match.groupValues[1] }
            val owner = Regex("""\b(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)\b""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            Regex(
                """\b(?:DeferredHolder|RegistryObject)\s*<\s*(?:RecipeType\s*<\s*\?\s*>\s*,\s*)?RecipeType\s*<\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s*>\s+([A-Z][A-Z0-9_]*)\b"""
            ).findAll(source).forEach { match ->
                val recipeClass = resolveJavaTypeName(match.groupValues[1], packageName, imports)
                val fieldName = match.groupValues[2]
                qualified[normalizeRecipeTypeExpression("$owner.$fieldName.get()")] = recipeClass
                simpleCandidates.getOrPut(normalizeRecipeTypeExpression("$fieldName.get()")) { linkedSetOf() }
                    .add(recipeClass)
            }
        }

    simpleCandidates.forEach { (expression, recipeClasses) ->
        if (recipeClasses.size == 1) {
            qualified[expression] = recipeClasses.single()
        }
    }
    return RecipeTypeHints(qualified)
}

internal fun StructuralRefactorPass.normalizeRecipeTypeExpression(expression: String): String =
    expression.replace(Regex("""\s+"""), "")

internal fun StructuralRefactorPass.resolveJavaTypeName(typeName: String, packageName: String, imports: Map<String, String>): String =
    when {
        "." in typeName -> typeName
        imports.containsKey(typeName) -> imports.getValue(typeName)
        packageName.isNotBlank() -> "$packageName.$typeName"
        else -> typeName
    }

internal fun StructuralRefactorPass.collectHolderValueParameterHints(srcDir: Path): HolderValueParameterHints {
    val soundEventParameters = linkedMapOf<String, MutableSet<Int>>()
    val potionParameters = linkedMapOf<String, MutableSet<Int>>()
    val mobEffectHolderParameters = linkedMapOf<String, MutableSet<Int>>()
    val methodPattern = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private|static|final|abstract|synchronized|default)\s+)*(?:<[^>{};]+>\s*)?(?:[A-Za-z_$][\w$]*[<>\w$., ?\[\]]*\s+)+([A-Za-z_$][\w$]*)\s*\("""
    )
    val constructorPattern = Regex(
        """(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|protected|private)\s+)?([A-Za-z_$][\w$]*)\s*\("""
    )

    fun addHolderParameterHints(methodName: String, paramsText: String) {
        val params = splitTopLevelJavaArgs(paramsText)
        params.forEachIndexed { index, param ->
            val compact = param.replace(Regex("""\s+"""), "")
            if (!compact.contains("Holder<") &&
                !compact.contains("DeferredHolder<") &&
                Regex("""(?:^|[<,\s])SoundEvent(?:[>\s,\[]|$)""").containsMatchIn(param)) {
                soundEventParameters.getOrPut(methodName) { linkedSetOf() }.add(index)
            }
            if (!compact.contains("Holder<") &&
                !compact.contains("DeferredHolder<") &&
                Regex("""(?:^|[<,\s])Potion(?:[>\s,\[]|$)""").containsMatchIn(param)) {
                potionParameters.getOrPut(methodName) { linkedSetOf() }.add(index)
            }
            if (compact.contains("Holder<") &&
                Regex("""(?:^|[<,])(?:net\.minecraft\.world\.effect\.)?MobEffect(?:[>,]|$)""")
                    .containsMatchIn(compact)) {
                mobEffectHolderParameters.getOrPut(methodName) { linkedSetOf() }.add(index)
            }
        }
    }

    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            val declaredTypes = Regex("""\b(?:class|record|enum)\s+([A-Za-z_$][\w$]*)\b""")
                .findAll(maskJavaCommentsAndLiterals(source))
                .map { it.groupValues[1] }
                .toSet()
            methodPattern.findAll(source).forEach { match ->
                val openParen = source.indexOf('(', match.range.last - 1)
                val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
                if (openParen < 0 || closeParen < 0) return@forEach
                val methodName = match.groupValues[1]
                addHolderParameterHints(methodName, source.substring(openParen + 1, closeParen))
            }
            constructorPattern.findAll(source).forEach { match ->
                val constructorName = match.groupValues[1]
                if (constructorName !in declaredTypes) return@forEach
                val openParen = source.indexOf('(', match.range.last - 1)
                val closeParen = if (openParen >= 0) findMatchingParen(source, openParen) else -1
                if (openParen < 0 || closeParen < 0) return@forEach
                addHolderParameterHints(constructorName, source.substring(openParen + 1, closeParen))
            }
        }

    return HolderValueParameterHints(
        soundEventParameters = soundEventParameters.mapValues { it.value.toSet() },
        potionParameters = potionParameters.mapValues { it.value.toSet() },
        mobEffectHolderParameters = mobEffectHolderParameters.mapValues { it.value.toSet() },
        mobEffectDeferredHolderFields = collectDeferredHolderFieldsOf(srcDir, "MobEffect")
    )
}

internal fun StructuralRefactorPass.migrateProjectMobEffectHolderArgumentCallSites(
    srcDir: Path,
    javaFiles: List<Path>,
    dryRun: Boolean
): List<Change> {
    val hints = collectHolderValueParameterHints(srcDir)
    if (hints.mobEffectHolderParameters.isEmpty() || hints.mobEffectDeferredHolderFields.isEmpty()) return emptyList()
    val changes = mutableListOf<Change>()
    javaFiles.forEach { javaFile ->
        val original = javaFile.readText()
        val migrated = migrateHolderValueMethodArgumentSource(original, hints)
        if (migrated != original) {
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Pass proven MobEffect DeferredHolder fields directly to Holder<MobEffect> parameters",
                before = "DeferredHolder<MobEffect, ?>.get() passed to Holder<MobEffect>",
                after = "DeferredHolder<MobEffect, ?> passed directly",
                confidence = Confidence.HIGH,
                ruleId = "struct-mobeffect-holder-deferred-argument"
            ))
            if (!dryRun) javaFile.writeText(migrated)
        }
    }
    return changes
}

internal fun StructuralRefactorPass.collectCriterionInstanceFactoryHints(srcDir: Path): CriterionInstanceFactoryHints {
    val factories = linkedMapOf<String, String>()
    val triggerHolders = linkedMapOf<String, String>()
    val simpleTriggerHolderCandidates = linkedMapOf<String, MutableSet<String>>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            val executableCode = maskJavaCommentsAndLiterals(source)
            val className = classNameOfJavaSource(source) ?: return@forEach
            val packageName = packageNameOf(source)
            if (executableCode.contains("record Instance") && executableCode.contains("Criterion<")) {
                val factory = Regex(
                    """(?m)^[ \t]*public\s+static\s+Criterion\s*<[^>\r\n]+>\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{"""
                ).findAll(executableCode).firstOrNull { match ->
                    val openBrace = executableCode.indexOf('{', match.range.last)
                    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
                    closeBrace > openBrace &&
                        executableCode.substring(openBrace, closeBrace).contains("new Instance(Optional.empty())")
                }?.groupValues?.get(1)
                if (factory != null) {
                    factories["$className.Instance"] = "$className.Instance.$factory()"
                    if (packageName != null) {
                        factories["$packageName.$className.Instance"] = "$packageName.$className.Instance.$factory()"
                    }
                }
            }
            if (executableCode.contains("DeferredHolder") && executableCode.contains("CriterionTrigger")) {
                Regex(
                    """DeferredHolder\s*<\s*CriterionTrigger\s*<\s*\?\s*>\s*,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>\s+([A-Z][A-Z0-9_]*)\s*="""
                ).findAll(executableCode).forEach { match ->
                    val triggerClass = match.groupValues[1].substringAfterLast('.')
                    val holder = if (packageName != null) {
                        "$packageName.$className.${match.groupValues[2]}"
                    } else {
                        "$className.${match.groupValues[2]}"
                    }
                    if (packageName != null) {
                        triggerHolders["$packageName.$triggerClass"] = holder
                    }
                    simpleTriggerHolderCandidates.getOrPut(triggerClass) { linkedSetOf() }.add(holder)
                }
            }
        }
    simpleTriggerHolderCandidates
        .filterValues { it.size == 1 }
        .forEach { (triggerClass, holders) -> triggerHolders[triggerClass] = holders.single() }
    return CriterionInstanceFactoryHints(factories, triggerHolders)
}

internal fun StructuralRefactorPass.collectDeferredRegisterOwners(srcDir: Path): Map<String, DeferredRegisterOwner> {
    val owners = linkedMapOf<String, DeferredRegisterOwner>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("DeferredRegister")) return@forEach
            val className = classNameOfJavaSource(source) ?: return@forEach
            val classBody = javaClassBodyRange(source, className)
            val field = Regex("""\bDeferredRegister\b[^\r\n;=]*\s+([A-Z][A-Z0-9_]*)\s*=""")
                .findAll(source)
                .firstOrNull { classBody == null || isDirectClassBodyMember(source, classBody, it.range.first) }
                ?.groupValues
                ?.get(1)
                ?: return@forEach
            owners[className] = DeferredRegisterOwner(className, field)
        }
    return owners
}

internal fun StructuralRefactorPass.collectDeferredRegisterFields(srcDir: Path): List<DeferredRegisterField> {
    val fields = linkedSetOf<DeferredRegisterField>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("DeferredRegister")) return@forEach
            val className = classNameOfJavaSource(source) ?: return@forEach
            val classBody = javaClassBodyRange(source, className)
            val packageName = packageNameOf(source)
            val qualifiedName = if (packageName.isBlank()) className else "$packageName.$className"
            Regex("""\bDeferredRegister\b[^\r\n;=]*\s+([A-Z][A-Z0-9_]*)\s*=""")
                .findAll(source)
                .filter { classBody == null || isDirectClassBodyMember(source, classBody, it.range.first) }
                .forEach { match ->
                    fields += DeferredRegisterField(className, qualifiedName, match.groupValues[1], javaFile)
                }
        }
    return fields.toList()
}

internal fun StructuralRefactorPass.isDirectClassBodyMember(source: String, classBody: IntRange, index: Int): Boolean {
    var depth = 0
    var i = classBody.first
    while (i < index && i < source.length) {
        when (source[i]) {
            '"' -> i = skipJavaStringLiteral(source, i)
            '\'' -> i = skipJavaCharLiteral(source, i)
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    return depth == 1
}

internal fun StructuralRefactorPass.javaClassBodyRange(source: String, className: String): IntRange? {
    val classPattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|interface|enum|record)\s+${Regex.escape(className)}\b[^{]*\{"""
    )
    val match = classPattern.find(source) ?: return null
    val openBrace = source.indexOf('{', match.range.last)
    val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    return if (openBrace >= 0 && closeBrace > openBrace) openBrace..closeBrace else null
}

internal fun StructuralRefactorPass.collectGlobalLootModifierProviderClasses(srcDir: Path): Set<String> {
    val classes = linkedSetOf<String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("GlobalLootModifierProvider")) return@forEach
            Regex("""\bclass\s+([A-Za-z_$][\w$]*)\s+extends\s+GlobalLootModifierProvider\b""")
                .findAll(source)
                .forEach { classes += it.groupValues[1] }
        }
    return classes
}

internal fun StructuralRefactorPass.collectDeferredHolderRegistryBaseHints(srcDir: Path): Map<String, String> {
    val superByClass = linkedMapOf<String, String>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            Regex("""\bclass\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]+>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)""")
                .findAll(source)
                .forEach { match ->
                    superByClass[match.groupValues[1]] = match.groupValues[2].substringAfterLast('.')
                }
        }
    val rootBases = mapOf(
        "Block" to "Block",
        "BaseEntityBlock" to "Block",
        "BlockAttachedEntity" to "Block",
        "BushBlock" to "Block",
        "ButtonBlock" to "Block",
        "CeilingHangingSignBlock" to "Block",
        "DoorBlock" to "Block",
        "FallingBlock" to "Block",
        "FenceBlock" to "Block",
        "FenceGateBlock" to "Block",
        "FlowerPotBlock" to "Block",
        "HorizontalDirectionalBlock" to "Block",
        "LadderBlock" to "Block",
        "LeavesBlock" to "Block",
        "PipeBlock" to "Block",
        "PressurePlateBlock" to "Block",
        "LiquidBlock" to "Block",
        "RotatedPillarBlock" to "Block",
        "SaplingBlock" to "Block",
        "SignBlock" to "Block",
        "SlabBlock" to "Block",
        "StairBlock" to "Block",
        "StandingSignBlock" to "Block",
        "TrapDoorBlock" to "Block",
        "WallHangingSignBlock" to "Block",
        "WallSignBlock" to "Block",
        "WaterlilyBlock" to "Block",
        "Item" to "Item",
        "ArmorItem" to "Item",
        "BowItem" to "Item",
        "BucketItem" to "Item",
        "BlockItem" to "Item",
        "ItemNameBlockItem" to "Item",
        "CrossbowItem" to "Item",
        "DiggerItem" to "Item",
        "ShieldItem" to "Item",
        "SpawnEggItem" to "Item",
        "SwordItem" to "Item",
        "TieredItem" to "Item",
        "TridentItem" to "Item",
        "FluidType" to "FluidType"
    )
    val resolved = linkedMapOf<String, String>()
    fun resolve(className: String, seen: Set<String> = emptySet()): String? {
        if (className in seen) return null
        rootBases[className]?.let { return it }
        val parent = superByClass[className] ?: return null
        rootBases[parent]?.let { return it }
        return resolve(parent, seen + className)
    }
    superByClass.keys.forEach { className ->
        val base = resolve(className)
        if (base != null && base != className) {
            resolved[className] = base
        }
    }
    return resolved
}

internal fun StructuralRefactorPass.collectDeferredHolderFields(srcDir: Path): Map<String, Set<String>> {
    val fieldsByOwner = linkedMapOf<String, MutableSet<String>>()
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("DeferredHolder")) return@forEach
            val owner = classNameOfJavaSource(source) ?: return@forEach
            Regex(
                """(?m)\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<[^;\r\n=]+>\s+([A-Za-z_$][\w$]*)\b"""
            ).findAll(source)
                .forEach { match ->
                    fieldsByOwner.getOrPut(owner) { linkedSetOf() } += match.groupValues[1]
                }
        }
    return fieldsByOwner
}

internal fun StructuralRefactorPass.collectDeferredHolderFieldsOf(srcDir: Path, registryType: String): Set<String> {
    val fields = linkedSetOf<String>()
    val typePattern = Regex.escape(registryType)
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("DeferredHolder") || !source.contains(registryType)) return@forEach
            val owner = classNameOfJavaSource(source) ?: return@forEach
            val packageName = packageNameOf(source)
            Regex(
                """(?m)\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*$typePattern\s*,[^;\r\n=]+>\s+([A-Za-z_$][\w$]*)\b"""
            ).findAll(source)
                .forEach { match ->
                    val field = match.groupValues[1]
                    fields += "$owner.$field"
                    if (packageName != null) {
                        fields += "$packageName.$owner.$field"
                    }
                }
        }
    return fields
}

internal fun StructuralRefactorPass.collectDeferredHolderTupleFieldsOf(srcDir: Path, registryType: String): Set<String> {
    val fields = linkedSetOf<String>()
    val typePattern = Regex.escape(registryType)
    walkJavaSourceRoots(srcDir)
        .filter { it.toString().endsWith(".java") }
        .forEach { javaFile ->
            val source = javaFile.readText()
            if (!source.contains("Tuple") || !source.contains("DeferredHolder") || !source.contains(registryType)) return@forEach
            val owner = classNameOfJavaSource(source) ?: return@forEach
            val packageName = packageNameOf(source)
            Regex(
                """(?m)\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:net\.minecraft\.util\.)?Tuple\s*<\s*(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*$typePattern\s*,[^>]+>\s*,\s*(?:net\.neoforged\.neoforge\.registries\.)?DeferredHolder\s*<\s*(?:[A-Za-z_$][\w$]*\.)*$typePattern\s*,[^>]+>\s*>\s+([A-Za-z_$][\w$]*)\b"""
            ).findAll(source)
                .forEach { match ->
                    val field = match.groupValues[1]
                    fields += "$owner.$field"
                    if (packageName != null) {
                        fields += "$packageName.$owner.$field"
                    }
                }
        }
    return fields
}

internal fun StructuralRefactorPass.migrateRecordComponentFieldAccessSource(
    source: String,
    recordComponents: Map<String, Set<String>>
): String {
    val componentsByType = recordComponents.toMutableMap()
    componentsByType.putAll(collectJavaRecordComponentsFromSource(source))
    val executableSource = maskJavaCommentsAndLiterals(source)
    val imports = javaNonStaticImports(source)
    ExactExternalTypeContracts.recordComponentSurfaces.forEach { surface ->
        val importedOwner = surface.sourceType.substringBefore('.')
        val simpleType = surface.sourceType.substringAfterLast('.')
        val exactImportedOwner = imports[importedOwner] == surface.importOwner
        val exactQualifiedType = Regex(
            """(?<![\w$])${Regex.escape(surface.qualifiedType)}(?![\w$])"""
        ).containsMatchIn(executableSource)
        if (exactImportedOwner || exactQualifiedType) {
            componentsByType.putIfAbsent(simpleType, surface.components)
        }
    }
    if (componentsByType.isEmpty()) return source
    var result = source
    val localRecordComponents = linkedMapOf<String, Set<String>>()

    componentsByType.forEach { (typeName, components) ->
        val executableResult = maskJavaCommentsAndLiterals(result)
        if (!executableResult.contains(typeName)) return@forEach
        val variables = Regex("""\b(?:final\s+)?(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(typeName)}\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableResult)
            .map { it.groupValues[1] }
            .toMutableSet()
        if (Regex("""\brecord\s+${Regex.escape(typeName)}\s*\(""").containsMatchIn(executableResult)) {
            localRecordComponents[typeName] = components
        }
        variables.forEach { variable ->
            components.forEach { component ->
                result = replaceExecutableRegex(
                    result,
                    Regex("""(?<![.\w$])${Regex.escape(variable)}\.${Regex.escape(component)}\b(?!\s*\()""")
                ) { "$variable.$component()" }
            }
        }
    }

    val recordFieldsByOwner = recordFieldsByOwnerType(source, componentsByType.keys)
    recordFieldsByOwner.forEach { (ownerType, fields) ->
        val executableResult = maskJavaCommentsAndLiterals(result)
        val ownerVariables = Regex("""\b(?:final\s+)?(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(ownerType)}\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executableResult)
            .map { it.groupValues[1] }
            .toSet()
        ownerVariables.forEach { ownerVariable ->
            fields.forEach { (fieldName, recordType) ->
                componentsByType[recordType].orEmpty().forEach { component ->
                    result = replaceExecutableRegex(
                        result,
                        Regex("""(?<![\w$])${Regex.escape(ownerVariable)}\.${Regex.escape(fieldName)}\.${Regex.escape(component)}\b(?!\s*\()""")
                    ) { "$ownerVariable.$fieldName.$component()" }
                }
            }
        }
    }

    localRecordComponents.values.flatten().toSet().forEach { component ->
        result = replaceExecutableRegex(
            result,
            Regex("""(\b[A-Za-z_$][\w$]*\s*->\s*[A-Za-z_$][\w$]*)\.${Regex.escape(component)}\b(?!\s*\()""")
        ) { match -> if (isJavaSwitchCaseArrow(result, match.range.first)) match.value else "${match.groupValues[1]}.$component()" }
        result = replaceExecutableRegex(
            result,
            Regex("""(\(\s*[A-Za-z_$][\w$]*\s*\)\s*->\s*[A-Za-z_$][\w$]*)\.${Regex.escape(component)}\b(?!\s*\()""")
        ) { match -> if (isJavaSwitchCaseArrow(result, match.range.first)) match.value else "${match.groupValues[1]}.$component()" }
    }
    return result
}

internal fun StructuralRefactorPass.isJavaSwitchCaseArrow(source: String, offset: Int): Boolean {
    val lineStart = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    val prefix = source.substring(lineStart, offset).trimEnd()
    return prefix.endsWith("case") || prefix.endsWith("default")
}

internal fun StructuralRefactorPass.recordFieldsByOwnerType(
    source: String,
    recordTypes: Set<String>
): Map<String, List<Pair<String, String>>> {
    if (recordTypes.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, MutableList<Pair<String, String>>>()
    val executableCode = maskJavaCommentsAndLiterals(source)
    collectJavaClassDeclarations(source).forEach { declaration ->
        val body = executableCode.substring(declaration.bodyRange.first + 1, declaration.bodyRange.last)
        Regex(
            """(?m)^[ \t]*(?:@\w+(?:\.\w+)*(?:\([^)]*\))?\s+)*(?:public|protected|private)\s+(?!static\b)(?:final\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s+([A-Za-z_$][\w$]*)\s*(?:=[^;\r\n]*)?;"""
        ).findAll(body).forEach { field ->
            val fieldType = field.groupValues[1].substringAfterLast('.')
            if (fieldType in recordTypes) {
                result.getOrPut(declaration.name) { mutableListOf() } += field.groupValues[2] to fieldType
            }
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateRecordCodecBuilderWitnessSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("RecordCodecBuilder.")) return source
    var result = replaceExecutableRegex(
        source,
        Regex("""\bResourceLocation\.CODEC\.codec\(\)\.listOf\(\)""")
    ) {
        "ResourceLocation.CODEC.listOf()"
    }
    Regex("""\b(?:class|record)\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
        .forEach { typeName ->
            val isBiomeSourceType = Regex("""\bclass\s+${Regex.escape(typeName)}\s+extends\s+(?:[A-Za-z_$][\w$]*\.)?BiomeSource\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(result))
            result = replaceExecutableRegex(
                result,
                Regex("""((?:(?:public|protected|private|static|final)\s+)*(?:(?:[A-Za-z_$][\w$]*\.)*)Codec\s*<\s*${Regex.escape(typeName)}\s*>\s+[A-Za-z_$][\w$]*\s*=\s*)((?:[A-Za-z_$][\w$]*\.)*RecordCodecBuilder)(?:\.<[^>]+>)?\.create\(""")
            ) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}.<$typeName>create("
            }
            result = replaceExecutableRegex(
                result,
                Regex("""((?:(?:public|protected|private|static|final)\s+)*(?:(?:[A-Za-z_$][\w$]*\.)*)MapCodec\s*<\s*${Regex.escape(typeName)}\s*>\s+[A-Za-z_$][\w$]*\s*=\s*)((?:[A-Za-z_$][\w$]*\.)*RecordCodecBuilder)(?:\.<[^>]+>)?\.mapCodec\(""")
            ) { match ->
                if (isBiomeSourceType) {
                    "${match.groupValues[1]}${match.groupValues[2]}.mapCodec("
                } else {
                    "${match.groupValues[1]}${match.groupValues[2]}.<$typeName>mapCodec("
                }
            }
            if (maskJavaCommentsAndLiterals(result).contains("RecordCodecBuilder.<$typeName>") || isBiomeSourceType) {
                // only the getters inside this type's own builder call take its witness: another
                // codec in the same file builds another type
                val builderCall = Regex("""\bRecordCodecBuilder\s*\.\s*(?:<\s*${Regex.escape(typeName)}\s*>\s*)?(?:create|mapCodec)\s*\(""")
                val ownCodec = Regex("""\b(?:Map)?Codec\s*<\s*${Regex.escape(typeName)}\s*>\s+[A-Za-z_$][\w$]*\s*=\s*$""")
                val masked = maskJavaCommentsAndLiterals(result)
                val ranges = builderCall.findAll(masked)
                    .filter { call ->
                        masked.substring(call.range.first).startsWith("RecordCodecBuilder.<$typeName>") ||
                            ownCodec.containsMatchIn(masked.substring(maxOf(0, call.range.first - 200), call.range.first))
                    }
                    .mapNotNull { call -> findMatchingParen(masked, call.range.last).takeIf { it > 0 }?.let { call.range.last..it } }
                    .toList()
                for (range in ranges.asReversed()) {
                    val inside = Regex("""\.forGetter\(\s*([A-Za-z_$][\w$]*)\s*->\s*\1\.""")
                        .replace(result.substring(range)) { match -> ".forGetter(($typeName ${match.groupValues[1]}) -> ${match.groupValues[1]}." }
                    result = result.replaceRange(range, inside)
                }
            }
            if (isBiomeSourceType) {
                result = replaceExecutableRegex(
                    result,
                    Regex("""\bRecordCodecBuilder\.<${Regex.escape(typeName)}>mapCodec\(""")
                ) { "RecordCodecBuilder.mapCodec(" }
            }
        }
    if (maskJavaCommentsAndLiterals(result).contains("extends BiomeSource") &&
        maskJavaCommentsAndLiterals(result).contains("RecordCodecBuilder.<")) {
        result = replaceExecutableRegex(
            result,
            Regex("""RecordCodecBuilder\s*\.\s*<\s*([A-Za-z_$][\w$]*)\s*>\s*mapCodec\(""")
        ) { match ->
                val typeName = match.groupValues[1]
                val typeExtendsBiomeSource = Regex(
                    """\bclass\s+${Regex.escape(typeName)}\b[^{;]*\bextends\s+(?:[A-Za-z_$][\w$]*\.)?BiomeSource\b"""
                ).containsMatchIn(maskJavaCommentsAndLiterals(result))
                if (typeExtendsBiomeSource) "RecordCodecBuilder.mapCodec(" else match.value
            }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyTickEventSource(source: String): String {
    validateLegacyTickPhaseValueUses(source)
    var result = migrateUnsplitTickEventHandlers(source)
    var needsEntityTickImport = false

    val livingTickHandlerPattern = Regex(
        """\b((?:(?:public|protected|private|static|final|synchronized)\s+)*void\s+([A-Za-z_$][\w$]*)\s*\(\s*)((?:net\.(?:minecraftforge|neoforged\.neoforge)\.event\.entity\.living\.)?(?:LivingEvent\.)?LivingTickEvent)(\s+([A-Za-z_$][\w$]*)\s*\))"""
    )
    val livingTickTyping = SourceValueTypes(this, source)
    val migratedEntityTickHandlers = linkedSetOf<String>()
    var cursor = 0
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = livingTickHandlerPattern.find(executableResult, cursor) ?: break
        val openBrace = result.indexOf('{', match.range.last + 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val originalType = match.groupValues[3]
        val qualified = originalType.startsWith("net.")
        val exactOwner = setOf(
            "net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent",
            "net.neoforged.neoforge.event.entity.living.LivingEvent.LivingTickEvent"
        ).singleOrNull { livingTickTyping.isTypeName(originalType, it) }
        if (exactOwner == null) {
            cursor = match.range.last + 1
            continue
        }
        val methodName = match.groupValues[2]
        val importsEntityBefore = needsEntityTickImport
        needsEntityTickImport = true
        val replacementType = "EntityTickEvent.Pre"
        migratedEntityTickHandlers += methodName
        if (qualified) {
            needsEntityTickImport = importsEntityBefore
        }
        val qualifier = if (qualified) "net.neoforged.neoforge.event.tick." else ""
        val replacement = "${match.groupValues[1]}$qualifier$replacementType${match.groupValues[4]}"
        result = result.substring(0, match.range.first) + replacement + result.substring(match.range.last + 1)
        cursor = match.range.first + replacement.length
    }

    if (needsEntityTickImport) {
        result = addImportIfMissing(result, "net.neoforged.neoforge.event.tick.EntityTickEvent")
    }
    if (!Regex("""(?<![\w$.])LivingTickEvent(?![\w$])""").containsMatchIn(result.replace(Regex("""(?m)^import .*$"""), ""))) {
        result = removeImport(result, "net.neoforged.neoforge.event.entity.living.LivingEvent.LivingTickEvent")
        result = removeImport(result, "net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent")
    }
    if (!result.contains("LivingEvent.")) {
        result = removeImport(result, "net.neoforged.neoforge.event.entity.living.LivingEvent")
        result = removeImport(result, "net.minecraftforge.event.entity.living.LivingEvent")
    }

    result = guardMigratedLivingTickEntityHandlers(result, migratedEntityTickHandlers)

    if (result.contains("EntityTickEvent.Post") || result.contains("EntityTickEvent.Pre")) {
        result = Regex(
            """(?m)^([ \t]*)if\s*\(\s*(\w+)\.getEntity\(\)\.level\(\)\.isClientSide\(\)\s*\)\s*return;\s*\r?\n[ \t]*LivingEntity\s+(\w+)\s*=\s*\2\.getEntity\(\)\s*;"""
        ).replace(result) { match ->
            val indent = match.groupValues[1]
            val eventName = match.groupValues[2]
            val entityName = match.groupValues[3]
            "${indent}if (!($eventName.getEntity() instanceof LivingEntity $entityName)) return;\n" +
                "${indent}if ($entityName.level().isClientSide()) return;"
        }

        result = Regex(
            """(?m)^([ \t]*)LivingEntity\s+(\w+)\s*=\s*(\w+)\.getEntity\(\)\s*;"""
        ).replace(result) { match ->
            val indent = match.groupValues[1]
            val entityName = match.groupValues[2]
            val eventName = match.groupValues[3]
            "${indent}if (!($eventName.getEntity() instanceof LivingEntity $entityName)) return;"
        }
    }

    result = removeLegacyTickPhaseChecks(result)
    result = migrateSplitPlayerTickSideChecks(result)
    result = migrateSplitLevelTickSideChecks(result)
    result = removeInvalidTickEventImports(result)
    result = migratePlayerTickEventPlayerArguments(result)

    return result
}

internal fun StructuralRefactorPass.guardMigratedLivingTickEntityHandlers(source: String, methodNames: Set<String>): String {
    if (methodNames.isEmpty()) return source
    var result = source
    var changed = false
    var cursor = 0
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val method = javaMethodRanges(executableResult)
            .firstOrNull { it.range.first >= cursor && it.name in methodNames }
            ?: break
        val header = executableResult.substring(method.range.first, result.indexOf('{', method.range.first).coerceAtLeast(method.range.first))
        val eventName = Regex("""\bEntityTickEvent\.(?:Post|Pre)\s+([A-Za-z_$][\w$]*)\b""")
            .find(header)
            ?.groupValues
            ?.get(1)
        if (eventName == null) {
            cursor = method.range.last + 1
            continue
        }
        val openBrace = result.indexOf('{', method.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace <= openBrace) {
            cursor = method.range.last + 1
            continue
        }
        val body = result.substring(openBrace + 1, closeBrace)
        val executableBody = executableResult.substring(openBrace + 1, closeBrace)
        if (Regex("""instanceof\s+LivingEntity\b""").containsMatchIn(executableBody)) {
            cursor = closeBrace + 1
            continue
        }
        val directAssignment = Regex(
            """(?m)^([ \t]*)(?:LivingEntity|var)\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(eventName)}\.getEntity\(\)\s*;\s*\r?\n?"""
        ).find(executableBody)
        val localName = directAssignment?.groupValues?.get(2)
            ?: uniqueJavaLocalName(executableBody, "modporterLivingEntity")
        val bodyIndent = directAssignment?.groupValues?.get(1)
            ?: Regex("""(?m)^([ \t]*)\S""").find(body)?.groupValues?.get(1)
            ?: ((result.substring(method.range.first, openBrace).lineSequence().lastOrNull()?.takeWhile { it == ' ' || it == '\t' } ?: "") + "    ")
        val guardLine = "${bodyIndent}if (!($eventName.getEntity() instanceof LivingEntity $localName)) return;\n"
        val rewrittenBody = if (directAssignment != null) {
            val prefix = body.substring(0, directAssignment.range.first)
            val suffix = body.substring(directAssignment.range.last + 1)
            val rewrittenSuffix = replaceExecutableRegex(
                suffix,
                Regex("""\b${Regex.escape(eventName)}\.getEntity\s*\(\s*\)""")
            ) { localName }
            prefix + guardLine + rewrittenSuffix
        } else {
            val rewrittenOriginalBody = replaceExecutableRegex(
                body,
                Regex("""\b${Regex.escape(eventName)}\.getEntity\s*\(\s*\)""")
            ) { localName }
            "\n$guardLine$rewrittenOriginalBody"
        }
        result = result.substring(0, openBrace + 1) + rewrittenBody + result.substring(closeBrace)
        changed = true
        cursor = openBrace + 1 + rewrittenBody.length
    }
    return if (changed) addImportIfMissing(result, "net.minecraft.world.entity.LivingEntity") else source
}

internal fun StructuralRefactorPass.migratePlayerTickEventPlayerArguments(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("PlayerTickEvent.Post") || !executableCode.contains(".tick(player)")) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    val eventParameterPattern = Regex("""\bPlayerTickEvent\.Post\s+([A-Za-z_$][\w$]*)\b""")
    val tickPlayerPattern = Regex("""\b([A-Za-z_$][\w$.]*)\.tick\(player\);""")

    javaMethodRanges(executableCode).forEach { method ->
        val methodText = executableCode.substring(method.range)
        val eventName = eventParameterPattern.find(methodText)?.groupValues?.get(1) ?: return@forEach
        tickPlayerPattern.findAll(executableCode, method.range.first)
            .takeWhile { it.range.last <= method.range.last }
            .forEach { match ->
                edits += match.range to "${match.groupValues[1]}.tick($eventName.getEntity());"
            }
    }
    return applyStringEdits(source, edits)
}

internal fun StructuralRefactorPass.migrateSplitPlayerTickSideChecks(source: String): String {
    if (!source.contains("PlayerTickEvent") || (!source.contains(".side") && !source.contains(".phase"))) return source
    val edits = mutableListOf<Pair<IntRange, String>>()
    val eventPattern = Regex("""\bPlayerTickEvent\.(Post|Pre)\s+([A-Za-z_$][\w$]*)""")
    javaMethodRangesIncludingDefault(source).forEach { method ->
        val methodText = source.substring(method.range)
        val executableMethod = maskJavaCommentsAndLiterals(methodText)
        val eventMatch = eventPattern.find(executableMethod) ?: return@forEach
        val phaseClass = eventMatch.groupValues[1]
        val eventName = eventMatch.groupValues[2]
        val event = Regex.escape(eventName)
        val entitySide = "$eventName.getEntity().level().isClientSide()"
        var replacement = methodText
        replacement = replaceExecutableRegex(replacement, Regex("""\b$event\.side\.isClient\(\)""")) { entitySide }
        replacement = replaceExecutableRegex(replacement, Regex("""\b$event\.side\s*==\s*LogicalSide\.CLIENT\b""")) { entitySide }
        replacement = replaceExecutableRegex(replacement, Regex("""\bLogicalSide\.CLIENT\s*==\s*$event\.side\b""")) { entitySide }
        replacement = replaceExecutableRegex(replacement, Regex("""\b$event\.side\s*==\s*LogicalSide\.SERVER\b""")) { "!$entitySide" }
        replacement = replaceExecutableRegex(replacement, Regex("""\bLogicalSide\.SERVER\s*==\s*$event\.side\b""")) { "!$entitySide" }
        replacement = replaceExecutableRegex(replacement, Regex("""\b$event\.side\s*!=\s*LogicalSide\.SERVER\b""")) { entitySide }
        replacement = replaceExecutableRegex(replacement, Regex("""\bLogicalSide\.SERVER\s*!=\s*$event\.side\b""")) { entitySide }

        val discardedPhase = if (phaseClass == "Post") "START" else "END"
        val discarded = Regex.escape(discardedPhase)
        replacement = Regex(
            """if\s*\(\s*([^{}\r\n;]+?)\s*\|\|\s*$event\.phase\s*==\s*TickEvent\.Phase\.$discarded\s*\)\s*\{\s*return;\s*}"""
        ).replace(replacement) { match -> "if (${match.groupValues[1].trim()}) {\n            return;\n        }" }
        replacement = Regex(
            """if\s*\(\s*$event\.phase\s*==\s*TickEvent\.Phase\.$discarded\s*\|\|\s*([^{}\r\n;]+?)\s*\)\s*\{\s*return;\s*}"""
        ).replace(replacement) { match -> "if (${match.groupValues[1].trim()}) {\n            return;\n        }" }
        replacement = Regex(
            """(?m)^[ \t]*if\s*\(\s*$event\.phase\s*==\s*TickEvent\.Phase\.$discarded\s*\)\s*\{\s*return;\s*}\s*\r?\n?"""
        ).replace(replacement, "")
        if (replacement != methodText) {
            edits += method.range to replacement
        }
    }
    var result = applyStringEdits(source, edits)
    return removeUnusedSimpleImports(
        result,
        listOf(
            "net.neoforged.fml.LogicalSide",
            "net.minecraftforge.fml.LogicalSide"
        )
    )
}

internal fun StructuralRefactorPass.migrateSplitLevelTickSideChecks(source: String): String {
    if (!source.contains("LevelTickEvent") || !source.contains(".side")) return source
    var result = source
    val executableCode = maskJavaCommentsAndLiterals(source)
    val levelTickEventNames = Regex("""\bLevelTickEvent\.(?:Pre|Post)\s+([A-Za-z_$][\w$]*)""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    levelTickEventNames.forEach { eventName ->
        val event = Regex.escape(eventName)
        result = replaceExecutableRegex(
            result,
            Regex("""\b$event\.side\s*==\s*LogicalSide\.SERVER\b""")
        ) { "!$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\bLogicalSide\.SERVER\s*==\s*$event\.side\b""")
        ) { "!$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b$event\.side\s*!=\s*LogicalSide\.SERVER\b""")
        ) { "$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\bLogicalSide\.SERVER\s*!=\s*$event\.side\b""")
        ) { "$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b$event\.side\s*==\s*LogicalSide\.CLIENT\b""")
        ) { "$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\bLogicalSide\.CLIENT\s*==\s*$event\.side\b""")
        ) { "$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b$event\.side\s*!=\s*LogicalSide\.CLIENT\b""")
        ) { "!$eventName.getLevel().isClientSide()" }
        result = replaceExecutableRegex(
            result,
            Regex("""\bLogicalSide\.CLIENT\s*!=\s*$event\.side\b""")
        ) { "!$eventName.getLevel().isClientSide()" }
    }
    return removeUnusedSimpleImports(
        result,
        listOf(
            "net.neoforged.fml.LogicalSide",
            "net.minecraftforge.fml.LogicalSide"
        )
    )
}

internal fun StructuralRefactorPass.removeLegacyTickPhaseChecks(source: String): String {
    var result = source
    // `import ...TickEvent.Phase;` lets a source write Phase.START; spell it out first.
    val phaseImport = Regex("""(?m)^import\s+net\.(?:minecraftforge|neoforged\.neoforge)\.event\.(?:tick\.)?TickEvent\.Phase;[ \t]*\r?\n""")
    val executableSource = maskJavaCommentsAndLiterals(source)
    // The text pass replaces the legacy TickEvent import with the split target imports. A file
    // that neither imports nor declares any TickEvent can then only mean the legacy type by a
    // remaining TickEvent.Phase token.
    val tickEventUnbound =
        !Regex("""(?m)^\s*import\s+(?:static\s+)?[\w.]*\bTickEvent(?:\.[\w*]+)*\s*;""")
            .containsMatchIn(executableSource) &&
            !Regex("""\b(?:class|interface|enum|record)\s+TickEvent\b""")
                .containsMatchIn(executableSource)
    val hasBoundLegacyPhase = phaseImport.containsMatchIn(executableSource) || Regex(
        """(?m)^\s*import\s+net\.(?:minecraftforge|neoforged\.neoforge)\.event\.(?:tick\.)?TickEvent\s*;"""
    ).containsMatchIn(executableSource) || tickEventUnbound
    if (phaseImport.containsMatchIn(executableSource)) {
        result = replaceExecutableRegex(result, phaseImport) { "" }
        result = replaceExecutableRegex(result, Regex("""(?<![\w$.])Phase\.(START|END)(?![\w$])""")) { match ->
            "TickEvent.Phase.${match.groupValues[1]}"
        }
    }
    result = Regex(
        """(?m)^[ \t]*//.*TickEvent\.Phase\.(?:END|START).*\r?\n[ \t]*//\s*phase check removed in NeoForge\s*\r?\n?""",
        RegexOption.IGNORE_CASE
    ).replace(result, "")
    result = Regex(
        """(?m)^[ \t]*//.*Phase check removed.*\r?\n""",
        RegexOption.IGNORE_CASE
    ).replace(result, "")
    return removeSplitTickPhaseChecksInTypedMethods(result, hasBoundLegacyPhase)
}

internal fun StructuralRefactorPass.splitTickEventParameterNames(source: String, phaseClass: String): Set<String> {
    val splitTypes = "(?:ClientTickEvent|ServerTickEvent|LevelTickEvent|PlayerTickEvent|EntityTickEvent|RenderFrameEvent)"
    return Regex("""\b$splitTypes\.${Regex.escape(phaseClass)}\s+([A-Za-z_$][\w$]*)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()
}

internal fun StructuralRefactorPass.removeSplitTickPhaseChecksForEventName(source: String, eventName: String, phase: String): String {
    val event = Regex.escape(eventName)
    val phaseToken = Regex.escape(phase)
    val otherPhaseToken = if (phase == "END") "START" else "END"
    var result = source
    result = replaceExecutableRegex(result, Regex(
        """(?m)^[ \t]*if\s*\(\s*$event\.phase\s*!=\s*TickEvent\.Phase\.$phaseToken\s*\)\s*return;\s*\r?\n"""
    )) { "" }
    // "if (phase == START) return;" in a Post handler (and the END twin in a Pre handler) is the
    // same guard written the other way round.
    result = replaceExecutableRegex(result, Regex(
        """(?m)^[ \t]*if\s*\(\s*$event\.phase\s*==\s*TickEvent\.Phase\.$otherPhaseToken\s*\)\s*(?:\{\s*return;\s*\}|return;)[ \t]*\r?\n"""
    )) { "" }
    result = replaceExecutableRegex(result, Regex(
        """if\s*\(\s*$event\.phase\s*!=\s*TickEvent\.Phase\.$phaseToken\s*\|\|\s*([^{}\r\n;]+?)\s*\)\s*\{"""
    )) { match -> "if (${match.groupValues[1].trim()}) {" }
    result = replaceExecutableRegex(result, Regex(
        """if\s*\(\s*([^{}\r\n;]+?)\s*\|\|\s*$event\.phase\s*!=\s*TickEvent\.Phase\.$phaseToken\s*\)\s*\{"""
    )) { match -> "if (${match.groupValues[1].trim()}) {" }
    result = replaceExecutableRegex(result, Regex(
        """if\s*\(\s*$event\.phase\s*==\s*TickEvent\.Phase\.$phaseToken\s*&&\s*([^{}\r\n;]+?)\s*\)\s*\{"""
    )) { match -> "if (${match.groupValues[1].trim()}) {" }
    result = replaceExecutableRegex(result, Regex(
        """if\s*\(\s*([^{}\r\n;]+?)\s*&&\s*$event\.phase\s*==\s*TickEvent\.Phase\.$phaseToken\s*\)\s*\{"""
    )) { match -> "if (${match.groupValues[1].trim()}) {" }
    result = replaceExecutableRegex(
        result,
        Regex("""if\s*\(\s*$event\.phase\s*==\s*TickEvent\.Phase\.$phaseToken\s*\)\s*\{""")
    ) { "{" }
    result = replaceExecutableRegex(
        result,
        Regex("""$event\.phase\s*==\s*TickEvent\.Phase\.$phaseToken\s*&&\s*""")
    ) { "" }
    result = replaceExecutableRegex(
        result,
        Regex("""\s*&&\s*$event\.phase\s*==\s*TickEvent\.Phase\.$phaseToken""")
    ) { "" }
    return result
}

private fun StructuralRefactorPass.removeSplitTickPhaseChecksInTypedMethods(
    source: String,
    hasBoundLegacyPhase: Boolean
): String {
    if (!hasBoundLegacyPhase || !source.contains(".phase")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    val typing = SourceValueTypes(this, source)
    val splitOwners = listOf(
        "net.neoforged.neoforge.client.event.ClientTickEvent",
        "net.neoforged.neoforge.client.event.RenderFrameEvent",
        "net.neoforged.neoforge.event.tick.ServerTickEvent",
        "net.neoforged.neoforge.event.tick.LevelTickEvent",
        "net.neoforged.neoforge.event.tick.PlayerTickEvent",
        "net.neoforged.neoforge.event.tick.EntityTickEvent"
    )
    val lineStarts = buildList {
        add(0)
        source.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
    }
    fun offset(line: Int, column: Int): Int = lineStarts[line - 1] + column - 1

    var result = source
    val edits = unit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
        if (method.findAncestor(MethodDeclaration::class.java).isPresent) return@mapNotNull null
        val parameter = method.parameters.singleOrNull() ?: return@mapNotNull null
        val selectedPhase = when {
            splitOwners.any { typing.isTypeName(parameter.typeAsString, "$it.Post") } -> "END"
            splitOwners.any { typing.isTypeName(parameter.typeAsString, "$it.Pre") } -> "START"
            else -> return@mapNotNull null
        }
        val body = method.body.orElse(null) ?: return@mapNotNull null
        val phaseUses = body.findAll(FieldAccessExpr::class.java).filter {
            it.nameAsString == "phase" && it.scope.toString() == parameter.nameAsString
        }
        if (phaseUses.isEmpty() || phaseUses.any { use ->
                use.findAncestor(MethodDeclaration::class.java).orElse(null) !== method ||
                    use.findAncestor(LambdaExpr::class.java).isPresent
            }) {
            return@mapNotNull null
        }
        val range = method.range.orElse(null) ?: return@mapNotNull null
        val start = offset(range.begin.line, range.begin.column)
        val endExclusive = offset(range.end.line, range.end.column) + 1
        val original = source.substring(start, endExclusive)
        val migrated = removeSplitTickPhaseChecksForEventName(
            original,
            parameter.nameAsString,
            selectedPhase
        )
        if (migrated == original) null else Triple(start, endExclusive, migrated)
    }.sortedByDescending { it.first }
    for ((start, endExclusive, replacement) in edits) {
        result = result.replaceRange(start, endExclusive, replacement)
    }
    return result
}

internal fun StructuralRefactorPass.removeInvalidTickEventImports(source: String): String {
    var result = source
    result = removeImport(result, "net.minecraftforge.event.TickEvent")
    result = removeImport(result, "net.neoforged.neoforge.event.TickEvent")
    result = removeImport(result, "net.neoforged.neoforge.event.tick.TickEvent")
    return result
}

internal fun StructuralRefactorPass.migrateBlockEntitySerializationDeclarationSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("saveAdditional") &&
        !source.contains("loadAdditional") &&
        !source.contains("getUpdateTag") &&
        !source.contains("handleUpdateTag") &&
        !source.contains("onDataPacket") &&
        !Regex("""public\s+(?:final\s+)?void\s+load\s*\(""").containsMatchIn(source)
    ) {
        return source
    }
    if (collectJavaClassDeclarations(source).none {
            javaClassExtendsAny(it, javaBlockEntityBaseTypes(), javaInheritanceIndex)
        }
    ) {
        return source
    }

    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""((?:public|protected)\s+(?:final\s+)?)void\s+saveAdditional\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
        val params = match.groupValues[2]
        if (params.contains("HolderLookup.Provider")) {
            match.value
        } else {
            val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
            "${match.groupValues[1]}void saveAdditional($params, HolderLookup.Provider $providerName)"
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(protected\s+(?:final\s+)?)void\s+loadAdditional\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
        val params = match.groupValues[2]
        if (params.contains("HolderLookup.Provider")) {
            match.value
        } else {
            val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
            "${match.groupValues[1]}void loadAdditional($params, HolderLookup.Provider $providerName)"
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(public\s+(?:final\s+)?)void\s+load\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
        if (!javaClassExtendsAny(
                enclosingJavaClassDeclaration(result, match.range.first),
                javaBlockEntityBaseTypes(),
                javaInheritanceIndex
            )
        ) {
            match.value
        } else {
            val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
            "${match.groupValues[1].replace("public", "protected")}void loadAdditional(${match.groupValues[2]}, HolderLookup.Provider $providerName)"
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""public\s+((?:@[A-Za-z0-9_.]+\s+)*)(?:net\.minecraft\.nbt\.)?CompoundTag\s+getUpdateTag\s*\(\s*\)""")
    ) { match ->
        val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
        "public ${match.groupValues[1]}CompoundTag getUpdateTag(HolderLookup.Provider $providerName)"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""public\s+void\s+handleUpdateTag\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
        val params = match.groupValues[1]
        if (params.contains("HolderLookup.Provider")) {
            match.value
        } else {
            val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "lookupProvider")
            "public void handleUpdateTag($params, HolderLookup.Provider $providerName)"
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex(
            """public\s+void\s+onDataPacket\s*\(\s*([^,)]*(?:net\.minecraft\.network\.)?Connection\s+)(\w+)\s*,\s*([^,)]*(?:net\.minecraft\.network\.protocol\.game\.)?ClientboundBlockEntityDataPacket\s+)(\w+)\s*\)"""
        )
    ) { match ->
        val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "lookupProvider")
        "public void onDataPacket(${match.groupValues[1]}${match.groupValues[2]}, ${match.groupValues[3]}${match.groupValues[4]}, HolderLookup.Provider $providerName)"
    }
    return if (result == source) result else addImportIfMissing(result, "net.minecraft.core.HolderLookup")
}

internal fun StructuralRefactorPass.migrateBlockEntitySerializationSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex = JavaInheritanceIndex.EMPTY
): String {
    if (!source.contains("getUpdateTag") &&
        !source.contains("onDataPacket") &&
        !source.contains("saveAdditional") &&
        !source.contains("loadAdditional") &&
        !Regex("""public\s+(?:final\s+)?void\s+load\s*\(""").containsMatchIn(source)
    ) {
        return source
    }
    val blockEntityClass = collectJavaClassDeclarations(source)
        .any { javaClassExtendsAny(it, javaBlockEntityBaseTypes(), javaInheritanceIndex) }
    if (!blockEntityClass) return source

    var result = source
    var needsHolderLookup = false

    result = replaceExecutableRegex(
        result,
        Regex("""((?:public|protected)\s+(?:final\s+)?)void\s+saveAdditional\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
            val modifiers = match.groupValues[1]
            val params = match.groupValues[2]
            if (params.contains("HolderLookup.Provider")) {
                match.value
            } else {
                needsHolderLookup = true
                val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
                "${modifiers}void saveAdditional($params, HolderLookup.Provider $providerName)"
            }
        }

    result = replaceExecutableRegex(
        result,
        Regex("""(protected\s+(?:final\s+)?)void\s+loadAdditional\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
            val modifiers = match.groupValues[1]
            val params = match.groupValues[2]
            if (params.contains("HolderLookup.Provider")) {
                match.value
            } else {
                needsHolderLookup = true
                val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
                "${modifiers}void loadAdditional($params, HolderLookup.Provider $providerName)"
            }
        }

    result = replaceExecutableRegex(
        result,
        Regex("""(public\s+(?:final\s+)?)void\s+load\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
            if (!javaClassExtendsAny(
                    enclosingJavaClassDeclaration(result, match.range.first),
                    javaBlockEntityBaseTypes(),
                    javaInheritanceIndex
                )
            ) {
                return@replaceExecutableRegex match.value
            }
            val modifiers = match.groupValues[1]
            val params = match.groupValues[2]
            needsHolderLookup = true
            val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
            "${modifiers.replace("public", "protected")}void loadAdditional($params, HolderLookup.Provider $providerName)"
        }

    result = replaceExecutableRegex(
        result,
        Regex("""super\.load\s*\(\s*(\w+)\s*\)\s*;""")
    ) { match ->
            if (!javaClassExtendsAny(
                    enclosingJavaClassDeclaration(result, match.range.first),
                    javaBlockEntityBaseTypes(),
                    javaInheritanceIndex
                )
            ) {
                return@replaceExecutableRegex match.value
            }
            needsHolderLookup = true
            val providerName = exactHolderLookupProviderNameAtOrNull(
                result,
                match.range.first,
                "BlockEntity super.load migration"
            ) ?: return@replaceExecutableRegex match.value
            "super.loadAdditional(${match.groupValues[1]}, $providerName);"
        }

    result = replaceExecutableRegex(
        result,
        Regex("""super\.saveAdditional\s*\(\s*(\w+)\s*\)\s*;""")
    ) { match ->
            if (!javaClassExtendsAny(
                    enclosingJavaClassDeclaration(result, match.range.first),
                    javaBlockEntityBaseTypes(),
                    javaInheritanceIndex
                )
            ) {
                return@replaceExecutableRegex match.value
            }
            needsHolderLookup = true
            val providerName = exactHolderLookupProviderNameAtOrNull(
                result,
                match.range.first,
                "BlockEntity super.saveAdditional migration"
            ) ?: return@replaceExecutableRegex match.value
            "super.saveAdditional(${match.groupValues[1]}, $providerName);"
        }

    result = replaceExecutableRegex(
        result,
        Regex("""public\s+((?:@[A-Za-z0-9_.]+\s+)*)(?:net\.minecraft\.nbt\.)?CompoundTag\s+getUpdateTag\s*\(\s*\)""")
    ) { match ->
            needsHolderLookup = true
            val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "registries")
            "public ${match.groupValues[1]}CompoundTag getUpdateTag(HolderLookup.Provider $providerName)"
        }
    result = replaceExecutableRegex(result, Regex("""super\.getUpdateTag\(\)""")) { match ->
        if (enclosingJavaMethodNameAt(result, match.range.first) != "getUpdateTag") {
            return@replaceExecutableRegex match.value
        }
        val providerName = requiredHolderLookupProviderNameAt(
            result,
            match.range.first,
            "BlockEntity getUpdateTag super call migration"
        )
        "super.getUpdateTag($providerName)"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""saveAdditional\(\s*(\w+)\s*\)\s*;""")
    ) { match ->
        if (enclosingJavaMethodNameAt(result, match.range.first) != "getUpdateTag") {
            return@replaceExecutableRegex match.value
        }
        val providerName = requiredHolderLookupProviderNameAt(
            result,
            match.range.first,
            "BlockEntity getUpdateTag saveAdditional migration"
        )
        "saveAdditional(${match.groupValues[1]}, $providerName);"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\.saveWithoutMetadata\(net\.minecraft\.core\.RegistryAccess\.EMPTY\)""")
    ) { match ->
        if (enclosingJavaMethodNameAt(result, match.range.first) != "getUpdateTag") {
            return@replaceExecutableRegex match.value
        }
        val providerName = requiredHolderLookupProviderNameAt(
            result,
            match.range.first,
            "BlockEntity getUpdateTag saveWithoutMetadata migration"
        )
        ".saveWithoutMetadata($providerName)"
    }

    result = replaceExecutableRegex(
        result,
        Regex("""public\s+void\s+handleUpdateTag\s*\(\s*([^)]*CompoundTag\s+\w+)\s*\)""")
    ) { match ->
            val params = match.groupValues[1]
            if (params.contains("HolderLookup.Provider")) {
                match.value
            } else {
                needsHolderLookup = true
                val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "lookupProvider")
                "public void handleUpdateTag($params, HolderLookup.Provider $providerName)"
            }
        }
    result = replaceExecutableRegex(
        result,
        Regex("""super\.handleUpdateTag\s*\(\s*(\w+)\s*\)\s*;""")
    ) { match ->
            if (enclosingJavaMethodNameAt(result, match.range.first) != "handleUpdateTag") {
                return@replaceExecutableRegex match.value
            }
            needsHolderLookup = true
            val providerName = requiredHolderLookupProviderNameAt(
                result,
                match.range.first,
                "BlockEntity handleUpdateTag super call migration"
            )
            "super.handleUpdateTag(${match.groupValues[1]}, $providerName);"
        }
    javaMethodText(result, "handleUpdateTag")?.let { method ->
        val providerName = holderLookupProviderFromParameters(currentMethodParametersBeforeOffset(method, method.indexOf('{')))
            ?: throw IllegalStateException(
                "BlockEntity handleUpdateTag migration requires exactly one HolderLookup.Provider parameter"
            )
        val migratedMethod = replaceExecutableRegex(
            method,
            Regex("""\b(?:this\.)?load\s*\(\s*(\w+)\s*\)\s*;""")
        ) { match ->
                needsHolderLookup = true
                "this.loadAdditional(${match.groupValues[1]}, $providerName);"
            }
        if (migratedMethod != method) {
            val methodStart = result.indexOf(method)
            if (methodStart >= 0) {
                result = result.substring(0, methodStart) + migratedMethod + result.substring(methodStart + method.length)
            }
        }
    }

    result = replaceExecutableRegex(
        result,
        Regex(
            """public\s+void\s+onDataPacket\s*\(\s*([^,)]*(?:net\.minecraft\.network\.)?Connection\s+)(\w+)\s*,\s*([^,)]*(?:net\.minecraft\.network\.protocol\.game\.)?ClientboundBlockEntityDataPacket\s+)(\w+)\s*\)"""
        )
    ) { match ->
        needsHolderLookup = true
        val providerName = uniqueHolderLookupProviderNameForMethodAt(result, match.range.first, "lookupProvider")
        "public void onDataPacket(${match.groupValues[1]}${match.groupValues[2]}, ${match.groupValues[3]}${match.groupValues[4]}, HolderLookup.Provider $providerName)"
    }

    val packetName = Regex("""onDataPacket\s*\([^)]*ClientboundBlockEntityDataPacket\s+(\w+)""")
        .find(maskJavaCommentsAndLiterals(result))
        ?.groupValues
        ?.get(1)
    if (packetName != null && maskJavaCommentsAndLiterals(result).contains("$packetName.getOrDefault(")) {
        result = replaceExecutableRegex(
            result,
            Regex("""(?:net\.minecraft\.nbt\.)?CompoundTag\s+(\w+)\s*=\s*${Regex.escape(packetName)}\.getOrDefault\([^\r\n;]+CustomData\.EMPTY\)\.copyTag\(\)\s*;""")
        ) { match ->
                "CompoundTag ${match.groupValues[1]} = $packetName.getTag();"
            }
    }
    javaMethodText(result, "onDataPacket")?.let { method ->
        val providerName = holderLookupProviderFromParameters(currentMethodParametersBeforeOffset(method, method.indexOf('{')))
            ?: throw IllegalStateException(
                "BlockEntity onDataPacket migration requires exactly one HolderLookup.Provider parameter"
            )
        var migratedMethod = replaceExecutableRegex(
            method,
            Regex("""\b(?:this\.)?handleUpdateTag\s*\(\s*(\w+)\s*\)\s*;""")
        ) { match ->
                needsHolderLookup = true
                "this.handleUpdateTag(${match.groupValues[1]}, $providerName);"
            }
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""\b(?:this\.)?load\s*\(\s*([^;\r\n]+?\.getTag\(\)|[A-Za-z_$][\w$]*)\s*\)\s*;""")
        ) { match ->
                needsHolderLookup = true
                val tagExpression = match.groupValues[1].trim()
                val methodName = if (tagExpression.endsWith(".getTag()")) "loadAdditional" else "loadWithComponents"
                "this.$methodName($tagExpression, $providerName);"
            }
        if (migratedMethod != method) {
            val methodStart = result.indexOf(method)
            if (methodStart >= 0) {
                result = result.substring(0, methodStart) + migratedMethod + result.substring(methodStart + method.length)
            }
        }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""if\s*\(\s*(\w+)\s*!=\s*null\s*\)\s*\{\s*\r?\n\s*(?:this\.)?load\s*\(\s*\1\s*\)\s*;""")
    ) { match ->
            val tagName = match.groupValues[1]
            val providerName = requiredHolderLookupProviderNameAt(
                result,
                match.range.first,
                "BlockEntity nullable packet tag migration"
            )
            "if (!$tagName.isEmpty()) {\n            loadWithComponents($tagName, $providerName);"
        }
    result = replaceExecutableRegex(
        result,
        Regex("""if\s*\(\s*!(\w+)\.isEmpty\(\)\s*\)\s*\{\s*\r?\n\s*(?:this\.)?load\s*\(\s*\1\s*\)\s*;""")
    ) { match ->
            val tagName = match.groupValues[1]
            val providerName = requiredHolderLookupProviderNameAt(
                result,
                match.range.first,
                "BlockEntity non-empty packet tag migration"
            )
            "if (!$tagName.isEmpty()) {\n            loadWithComponents($tagName, $providerName);"
        }

    if (needsHolderLookup || result != source) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    return result
}

internal fun StructuralRefactorPass.collectHolderLookupCompoundTagMethods(javaFiles: List<Path>): Set<String> {
    val id = """[A-Za-z_$][\w$]*"""
    val pattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|default|static|final|abstract)\s+)*void\s+($id)\s*\(\s*(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*(?:net\.minecraft\.nbt\.)?CompoundTag\s+$id\s*,\s*(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*(?:net\.minecraft\.core\.)?HolderLookup\.Provider\s+$id\s*\)"""
    )
    val texts = javaFiles.asSequence()
        .map { runCatching { it.readText() }.getOrDefault("") }
        .toList()
    val projectDeclared = texts.asSequence()
        .flatMap { pattern.findAll(it).map { match -> match.groupValues[1] } }
        .toSet()
    val nitrogenBossMobMethods = if (texts.any { it.contains("com.aetherteam.nitrogen.entity.BossMob") }) {
        setOf("addBossSaveData", "readBossSaveData")
    } else {
        emptySet()
    }
    return projectDeclared + nitrogenBossMobMethods
}

internal fun StructuralRefactorPass.migrateEntityAdditionalSaveDataHolderLookupSource(
    source: String,
    javaInheritanceIndex: JavaInheritanceIndex,
    holderLookupCompoundTagMethods: Set<String>
): String {
    if (holderLookupCompoundTagMethods.none { source.contains("$it(") }) return source

    val id = """[A-Za-z_$][\w$]*"""
    val methodPattern = Regex(
        """(?m)^([ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|final|synchronized)\s+)+[A-Za-z_$][\w$<>\[\].?,\s]*\s+$id\s*)\(([^()]*)\)\s*\{"""
    )
    var result = source
    var cursor = 0
    while (true) {
        val match = methodPattern.find(result, cursor) ?: break
        if (Regex("""\bstatic\b""").containsMatchIn(match.groupValues[1])) {
            cursor = match.range.last + 1
            continue
        }
        val classDeclaration = enclosingJavaClassDeclaration(result, match.range.first)
        if (!javaClassExtendsAny(classDeclaration, javaEntityBaseTypes(), javaInheritanceIndex)) {
            cursor = match.range.last + 1
            continue
        }
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val params = splitTopLevelJavaArgs(match.groupValues[2])
        val parameterTags = params.mapNotNull { parameter ->
            Regex(
                """(?:final\s+)?(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?\s*)*(?:net\.minecraft\.nbt\.)?CompoundTag\s+($id)$"""
            ).find(parameter.trim().replace(Regex("""\s+"""), " "))?.groupValues?.get(1)
        }
        val providerExpression = holderLookupProviderFromParameters(params)
            ?: entitySelfRegistryAccessExpression(classDeclaration, javaInheritanceIndex)
        if (providerExpression == null) {
            cursor = closeBrace + 1
            continue
        }
        val originalBody = result.substring(openBrace + 1, closeBrace)
        val tagNames = (parameterTags + Regex("""\b(?:net\.minecraft\.nbt\.)?CompoundTag\s+($id)\b""")
            .findAll(originalBody)
            .map { it.groupValues[1] })
            .toSet()
        if (tagNames.isEmpty()) {
            cursor = closeBrace + 1
            continue
        }
        var body = originalBody
        for (delegateName in holderLookupCompoundTagMethods) {
            body = Regex("""(?<![.\w$])((?:this|super)\.)?${Regex.escape(delegateName)}\(\s*($id)\s*\)""")
                .replace(body) { callMatch ->
                    val tagName = callMatch.groupValues[2]
                    if (tagName in tagNames) {
                        "${callMatch.groupValues[1]}$delegateName($tagName, $providerExpression)"
                    } else {
                        callMatch.value
                    }
                }
        }
        if (body != originalBody) {
            result = result.substring(0, openBrace + 1) + body + result.substring(closeBrace)
            cursor = openBrace + 1 + body.length
        } else {
            cursor = closeBrace + 1
        }
    }
    return result
}

internal fun StructuralRefactorPass.entitySelfRegistryAccessExpression(
    classDeclaration: JavaClassDeclaration?,
    javaInheritanceIndex: JavaInheritanceIndex
): String? = when {
    javaClassExtendsAny(classDeclaration, javaEntityBaseTypes(), javaInheritanceIndex) -> "this.registryAccess()"
    javaClassExtendsAny(classDeclaration, javaBlockEntityBaseTypes(), javaInheritanceIndex) -> "this.getLevel().registryAccess()"
    javaClassExtendsAny(classDeclaration, javaScreenBaseTypes(), javaInheritanceIndex) -> "this.minecraft.level.registryAccess()"
    else -> null
}

internal fun StructuralRefactorPass.javaScreenBaseTypes(): Set<String> = setOf(
    "Screen",
    "AbstractContainerScreen",
    "EffectRenderingInventoryScreen",
    "ItemCombinerScreen",
    "OptionsSubScreen"
)

internal fun StructuralRefactorPass.migrateBaseContainerBlockEntityItemsSource(source: String): String {
    if (!source.contains("extends BaseContainerBlockEntity") ||
        !source.contains("NonNullList<ItemStack>") ||
        !source.contains("items")
    ) {
        return source
    }
    if (source.contains("protected NonNullList<ItemStack> getItems()") &&
        source.contains("protected void setItems(NonNullList<ItemStack>")
    ) {
        return source
    }

    val insertion = """

  @Override
  protected NonNullList<ItemStack> getItems() {
    return this.items;
  }

  @Override
  protected void setItems(NonNullList<ItemStack> items) {
    this.items = items;
  }
""".trimEnd()
    val anchor = Regex("""(?m)^([ \t]*)@Override\s*\r?\n[ \t]*protected\s+AbstractContainerMenu\s+createMenu\b""").find(source)
    if (anchor != null) {
        return source.substring(0, anchor.range.first) + insertion + "\n\n" + source.substring(anchor.range.first)
    }
    return insertBeforeLastClassBrace(source, insertion)
}

internal fun StructuralRefactorPass.migrateBaseContainerCustomNameSetterSource(source: String): String {
    if (!source.contains("BlockEntity") || source.contains("setCustomName(net.minecraft.network.chat.Component") ||
        Regex("""\bvoid\s+setCustomName\s*\(""").containsMatchIn(source)) {
        return source
    }
    val containerBlockEntityParents = setOf(
        "BaseContainerBlockEntity",
        "RandomizableContainerBlockEntity",
        "AbstractFurnaceBlockEntity",
        "FurnaceBlockEntity",
        "SmokerBlockEntity",
        "BlastFurnaceBlockEntity",
        "ChestBlockEntity",
        "BarrelBlockEntity"
    )
    val directSuperclass = Regex(
        """\bclass\s+[A-Za-z_$][\w$]*(?:\s*<[^>{}]*>)?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\b"""
    ).find(source)?.groupValues?.get(1)?.substringAfterLast('.')
    val extendsContainerBlockEntity = directSuperclass in containerBlockEntityParents
    if (!extendsContainerBlockEntity) return source

    val insertion = """

	public void setCustomName(net.minecraft.network.chat.Component name) {
		this.applyComponents(net.minecraft.core.component.DataComponentMap.builder()
			.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name)
			.build(), net.minecraft.core.component.DataComponentPatch.EMPTY);
	}
""".trimEnd()
    val anchor = Regex("""(?m)^([ \t]*)@Override\s*\r?\n[ \t]*protected\s+Component\s+getDefaultName\s*\(""").find(source)
    if (anchor != null) {
        return source.substring(0, anchor.range.first) + insertion + "\n\n" + source.substring(anchor.range.first)
    }
    return insertBeforeLastClassBrace(source, insertion)
}

internal fun StructuralRefactorPass.migrateINBTSerializableHolderLookupSource(
    source: String,
    projectSerializableTypeFqns: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("serializeNBT()") &&
        !Regex("""\bdeserializeNBT\s*\(\s*CompoundTag\b""").containsMatchIn(executableCode)
    ) return source
    if (inbtSerializableTypeBodyRanges(source, projectSerializableTypeFqns).isEmpty()) return source

    fun replaceInSerializableTypes(current: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val ranges = inbtSerializableTypeBodyRanges(current, projectSerializableTypeFqns)
        if (ranges.isEmpty()) return current
        return replaceExecutableRegex(current, pattern) { match ->
            if (ranges.any { match.range.first in it }) replacement(match) else match.value
        }
    }

    var result = source
    result = replaceInSerializableTypes(
        result,
        Regex("""\b(public|protected)\s+CompoundTag\s+serializeNBT\s*\(\s*\)""")
    ) { match ->
        val providerName = uniqueHolderLookupProviderNameForMethodAt(
            result,
            match.range.first,
            "registries"
        )
        "${match.groupValues[1]} CompoundTag serializeNBT(HolderLookup.Provider $providerName)"
    }
    result = replaceInSerializableTypes(
        result,
        Regex("""\b(public|protected)\s+void\s+deserializeNBT\s*\(\s*CompoundTag\s+([A-Za-z_$][\w$]*)\s*\)""")
    ) { match ->
        val providerName = uniqueHolderLookupProviderNameForMethodAt(
            result,
            match.range.first,
            "registries"
        )
        "${match.groupValues[1]} void deserializeNBT(HolderLookup.Provider $providerName, CompoundTag ${match.groupValues[2]})"
    }
    // the widened overrides pass their provider on to the library implementation they extend
    fun widenedOverrideRanges(text: String): List<IntRange> =
        javaMethodRangesIncludingDefault(text)
            .filter {
                (it.name == "serializeNBT" || it.name == "deserializeNBT") &&
                    it.header.contains("HolderLookup.Provider")
            }
            .map { it.range }
    var overrides = widenedOverrideRanges(result)
    result = replaceExecutableRegex(result, Regex("""\bsuper\.serializeNBT\(\s*\)""")) { match ->
        if (overrides.none { match.range.first in it }) {
            match.value
        } else {
            val providerName = exactHolderLookupProviderNameAtOrNull(
                result,
                match.range.first,
                "INBTSerializable super.serializeNBT forwarding"
            ) ?: throw IllegalStateException(
                "Cannot resolve HolderLookup.Provider for super.serializeNBT forwarding"
            )
            "super.serializeNBT($providerName)"
        }
    }
    overrides = widenedOverrideRanges(result)
    result = replaceExecutableRegex(
        result,
        Regex("""\bsuper\.deserializeNBT\(\s*([^(),]+)\)""")
    ) { match ->
        if (overrides.none { match.range.first in it }) {
            match.value
        } else {
            val providerName = exactHolderLookupProviderNameAtOrNull(
                result,
                match.range.first,
                "INBTSerializable super.deserializeNBT forwarding"
            ) ?: throw IllegalStateException(
                "Cannot resolve HolderLookup.Provider for super.deserializeNBT forwarding"
            )
            "super.deserializeNBT($providerName, ${match.groupValues[1].trim()})"
        }
    }
    if (result != source && result.contains("HolderLookup.Provider")) {
        result = addImportIfMissing(result, "net.minecraft.core.HolderLookup")
    }
    return result
}

internal data class JavaTypeContract(
    val packageName: String,
    val simpleName: String,
    val fqn: String,
    val source: String,
    val superTypeRefs: List<String>
)

internal fun StructuralRefactorPass.collectProjectINBTSerializableTypes(javaFiles: List<Path>): Set<String> {
    val types = javaFiles.flatMap { file ->
        val source = file.readText()
        javaTypeContracts(source)
    }
    if (types.isEmpty()) return emptySet()

    val knownFqns = types.map { it.fqn }.toSet()
    val byFqn = types.associateBy { it.fqn }
    val serializableFqns = mutableSetOf<String>()
    var changed: Boolean
    do {
        changed = false
        for (type in types) {
            if (type.fqn in serializableFqns) continue
            val inheritsSerializable = type.superTypeRefs.any { reference ->
                val resolved = resolveJavaTypeReference(reference, type.source, type.packageName, knownFqns)
                isCompoundTagINBTSerializableReference(reference, resolved, type.source) ||
                    (resolved != null &&
                        resolved != "net.neoforged.neoforge.common.util.INBTSerializable" &&
                        resolved in libraryNbtSerializableRoots) ||
                    (resolved != null && resolved in serializableFqns) ||
                    (resolved != null && byFqn[resolved]?.fqn in serializableFqns)
            }
            if (inheritsSerializable) {
                serializableFqns += type.fqn
                changed = true
            }
        }
    } while (changed)
    return serializableFqns
}

internal fun StructuralRefactorPass.isCompoundTagINBTSerializableReference(
    reference: String,
    resolvedReference: String?,
    ownerSource: String
): Boolean {
    if (resolvedReference != "net.neoforged.neoforge.common.util.INBTSerializable") return false
    val genericStart = reference.indexOf('<')
    val genericEnd = reference.lastIndexOf('>')
    if (genericStart < 0 || genericEnd <= genericStart) return false
    val arguments = splitTopLevelJavaArgs(reference.substring(genericStart + 1, genericEnd))
    if (arguments.size != 1) return false
    val tagType = arguments.single().trim()
    if (tagType == "net.minecraft.nbt.CompoundTag") return true
    if (tagType != "CompoundTag") return false
    return Regex("""(?m)^[ \t]*import\s+net\.minecraft\.nbt\.CompoundTag\s*;""")
        .containsMatchIn(ownerSource) ||
        Regex("""(?m)^[ \t]*import\s+net\.minecraft\.nbt\.\*\s*;""")
            .containsMatchIn(ownerSource)
}

internal fun StructuralRefactorPass.javaTypeContracts(source: String): List<JavaTypeContract> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val packageName = packageNameOf(source)
    val typePattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|record|interface)\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]+>)?([^{;]*)\{"""
    )
    return typePattern.findAll(executableCode).map { match ->
        val simpleName = match.groupValues[1]
        val fqn = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
        JavaTypeContract(
            packageName = packageName,
            simpleName = simpleName,
            fqn = fqn,
            source = source,
            superTypeRefs = declaredSuperTypeReferences(match.groupValues[2])
        )
    }.toList()
}

internal fun StructuralRefactorPass.declaredSuperTypeReferences(typeTail: String): List<String> {
    val result = mutableListOf<String>()
    Regex("""\bextends\s+([^{};]+?)(?=\bimplements\b|$)""")
        .find(typeTail)
        ?.groupValues
        ?.get(1)
        ?.let { result += splitTopLevelJavaArgs(it).map { ref -> ref.trim() } }
    Regex("""\bimplements\s+([^{};]+)$""")
        .find(typeTail)
        ?.groupValues
        ?.get(1)
        ?.let { result += splitTopLevelJavaArgs(it).map { ref -> ref.trim() } }
    return result.filter { it.isNotBlank() }
}

/**
 * Library types whose subclasses are INBTSerializable<CompoundTag> and whose 1.21.1 build declares
 * serializeNBT(HolderLookup.Provider) / deserializeNBT(HolderLookup.Provider, CompoundTag)
 * (javap of neoforge-21.1.86: ItemStackHandler implements INBTSerializable<CompoundTag>).
 */
internal val libraryNbtSerializableRoots = setOf(
    "net.neoforged.neoforge.common.util.INBTSerializable",
    "net.neoforged.neoforge.items.ItemStackHandler",
    "net.minecraftforge.items.ItemStackHandler"
)

internal fun StructuralRefactorPass.resolveJavaTypeReference(
    reference: String,
    ownerSource: String,
    ownerPackageName: String,
    knownFqns: Set<String>
): String? {
    val raw = reference
        .trim()
        .substringBefore('<')
        .trim()
        .removePrefix("? extends ")
        .removePrefix("? super ")
        .trim()
    if (raw.isBlank()) return null
    if (raw in libraryNbtSerializableRoots) return raw
    if (raw.contains('.')) {
        return raw.takeIf { it in knownFqns || it in libraryNbtSerializableRoots }
    }

    Regex("""(?m)^[ \t]*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(raw)})\s*;""")
        .find(ownerSource)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it in knownFqns || it in libraryNbtSerializableRoots }
        ?.let { return it }

    val samePackageFqn = if (ownerPackageName.isBlank()) raw else "$ownerPackageName.$raw"
    return samePackageFqn.takeIf { it in knownFqns }
}

internal fun StructuralRefactorPass.inbtSerializableTypeBodyRanges(
    source: String,
    projectSerializableTypeFqns: Set<String>
): List<IntRange> {
    val executableCode = maskJavaCommentsAndLiterals(source)
    val packageName = packageNameOf(source)
    val typePattern = Regex(
        """(?m)^[ \t]*(?:@[^\r\n]+\r?\n[ \t]*)*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|record|interface)\s+([A-Za-z_$][\w$]*)(?:\s*<[^>{}]+>)?[^{;]*\b(?:implements|extends)\b[^{;]*\{"""
    )
    return typePattern.findAll(executableCode).mapNotNull { match ->
        val simpleName = match.groupValues[1]
        val fqn = if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
        if (fqn !in projectSerializableTypeFqns) return@mapNotNull null
        val openBrace = executableCode.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableCode, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) null else openBrace..closeBrace
    }.toList()
}

internal fun StructuralRefactorPass.migrateTooltipContextImportsSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Item.TooltipContext")) return source
    if (executableCode.contains("import net.minecraft.world.item.Item;")) return source
    return addExecutableImportIfMissing(source, "net.minecraft.world.item.Item")
}

internal fun StructuralRefactorPass.migrateRemovedJsonReloadDeserializersSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("Deserializers.createFunctionSerializer().create()")) return source
    var result = replaceExecutableRegex(
        source,
        Regex("""\bDeserializers\.createFunctionSerializer\(\)\.create\(\)""")
    ) { "new GsonBuilder().create()" }
    result = addImportIfMissing(result, "com.google.gson.GsonBuilder")
    val withoutDeserializers = removeImport(result, "net.minecraft.world.level.storage.loot.Deserializers")
    if (!maskJavaCommentsAndLiterals(withoutDeserializers).contains("Deserializers.")) {
        result = withoutDeserializers
    }
    return result
}

internal fun StructuralRefactorPass.migrateModLoadingContextRegisterConfigSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ModLoadingContext.get().registerConfig(") &&
        !executableCode.contains("ModLoadingContext.get().getActiveContainer().registerConfig(")
    ) {
        return source
    }

    val containerName = Regex("""\bModContainer\s+([A-Za-z_$][\w$]*)""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .distinct()
        .singleOrNull()
        ?: return source

    var result = replaceExecutableRegex(
        source,
        Regex("""\bModLoadingContext\.get\(\)\.getActiveContainer\(\)\.registerConfig\(""")
    ) { "$containerName.registerConfig(" }
    result = replaceExecutableRegex(
        result,
        Regex("""\bModLoadingContext\.get\(\)\.registerConfig\(""")
    ) { "$containerName.registerConfig(" }

    val withoutImport = removeImport(result, "net.neoforged.fml.ModLoadingContext")
    if (!maskJavaCommentsAndLiterals(withoutImport).contains("ModLoadingContext.")) {
        result = withoutImport
    }
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectHolderCallsSource(
    source: String,
    mobEffectDeferredHolderFields: Set<String> = emptySet()
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("MobEffect") &&
        !executableCode.contains("hasEffect(") &&
        !executableCode.contains("getEffect(") &&
        !executableCode.contains("removeEffect(") &&
        !executableCode.contains("addEffect(")
    ) return source

    val holderConstant = """(?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+"""
    var result = source

    result = migrateMobEffectHolderCollectionsSource(result, mobEffectDeferredHolderFields)
    result = migrateMobEffectInstanceEffectAssignments(result)
    result = migrateMobEffectEnhancedForHolderVariables(result)
    result = migrateMobEffectHolderMethodBoundaries(result, mobEffectDeferredHolderFields)
    result = migrateMobEffectHolderFieldBoundaries(result, mobEffectDeferredHolderFields)

    result = replaceExecutableRegex(
        result,
        Regex("""\bMobEffect\s+(\w+)\s*=\s*($holderConstant)\.get\(\)\s*;""")
    ) { match ->
        "Holder<MobEffect> ${match.groupValues[1]} = ${match.groupValues[2]};"
    }

    val holderVariables = Regex("""\bHolder\s*<\s*MobEffect\s*>\s+(\w+)\s*=""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    val mobEffectVariables = Regex("""\bMobEffect\s+(\w+)\s*=""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()

    result = replaceExecutableRegex(
        result,
        Regex("""(new\s+MobEffectInstance\s*\(\s*)((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)""")
    ) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(\.\s*(?:hasEffect|getEffect|removeEffect)\s*\(\s*)((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)(\s*\))""")
    ) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}"
    }
    result = replaceExecutableRegex(
        result,
        Regex("""(new\s+ClientboundRemoveMobEffectPacket\s*\(\s*(?:[^()]|\([^()]*\))*?,\s*)($holderConstant)\.get\(\)(\s*\))""")
    ) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}"
    }

    holderVariables.forEach { variable ->
        result = replaceExecutableRegex(
            result,
            Regex("""\bBuiltInRegistries\.MOB_EFFECT\.wrapAsHolder\(\s*${Regex.escape(variable)}\s*\)""")
        ) { variable }
    }
    for (variable in mobEffectVariables) {
        val varPattern = Regex.escape(variable)
        result = replaceExecutableRegex(
            result,
            Regex("""(new\s+MobEffectInstance\s*\(\s*)$varPattern\b""")
        ) { match ->
            "${match.groupValues[1]}BuiltInRegistries.MOB_EFFECT.wrapAsHolder($variable)"
        }
        result = replaceExecutableRegex(
            result,
            Regex("""(\.\s*(?:hasEffect|getEffect|removeEffect)\s*\(\s*)$varPattern(\s*\))""")
        ) { match ->
            "${match.groupValues[1]}BuiltInRegistries.MOB_EFFECT.wrapAsHolder($variable)${match.groupValues[2]}"
        }
    }

    var holderParamChanged = false
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodText = result.substring(method.range)
        val mobEffectParams = javaMethodParameters(methodText)
            .filter { simpleJavaTypeName(it.type) == "MobEffect" }
        if (mobEffectParams.isEmpty()) continue

        val executableMethodText = maskJavaCommentsAndLiterals(methodText)
        val holderParamNames = mobEffectParams
            .mapNotNull { param ->
                val paramName = param.name
                val paramPattern = Regex.escape(paramName)
                val passedToHolderApi =
                    Regex("""\b(?:hasEffect|getEffect|removeEffect)\s*\(\s*$paramPattern\s*\)""").containsMatchIn(executableMethodText) ||
                        Regex("""new\s+MobEffectInstance\s*\(\s*$paramPattern\b""").containsMatchIn(executableMethodText)
                if (passedToHolderApi) paramName else null
            }
            .toSet()
        if (holderParamNames.isEmpty()) continue

        var migratedMethod = methodText
        for (paramName in holderParamNames) {
            val paramPattern = Regex.escape(paramName)
            migratedMethod = replaceExecutableRegex(
                migratedMethod,
                Regex("""\bMobEffect\s+$paramPattern\b""")
            ) { "Holder<MobEffect> $paramName" }
            migratedMethod = replaceExecutableRegex(
                migratedMethod,
                Regex("""\b$paramPattern\.getDescriptionId\(\)""")
            ) { "$paramName.value().getDescriptionId()" }
            migratedMethod = replaceExecutableRegex(
                migratedMethod,
                Regex("""\b$paramPattern\.getCategory\(\)""")
            ) { "$paramName.value().getCategory()" }
        }
        if (migratedMethod != methodText) {
            result = result.replaceRange(method.range, migratedMethod)
            holderParamChanged = true
        }
    }

    if (result != source) {
        val resultExecutableCode = maskJavaCommentsAndLiterals(result)
        if (holderVariables.isNotEmpty() || holderParamChanged || resultExecutableCode.contains("Holder<MobEffect>")) {
            result = addImportIfMissing(result, "net.minecraft.core.Holder")
        }
        if (mobEffectVariables.isNotEmpty()) {
            result = addImportIfMissing(result, "net.minecraft.core.registries.BuiltInRegistries")
        }
        if (!maskJavaCommentsAndLiterals(result).contains("BuiltInRegistries.MOB_EFFECT")) {
            result = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMobEffectApplyTickReturns(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("applyEffectTick(") || !executableCode.contains("return;")) return source

    var result = source
    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        if (method.name != "applyEffectTick") continue
        val methodText = result.substring(method.range)
        val executableMethodText = maskJavaCommentsAndLiterals(methodText)
        if (!Regex("""\bboolean\s+applyEffectTick\s*\(""").containsMatchIn(executableMethodText)) continue
        val migratedMethod = replaceExecutableRegex(
            methodText,
            Regex("""\breturn\s*;""")
        ) { "return true;" }
        if (migratedMethod != methodText) {
            result = result.replaceRange(method.range, migratedMethod)
        }
    }
    return result
}

internal fun StructuralRefactorPass.migrateLegacyMobEffectRemoveAttributeModifierOverrides(source: String): String {
    if (!source.contains("removeAttributeModifiers(") ||
        !source.contains("LivingEntity") ||
        !source.contains("AttributeMap")) {
        return source
    }
    var result = source
    var changed = false
    val methodPattern = Regex(
        """(?m)^([ \t]*(?:@Override\s*\r?\n[ \t]*)?public\s+void\s+)removeAttributeModifiers\s*\(\s*LivingEntity\s+([A-Za-z_$][\w$]*)\s*,\s*AttributeMap\s+([A-Za-z_$][\w$]*)\s*,\s*int\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    methodPattern.findAll(maskJavaCommentsAndLiterals(result)).toList().asReversed().forEach { match ->
        val openBrace = result.indexOf('{', match.range.last - 1)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) return@forEach
        val living = match.groupValues[2]
        val attributes = match.groupValues[3]
        val amplifier = match.groupValues[4]
        val body = result.substring(openBrace + 1, closeBrace)
        val migratedBody = Regex(
            """super\.removeAttributeModifiers\(\s*${Regex.escape(living)}\s*,\s*${Regex.escape(attributes)}\s*,\s*${Regex.escape(amplifier)}\s*\)\s*;"""
        ).replace(body, "super.onMobRemoved($living, $amplifier, reason);")
        val migratedHeader = "${match.groupValues[1]}onMobRemoved(LivingEntity $living, int $amplifier, Entity.RemovalReason reason) {"
        result = result.substring(0, match.range.first) + migratedHeader + migratedBody + result.substring(closeBrace)
        changed = true
    }
    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.world.entity.Entity")
    result = removeUnusedSimpleImport(result, "net.minecraft.world.entity.ai.attributes.AttributeMap", "AttributeMap")
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectHolderCollectionsSource(
    source: String,
    mobEffectDeferredHolderFields: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("MobEffect") || !executableCode.contains("Set.of(")) return source
    var result = source
    val holderCollectionNames = linkedSetOf<String>()
    val declarationPattern = Regex(
        """(?s)\b((?:java\.util\.)?(?:Set|List|Collection))\s*<\s*MobEffect\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*((?:java\.util\.)?(?:Set|List|Collection)\.of\s*\()"""
    )
    val replacements = mutableListOf<Pair<IntRange, String>>()
    for (match in declarationPattern.findAll(executableCode)) {
        val initializerOpen = executableCode.indexOf('(', match.range.last)
        if (initializerOpen < 0) continue
        val initializerClose = findMatchingParen(executableCode, initializerOpen)
        if (initializerClose < 0) continue
        val initializer = executableCode.substring(initializerOpen + 1, initializerClose)
        if (!initializer.contains("MobEffects.") &&
            !containsProvenMobEffectDeferredHolderGet(initializer, mobEffectDeferredHolderFields)
        ) {
            continue
        }
        val replacement = "${match.groupValues[1]}<Holder<MobEffect>> ${match.groupValues[2]} = ${match.groupValues[3]}"
        replacements += match.range to replacement
        val migratedInitializer = replaceProvenMobEffectDeferredHolderGetsInText(
            result.substring(initializerOpen + 1, initializerClose),
            mobEffectDeferredHolderFields
        )
        if (migratedInitializer != result.substring(initializerOpen + 1, initializerClose)) {
            replacements += (initializerOpen + 1 until initializerClose) to migratedInitializer
        }
        holderCollectionNames += match.groupValues[2]
    }
    if (replacements.isEmpty()) return source
    for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, replacement)
    }
    if (holderCollectionNames.isNotEmpty() || result.contains("Holder<MobEffect>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    return result
}

internal fun StructuralRefactorPass.containsProvenMobEffectDeferredHolderGet(
    text: String,
    mobEffectDeferredHolderFields: Set<String>
): Boolean =
    Regex("""\b((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)""")
        .findAll(text)
        .any { it.groupValues[1] in mobEffectDeferredHolderFields }

internal fun StructuralRefactorPass.replaceProvenMobEffectDeferredHolderGetsInText(
    text: String,
    mobEffectDeferredHolderFields: Set<String>
): String {
    if (mobEffectDeferredHolderFields.isEmpty()) return text
    return Regex("""\b((?:[A-Za-z_$][\w$]*\.)+([A-Z0-9_$]+))\.get\(\)""")
        .replace(text) { match ->
        if (match.groupValues[1] in mobEffectDeferredHolderFields) {
            match.groupValues[1]
        } else {
            match.value
        }
    }
}

internal fun StructuralRefactorPass.migrateMobEffectInstanceEffectAssignments(source: String): String {
    if (!source.contains(".getEffect()") ||
        (!source.contains("MobEffectInstance") && !source.contains(".getEffectInstance()"))
    ) {
        return source
    }
    var result = source
    val executableCode = maskJavaCommentsAndLiterals(result)
    val mobEffectInstanceVariables = Regex("""\b(?:net\.minecraft\.world\.effect\.)?MobEffectInstance\s+([A-Za-z_$][\w$]*)\b""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    for (variable in mobEffectInstanceVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\bMobEffect\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(variable)}\.getEffect\(\)\s*;""")
        ) { match -> "Holder<MobEffect> ${match.groupValues[1]} = $variable.getEffect();" }
    }
    result = replaceExecutableRegex(
        result,
        Regex("""\bMobEffect\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*\(\))*)\.getEffectInstance\(\)\.getEffect\(\)\s*;""")
    ) { match -> "Holder<MobEffect> ${match.groupValues[1]} = ${match.groupValues[2]}.getEffectInstance().getEffect();" }
    if (result != source && result.contains("Holder<MobEffect>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectEnhancedForHolderVariables(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("for") || !executableCode.contains("MobEffect")) return source
    var result = source
    val replacements = mutableListOf<Pair<IntRange, String>>()
    val loopPattern = Regex("""for\s*\(\s*MobEffect\s+([A-Za-z_$][\w$]*)\s*:\s*((?:[^()]|\([^()]*\))*)\)\s*\{""")
    for (match in loopPattern.findAll(executableCode)) {
        val openBrace = executableCode.indexOf('{', match.range.last)
        if (openBrace < 0) continue
        val closeBrace = findMatchingBrace(executableCode, openBrace)
        if (closeBrace < 0) continue
        val variable = match.groupValues[1]
        val variablePattern = Regex.escape(variable)
        val body = executableCode.substring(openBrace + 1, closeBrace)
        val holderApiUse =
            Regex("""\b(?:hasEffect|getEffect|removeEffect)\s*\(\s*$variablePattern\s*\)""").containsMatchIn(body) ||
                Regex("""new\s+MobEffectInstance\s*\(\s*$variablePattern\b""").containsMatchIn(body)
        if (!holderApiUse) continue
        replacements += match.range to "for (Holder<MobEffect> $variable : ${match.groupValues[2].trim()}) {"
    }
    if (replacements.isEmpty()) return source
    for ((range, replacement) in replacements.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, replacement)
    }
    result = addImportIfMissing(result, "net.minecraft.core.Holder")
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectHolderMethodBoundaries(
    source: String,
    mobEffectDeferredHolderFields: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("MobEffect") || !executableCode.contains("MobEffects.")) return source
    var result = source

    val collectionPattern = Regex(
        """\b(((?:java\.util\.)?(?:List|Set|Collection))\s*<\s*MobEffect\s*>)\s+([A-Za-z_$][\w$]*)\s*="""
    )
    val collectionEdits = mutableListOf<Pair<IntRange, String>>()
    collectionPattern.findAll(maskJavaCommentsAndLiterals(result)).forEach { match ->
        val name = match.groupValues[3]
        val usage = Regex("""\b${Regex.escape(name)}\.add\s*\([^;\r\n]*\.getEffect\(\)\s*\)""")
            .containsMatchIn(maskJavaCommentsAndLiterals(result))
        if (usage) {
            collectionEdits += match.groups[1]!!.range to "${match.groupValues[2]}<Holder<MobEffect>>"
        }
    }
    if (collectionEdits.isNotEmpty()) {
        result = applyStringEdits(result, collectionEdits)
    }

    for (method in javaMethodRangesIncludingDefault(result).asReversed()) {
        val methodText = result.substring(method.range)
        if (!methodText.contains("MobEffect") || !methodText.contains("MobEffects.")) continue
        val executableMethod = maskJavaCommentsAndLiterals(methodText)
        var migratedMethod = methodText
        var changed = false

        val returnsHolderEffect = Regex("""\breturn\s+MobEffects\.[A-Z0-9_]+\s*;""").containsMatchIn(executableMethod) ||
            mobEffectDeferredHolderFields.any { field ->
                Regex("""\breturn\s+${Regex.escape(field)}\.get\(\)\s*;""").containsMatchIn(executableMethod)
            }
        if (returnsHolderEffect) {
            migratedMethod = replaceExecutableRegex(
                migratedMethod,
                Regex("""\bMobEffect\s+${Regex.escape(method.name)}\s*\(""")
            ) {
                changed = true
                "Holder<MobEffect> ${method.name}("
            }
        }

        val mobEffectParams = javaMethodParameters(migratedMethod)
            .filter { simpleJavaTypeName(it.type) == "MobEffect" }
        for (param in mobEffectParams) {
            val paramName = param.name
            val paramPattern = Regex.escape(paramName)
            val holderUse = Regex("""\b$paramPattern\s*(?:==|!=)\s*MobEffects\.[A-Z0-9_]+\b""").containsMatchIn(executableMethod) ||
                Regex("""\bMobEffects\.[A-Z0-9_]+\s*(?:==|!=)\s*$paramPattern\b""").containsMatchIn(executableMethod) ||
                Regex("""\b$paramPattern\.(?:isBeneficial|getDescriptionId|getCategory)\s*\(""").containsMatchIn(executableMethod) ||
                returnsHolderEffect
            if (!holderUse) continue
            migratedMethod = replaceExecutableRegex(
                migratedMethod,
                Regex("""\bMobEffect\s+$paramPattern\b""")
            ) {
                changed = true
                "Holder<MobEffect> $paramName"
            }
            migratedMethod = migrateHolderMobEffectVariableAccess(migratedMethod, paramName, mobEffectDeferredHolderFields)
        }

        if (changed) {
            result = result.replaceRange(method.range, migratedMethod)
        }
    }

    if (result != source && result.contains("Holder<MobEffect>")) {
        result = addImportIfMissing(result, "net.minecraft.core.Holder")
    }
    return result
}

internal fun StructuralRefactorPass.migrateMobEffectHolderFieldBoundaries(
    source: String,
    mobEffectDeferredHolderFields: Set<String>
): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("MobEffect") ||
        !executableCode.contains("MobEffectInstance") &&
        !executableCode.contains("MobEffects.") &&
        !executableCode.contains("getDisplayName(")) {
        return source
    }

    val fieldNames = Regex("""\b(?:private|protected|public)\s+final\s+MobEffect\s+([A-Za-z_$][\w$]*)\s*;""")
        .findAll(executableCode)
        .map { it.groupValues[1] }
        .toSet()
    if (fieldNames.isEmpty()) return source

    var result = source
    var changed = false
    for (fieldName in fieldNames) {
        val fieldPattern = Regex.escape(fieldName)
        val holderUse =
            Regex("""BuiltInRegistries\.MOB_EFFECT\.wrapAsHolder\s*\(\s*$fieldPattern\s*\)""").containsMatchIn(executableCode) ||
                Regex("""\b$fieldPattern\s*(?:==|!=)\s*MobEffects\.[A-Z0-9_]+\b""").containsMatchIn(executableCode) ||
                Regex("""\b$fieldPattern\.getDisplayName\s*\(""").containsMatchIn(executableCode)
        if (!holderUse) continue

        result = replaceExecutableRegex(
            result,
            Regex("""\bMobEffect\s+$fieldPattern\b""")
        ) {
            changed = true
            "Holder<MobEffect> $fieldName"
        }
        result = migrateHolderMobEffectVariableAccess(result, fieldName, mobEffectDeferredHolderFields)
        result = replaceExecutableRegex(
            result,
            Regex("""BuiltInRegistries\.MOB_EFFECT\.wrapAsHolder\s*\(\s*$fieldPattern\s*\)""")
        ) {
            changed = true
            fieldName
        }
    }

    if (!changed) return source
    result = addImportIfMissing(result, "net.minecraft.core.Holder")
    val withoutBuiltInRegistries = removeImport(result, "net.minecraft.core.registries.BuiltInRegistries")
    if (!maskJavaCommentsAndLiterals(withoutBuiltInRegistries).contains("BuiltInRegistries.")) {
        result = withoutBuiltInRegistries
    }
    return result
}

internal fun StructuralRefactorPass.migrateHolderMobEffectVariableAccess(
    source: String,
    variable: String,
    mobEffectDeferredHolderFields: Set<String>
): String {
    val variablePattern = Regex.escape(variable)
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""\b$variablePattern\s*==\s*(MobEffects\.[A-Z0-9_]+)\b""")
    ) { match -> "$variable.is(${match.groupValues[1]})" }
    result = replaceExecutableRegex(
        result,
        Regex("""\b$variablePattern\s*!=\s*(MobEffects\.[A-Z0-9_]+)\b""")
    ) { match -> "!$variable.is(${match.groupValues[1]})" }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(MobEffects\.[A-Z0-9_]+)\s*==\s*$variablePattern\b""")
    ) { match -> "$variable.is(${match.groupValues[1]})" }
    result = replaceExecutableRegex(
        result,
        Regex("""\b(MobEffects\.[A-Z0-9_]+)\s*!=\s*$variablePattern\b""")
    ) { match -> "!$variable.is(${match.groupValues[1]})" }
    result = replaceExecutableRegex(
        result,
        Regex("""\b$variablePattern\.(isBeneficial|getDescriptionId|getCategory|getDisplayName|isInstantenous|getColor)\s*\(""")
    ) { match -> "$variable.value().${match.groupValues[1]}(" }
    for (field in mobEffectDeferredHolderFields) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b$variablePattern\s*==\s*${Regex.escape(field)}\.get\(\)""")
        ) { "$variable.is($field)" }
        result = replaceExecutableRegex(
            result,
            Regex("""\b$variablePattern\s*!=\s*${Regex.escape(field)}\.get\(\)""")
        ) { "!$variable.is($field)" }
        result = replaceExecutableRegex(
            result,
            Regex("""\breturn\s+${Regex.escape(field)}\.get\(\)\s*;""")
        ) { "return $field;" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateHolderValueAccessorsSource(source: String): String {
    var result = source
    result = replaceExecutableRegex(
        result,
        Regex("""\.getEffect\(\)\.getDescriptionId\(\)""")
    ) { ".getEffect().value().getDescriptionId()" }
    result = replaceExecutableRegex(
        result,
        Regex("""\.getEffect\(\)\s*([!=]=)\s*((?:[A-Za-z_$][\w$]*\.)+[A-Z0-9_$]+)\.get\(\)""")
    ) { match -> ".getEffect().value() ${match.groupValues[1]} ${match.groupValues[2]}.get()" }
    val executableCode = maskJavaCommentsAndLiterals(result)
    val playerSkinVariables = Regex(
        """\b(?:net\.minecraft\.client\.player\.)?(?:AbstractClientPlayer|LocalPlayer|RemotePlayer)\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(executableCode).map { it.groupValues[1] }.toSet()
    for (variable in playerSkinVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\b${Regex.escape(variable)}\.getModelName\(\)""")
        ) { "$variable.getSkin().model().id()" }
    }
    val mobEffectHolderVariables = Regex(
        """\b(?:net\.minecraft\.core\.)?Holder\s*<\s*(?:net\.minecraft\.world\.effect\.)?MobEffect\s*>\s+([A-Za-z_$][\w$]*)\b"""
    ).findAll(executableCode).map { it.groupValues[1] }.toSet()
    for (variable in mobEffectHolderVariables) {
        result = replaceExecutableRegex(
            result,
            Regex("""\bMobEffect\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(variable)}\s*;""")
        ) { match -> "MobEffect ${match.groupValues[1]} = $variable.value();" }
    }
    return result
}

internal fun StructuralRefactorPass.migrateScreenBackgroundRenderedEventSource(source: String): String {
    val executableCode = maskJavaCommentsAndLiterals(source)
    if (!executableCode.contains("ScreenEvent.BackgroundRendered") ||
        !executableCode.contains("renderBackground(GuiGraphics")) {
        return source
    }

    var result = source
    var cursor = 0
    val methodPattern = Regex(
        """(?m)^([ \t]*)@Override\s*\r?\n[ \t]*public\s+void\s+renderBackground\s*\(\s*GuiGraphics\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
    )
    while (true) {
        val executableResult = maskJavaCommentsAndLiterals(result)
        val match = methodPattern.find(executableResult, cursor) ?: break
        val indent = match.groupValues[1]
        val graphicsName = match.groupValues[2]
        val openBrace = executableResult.indexOf('{', match.range.last)
        if (openBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val closeBrace = findMatchingBrace(executableResult, openBrace)
        if (closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        val body = executableResult.substring(openBrace + 1, closeBrace)
        val eventOnlyBody = Regex(
            """(?s)^\s*(?:if\s*\([^{}]+\)\s*\{\s*)?NeoForge\.EVENT_BUS\.post\(\s*new\s+ScreenEvent\.BackgroundRendered\(\s*this\s*,\s*${Regex.escape(graphicsName)}\s*\)\s*\)(?:\.isCanceled\(\))*\s*;\s*(?:\}\s*)?$"""
        )
        if (!eventOnlyBody.matches(body)) {
            cursor = closeBrace + 1
            continue
        }

        val removalStart = javaDeclarationStartWithLeadingMetadata(result, match.range.first)
        val leadingMetadata = result.substring(removalStart, match.range.first)
            .replace(
                "Screen#renderBackground(GuiGraphics)",
                "Screen#renderBackground(GuiGraphics, int, int, float)"
            )
        val replacement = """
${leadingMetadata}${indent}@Override
${indent}public void renderBackground(GuiGraphics $graphicsName, int mouseX, int mouseY, float partialTicks) {
${indent}}
""".trimStart('\n').trimEnd()
        result = result.substring(0, removalStart) + replacement + result.substring(closeBrace + 1)
        cursor = removalStart + replacement.length
    }
    if (result != source) {
        result = removeUnusedSimpleImport(result, "net.neoforged.neoforge.common.NeoForge", "NeoForge")
        result = removeUnusedSimpleImport(result, "net.neoforged.neoforge.client.event.ScreenEvent", "ScreenEvent")
        result = cleanupRedundantBlankLines(result)
    }
    return result
}

/**
 * Screen#renderBackground(GuiGraphics) became renderBackground(GuiGraphics, int, int, float) in
 * 1.20.2+. Inside a method declared with that exact parameter list (render, renderBackground and
 * friends) the call forwards the method's own mouse and partial-tick parameters.
 */
internal fun StructuralRefactorPass.migrateScreenRenderBackgroundCallSites(source: String): String {
    // Screen#renderBackground(GuiGraphics) overrides take 1.21's (GuiGraphics, int, int, float) first,
    // so the calls inside them find the mouse and partial-tick parameters below.
    val widened = migrateScreenRenderBackgroundOverrides(source)
    return migrateScreenRenderBackgroundCallSitesIn(widened)
}

/** `renderBackground(GuiGraphics g)` overrides become `(GuiGraphics g, int mouseX, int mouseY, float partialTick)`, their super call likewise. */
internal fun StructuralRefactorPass.migrateScreenRenderBackgroundOverrides(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    val override = Regex("""@Override\s*(?:public|protected)\s+void\s+renderBackground\s*\(\s*(?:net\.minecraft\.client\.gui\.)?GuiGraphics\s+([A-Za-z_$][\w$]*)\s*\)""")
    if (!override.containsMatchIn(executable)) return source
    var result = replaceExecutableRegex(source, override) { match ->
        match.value.replaceFirst(Regex("""\(\s*((?:net\.minecraft\.client\.gui\.)?GuiGraphics\s+[A-Za-z_$][\w$]*)\s*\)"""), "($1, int mouseX, int mouseY, float partialTick)")
    }
    result = replaceExecutableRegex(result, Regex("""\bsuper\.renderBackground\(\s*([A-Za-z_$][\w$]*)\s*\)""")) { match ->
        "super.renderBackground(${match.groupValues[1]}, mouseX, mouseY, partialTick)"
    }
    return result
}

internal fun StructuralRefactorPass.migrateScreenRenderBackgroundCallSitesIn(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    if (!executable.contains("renderBackground(")) return source
    val call = Regex("""(?<![\w.])((?:this\.|super\.)?renderBackground)\(\s*([A-Za-z_$][\w$]*)\s*\)""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val parameters = javaMethodParameterText(method.header)?.let(::splitTopLevelJavaArgs) ?: continue
        if (parameters.size != 4) continue
        val types = parameters.map { javaParameterSimpleType(it) }
        if (types != listOf("GuiGraphics", "int", "int", "float")) continue
        val names = parameters.map { javaParameterName(it) ?: return@migrateScreenRenderBackgroundCallSitesIn source }
        val body = executable.substring(method.range)
        call.findAll(body).forEach { match ->
            if (match.groupValues[2] != names[0]) return@forEach
            val start = method.range.first + match.range.first
            val end = method.range.first + match.range.last
            edits += (start..end) to "${match.groupValues[1]}(${names[0]}, ${names[1]}, ${names[2]}, ${names[3]})"
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    for ((range, text) in edits.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, text)
    }
    return result
}

/**
 * ServerAdvancementManager.getAllAdvancements() yields AdvancementHolder records in 1.20.2+
 * (id + value); DisplayInfo, criteria and rewards hang off value(), and PlayerAdvancements
 * takes the holder. DisplayInfo.getFrame() became getType() (FrameType -> AdvancementType).
 */
internal fun StructuralRefactorPass.migrateAdvancementHolderIteration(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    if (!executable.contains("getAllAdvancements()") && !executable.contains(".getFrame()")) return source
    var result = source
    val loop = Regex("""\bfor\s*\(\s*(?:final\s+)?Advancement\s+([A-Za-z_$][\w$]*)\s*:\s*((?:[^()]|\([^()]*\))*?\.getAllAdvancements\(\))\s*\)""")
    var cursor = 0
    while (true) {
        val code = maskJavaCommentsAndLiterals(result)
        val match = loop.find(code, cursor) ?: break
        val variable = match.groupValues[1]
        val openBrace = code.indexOf('{', match.range.last)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
        if (openBrace < 0 || closeBrace < 0) {
            cursor = match.range.last + 1
            continue
        }
        var body = result.substring(openBrace, closeBrace + 1)
        val name = Regex.escape(variable)
        body = body.replace(Regex("""\b$name\.getDisplay\(\)"""), "$variable.value().display().orElse(null)")
        body = body.replace(Regex("""\b$name\.getId\(\)"""), "$variable.id()")
        body = body.replace(Regex("""\b$name\.getCriteria\(\)"""), "$variable.value().criteria()")
        body = body.replace(Regex("""\b$name\.getRewards\(\)"""), "$variable.value().rewards()")
        body = body.replace(Regex("""\b$name\.getRequirements\(\)"""), "$variable.value().requirements()")
        val header = "for (AdvancementHolder $variable : ${match.groupValues[2]})"
        result = result.substring(0, match.range.first) + header + result.substring(match.range.last + 1, openBrace) + body + result.substring(closeBrace + 1)
        cursor = match.range.first + header.length
    }
    if (result != source) {
        result = addImportIfMissing(result, "net.minecraft.advancements.AdvancementHolder")
        result = removeUnusedSimpleImport(result, "net.minecraft.advancements.Advancement", "Advancement")
    }
    if (Regex("""(?m)^\s*import\s+net\.minecraft\.advancements\.DisplayInfo\s*;""").containsMatchIn(result)) {
        val displayVariables = Regex("""\bDisplayInfo\s+([A-Za-z_$][\w$]*)\s*[=;)]""")
            .findAll(maskJavaCommentsAndLiterals(result)).map { it.groupValues[1] }.toSet()
        displayVariables.forEach { variable ->
            result = result.replace(Regex("""\b${Regex.escape(variable)}\.getFrame\(\)"""), "$variable.getType()")
        }
    }
    return result
}

/** Expression that yields a RegistryAccess / HolderLookup.Provider from the enclosing method's parameters, if any. */
internal fun StructuralRefactorPass.registryAccessExpressionInScope(methodHeader: String, methodBody: String, beforeOffset: Int = methodBody.length): String? {
    val parameters = javaMethodParameterText(methodHeader)?.let(::splitTopLevelJavaArgs).orEmpty()
    holderLookupProviderFromParameters(parameters)?.let { return it }
    val locals = Regex("""(?m)^\s*(?:final\s+)?([A-Z][\w.<>]*)\s+([A-Za-z_$][\w$]*)\s*=""").findAll(methodBody)
        .filter { it.range.first < beforeOffset }
        .map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList()
    for (declaration in parameters + locals) {
        val type = javaParameterSimpleType(declaration) ?: continue
        val name = javaParameterName(declaration) ?: continue
        val expression = when (type) {
            "RegistryAccess", "HolderLookup.Provider", "Provider" -> name
            "Level", "ServerLevel", "ClientLevel", "LevelAccessor", "WorldGenLevel", "ServerLevelAccessor", "LevelReader" -> "$name.registryAccess()"
            "MinecraftServer", "CommandSourceStack" -> "$name.registryAccess()"
            "GameTestHelper" -> "$name.getLevel().registryAccess()"
            "LevelChunk" -> "$name.getLevel().registryAccess()"
            "Entity", "LivingEntity", "Player", "ServerPlayer", "Mob", "ItemEntity", "Projectile" -> "$name.level().registryAccess()"
            else -> null
        }
        if (expression != null) return expression
    }
    return null
}

/**
 * 1.20.5+ needs a HolderLookup.Provider for block-entity NBT and enchantments live in the dynamic
 * registry: ChunkAccess.getBlockEntityNbtForSaving(pos, provider) and
 * ItemStack.enchant(Holder<Enchantment>, level). The registry access comes from the enclosing
 * method's own Level, Server, Entity or GameTestHelper parameter; without one the call is left
 * for the compiler to report.
 */
internal fun StructuralRefactorPass.migrateRegistryAccessDependentCalls(source: String): String {
    val executable = maskJavaCommentsAndLiterals(source)
    if (!executable.contains("getBlockEntityNbtForSaving(") && !executable.contains(".enchant(Enchantments.")) return source
    val nbtCall = Regex("""\.getBlockEntityNbtForSaving\(\s*((?:[^(),]|\([^()]*\))+?)\s*\)""")
    val enchantCall = Regex("""\.enchant\(\s*(Enchantments\.[A-Z_][A-Z0-9_]*)\s*,""")
    val edits = mutableListOf<Pair<IntRange, String>>()
    var needsRegistries = false
    for (method in javaMethodRangesIncludingDefault(executable)) {
        val body = executable.substring(method.range)
        if (!body.contains("getBlockEntityNbtForSaving(") && !body.contains(".enchant(Enchantments.")) continue
        nbtCall.findAll(body).forEach { match ->
            val access = registryAccessExpressionInScope(method.header, body, match.range.first) ?: return@forEach
            val start = method.range.first + match.range.first
            val end = method.range.first + match.range.last
            edits += (start..end) to ".getBlockEntityNbtForSaving(${match.groupValues[1]}, $access)"
        }
        enchantCall.findAll(body).forEach { match ->
            val access = registryAccessExpressionInScope(method.header, body, match.range.first) ?: return@forEach
            val start = method.range.first + match.range.first
            val end = method.range.first + match.range.last
            edits += (start..end) to ".enchant($access.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(${match.groupValues[1]}),"
            needsRegistries = true
        }
    }
    if (edits.isEmpty()) return source
    var result = source
    for ((range, text) in edits.sortedByDescending { it.first.first }) {
        result = result.replaceRange(range, text)
    }
    if (needsRegistries) result = addImportIfMissing(result, "net.minecraft.core.registries.Registries")
    return result
}

/**
 * Forge's ConfigScreenHandler.ConfigScreenFactory extension point became
 * IConfigScreenFactory (ModContainer, Screen) -> Screen registered on the mod container.
 */
internal fun StructuralRefactorPass.migrateConfigScreenFactoryRegistration(source: String): String {
    if (!source.contains("ConfigScreenHandler.ConfigScreenFactory")) return source
    val handler = """(?:net\.(?:minecraftforge|neoforged\.neoforge)\.client\.)?ConfigScreenHandler\.ConfigScreenFactory"""
    val registration = Regex(
        """((?:net\.(?:minecraftforge|neoforged)\.fml\.)?)ModLoadingContext\.get\(\)\.registerExtensionPoint\(\s*$handler\.class\s*,\s*\(\)\s*->\s*new\s+$handler\(\s*((?:[^()]|\([^()]*\))+?)\s*\)\s*\)"""
    )
    val executable = maskJavaCommentsAndLiterals(source)
    val methodArity = javaMethodRangesIncludingDefault(executable).associate { method ->
        method.name to (javaMethodParameterText(method.header)?.let(::splitTopLevelJavaArgs)?.size ?: -1)
    }
    fun constructorArities(owner: String): Set<Int> =
        Regex("""(?m)^[ \t]*(?:(?:public|protected|private)\s+)?${Regex.escape(owner)}\s*\(([^()]*)\)\s*(?:throws\s+[^{]+)?\{""")
            .findAll(executable)
            .map { match -> match.groupValues[1].trim().let { if (it.isEmpty()) 0 else splitTopLevelJavaArgs(it).size } }
            .toSet()
    var changed = false
    var needsImport = false
    val result = registration.replace(source) { match ->
        val qualified = match.groupValues[1].isNotEmpty()
        val factory = match.groupValues[2].trim()
        val methodReference = Regex("""^([\w.]+)::([A-Za-z_$][\w$]*)$""").matchEntire(factory)
        val lambda = Regex("""^\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*->\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(factory)
        val body = when {
            methodReference != null -> {
                val owner = methodReference.groupValues[1]
                val name = methodReference.groupValues[2]
                when {
                    name == "new" ->
                        if (1 in constructorArities(owner.substringAfterLast('.'))) "new $owner(parent)"
                        else "new $owner(net.minecraft.client.Minecraft.getInstance(), parent)"
                    methodArity[name] == 1 -> "$owner.$name(parent)"
                    else -> "$owner.$name(net.minecraft.client.Minecraft.getInstance(), parent)"
                }
            }
            lambda != null -> lambda.groupValues[3].trim()
                .replace(Regex("""\b${Regex.escape(lambda.groupValues[1])}\b"""), "net.minecraft.client.Minecraft.getInstance()")
                .replace(Regex("""\b${Regex.escape(lambda.groupValues[2])}\b"""), "parent")
            else -> return@replace match.value
        }
        changed = true
        if (!qualified) needsImport = true
        val context = if (qualified) "net.neoforged.fml.ModLoadingContext" else "ModLoadingContext"
        val factoryType = if (qualified) "net.neoforged.neoforge.client.gui.IConfigScreenFactory" else "IConfigScreenFactory"
        "$context.get().getActiveContainer().registerExtensionPoint($factoryType.class, (container, parent) -> $body)"
    }
    if (!changed) return source
    return result
        .replace(Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.client\.ConfigScreenHandler;\s*\r?\n"""), "")
        .let { if (needsImport) addImportIfMissing(it, "net.neoforged.neoforge.client.gui.IConfigScreenFactory") else it }
}

/**
 * `MobEffectInstance` and `LivingEntity#getEffect/hasEffect/removeEffect` take `Holder<MobEffect>` in
 * 1.21, so registry lookups feeding them use `Registry#getHolder`, and a `MobEffectInstance#getEffect()`
 * result that is only read as an effect needs `Holder#value()`.
 */
internal fun StructuralRefactorPass.migrateMobEffectHolderLookups(source: String): String {
    if (!source.contains("MOB_EFFECT.get(") && !source.contains(".getEffect()")) return source
    val args = """((?:[^()]|\([^()]*\))+)"""
    var result = replaceExecutableRegex(
        source,
        Regex("""new\s+MobEffectInstance\(\s*BuiltInRegistries\.MOB_EFFECT\.get\($args\)\s*,""")
    ) { match -> "new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.getHolder(${match.groupValues[1].trim()}).orElseThrow()," }

    val holderUse = Regex("""(?:\.(?:getEffect|hasEffect|removeEffect)\(|new\s+MobEffectInstance\(|null\s*[!=]=)\s*$""")
    val holderUseAfter = Regex("""^\s*[!=]=\s*null""")
    result = rewriteJavaLocalsByUsage(
        result,
        Regex("""(?m)^([ \t]*)(?:var|(?:net\.minecraft\.world\.effect\.)?MobEffect)\s+([A-Za-z_$][\w$]*)\s*=\s*BuiltInRegistries\.MOB_EFFECT\.get\($args\)\s*;"""),
        nameGroup = 2,
        usesMatch = { before, after -> holderUse.containsMatchIn(before) || holderUseAfter.containsMatchIn(after) }
    ) { match -> "${match.groupValues[1]}var ${match.groupValues[2]} = BuiltInRegistries.MOB_EFFECT.getHolder(${match.groupValues[3].trim()}).orElse(null);" }

    val valueUse = Regex("""MOB_EFFECT\.(?:getKey|getId|getResourceKey|wrapAsHolder)\(\s*$""")
    val valueUseAfter = Regex("""^\s*\.(?:isBeneficial|getCategory|getDescriptionId|getDisplayName|getColor|isInstantenous|getAttributeModifiers|createModifiers)\(""")
    result = rewriteJavaLocalsByUsage(
        result,
        Regex("""(?m)^([ \t]*)((?:var|(?:net\.minecraft\.world\.effect\.)?MobEffect)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*\.getEffect\(\))\s*;"""),
        nameGroup = 3,
        usesMatch = { before, after -> valueUse.containsMatchIn(before) || valueUseAfter.containsMatchIn(after) }
    ) { match -> "${match.groupValues[1]}${match.groupValues[2]}.value();" }
    return result
}

/**
 * Rewrites each local matched by [declaration] (its name in group [nameGroup]) when every later use
 * inside the enclosing block satisfies [usesMatch], given the text before and after that use.
 */
internal fun StructuralRefactorPass.rewriteJavaLocalsByUsage(
    source: String,
    declaration: Regex,
    nameGroup: Int,
    usesMatch: (before: String, after: String) -> Boolean,
    replacement: (MatchResult) -> String
): String {
    val executable = maskJavaCommentsAndLiterals(source)
    val edits = mutableListOf<Pair<IntRange, String>>()
    declaration.findAll(executable).forEach { match ->
        val name = match.groupValues[nameGroup]
        val regionStart = match.range.last + 1
        val region = executable.substring(regionStart, enclosingJavaBlockEnd(executable, regionStart))
        val uses = Regex("""(?<![\w$.])${Regex.escape(name)}(?![\w$])""").findAll(region).toList()
        if (uses.isEmpty()) return@forEach
        if (!uses.all { use -> usesMatch(region.substring(0, use.range.first), region.substring(use.range.last + 1)) }) return@forEach
        edits += match.range to replacement(match)
    }
    if (edits.isEmpty()) return source
    var result = source
    edits.asReversed().forEach { (range, text) -> result = result.replaceRange(range, text) }
    return result
}

/** End (exclusive) of the block enclosing [offset], or the source length outside any block. */
internal fun StructuralRefactorPass.enclosingJavaBlockEnd(executable: String, offset: Int): Int {
    var depth = 0
    var cursor = offset
    while (cursor < executable.length) {
        when (executable[cursor]) {
            '{' -> depth++
            '}' -> if (depth == 0) return cursor else depth--
        }
        cursor++
    }
    return executable.length
}

/** `ChunkHolder` futures resolve to `ChunkResult` in 1.21, which replaced `Either#left().orElse`. */
internal fun StructuralRefactorPass.migrateChunkHolderResultAccess(source: String): String {
    val future = """\.get(?:Full|Ticking|EntityTicking)ChunkFuture\(\)"""
    if (!Regex(future).containsMatchIn(source)) return source
    var result = source
    val names = Regex("""\b(?:var|[A-Za-z_$][\w$<>,.? ]*?)\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]*?$future\.getNow\(""")
        .findAll(maskJavaCommentsAndLiterals(result))
        .map { it.groupValues[1] }
        .toSet()
    for (name in names) {
        val escaped = Regex.escape(name)
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.left\(\)\.orElse\(""")) { "$name.orElse(" }
        result = replaceExecutableRegex(result, Regex("""\b$escaped\.left\(\)\.isPresent\(\)""")) { "$name.isSuccess()" }
    }
    return replaceExecutableRegex(result, Regex("""($future\.getNow\([^()]*\))\.left\(\)\.orElse\(""")) { "${it.groupValues[1]}.orElse(" }
}

/** NeoForge item capabilities return the handler (or null) directly instead of a LazyOptional. */
internal fun StructuralRefactorPass.migrateItemCapabilityOptionalAccess(source: String): String {
    if (!source.contains("Capabilities.") || !source.contains(".orElse(")) return source
    return replaceExecutableRegex(
        source,
        Regex("""(\.getCapability\(\s*(?:net\.neoforged\.neoforge\.capabilities\.)?Capabilities\.(?:FluidHandler|ItemHandler|EnergyStorage)\.ITEM\s*\))\.orElse\(\s*null\s*\)""")
    ) { it.groupValues[1] }
}
