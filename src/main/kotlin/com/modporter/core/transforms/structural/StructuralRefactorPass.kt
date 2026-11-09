package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.modporter.core.transforms.shared.HolderLookupProviderPropagationMigration
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

internal val logger = KotlinLogging.logger {}
internal val STRUCTURAL_RESOURCE_JSON = Json {
    prettyPrint = true
}

/**
 * Pass 3: Structural refactoring for complex API migrations.
 * Handles capability system, networking, and other multi-node transformations.
 */
class StructuralRefactorPass : Pass {
    override val name = "Structural Refactor"
    override val order = 3

    /**
     * The project's own classes whose NBT round-trips take a HolderLookup.Provider on 1.21.1:
     * ItemStackHandler subclasses and INBTSerializable<CompoundTag> implementers, whose
     * serializeNBT/deserializeNBT are widened like the platform ones.
     */
    internal var projectNbtHandlerTypeNames: Set<String> = emptySet()

    /** The project's classes that carry a Level (`context.world`), keyed by simple name; see [ProjectLevelCarriers]. */
    internal var projectLevelCarriers: Map<String, String> = emptyMap()

    /** Names of handler-typed values anywhere in the project, so `port.offlineBuffer.deserializeNBT(tag)` is a handler call. */
    internal var projectNbtHandlerFieldNames: Set<String> = emptySet()
    internal var projectItemStackFields: Map<String, Set<String>> = emptyMap()
    internal var projectCreativeTabHolders: Map<String, Set<String>> = emptyMap()

    /** Frame-time accessor names the project declares itself; see [MinecraftFrameTimeCalls]. */
    internal var projectFrameTimeMethods: Set<String> = emptySet()

    /** Registry element types and type hierarchy for registry-typed library arguments; see [LibraryTypeArguments]. */
    internal var projectRegistryElements: RegistryElementTypes? = null
    internal var projectRecipeInputPositions: Map<String, Set<Int>> = mapOf("Recipe" to setOf(0))
    internal var projectEnumRecipeTypeInputs: EnumRecipeTypeInputs = EnumRecipeTypeInputs.empty()
    internal var projectValueFacts: ProjectValueFacts = ProjectValueFacts()

    /**
     * The project's direct-superclass map, collected before the per-file rules run. The full
     * [projectValueFacts] are only collected by the late project-level migrations, so per-file
     * rules that must walk a project class to its platform base read this map.
     */
    internal var projectParents: Map<String, String> = emptyMap()

    /** Project-owned legacy ITeleporter implementations and their subclasses, collected before per-file rewrites. */
    internal var projectLegacyTeleporterTypes: Set<String> = emptySet()

    /** The early, inventory-backed migration owns legacy plant semantics for this invocation. */
    internal var legacyPlantRuntimeOwned: Boolean = false

    /** (owner, name) of the widened NBT methods that are static, so `Owner::read` becomes `tag -> Owner.read(tag, registries)`. */
    internal var staticHolderLookupNbtMethods: Set<Pair<String, String>> = emptySet()

    override fun analyze(projectDir: Path): PassResult = processFiles(projectDir, dryRun = true)
    override fun apply(projectDir: Path): PassResult = processFiles(projectDir, dryRun = false)

    internal fun parseProblems(parseResult: com.github.javaparser.ParseResult<CompilationUnit>): String =
        parseResult.problems.joinToString("; ") { it.verboseMessage }.ifBlank { "unknown parse failure" }

    internal fun parserLanguageLevel(parser: JavaParser): String =
        parser.parserConfiguration.languageLevel.toString()

    internal fun processFiles(projectDir: Path, dryRun: Boolean): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))

        legacyPlantRuntimeOwned = false
        if (LegacyMigrationArtifactInventoryHandoff.isPublished(projectDir) ||
            projectDir.resolve(".modporter/legacy-plant-compat.required").exists()
        ) {
            try {
                LegacyMigrationArtifactInventoryHandoff.require(projectDir)
                LegacySourceContractOwnership.requireApplied(projectDir, dryRun)
                legacyPlantRuntimeOwned = true
            } catch (e: Exception) {
                errors.add("Early legacy compatibility preflight error: ${e.message}")
                return PassResult(name, changes, errors, skipped)
            }
        }

        val journeyMap = CreateJourneyMapMigration.migrate(this, projectDir, dryRun)
        changes.addAll(journeyMap.changes)
        errors.addAll(journeyMap.gates)
        if (journeyMap.gates.isNotEmpty()) {
            return PassResult(name, changes, errors, skipped)
        }

        val javaFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/") ||
                    rel.startsWith("src/references/") || rel.contains("/src/references/") ||
                    rel.startsWith("src/main/java/com/modporter/generated/")
            }}
            .toList()

        try {
            changes.addAll(migrateLegacyPortalFamilyTransaction(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy portal family transaction error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        projectParents = runCatching { ProjectValueFacts.collect(this, javaFiles).parents }.getOrDefault(emptyMap())
        projectLegacyTeleporterTypes = collectLegacyTeleporterTypes(javaFiles)
        val projectDistExecutorTypes = javaFiles.mapNotNull { javaFile ->
            val javaSource = runCatching { javaFile.readText() }.getOrDefault("")
            val executable = maskJavaCommentsAndLiterals(javaSource)
            if (!Regex("""\b(?:class|interface|enum|record)\s+DistExecutor\b""").containsMatchIn(executable)) {
                return@mapNotNull null
            }
            packageNameOf(javaSource).let { packageName ->
                if (packageName.isBlank()) "DistExecutor" else "$packageName.DistExecutor"
            }
        }.toSet()

        for (file in javaFiles) {
            try {
                val source = file.readText()
                val fileChanges = mutableListOf<Change>()
                val hasNativeSimpleDistExecutorOwner =
                    hasNativeDistExecutorEvidence(source, projectDistExecutorTypes)
                var distSource = migrateDistExecutorDistGuards(
                    source,
                    file,
                    fileChanges,
                    projectDistExecutorTypes
                )
                distSource = migrateClientPlayerDistLinkage(distSource, file, fileChanges)
                distSource = migrateClientOnlyEventBusSubscriberDist(distSource, file, fileChanges)
                distSource = migrateAbstractScreenInitSubscriberFanout(distSource, file, fileChanges)
                val itemAttributeSource = migrateLegacyItemAttributeModifierOverrides(distSource)
                if (itemAttributeSource != distSource) {
                    fileChanges.add(Change(
                        file = file,
                        line = 1,
                        description = "Migrate direct stack-sensitive item attribute override to 1.21 item attribute components",
                        before = "getAttributeModifiers(EquipmentSlot, ItemStack) builds a Multimap directly",
                        after = "getDefaultAttributeModifiers(ItemStack) builds ItemAttributeModifiers directly",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-item-direct-dynamic-attribute-component"
                    ))
                    distSource = itemAttributeSource
                }
                val parseResult = parser.parse(distSource)
                if (!parseResult.isSuccessful) {
                    changes.addAll(fileChanges)
                    skipped.add(
                        "Could not parse $file for structural transforms " +
                            "(languageLevel=${parserLanguageLevel(parser)}): ${parseProblems(parseResult)}"
                    )
                    if (!dryRun && distSource != source) {
                        file.writeText(distSource)
                    }
                    continue
                }

                val cu = parseResult.result.orElse(null) ?: continue
                val preAstSource = migrateItemStackCapabilityOptionals(distSource, cu, file, fileChanges)
                val migratedParseResult = if (preAstSource != distSource) parser.parse(preAstSource) else parseResult
                if (!migratedParseResult.isSuccessful) {
                    changes.addAll(fileChanges)
                    skipped.add(
                        "Could not parse $file after pre-AST structural rewrites " +
                            "(languageLevel=${parserLanguageLevel(parser)}): ${parseProblems(migratedParseResult)}"
                    )
                    if (!dryRun && preAstSource != source) {
                        file.writeText(preAstSource)
                    }
                    continue
                }
                val migratedCu = migratedParseResult.result.orElse(null) ?: continue

                // Detect and transform capability patterns
                detectCapabilityPatterns(migratedCu, file, fileChanges)

                // Detect and transform networking patterns
                detectNetworkingPatterns(migratedCu, file, fileChanges)

                // Detect LazyOptional usage
                detectLazyOptionalUsage(migratedCu, file, fileChanges)

                // Detect DistExecutor usage (removed in NeoForge)
                detectDistExecutorUsage(
                    migratedCu,
                    file,
                    fileChanges,
                    hasNativeSimpleDistExecutorOwner
                )

                changes.addAll(fileChanges)

                if (!dryRun && fileChanges.isNotEmpty()) {
                    file.writeText(migratedCu.toString())
                }
            } catch (e: Exception) {
                errors.add("Structural analysis error in $file: ${e.message}")
            }
        }

        var closedLegacyParticleFiles: Set<Path> = emptySet()
        try {
            val particleOutcome = LegacyParticleSerializationMigration().migrate(projectDir, dryRun)
            changes.addAll(particleOutcome.changes)
            closedLegacyParticleFiles = particleOutcome.ownedFiles
        } catch (e: Exception) {
            errors.add("Legacy particle serialization migration error: ${e.message}")
        }

        try {
            changes.addAll(FinalTestFunctionSubclassAdapters.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Final TestFunction adapter migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyBlockSourceAdapterMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("BlockSource adapter migration error: ${e.message}")
        }

        try {
            changes.addAll(FinalBlockSourceAdapters.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Final BlockSource adapter migration error: ${e.message}")
        }

        // Replace obsolete WorldGenRegion private-field accessors with the
        // public ServerLevel structure manager path before later source scans.
        try {
            val worldGenRegionAccessorChanges = migrateLegacyWorldGenRegionStructureManagerAccessor(projectDir, dryRun)
            changes.addAll(worldGenRegionAccessorChanges)
        } catch (e: Exception) {
            errors.add("WorldGenRegion structure manager accessor migration error: ${e.message}")
        }

        try {
            val recipeAccessorChanges = cleanupRecursiveRecipeDimensionAccessors(projectDir, dryRun)
            changes.addAll(recipeAccessorChanges)
        } catch (e: Exception) {
            errors.add("Recipe shaped accessor cleanup error: ${e.message}")
        }

        try {
            val configValidatorChanges = migrateConfigResourceLocationListValidators(projectDir, dryRun)
            changes.addAll(configValidatorChanges)
        } catch (e: Exception) {
            errors.add("Config ResourceLocation list validator migration error: ${e.message}")
        }

        try {
            val structureEntityProcessorChanges = migrateBlockAttachedStructureTemplateProcessors(projectDir, dryRun)
            changes.addAll(structureEntityProcessorChanges)
        } catch (e: Exception) {
            errors.add("Block-attached structure entity processor migration error: ${e.message}")
        }

        try {
            val animalFoodChanges = migrateAnimalIsFoodOverrides(javaFiles, dryRun)
            changes.addAll(animalFoodChanges)
        } catch (e: Exception) {
            errors.add("Animal isFood override migration error: ${e.message}")
        }

        try {
            val idBackedEntityLookupChanges = migrateIdBackedEntitySpatialLookups(javaFiles, dryRun)
            changes.addAll(idBackedEntityLookupChanges)
        } catch (e: Exception) {
            errors.add("ID-backed entity spatial lookup migration error: ${e.message}")
        }

        try {
            val payloadContextChanges = migrateInstancePayloadContextHandlers(projectDir, dryRun)
            changes.addAll(payloadContextChanges)
        } catch (e: Exception) {
            errors.add("Instance payload context migration error: ${e.message}")
        }

        // Packet tables: an abstract packet base plus an enum of (Packet.class, Packet::new, direction)
        // entries feeding SimpleChannel#messageBuilder become payloads registered through
        // RegisterPayloadHandlersEvent, before the other packet passes look at those files.
        try {
            val tableModId = detectModId(projectDir)
            if (tableModId != null) changes.addAll(PacketTablePayloads.migrate(projectDir, tableModId, dryRun))
        } catch (e: Exception) {
            errors.add("Packet table payload migration error: ${e.message}")
        }
        // Raw-byte event channels become payloads carrying the same bytes on the same id.
        try {
            val channelModId = detectModId(projectDir)
            if (channelModId != null) changes.addAll(ForgeEventNetworkChannels.migrate(projectDir, channelModId, dryRun))
        } catch (e: Exception) {
            errors.add("Event network channel migration error: ${e.message}")
        }
        // The payload stream codecs hand their writers and readers a RegistryFriendlyByteBuf; carrying
        // that through the project's signatures now lets the NBT provider threading see the registries
        // a buffer already brings (the pass repeats once the single-file rules have run).
        try {
            changes.addAll(migrateRegistryBufferParameters(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Registry buffer parameter propagation error: ${e.message}")
        }

        // Particle data that writes and reads itself keeps that contract behind generated
        // interfaces; its ParticleType gains the MapCodec/StreamCodec pair 1.20.5 requires.
        try {
            changes.addAll(
                ParticleOptionsCodecs.migrate(
                    projectDir,
                    { detectRequiredGeneratedCompatPackage(projectDir, "particle options codecs") },
                    dryRun,
                    closedLegacyParticleFiles
                )
            )
        } catch (e: Exception) {
            errors.add("Particle options codec migration error: ${e.message}")
        }

        // DFU 7 dispatches over map codecs: a project's dispatched codec family moves to MapCodec.
        try {
            changes.addAll(DispatchedCodecFamilies.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Dispatched codec family migration error: ${e.message}")
        }

        // DistExecutor calls the statement-level guards could not express use a generated typed
        // adapter backed by FMLLoader.getDist().
        try {
            changes.addAll(DistExecutorCompat.migrate(projectDir, { detectRequiredGeneratedCompatPackage(projectDir, "DistExecutor compat") }, dryRun))
        } catch (e: Exception) {
            errors.add("DistExecutor compat migration error: ${e.message}")
        }

        // Migrate networking helper classes that declare nested Forge
        // SimpleChannel packet definitions before the generic packet pass
        // treats the helper as a removable registration wrapper.
        try {
            val nestedNetworkChanges = migrateKnownNestedSimpleChannelNetworking(projectDir, dryRun)
            changes.addAll(nestedNetworkChanges)
        } catch (e: Exception) {
            errors.add("Nested SimpleChannel networking migration error: ${e.message}")
        }

        // Transform packet classes to implement CustomPacketPayload + generate ModNetwork.java
        try {
            val packetChanges = transformPacketClasses(projectDir, dryRun)
            changes.addAll(packetChanges)
        } catch (e: Exception) {
            errors.add("Packet class transformation error: ${e.message}")
        }

        // Migrate legacy Nitrogen/Forge packet classes that used BasePacket's
        // instance encode/decode/execute contract onto NeoForge payloads.
        try {
            val basePacketChanges = migrateBasePacketPayloads(projectDir, dryRun)
            changes.addAll(basePacketChanges)
        } catch (e: Exception) {
            errors.add("BasePacket payload migration error: ${e.message}")
        }

        // Remove EVENT_BUS.register(this) from @Mod classes without @SubscribeEvent methods
        try {
            val busChanges = removeEmptyEventBusRegistration(projectDir, dryRun)
            changes.addAll(busChanges)
        } catch (e: Exception) {
            errors.add("Event bus cleanup error: ${e.message}")
        }

        // Migrate FMLJavaModLoadingContext constructor to IEventBus/ModContainer
        try {
            val fmlChanges = migrateFMLJavaModLoadingContext(projectDir, dryRun)
            changes.addAll(fmlChanges)
        } catch (e: Exception) {
            errors.add("FMLJavaModLoadingContext migration error: ${e.message}")
        }

        try {
            val fmlHelperChanges = migrateStaticFmlModEventBusHelperInitializers(projectDir, dryRun)
            changes.addAll(fmlHelperChanges)
        } catch (e: Exception) {
            errors.add("Static FML mod event bus helper migration error: ${e.message}")
        }

        // Migrate legacy Item#initCapabilities fluid bucket providers to
        // RegisterCapabilitiesEvent item registrations.
        try {
            val bucketCapabilityChanges = migrateFluidBucketItemCapabilities(projectDir, dryRun)
            changes.addAll(bucketCapabilityChanges)
        } catch (e: Exception) {
            errors.add("Fluid bucket capability migration error: ${e.message}")
        }

        // Migrate custom fluid item providers that used Item#initCapabilities
        // and an ICapabilityProvider wrapper around IFluidHandlerItem.
        try {
            val customFluidCapabilityChanges = migrateCustomFluidItemCapabilities(projectDir, dryRun)
            changes.addAll(customFluidCapabilityChanges)
        } catch (e: Exception) {
            errors.add("Custom fluid capability migration error: ${e.message}")
        }

        // Migrate item-stack-backed Item#initCapabilities inventory providers
        // to registered item capabilities backed by vanilla container data components.
        try {
            val itemStackHandlerItemCapabilityChanges = migrateItemStackHandlerItemCapabilities(projectDir, dryRun)
            changes.addAll(itemStackHandlerItemCapabilityChanges)
        } catch (e: Exception) {
            errors.add("ItemStackHandler item capability migration error: ${e.message}")
        }

        // Migrate direct FluidHandlerItemStack Item#initCapabilities hooks to
        // registered item capabilities backed by SimpleFluidContent components.
        try {
            val directFluidHandlerChanges = migrateDirectFluidHandlerItemStackCapabilities(projectDir, dryRun)
            changes.addAll(directFluidHandlerChanges)
        } catch (e: Exception) {
            errors.add("Direct fluid handler item capability migration error: ${e.message}")
        }

        // Migrate legacy Curios Item#initCapabilities providers to the
        // RegisterCapabilitiesEvent item capability registration model.
        try {
            val curiosCapabilityChanges = migrateCuriosItemCapabilities(projectDir, dryRun)
            changes.addAll(curiosCapabilityChanges)
        } catch (e: Exception) {
            errors.add("Curios item capability migration error: ${e.message}")
        }

        // Migrate legacy custom entity capabilities declared through
        try {
            changes.addAll(migrateSelfCapabilityAttachments(projectDir, dryRun))
            changes.addAll(removeDirectlySubscribedEventForwarders(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Serializable self capability attachment migration error: ${e.message}")
        }

        // CapabilityManager + AttachCapabilitiesEvent to NeoForge EntityCapability
        // registrations while preserving LazyOptional call chains.
        try {
            val wrappedSerializableChanges = WrappedSerializableEntityCapabilityMigration()
                .apply(projectDir, dryRun, errors)
            changes.addAll(wrappedSerializableChanges)
            val providerBackedCapabilityChanges = migrateProviderBackedEntityCapabilities(projectDir, dryRun, errors)
            changes.addAll(providerBackedCapabilityChanges)
            val customEntityCapabilityChanges = migrateCustomEntityCapabilities(projectDir, dryRun, errors)
            changes.addAll(customEntityCapabilityChanges)
        } catch (e: Exception) {
            errors.add("Custom entity capability migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateLegacyGenericEvents(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Generic event base migration error: ${e.message}")
        }

        // Migrate third-party Nitrogen's old capability sync surface to the
        // attachment/payload surface exposed by its 1.21.1 NeoForge build.
        try {
            val nitrogenChanges = migrateNitrogenAttachmentApi(projectDir, dryRun)
            changes.addAll(nitrogenChanges)
        } catch (e: Exception) {
            errors.add("Nitrogen attachment API migration error: ${e.message}")
        }

        // Quark 1.21 replaced the old InventoryIIH backpack item-handler
        // wrapper with the public BackpackContainer used by BackpackSlot.
        try {
            val quarkBackpackChanges = migrateQuarkBackpackInventoryWrappers(projectDir, dryRun)
            changes.addAll(quarkBackpackChanges)
        } catch (e: Exception) {
            errors.add("Quark backpack inventory wrapper migration error: ${e.message}")
        }

        // Migrate block-entity fluid handlers from legacy getCapability()
        // overrides to RegisterCapabilitiesEvent block-entity registrations.
        try {
            val blockEntityFluidCapabilityChanges = migrateBlockEntityFluidCapabilities(projectDir, dryRun)
            changes.addAll(blockEntityFluidCapabilityChanges)
        } catch (e: Exception) {
            errors.add("Block entity fluid capability migration error: ${e.message}")
        }

        // Migrate remaining source-shaped block-entity capability overrides
        // to RegisterCapabilitiesEvent providers. Candidate getCapability()
        // shapes hard-gate when their provider expressions cannot be derived.
        try {
            val blockEntityCapabilityChanges = migrateBlockEntityLegacyCapabilities(projectDir, dryRun, errors)
            changes.addAll(blockEntityCapabilityChanges)
        } catch (e: Exception) {
            errors.add("Block entity capability migration error: ${e.message}")
        }

        // Block entities that still answer getCapability(Capability, Direction) keep it as the
        // mod's own API; generated providers forward NeoForge's queries into it.
        try {
            val capabilityModId = detectModId(projectDir)
            if (capabilityModId != null) {
                val legacy = LegacyBlockEntityCapabilities.migrate(
                    projectDir,
                    capabilityModId,
                    { detectRequiredGeneratedCompatPackage(projectDir, "legacy block entity capabilities") },
                    dryRun,
                    errors
                )
                changes.addAll(legacy.changes)
                errors.removeAll(LegacyBlockEntityCapabilities.withdrawnGates(errors, projectDir, legacy.adaptedFiles))
                errors.addAll(legacy.gates)
                val queries = LegacyBlockEntityCapabilityQueries.migrate(projectDir,
                    { detectRequiredGeneratedCompatPackage(projectDir, "legacy block entity capability queries") }, dryRun)
                changes.addAll(queries.changes)
                errors.addAll(queries.gates)
                if (queries.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
                val peripheral = LegacyComputerCraftPeripheralCapabilities.migrate(projectDir, capabilityModId,
                    detectRequiredGeneratedCompatPackage(projectDir, "ComputerCraft peripheral capability"), dryRun)
                changes.addAll(peripheral.changes)
                errors.addAll(peripheral.gates)
            }
        } catch (e: Exception) {
            errors.add("Legacy block entity capability migration error: ${e.message}")
        }

        try {
            val forcedChunkModId = detectModId(projectDir)
            if (forcedChunkModId != null) {
                val forcedChunks = LegacyForcedChunkTickets.migrate(projectDir, forcedChunkModId,
                    { detectRequiredGeneratedCompatPackage(projectDir, "legacy forced chunk tickets") }, dryRun)
                changes.addAll(forcedChunks.changes)
                errors.addAll(forcedChunks.gates)
            }
        } catch (e: Exception) {
            errors.add("Legacy forced chunk ticket migration error: ${e.message}")
        }

        // Rewrite legacy capability lookups through source-declared facade
        // get(Player) methods before the facade itself is converted.
        try {
            val capabilityFacadeLookupChanges = migrateLegacyCapabilityFacadeLookups(projectDir, dryRun)
            changes.addAll(capabilityFacadeLookupChanges)
        } catch (e: Exception) {
            errors.add("Legacy capability facade lookup migration error: ${e.message}")
        }

        // Migrate legacy player capability facades to NeoForge attachments.
        try {
            val capabilityFacadeChanges = migrateLegacyCapabilityFacadeToAttachment(projectDir, dryRun, errors)
            changes.addAll(capabilityFacadeChanges)
        } catch (e: Exception) {
            errors.add("Legacy capability facade attachment migration error: ${e.message}")
        }

        // Migrate legacy advancement criterion triggers to the 1.21 codec
        // shape and register trigger types through DeferredRegister.
        try {
            changes.addAll(LegacyCriterionTriggerHierarchyMigration().migrate(projectDir, dryRun))
            val advancementTriggerChanges = migrateAdvancementCriterionTriggers(projectDir, dryRun)
            changes.addAll(advancementTriggerChanges)
        } catch (e: Exception) {
            errors.add("Advancement criterion trigger migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyJukeboxApiMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Jukebox component migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyDefaultTooltipFlagsMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Default tooltip flags migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyConfigScreenFactoryMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Config screen factory migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyPositionImplMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("PositionImpl value migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyProjectileDispenseAccessorMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Projectile dispense accessor migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            changes.addAll(LegacyMultiItemValueMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("MultiItemValue migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyItemStackBooleanEqualsMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("ItemStack boolean equals migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyTrackingChunkPacketTargetMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Tracking chunk packet target migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyDirectTrackingChunkTargetMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Direct tracking chunk target migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyPacketTargetMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy packet target migration error: ${e.message}")
        }

        // Forge criterion instances that only carry the id and an ANY player predicate.
        try {
            changes.addAll(LegacyCriterionInstances.migrate(projectDir, dryRun, errors))
        } catch (e: Exception) {
            errors.add("Legacy criterion instance migration error: ${e.message}")
        }

        try {
            val conditionCodecChanges = migrateLegacyConditionCodecRegistrations(projectDir, dryRun)
            changes.addAll(conditionCodecChanges)
        } catch (e: Exception) {
            errors.add("Condition codec registration migration error: ${e.message}")
        }

        // Minecraft 1.21 made enchantments registry/data driven. Old subclasses
        // that are no longer constructed after key migration cannot extend the
        // Enchantment record; keep any static helper logic as plain utility code.
        try {
            val legacyEnchantmentChanges = rewriteUnusedLegacyEnchantmentSubclasses(projectDir, dryRun)
            changes.addAll(legacyEnchantmentChanges)
        } catch (e: Exception) {
            errors.add("Legacy enchantment subclass migration error: ${e.message}")
        }

        // NeoForge removed Rarity.create; preserve custom rarity semantics with
        // enum extension metadata and a generated parameter helper.
        try {
            val rarityEnumExtensionChanges = migrateLegacyRarityEnumExtensions(projectDir, dryRun)
            changes.addAll(rarityEnumExtensionChanges)
        } catch (e: Exception) {
            errors.add("Rarity enum extension migration error: ${e.message}")
        }

        try {
            val grassColorEnumExtensionChanges = migrateLegacyGrassColorModifierEnumExtensions(projectDir, dryRun)
            changes.addAll(grassColorEnumExtensionChanges)
        } catch (e: Exception) {
            errors.add("Grass color enum extension migration error: ${e.message}")
        }

        try {
            val recipeBookEnumExtensionChanges = migrateLegacyRecipeBookCategoryEnumExtensions(projectDir, dryRun)
            changes.addAll(recipeBookEnumExtensionChanges)
        } catch (e: Exception) {
            errors.add("Recipe book category enum extension migration error: ${e.message}")
        }

        try {
            val recipeBookTypeEnumExtensionChanges = migrateLegacyRecipeBookTypeEnumExtensions(projectDir, dryRun)
            changes.addAll(recipeBookTypeEnumExtensionChanges)
        } catch (e: Exception) {
            errors.add("Recipe book type enum extension migration error: ${e.message}")
        }

        try {
            val customRecipeBookFinderChanges = migrateCustomRecipeBookCategoryFinders(projectDir, dryRun)
            changes.addAll(customRecipeBookFinderChanges)
        } catch (e: Exception) {
            errors.add("Custom recipe book category finder migration error: ${e.message}")
        }

        try {
            val mobCategoryEnumExtensionChanges = migrateLegacyMobCategoryEnumExtensions(projectDir, dryRun)
            changes.addAll(mobCategoryEnumExtensionChanges)
        } catch (e: Exception) {
            errors.add("Mob category enum extension migration error: ${e.message}")
        }

        try {
            val pathTypeCreateChanges = migrateLegacyPathTypeCreateConstants(projectDir, dryRun)
            changes.addAll(pathTypeCreateChanges)
        } catch (e: Exception) {
            errors.add("PathType.create migration error: ${e.message}")
        }

        try {
            val imageButtonChanges = migrateLegacyImageButtonConstructors(projectDir, dryRun)
            changes.addAll(imageButtonChanges)
        } catch (e: Exception) {
            errors.add("Legacy ImageButton constructor migration error: ${e.message}")
        }

        try {
            val stateSwitchingButtonChanges = migrateLegacyStateSwitchingButtonTextures(projectDir, dryRun)
            changes.addAll(stateSwitchingButtonChanges)
        } catch (e: Exception) {
            errors.add("Legacy StateSwitchingButton texture migration error: ${e.message}")
        }

        try {
            val curiosClientChanges = migrateCuriosClientApi(projectDir, dryRun)
            changes.addAll(curiosClientChanges)
        } catch (e: Exception) {
            errors.add("Curios client API migration error: ${e.message}")
        }

        // Convert stale Class::register mod-bus listeners to direct
        // DeferredRegister.register(bus) calls when the target class already
        // declares a DeferredRegister field.
        try {
            val deferredRegisterListenerChanges = migrateDeferredRegisterListenerReferences(projectDir, dryRun)
            changes.addAll(deferredRegisterListenerChanges)
        } catch (e: Exception) {
            errors.add("DeferredRegister listener migration error: ${e.message}")
        }

        // BowlFoodItem was folded into food component data. Preserve the old
        // "returns bowl after eating" semantics on every source-derived subclass.
        try {
            val bowlFoodItemChanges = migrateLegacyBowlFoodItemSubclasses(projectDir, dryRun)
            changes.addAll(bowlFoodItemChanges)
        } catch (e: Exception) {
            errors.add("BowlFoodItem subclass migration error: ${e.message}")
        }

        try {
            changes.addAll(ExactLegacyCustomDataMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Exact legacy custom-data graph migration error: ${e.message}")
        }

        // Migrate common 1.21 API surface changes that are too broad for
        // project-specific rewrites: split tick events, BlockEntity NBT hooks,
        // tooltip context imports, and Holder<MobEffect> call sites.
        try {
            val componentTransport = LegacyItemComponentPatchTransport.migrate(projectDir, dryRun)
            changes.addAll(componentTransport.changes)
            errors.addAll(componentTransport.gates)
            if (componentTransport.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Legacy ItemStack component transport migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val datagenIngredient = DatagenIngredientValues.migrate(this, projectDir, dryRun)
            changes.addAll(datagenIngredient.changes)
            errors.addAll(datagenIngredient.gates)
            if (datagenIngredient.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Datagen ingredient value migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val stationMaps = CreateStationMapDecorationMigration.migrate(this, projectDir, dryRun)
            changes.addAll(stationMaps.changes)
            errors.addAll(stationMaps.gates)
            if (stationMaps.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Create station map decoration migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            changes.addAll(migrateLexicallyBoundDeferredHolderGenerics(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Lexically bound DeferredHolder generic migration error: ${e.message}")
        }

        try {
            val commonApiChanges = migrateCommonNeoForge121Apis(projectDir, dryRun, errors)
            changes.addAll(commonApiChanges)
            if (!dryRun) {
                changes.addAll(HolderLookupProviderPropagationMigration().migrate(projectDir, dryRun = false))
            }
            changes.addAll(LegacyMutableInventoryMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Common NeoForge 1.21 API migration error: ${e.message}")
        }

        // Handle exact residual BlockEntity queries only after the broader
        // source-relationship migration has consumed the shapes it owns.
        try {
            val queryChanges = ExactBlockEntityCapabilityQueryMigration(
                projectDir.resolve("src/main/java"),
                plannedLazyOptionalType = {
                    detectRequiredGeneratedCompatPackage(
                        projectDir,
                        "exact BlockEntity capability query migration"
                    ) + ".LazyOptional"
                }
            ).migrate(dryRun)
            changes.addAll(queryChanges)
        } catch (e: Exception) {
            errors.add("Block entity capability query migration error: ${e.message}")
        }

        try {
            changes.addAll(ExactFluidTankProviderCallMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Exact FluidTank provider call migration error: ${e.message}")
        }

        try {
            changes.addAll(ExactExternalProviderCallMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Exact external provider call migration error: ${e.message}")
        }

        try {
            changes.addAll(ExactProjectProviderCallMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Exact project provider call migration error: ${e.message}")
        }

        try {
            val savedDataBackups = SourceOwnedSavedDataBackups.migrate(projectDir, dryRun)
            changes.addAll(savedDataBackups.changes)
            errors.addAll(savedDataBackups.gates)
            if (savedDataBackups.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Source-owned SavedData backup migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val structureStartLoadChanges = migrateProjectStructureStartLoadFromTagCalls(projectDir, dryRun)
            changes.addAll(structureStartLoadChanges)
        } catch (e: Exception) {
            errors.add("StructureStart load migration error: ${e.message}")
        }

        try {
            val containerHelperThreadChanges = migrateProjectContainerHelperProviderMethods(projectDir, dryRun)
            changes.addAll(containerHelperThreadChanges)
        } catch (e: Exception) {
            errors.add("ContainerHelper provider threading migration error: ${e.message}")
        }

        try {
            val mapEntryChanges = migrateProjectMapEntryValueGetCalls(projectDir, dryRun)
            changes.addAll(mapEntryChanges)
        } catch (e: Exception) {
            errors.add("Map.Entry value get migration error: ${e.message}")
        }

        try {
            val clientColorChanges = migrateDeprecatedClientColorRegistrations(projectDir, dryRun)
            changes.addAll(clientColorChanges)
        } catch (e: Exception) {
            errors.add("Client color registration migration error: ${e.message}")
        }

        try {
            val renderLayerChanges = migrateDeprecatedRenderLayerRegistrations(projectDir, dryRun)
            changes.addAll(renderLayerChanges)
        } catch (e: Exception) {
            errors.add("Render layer resource migration error: ${e.message}")
        }

        try {
            val fluidClientExtensionChanges = migrateFluidTypeClientExtensionRegistrations(projectDir, dryRun)
            changes.addAll(fluidClientExtensionChanges)
        } catch (e: Exception) {
            errors.add("FluidType client extension migration error: ${e.message}")
        }

        try {
            val levelReaderDeprecationChanges = migrateDeprecatedLevelReaderHasChunksAtCalls(projectDir, dryRun)
            changes.addAll(levelReaderDeprecationChanges)
        } catch (e: Exception) {
            errors.add("LevelReader hasChunksAt migration error: ${e.message}")
        }

        try {
            val xlintBridgeChanges = migrateXlintCleanApiBridges(projectDir, dryRun)
            changes.addAll(xlintBridgeChanges)
        } catch (e: Exception) {
            errors.add("Xlint-clean API bridge migration error: ${e.message}")
        }

        try {
            val livingDamageHelperChanges = migrateLivingDamageEventHelperBoundaries(projectDir, dryRun)
            changes.addAll(livingDamageHelperChanges)
        } catch (e: Exception) {
            errors.add("Living damage helper boundary migration error: ${e.message}")
        }

        try {
            val entityLevelChanges = migrateEntityLevelAccessorCalls(projectDir, dryRun)
            changes.addAll(entityLevelChanges)
        } catch (e: Exception) {
            errors.add("Entity level accessor migration error: ${e.message}")
        }

        try {
            val cumulusChanges = migrateCumulusMenuApi(projectDir, dryRun)
            changes.addAll(cumulusChanges)
        } catch (e: Exception) {
            errors.add("Cumulus menu API migration error: ${e.message}")
        }

        try {
            val registerAdditionalModelChanges = migrateRegisterAdditionalModelResourceLocations(projectDir, dryRun)
            changes.addAll(registerAdditionalModelChanges)
        } catch (e: Exception) {
            errors.add("RegisterAdditional model migration error: ${e.message}")
        }

        try {
            val deferredRegisterListenerChanges = migrateDeferredRegisterListenerReferences(projectDir, dryRun)
            changes.addAll(deferredRegisterListenerChanges)
        } catch (e: Exception) {
            errors.add("DeferredRegister listener migration error: ${e.message}")
        }

        try {
            val missingDeferredRegisterChanges = registerMissingDeferredRegisterFields(projectDir, dryRun)
            changes.addAll(missingDeferredRegisterChanges)
        } catch (e: Exception) {
            errors.add("DeferredRegister completeness migration error: ${e.message}")
        }

        try {
            val dataDrivenEnchantmentChanges = removeDataDrivenEnchantmentDeferredRegisterCalls(projectDir, dryRun)
            changes.addAll(dataDrivenEnchantmentChanges)
        } catch (e: Exception) {
            errors.add("Data-driven enchantment register cleanup error: ${e.message}")
        }

        try {
            val missingMappingChanges = migrateMissingMappingsAliases(projectDir, dryRun)
            changes.addAll(missingMappingChanges)
        } catch (e: Exception) {
            errors.add("MissingMappings alias migration error: ${e.message}")
        }

        // Forge/early-NeoForge built-in pack helpers were removed in 1.21.
        // Preserve their source semantics through explicit PackLocationInfo,
        // PackSelectionConfig, and generated PackResources adapters.
        try {
            val packResourceChanges = migrateLegacyPackResourceApis(projectDir, dryRun)
            changes.addAll(packResourceChanges)
        } catch (e: Exception) {
            errors.add("Legacy pack resource API migration error: ${e.message}")
        }

        try {
            changes.addAll(migratePackResourceLocations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Pack resource location binding migration error: ${e.message}")
        }

        try {
            val itemAttributeChanges = migrateLegacyItemAttributeModifierOverrides(projectDir, dryRun)
            changes.addAll(itemAttributeChanges)
        } catch (e: Exception) {
            errors.add("Item attribute modifier migration error: ${e.message}")
        }

        // NeoForge 1.21 deprecates EventBusSubscriber.Bus.MOD. Preserve
        // mod-bus semantics by registering static handlers on the mod event bus.
        try {
            val staticModBusSubscriberChanges = migrateStaticModBusSubscribers(projectDir, dryRun)
            changes.addAll(staticModBusSubscriberChanges)
        } catch (e: Exception) {
            errors.add("Static mod-bus subscriber migration error: ${e.message}")
        }

        // Clean up @EventBusSubscriber: remove from classes without @SubscribeEvent,
        // and remove deprecated bus= parameter
        try {
            val ebsChanges = cleanupEventBusSubscriber(projectDir, dryRun)
            changes.addAll(ebsChanges)
        } catch (e: Exception) {
            errors.add("EventBusSubscriber cleanup error: ${e.message}")
        }

        // Remove orphaned SimpleChannel code once generated payload registration owns it.
        try {
            val netCleanupChanges = cleanupSimpleChannelRemnants(projectDir, dryRun)
            changes.addAll(netCleanupChanges)
        } catch (e: Exception) {
            errors.add("SimpleChannel cleanup error: ${e.message}")
        }

        // Fix static @SubscribeEvent with instance registration:
        // EVENT_BUS.register(this) -> EVENT_BUS.register(ClassName.class) when methods are static
        try {
            val staticBusChanges = fixStaticEventBusRegistration(projectDir, dryRun)
            changes.addAll(staticBusChanges)
        } catch (e: Exception) {
            errors.add("Static EventBus registration fix error: ${e.message}")
        }

        // Migrate Block.use() -> useWithoutItem() (1.20 -> 1.21 API change)
        try {
            val useChanges = migrateBlockUseMethod(projectDir, dryRun)
            changes.addAll(useChanges)
        } catch (e: Exception) {
            errors.add("Block.use() migration error: ${e.message}")
        }

        // Migrate ItemEntityPickupEvent.Pre API changes
        try {
            val pickupChanges = migrateItemPickupEvent(projectDir, dryRun)
            changes.addAll(pickupChanges)
        } catch (e: Exception) {
            errors.add("ItemEntityPickupEvent migration error: ${e.message}")
        }

        // Remove onAddedToWorld() override (removed in 1.21)
        try {
            val removedMethodChanges = removeObsoleteMethods(projectDir, dryRun)
            changes.addAll(removedMethodChanges)
        } catch (e: Exception) {
            errors.add("Obsolete method removal error: ${e.message}")
        }

        // Add codec() override for BaseEntityBlock subclasses
        try {
            val codecChanges = addBaseEntityBlockCodec(projectDir, dryRun)
            changes.addAll(codecChanges)
        } catch (e: Exception) {
            errors.add("BaseEntityBlock codec error: ${e.message}")
        }

        // DropExperienceBlock moved its xp provider before Properties in 1.21.
        // Custom subclasses that simply forward to super must expose the same
        // order, and their constructor call sites must follow it.
        try {
            val dropExperienceChanges = migrateDropExperienceBlockConstructorOrder(projectDir, dryRun)
            changes.addAll(dropExperienceChanges)
        } catch (e: Exception) {
            errors.add("DropExperienceBlock constructor migration error: ${e.message}")
        }

        // StairBlock now takes BlockState directly instead of a supplier. Custom
        // subclasses that only forward the legacy Supplier<BlockState> argument
        // should expose the same constructor boundary as vanilla StairBlock.
        try {
            val stairBlockChanges = migrateStairBlockStateSupplierConstructors(projectDir, dryRun)
            changes.addAll(stairBlockChanges)
        } catch (e: Exception) {
            errors.add("StairBlock constructor migration error: ${e.message}")
        }

        // FlowerBlock now takes Holder<MobEffect> directly. Subclasses that
        // only forward the legacy Supplier<MobEffect> constructor need the same
        // signature, and call sites should pass holder constants directly.
        try {
            val flowerBlockChanges = migrateFlowerBlockMobEffectHolderConstructors(projectDir, dryRun)
            changes.addAll(flowerBlockChanges)
        } catch (e: Exception) {
            errors.add("FlowerBlock constructor migration error: ${e.message}")
        }

        // Migrate old FinishedRecipe-style builder result adapters to the
        // 1.21 RecipeOutput.accept(id, recipe, advancement) contract.
        try {
            val recipeOutputChanges = migrateLegacyRecipeOutputBuilders(projectDir, dryRun)
            changes.addAll(recipeOutputChanges)
        } catch (e: Exception) {
            errors.add("RecipeOutput builder migration error: ${e.message}")
        }

        // Migrate Nitrogen's 1.20 block-state recipe builder source shapes to
        // the 1.21 factory/Optional property-map API. This derives recipe and
        // serializer types from project source declarations instead of package
        // conventions.
        try {
            val nitrogenBuilderChanges = migrateNitrogenRecipeBuilders(projectDir, dryRun)
            changes.addAll(nitrogenBuilderChanges)
        } catch (e: Exception) {
            errors.add("Nitrogen recipe builder migration error: ${e.message}")
        }

        // Recipe ids live on RecipeHolder in 1.21, not on the recipe object.
        try {
            val recipeIdChanges = migrateMmlibRecipeIdTracking(projectDir, dryRun)
            changes.addAll(recipeIdChanges)
        } catch (e: Exception) {
            errors.add("MMLib recipe id tracking migration error: ${e.message}")
        }

        try {
            val fluidIngredientGetterChanges = migrateFluidIngredientGetterNullSemantics(projectDir, dryRun)
            changes.addAll(fluidIngredientGetterChanges)
        } catch (e: Exception) {
            errors.add("FluidIngredient getter null-semantics migration error: ${e.message}")
        }

        try {
            val fluidStackGetterChanges = migrateFluidStackGetterNullSemantics(projectDir, dryRun)
            changes.addAll(fluidStackGetterChanges)
        } catch (e: Exception) {
            errors.add("FluidStack getter null-semantics migration error: ${e.message}")
        }

        // RegistryObject.isPresent() became DeferredHolder.isBound() after
        // registry holder type migration.
        try {
            val deferredPresenceChanges = migrateDeferredHolderPresenceChecks(projectDir, dryRun)
            changes.addAll(deferredPresenceChanges)
        } catch (e: Exception) {
            errors.add("DeferredHolder presence migration error: ${e.message}")
        }

        // Move client menu screen registration from private MenuScreens.register
        // to NeoForge's RegisterMenuScreensEvent surface.
        try {
            val menuScreenChanges = migrateMenuScreensRegistration(projectDir, dryRun)
            changes.addAll(menuScreenChanges)
        } catch (e: Exception) {
            errors.add("Menu screen registration migration error: ${e.message}")
        }

        // Block.useItemOn now returns ItemInteractionResult. Preserve helper
        // methods that still compute InteractionResult by explicitly mapping it.
        try {
            val itemInteractionChanges = migrateUseItemOnInteractionResultReturns(projectDir, dryRun)
            val libraryInteractionChanges = migrateProjectLibraryItemInteractionResultBridges(projectDir, dryRun)
            changes.addAll(migrateSimpleChannelPresenceChecks(projectDir, dryRun))
            changes.addAll(migrateCuriosModifierIdentifiers(projectDir, dryRun))
            changes.addAll(itemInteractionChanges)
            changes.addAll(libraryInteractionChanges)
        } catch (e: Exception) {
            errors.add("ItemInteractionResult return migration error: ${e.message}")
        }

        try {
            val accessorChanges = migrateSourceBackedAccessorCalls(projectDir, dryRun)
            changes.addAll(accessorChanges)
        } catch (e: Exception) {
            errors.add("Source-backed accessor migration error: ${e.message}")
        }

        // Extract client-only @SubscribeEvent methods into @EventBusSubscriber(Dist.CLIENT) inner class
        try {
            val clientEventChanges = extractClientOnlyEventMethods(projectDir, dryRun)
            changes.addAll(clientEventChanges)
        } catch (e: Exception) {
            errors.add("Client event extraction error: ${e.message}")
        }

        // Move mod-bus listener registrations that were accidentally attached
        // to the common NeoForge bus back onto the constructor's mod event bus.
        try {
            val listenerRegistrationChanges = migrateCommonBusModEventListeners(projectDir, dryRun)
            changes.addAll(listenerRegistrationChanges)
        } catch (e: Exception) {
            errors.add("Mod-bus listener registration migration error: ${e.message}")
        }

        // Remove empty @EventBusSubscriber inner classes left over after extraction/cleanup
        try {
            val emptyClassChanges = removeEmptySubscriberClasses(projectDir, dryRun)
            changes.addAll(emptyClassChanges)
        } catch (e: Exception) {
            errors.add("Empty subscriber class removal error: ${e.message}")
        }

        // Some structural migrations create mod-bus subscribers after the
        // first subscriber pass. Normalize those generated surfaces too, then
        // fail closed if any MOD bus annotation still lacks explicit wiring.
        if (!dryRun) {
            try {
                val finalStaticModBusChanges = migrateStaticModBusSubscribers(projectDir, false)
                changes.addAll(finalStaticModBusChanges)
                val finalSubscriberCleanupChanges = cleanupEventBusSubscriber(projectDir, false)
                changes.addAll(finalSubscriberCleanupChanges)
                validateNoUnresolvedEventBusSubscriberModBusParameters(projectDir)
            } catch (e: Exception) {
                errors.add("Final EventBusSubscriber normalization error: ${e.message}")
            }
        }

        // Final structural normalization: attachment registration methods are
        // ordinary mod-constructor registration helpers, not event handlers.
        try {
            val attachmentRegistrationChanges = cleanupAttachmentRegistrationEventAnnotations(projectDir, dryRun)
            changes.addAll(attachmentRegistrationChanges)
        } catch (e: Exception) {
            errors.add("Attachment registration cleanup error: ${e.message}")
        }

        try {
            val dimensionTransitionChanges = migrateDelegatingEntityDimensionTransitionOverrides(projectDir, dryRun)
            changes.addAll(dimensionTransitionChanges)
        } catch (e: Exception) {
            errors.add("Entity dimension transition override migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateRegistryKeyAccessors(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Registry key accessor migration error: ${e.message}")
        }

        // Some later project-level rewrites can touch entity sources after the
        // common API pass. Run the accessor migration once more at the end so
        // Entity#getLevel() cannot be reintroduced by write-order effects.
        try {
            val finalEntityLevelChanges = migrateEntityLevelAccessorCalls(projectDir, dryRun)
            changes.addAll(finalEntityLevelChanges)
        } catch (e: Exception) {
            errors.add("Final entity level accessor migration error: ${e.message}")
        }

        // Final source-shape normalization for collection APIs. Some project-level
        // rewrites can run after the common API pass; collection getSize() is only
        // valid when the declared receiver API provides it, not for java.util maps.
        try {
            val finalCollectionSizeChanges = migrateDeclaredCollectionGetSizeCalls(projectDir, dryRun)
            changes.addAll(finalCollectionSizeChanges)
        } catch (e: Exception) {
            errors.add("Final collection size migration error: ${e.message}")
        }

        try {
            val duplicateLookupChanges = normalizeDuplicateHolderLookupConstructorArguments(projectDir, dryRun)
            changes.addAll(duplicateLookupChanges)
        } catch (e: Exception) {
            errors.add("Duplicate HolderLookup constructor argument cleanup error: ${e.message}")
        }

        try {
            val commentedBypassChanges = cleanupCommentedOutJavaLogicBlocks(projectDir, dryRun)
            changes.addAll(commentedBypassChanges)
        } catch (e: Exception) {
            errors.add("Commented-out source logic cleanup error: ${e.message}")
        }

        // Re-run after single-file constructor migrations have normalized old
        // tool constructors into Item.Properties.attributes(...). The override
        // migration is project-level because helper methods can live in shared
        // interfaces, so it needs to see the final source shape.
        try {
            val finalItemAttributeChanges = migrateLegacyItemAttributeModifierOverrides(projectDir, dryRun)
            changes.addAll(finalItemAttributeChanges)
        } catch (e: Exception) {
            errors.add("Final item attribute modifier migration error: ${e.message}")
        }

        // Gui overlays bound to static method references become layered-draw layers.
        try {
            val overlayModId = detectModId(projectDir)
            if (overlayModId != null) changes.addAll(migrateGuiOverlayMethodReferences(projectDir, overlayModId, dryRun))
        } catch (e: Exception) {
            errors.add("Gui overlay layer migration error: ${e.message}")
        }

        // Project subclasses of RecipeWrapper that still rely on Forge's Container contract.
        try {
            changes.addAll(migrateRecipeWrapperSubclassContainers(projectDir, dryRun, errors))
        } catch (e: Exception) {
            errors.add("RecipeWrapper subclass container migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyRecipeWrapperContainers.migrate(projectDir,
                { detectRequiredGeneratedCompatPackage(projectDir, "legacy RecipeWrapper containers") }, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy RecipeWrapper container migration error: ${e.message}")
        }

        // Recipe serializers no structured rule turned into record codecs keep their JSON and
        // network code behind codec()/streamCodec(); recipes keeping their own id learn it on load.
        // (Before the buffer propagation: their buffer methods no longer override anything.)
        try {
            changes.addAll(LegacyRecipeSerializerCodecs.migrate(
                projectDir,
                { detectModId(projectDir) },
                { detectRequiredGeneratedCompatPackage(projectDir, "legacy recipe serializer codecs") },
                dryRun
            ))
        } catch (e: Exception) {
            errors.add("Legacy recipe serializer codec migration error: ${e.message}")
        }

        // Static equipment slot lookups (an instance method on 1.21.1) keep Forge 1.20.1's body.
        try {
            changes.addAll(LegacyEquipmentSlots.migrate(
                projectDir,
                { detectRequiredGeneratedCompatPackage(projectDir, "legacy equipment slot lookups") },
                dryRun
            ))
        } catch (e: Exception) {
            errors.add("Legacy equipment slot migration error: ${e.message}")
        }

        // Helpers handing a criterion instance to addCriterion take the 1.21 Criterion<?>.
        try {
            changes.addAll(CriterionParameters.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Criterion parameter migration error: ${e.message}")
        }

        try {
            changes.addAll(AdvancementValues.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Advancement value migration error: ${e.message}")
        }

        try {
            val triggerRegistration =
                CreateAdvancementTriggerRegistrationMigration.migrate(projectDir, dryRun)
            changes.addAll(triggerRegistration.changes)
            errors.addAll(triggerRegistration.gates)
            if (triggerRegistration.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create advancement trigger registration migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        // FinishedRecipe-style data generation results holding their recipe hand it to RecipeOutput.
        try {
            changes.addAll(LegacyFinishedRecipeResults.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy finished recipe result migration error: ${e.message}")
        }

        try {
            changes.addAll(RawShapedRecipeOutputResults.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Raw shaped recipe output migration error: ${e.message}")
        }

        // Recipe predicates filtering getRecipeFor's holders test the holder's recipe.
        try {
            changes.addAll(RecipeHolderPredicateFilters.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Recipe holder predicate filter migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyRecipeIds.migrate(this, projectDir, detectModId(projectDir),
                { detectRequiredGeneratedCompatPackage(projectDir, "legacy recipe identity") }, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy recipe identity migration error: ${e.message}")
        }

        // The legacy particle contract takes the registry buffer a single-file rule gave one of its implementations.
        try {
            changes.addAll(ParticleOptionsCodecs.alignRegistryBuffers(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Particle contract buffer alignment error: ${e.message}")
        }

        // RegistryFriendlyByteBuf flows through the project's own signatures once the
        // single-file rules have introduced it at the platform boundaries.
        try {
            changes.addAll(migrateLegacyTagIngredients(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy tag ingredient migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateRegistryBufferParameters(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Registry buffer parameter propagation error: ${e.message}")
        }

        try {
            val fluidIngredient = SourceOwnedFluidIngredient.migrate(this, projectDir, dryRun)
            changes.addAll(fluidIngredient.changes)
            errors.addAll(fluidIngredient.gates)
            if (fluidIngredient.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Source-owned fluid ingredient migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        // Native add-entity packet reconstruction follows complex-spawn signature propagation.
        try {
            changes.addAll(migrateLegacyClientSpawnFactories(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy client spawn factory migration error: ${e.message}")
        }

        // AttributeInstance.hasModifier takes the modifier's id on 1.21.1.
        try {
            changes.addAll(AttributeModifierIds.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Attribute modifier id migration error: ${e.message}")
        }

        // Methods the project calls itself that override nothing on 1.21.1 lose their @Override.
        try {
            changes.addAll(StaleOverrideAnnotations.migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Stale override annotation cleanup error: ${e.message}")
        }

        try {
            val filtering =
                CreateFilteringEmptyStackSizeMigration.migrate(projectDir, dryRun)
            changes.addAll(filtering.changes)
            errors.addAll(filtering.gates)
            if (filtering.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create filtering empty-stack migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        // Publish the complete item-data family before the final arity graph observes it.
        try {
            val itemData = CreateItemStackDataFamilyMigration.migrate(projectDir, dryRun)
            changes.addAll(itemData.changes)
            errors.addAll(itemData.gates)
            if (itemData.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Create item-data family migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        // Calls that still miss the HolderLookup.Provider their target now takes.
        try {
            changes.addAll(ProviderArityRepair.migrate(this, projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Provider arity repair error: ${e.message}")
        }

        try {
            if (!legacyPlantRuntimeOwned) {
                changes.addAll(LegacyPlantables.migrate(this, projectDir, dryRun))
            }
        } catch (e: Exception) {
            errors.add("Legacy plantable migration error: ${e.message}")
        }
        try {
            // The looting policy migration is defined only over the sealed artifact inventory.
            if (LegacyMigrationArtifactInventoryHandoff.isPublished(projectDir)) {
                changes.addAll(LegacyLootingPolicyMigration.migrate(this, projectDir, dryRun))
            }
        } catch (e: Exception) {
            errors.add("Legacy looting migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateProjectKeyBackedDynamicAttributes(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Key-backed dynamic enchantment attribute migration error: ${e.message}")
        }

        try {
            changes.addAll(LateClientBaseApiMigration.migrate(this, projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Late client base API migration error: ${e.message}")
        }

        try {
            val copperStairs = CreateCopperStairFactoryMigration.migrate(this, projectDir, dryRun)
            changes.addAll(copperStairs.changes)
            errors.addAll(copperStairs.gates)
            if (copperStairs.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Create copper stair migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val createDatagen =
                CreateRegistrateDatagenLifecycleMigration.migrate(projectDir, dryRun)
            changes.addAll(createDatagen.changes)
            errors.addAll(createDatagen.gates)
            if (createDatagen.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create Registrate datagen lifecycle migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val commonMetalTags =
                CreateCommonMetalTagNamespaceMigration.migrate(projectDir, dryRun)
            changes.addAll(commonMetalTags.changes)
            errors.addAll(commonMetalTags.gates)
            if (commonMetalTags.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create common-metal tag namespace migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val createLegacyTags =
                CreateLegacyTagConventionMigration.migrate(projectDir, dryRun)
            changes.addAll(createLegacyTags.changes)
            errors.addAll(createLegacyTags.gates)
            if (createLegacyTags.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create legacy tag convention migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val enchantmentBootstrap =
                CreateEnchantmentDatagenBootstrapMigration.migrate(projectDir, dryRun)
            changes.addAll(enchantmentBootstrap.changes)
            errors.addAll(enchantmentBootstrap.gates)
            if (enchantmentBootstrap.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create enchantment datagen bootstrap migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val flapDisplay =
                CreateFlapDisplaySerializationMigration.migrate(projectDir, dryRun)
            changes.addAll(flapDisplay.changes)
            errors.addAll(flapDisplay.gates)
            if (flapDisplay.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create flap-display component migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val copperDataMaps = CreateCopperDataMapMigration.migrate(projectDir, dryRun)
            changes.addAll(copperDataMaps.changes)
            errors.addAll(copperDataMaps.gates)
            if (copperDataMaps.gates.isNotEmpty()) {
                return PassResult(name, changes, errors, skipped)
            }
        } catch (e: Exception) {
            errors.add("Create copper data-map migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val analogShape = CreateAnalogLeverShapeAccess.migrate(this, projectDir, dryRun)
            changes.addAll(analogShape.changes)
            errors.addAll(analogShape.gates)
            if (analogShape.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Create analog lever shape migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val finalImportChanges = normalizeIntroducedJavaImports(projectDir, dryRun)
            changes.addAll(finalImportChanges)
        } catch (e: Exception) {
            errors.add("Final introduced import normalization error: ${e.message}")
        }


        try {
            changes.addAll(migrateLegacyFlywheelBlockModels(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy Flywheel block model migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyFoodCodecFamilies.migrate(this, projectDir,
                { detectRequiredGeneratedCompatPackage(projectDir, "legacy food codecs") }, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy food codec migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateLegacyPotionUtilities(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy potion utility migration error: ${e.message}")
        }

        try {
            changes.addAll(LegacyPotionBrewingInstances.migrate(this, projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy PotionBrewing instance migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateRecordRegistryValueArguments(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Record registry value argument migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateLegacyPanoramaCalls(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy panorama rendering migration error: ${e.message}")
        }
        try {
            changes.addAll(migrateLegacyPositionValues(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy position value migration error: ${e.message}")
        }
        try {
            changes.addAll(migrateLegacyMinecartFactories(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Legacy minecart factory migration error: ${e.message}")
        }

        try {
            changes.addAll(migrateProjectExpectedCodecShapes(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Expected codec shape migration error: ${e.message}")
        }

        try {
            changes.addAll(CreateEffectComponentSurfaces.migrate(this, projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Create effect and component boundary migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            changes.addAll(CreateProjectileAndStewSurfaces.migrate(this, projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Create projectile and stew source migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        try {
            val poseAndLoot = CreatePoseAndLootMixinMigration.migrate(projectDir, dryRun)
            changes.addAll(poseAndLoot.changes)
            errors.addAll(poseAndLoot.gates)
            if (poseAndLoot.gates.isNotEmpty()) return PassResult(name, changes, errors, skipped)
        } catch (e: Exception) {
            errors.add("Create pose and loot mixin migration error: ${e.message}")
            return PassResult(name, changes, errors, skipped)
        }

        return PassResult(name, changes, errors, skipped)
    }

}
