package com.modporter.core.transforms.build

import com.modporter.core.pipeline.*
import com.modporter.core.transforms.structural.FinalCapabilityCompatMigration
import com.modporter.core.transforms.shared.HolderLookupProviderPropagationMigration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.toList

private val logger = KotlinLogging.logger {}

/**
 * Pass 4: Build system migration.
 * Transforms build.gradle/build.gradle.kts from Forge 1.20.1 MDK to NeoForge 1.21.1.
 *
 * Key transformations:
 * - Forge Gradle plugin -> NeoForge ModDev plugin
 * - net.minecraftforge:forge dependency -> neoForge { version = "..." }
 * - Repository URLs
 * - Mappings configuration
 * - Run configurations
 * - settings.gradle plugin repositories
 */
class BuildSystemPass(
    val offlineMode: Boolean = true,
    val mappingsPrefix: String = "/mappings/forge2neo",
    private val dependencyResolverFactory: (offlineMode: Boolean, mappingsPrefix: String) -> DependencyResolver =
        { offline, prefix -> DependencyResolver(offlineMode = offline, mappingsPrefix = prefix) }
) : PlannedChangesAwarePass {
    override val name = "Build System"
    override val order = 4
    override val migratesBuildRoot = true

    private val targetNeoForgeVersion = "21.1.230"
    private val reflectionFieldMappings: List<ReflectionFieldMapping> by lazy(::loadReflectionFieldMappings)
    private val accessTransformerMemberMappings: Map<String, AccessTransformerMemberMapping> by lazy(
        ::loadAccessTransformerMemberMappings
    )

    /** Java package prefixes of dependencies removed from this project's build (no 1.21.1 artifact). */
    private val removedDependencyPackages = linkedSetOf<String>()

    override fun analyze(projectDir: Path): PassResult = processBuildFiles(projectDir, dryRun = true)

    override fun analyze(projectDir: Path, precedingResults: List<PassResult>): PassResult =
        processBuildFiles(
            projectDir,
            dryRun = true,
            plannedChanges = precedingResults.flatMap { it.changes }
        )

    override fun apply(projectDir: Path): PassResult = processBuildFiles(projectDir, dryRun = false)

    /**
     * Prepares a producer-owned target stage for dependency resolution without running the
     * unrelated Java migrations, reflection rewrites, or runtime hard gates of the normal pass.
     * Resolved source components come from the producer's source graph; build declarations alone
     * are not accepted as proof of source-closure identity.
     */
    internal fun prepareDependencies(
        ownedStagedProject: Path,
        sourceDependencies: List<BuildResolvedSourceDependency>
    ): BuildDependencyPreparationReceipt {
        val projectDir = requireOwnedDependencyStage(ownedStagedProject)
        require(sourceDependencies.isNotEmpty()) {
            "Dependency preparation requires the resolved source dependency graph"
        }
        require(sourceDependencies.distinct().size == sourceDependencies.size) {
            "Resolved source dependency inputs contain duplicates"
        }
        val beforeBuild = snapshotBuildFiles(projectDir)
        val beforeResources = snapshotResourceFiles(projectDir)
        val beforeSource = snapshotSourceFiles(projectDir)
        val catalog = dependencyCatalog()
        val decisions = dependencyDecisions(sourceDependencies, catalog)
        val context = DependencyOnlyContext()
        val result = processBuildFiles(projectDir, dryRun = false, dependencyOnly = context)
        require(result.errors.isEmpty()) {
            "Dependency-only BuildSystem preparation failed: ${result.errors.joinToString()}"
        }
        val afterBuild = snapshotBuildFiles(projectDir)
        val afterResources = snapshotResourceFiles(projectDir)
        val afterSource = snapshotSourceFiles(projectDir)
        val changedSource = fileTransitions(beforeSource, afterSource)
        val stubSources = context.stubResults.flatMap(RemovedDependencyStubResult::sources)
        val stubHashes = stubSources.associate {
            it.path to (it.beforeSourceSha256 to it.afterSourceSha256)
        }
        require(stubHashes.size == stubSources.size) {
            "A source file was rewritten by more than one removed-dependency stub pass"
        }
        require(changedSource.associate {
            it.path to (it.beforeSha256 to it.afterSha256)
        } == stubHashes) {
            "Dependency-only preparation changed source outside receipt-bound dependency stubs"
        }

        val removals = decisions.filter {
            it.kind == BuildDependencyDecisionKind.REMOVE
        }.mapNotNull { decision ->
            val decisionPrefixes = decision.packagePrefixes.map { it.trimEnd('.') }.toSet()
            val sources = stubSources.filter { source ->
                source.packagePrefixes.any { it.trimEnd('.') in decisionPrefixes }
            }
            if (sources.isEmpty()) return@mapNotNull null
            require(sources.all { source ->
                source.packagePrefixes.all { it.trimEnd('.') in decisionPrefixes }
            }) {
                "A removed-dependency source file mixes package ownership across decisions"
            }
            val remaining = findRemovedPackageReferences(projectDir, decision.packagePrefixes)
            require(remaining.isEmpty()) {
                "Removed dependency still has project source references: $remaining"
            }
            BuildRemovedDependencyReceipt(
                sourceComponent = decision.source.component,
                sourceArtifactSha256 = decision.source.artifactSha256,
                decisionSha256 = decision.sha256,
                packagePrefixes = decision.packagePrefixes,
                changedSourceHashes = sources.associate {
                    it.path to (it.beforeSourceSha256 to it.afterSourceSha256)
                },
                inertRewrites = sources.flatMap(RemovedDependencySourceReceipt::inertRewrites),
                remainingRemovedPackageReferences = remaining
            )
        }
        val removalSourcePaths = removals.flatMap { it.changedSourceHashes.keys }
        require(removalSourcePaths.size == removalSourcePaths.distinct().size &&
            removalSourcePaths.toSet() == stubHashes.keys
        ) {
            "Every strict dependency stub must belong to exactly one source-graph removal decision"
        }
        return BuildDependencyPreparationReceipt(
            result = result,
            mappingResourceSha256 = catalog.resourceSha256,
            decisions = decisions,
            removals = removals,
            buildFilesBefore = beforeBuild,
            buildFilesAfter = afterBuild,
            resourceFilesBefore = beforeResources,
            resourceFilesAfter = afterResources,
            sourceFilesBefore = beforeSource,
            sourceFilesAfter = afterSource,
            changedBuildFiles = fileTransitions(beforeBuild, afterBuild),
            changedResourceFiles = fileTransitions(beforeResources, afterResources),
            changedSourceFiles = changedSource
        )
    }

    private fun processBuildFiles(
        projectDir: Path,
        dryRun: Boolean,
        dependencyOnly: DependencyOnlyContext? = null,
        plannedChanges: List<Change> = emptyList()
    ): PassResult {
        val result = processBuildFilesBeforeRuntime(projectDir, dryRun, dependencyOnly, plannedChanges)
        if (dependencyOnly != null || result.errors.isNotEmpty()) return result
        val normalized = try {
            val capabilities = FinalCapabilityCompatMigration.migrate(projectDir, dryRun)
            result.copy(
                changes = result.changes + capabilities.changes,
                errors = result.errors + capabilities.gates
            )
        } catch (e: Exception) {
            result.copy(errors = result.errors + "Final capability compatibility migration error: ${e.message}")
        }
        if (normalized.errors.isNotEmpty()) return normalized
        return try {
            normalized.copy(changes = normalized.changes + BuildSystemLegacyPlantCompat.install(projectDir, dryRun))
        } catch (e: Exception) {
            normalized.copy(errors = normalized.errors + "Failed to install shared legacy plant runtime: ${e.message}")
        }
    }

    private fun processBuildFilesBeforeRuntime(
        projectDir: Path,
        dryRun: Boolean,
        dependencyOnly: DependencyOnlyContext?,
        plannedChanges: List<Change>
    ): PassResult {
        val moduleDir = GradleModLayout.modModule(projectDir)
        if (moduleDir == projectDir) {
            return processModuleBuildFiles(
                projectDir,
                dryRun,
                standalone = true,
                dependencyOnly = dependencyOnly,
                plannedChanges = plannedChanges
            )
        }
        // A multi-project build: the mod module is migrated like a project of its own, the root keeps
        // the settings, plugin classpath (buildSrc) and wrapper every module builds with.
        val module = processModuleBuildFiles(
            moduleDir,
            dryRun,
            standalone = false,
            dependencyOnly = dependencyOnly,
            plannedChanges = plannedChanges
        )
        val changes = module.changes.toMutableList()
        val errors = module.errors.toMutableList()
        for (settingsFile in listOf("settings.gradle", "settings.gradle.kts")) {
            val path = projectDir.resolve(settingsFile)
            if (!path.exists()) continue
            try {
                val result = transformSettingsGradle(path, dryRun)
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform $settingsFile: ${e.message}")
            }
        }
        updateGradleWrapper(projectDir, dryRun, changes)
        if (dependencyOnly == null) {
            try {
                changes.addAll(BuildSrcPlugins.migrate(projectDir, dryRun))
            } catch (e: Exception) {
                errors.add("Failed to migrate buildSrc plugins: ${e.message}")
            }
        }
        val gradleProperties = projectDir.resolve("gradle.properties")
        if (gradleProperties.exists()) {
            try {
                val result = transformGradleProperties(gradleProperties, dryRun)
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform gradle.properties: ${e.message}")
            }
        }
        return PassResult(name, changes, errors, module.skipped)
    }

    private fun processModuleBuildFiles(
        projectDir: Path,
        dryRun: Boolean,
        standalone: Boolean,
        dependencyOnly: DependencyOnlyContext?,
        plannedChanges: List<Change>
    ): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        removedDependencyPackages.clear()

        // Handle build.gradle (Groovy)
        val buildGradle = projectDir.resolve("build.gradle")
        if (buildGradle.exists()) {
            try {
                val result = transformBuildGradle(
                    buildGradle,
                    dryRun,
                    dependencyOnly = dependencyOnly != null
                )
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform build.gradle: ${e.message}")
            }
        }

        // Handle build.gradle.kts (Kotlin DSL)
        val buildGradleKts = projectDir.resolve("build.gradle.kts")
        val kotlinModuleModId = if (!standalone && buildGradleKts.exists() && ForgeGradleKotlinModule.applies(buildGradleKts.readText())) {
            detectModId(projectDir) ?: declaredModId(projectDir)
        } else null
        if (kotlinModuleModId != null) {
            // a module script of a multi-project build is migrated statement by statement
            changes.addAll(ForgeGradleKotlinModule.migrate(buildGradleKts, kotlinModuleModId, targetNeoForgeVersion, dryRun))
        } else if (buildGradleKts.exists()) {
            try {
                val result = transformBuildGradle(
                    buildGradleKts,
                    dryRun,
                    dependencyOnly = dependencyOnly != null
                )
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform build.gradle.kts: ${e.message}")
            }
        }

        // Handle settings.gradle / settings.gradle.kts
        var hasSettings = false
        for (settingsFile in listOf("settings.gradle", "settings.gradle.kts")) {
            val path = projectDir.resolve(settingsFile)
            if (path.exists()) {
                hasSettings = true
                try {
                    val result = transformSettingsGradle(path, dryRun)
                    changes.addAll(result.first)
                    errors.addAll(result.second)
                } catch (e: Exception) {
                    errors.add("Failed to transform $settingsFile: ${e.message}")
                }
            }
        }
        // Create settings.gradle if missing (required for standalone project with plugins { } block)
        val hasBuildFile = buildGradle.exists() || buildGradleKts.exists()
        if (standalone && !hasSettings && hasBuildFile) {
            val settingsPath = projectDir.resolve("settings.gradle")
            val projectName = projectDir.fileName.toString()
            changes.add(Change(
                file = settingsPath, line = 0,
                description = "Create settings.gradle for NeoForge ModDev plugin resolution",
                before = "(missing)",
                after = "settings.gradle with NeoForge plugin repository",
                confidence = Confidence.HIGH,
                ruleId = "build-create-settings"
            ))
            if (!dryRun) {
                settingsPath.writeText(SETTINGS_GRADLE.replace("%%PROJECT_NAME%%", projectName))
            }
        }

        updateGradleWrapper(projectDir, dryRun, changes)

        try {
            changes.addAll(GradleJavaVersionMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate Java targets in Gradle build scripts: ${e.message}")
        }

        if (dependencyOnly != null) {
            try {
                changes.addAll(migrateAccessTransformers(projectDir, dryRun))
                changes.addAll(configureAccessTransformers(projectDir, dryRun))
            } catch (e: Exception) {
                errors.add("Failed to migrate access transformers: ${e.message}")
            }
            try {
                val stubResult = RemovedDependencyStubs.stubWithReceipts(
                    projectDir,
                    removedDependencyPackages,
                    dryRun
                )
                dependencyOnly.stubResults += stubResult
                changes += stubResult.changes
            } catch (e: Exception) {
                errors.add("Failed strict removed-dependency receipt admission: ${e.message}")
            }
            val gradleProperties = projectDir.resolve("gradle.properties")
            if (gradleProperties.exists()) {
                try {
                    val result = transformGradleProperties(gradleProperties, dryRun)
                    changes.addAll(result.first)
                    errors.addAll(result.second)
                } catch (e: Exception) {
                    errors.add("Failed to transform gradle.properties: ${e.message}")
                }
            }
            return PassResult(name, changes, errors)
        }

        try {
            changes.addAll(rewriteLegacyTreeGrowers(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to rewrite legacy tree growers: ${e.message}")
        }

        try {
            changes.addAll(rewriteLegacyArmorMaterials(projectDir, dryRun, errors))
        } catch (e: Exception) {
            errors.add("Failed to rewrite legacy armor materials: ${e.message}")
        }

        try {
            changes.addAll(migrateDataGenerationApis(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate data generation APIs: ${e.message}")
        }

        if (!dryRun) {
            try {
                changes.addAll(HolderLookupProviderPropagationMigration().migrate(projectDir, dryRun = false))
            } catch (e: Exception) {
                errors.add("Failed to thread HolderLookup.Provider through data generation call graph: ${e.message}")
            }
        }

        try {
            changes.addAll(migrateCustomStatRegistration(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate custom stat registration: ${e.message}")
        }

        try {
            changes.addAll(migrateRegisterEventResourceLocations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate RegisterEvent resource locations: ${e.message}")
        }

        try {
            changes.addAll(migrateWorldCarverRegisterEvents(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate world carver RegisterEvent registration: ${e.message}")
        }

        try {
            changes.addAll(rewriteLegacyCapabilityHooks(projectDir, dryRun))
            changes.addAll(addLegacyCapabilityShims(projectDir, dryRun, errors))
        } catch (e: Exception) {
            errors.add("Failed to add legacy capability compatibility shims: ${e.message}")
        }

        try {
            changes.addAll(addLegacyMmlibShims(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to add legacy MMLib compatibility shims: ${e.message}")
        }

        try {
            changes.addAll(cleanupDuplicateOverrides(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to cleanup duplicate overrides: ${e.message}")
        }

        try {
            changes.addAll(addMissingEmptyGameTestStructures(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to add empty GameTest structures: ${e.message}")
        }

        try {
            changes.addAll(migrateAnimalSpawnPlacements(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate animal spawn placements: ${e.message}")
        }

        try {
            changes.addAll(migrateStructureTemplatePoolReflectionFields(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate structure template pool reflection fields: ${e.message}")
        }

        try {
            changes.addAll(migratePendingBlockEntityReflectionFields(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate pending block entity reflection fields: ${e.message}")
        }

        try {
            changes.addAll(migrateEntityVisibilityReflectionHooks(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate entity visibility reflection hooks: ${e.message}")
        }

        try {
            changes.addAll(migrateCreativeModeInventorySelectedTabReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate creative inventory selected tab reflection: ${e.message}")
        }

        try {
            changes.addAll(migrateEntityRenderersAddLayersReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate entity renderer add-layers reflection: ${e.message}")
        }

        try {
            changes.addAll(migrateObfuscationReflectionMethodHandles(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate obfuscation reflection method handles: ${e.message}")
        }

        try {
            changes.addAll(PortalApiMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate portal provider pipelines: ${e.message}")
        }

        try {
            changes.addAll(CopperDataMapMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate copper reflection injection to data maps: ${e.message}")
        }

        try {
            changes.addAll(RecipeManagerApiMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate direct RecipeManager map access: ${e.message}")
        }

        try {
            changes.addAll(FoodPropertiesCodecMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate complete legacy FoodProperties codecs: ${e.message}")
        }

        try {
            changes.addAll(PotionBrewingApiMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate static PotionBrewing tables to level state: ${e.message}")
        }

        try {
            changes.addAll(GameTestReflectionMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate reflective GameTest discovery: ${e.message}")
        }

        try {
            changes.addAll(migrateLivingBreatheRefillMethodHandles(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate LivingBreatheEvent refill method handles: ${e.message}")
        }

        try {
            changes.addAll(migrateVarHandleFieldAccesses(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate VarHandle field access: ${e.message}")
        }

        try {
            changes.addAll(migrateRemovedStairStateSupplierReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate removed StairBlock stateSupplier reflection: ${e.message}")
        }

        try {
            changes.addAll(ThirdPartyReflectionMixinMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate optional third-party reflection to Mixin accessors: ${e.message}")
        }

        try {
            changes.addAll(RegistrateApiMigration().migrate(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate Registrate and removed NeoForge functional APIs: ${e.message}")
        }

        try {
            changes.addAll(migrateObfuscationReflectionFieldAccesses(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate ObfuscationReflectionHelper field access: ${e.message}")
        }

        try {
            changes.addAll(migrateClassForNameReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate Class.forName reflection: ${e.message}")
        }

        try {
            changes.addAll(rewriteDeferredHolderReflectionCollectors(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to rewrite DeferredHolder reflection collectors: ${e.message}")
        }

        try {
            changes.addAll(migrateSameProjectStaticFieldReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate same-project static field reflection: ${e.message}")
        }

        try {
            changes.addAll(migrateClientEventPackageTargets(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate client event package targets: ${e.message}")
        }

        try {
            changes.addAll(guardClientOnlyEventRegistrations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to guard client-only event registrations: ${e.message}")
        }

        try {
            changes.addAll(restoreNonItemStackGetTagCalls(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to restore non-ItemStack getTag calls: ${e.message}")
        }

        try {
            changes.addAll(migrateModifyBakingResultModelLocations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate model baking result locations: ${e.message}")
        }

        try {
            changes.addAll(migrateBlockPropertiesNoParticlesOnBreak(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate noParticlesOnBreak block properties: ${e.message}")
        }

        try {
            changes.addAll(migrateRemovedTitleScreenAccessors(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate removed TitleScreen accessor surfaces: ${e.message}")
        }

        try {
            changes.addAll(migrateAccessTransformers(projectDir, dryRun))
            changes.addAll(configureAccessTransformers(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate access transformers: ${e.message}")
        }

        try {
            changes.addAll(RemovedDependencyStubs.stub(projectDir, removedDependencyPackages, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to stub the bridging classes of removed dependencies: ${e.message}")
        }

        try {
            changes.addAll(migrateCoremodScripts(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate coremod scripts: ${e.message}")
        }

        try {
            changes.addAll(registerExistingMixinConfigs(projectDir, dryRun, plannedChanges))
        } catch (e: Exception) {
            errors.add("Failed to register existing mixin configs: ${e.message}")
        }

        try {
            changes.addAll(migrateTestFixtureResources(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to attach test fixture resources: ${e.message}")
        }

        try {
            changes.addAll(removeMainSourceBuildExcludes(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to remove main source/resource excludes: ${e.message}")
        }

        try {
            changes.addAll(migrateSrgReflectionLiterals(projectDir, dryRun))
            errors.addAll(detectForbiddenReflection(projectDir))
            errors.addAll(ResidualRemovedApiGate.scan(projectDir))
            if (!dryRun) {
                errors.addAll(detectLegacyCoremodApiReferences(projectDir))
            }
        } catch (e: Exception) {
            errors.add("Failed to scan hard gates: ${e.message}")
        }

        // Handle gradle.properties
        val gradleProperties = projectDir.resolve("gradle.properties")
        if (gradleProperties.exists()) {
            try {
                val result = transformGradleProperties(gradleProperties, dryRun)
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform gradle.properties: ${e.message}")
            }
        }

        return PassResult(name, changes, errors)
    }

    private data class DependencyOnlyContext(
        val stubResults: MutableList<RemovedDependencyStubResult> = mutableListOf()
    )

    private data class DependencyCatalogEntry(
        val forgePrefix: String,
        val sha256: String
    )

    private data class DependencyCatalogSnapshot(
        val resourceSha256: String,
        val entries: List<DependencyCatalogEntry>
    )

    private fun requireOwnedDependencyStage(projectDir: Path): Path {
        require(projectDir.isDirectory()) {
            "Dependency preparation stage does not exist: $projectDir"
        }
        val real = projectDir.toRealPath()
        val parts = (0 until real.nameCount).map { real.getName(it).toString() }
        val ownedIndex = parts.indices.firstOrNull { index ->
            parts[index] == ".modporter" &&
                parts.getOrNull(index + 1) == "tmp"
        } ?: -1
        require(ownedIndex >= 0 && ownedIndex + 3 < parts.size) {
            "Dependency preparation may only mutate a producer-owned .modporter/tmp stage: $real"
        }
        return real
    }

    private fun dependencyCatalog(): DependencyCatalogSnapshot {
        val resource = "${mappingsPrefix.trimEnd('/')}/neoforge-deps.json"
            .let { if (it.startsWith('/')) it else "/$it" }
        val bytes = BuildSystemPass::class.java.getResourceAsStream(resource)?.use {
            it.readBytes()
        } ?: error("Dependency mapping resource not found: $resource")
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val entries = root.getValue("dependencies").jsonArray.map { element ->
            val entry = element.jsonObject
            DependencyCatalogEntry(
                forgePrefix = entry.getValue("forgePrefix").jsonPrimitive.content,
                sha256 = buildReceiptSha256(element.toString())
            )
        }
        require(entries.map(DependencyCatalogEntry::forgePrefix).distinct().size == entries.size) {
            "Dependency mapping catalog contains duplicate forgePrefix entries"
        }
        return DependencyCatalogSnapshot(buildReceiptSha256(bytes), entries)
    }

    private fun dependencyDecisions(
        sourceDependencies: List<BuildResolvedSourceDependency>,
        catalog: DependencyCatalogSnapshot
    ): List<BuildDependencyDecisionReceipt> {
        val resolver = DependencyResolver(offlineMode = true, mappingsPrefix = mappingsPrefix)
        val decisions = sourceDependencies.mapNotNull { source ->
            val known = resolver.knownDependencyFor(source.group, source.artifact)
                ?: return@mapNotNull null
            // Use the same ordered catalog selection as the build rewrite. A specific
            // artifact mapping may intentionally precede a broader group fallback.
            val entry = catalog.entries.singleOrNull { it.forgePrefix == known.forgePrefix }
            require(entry != null) {
                "Resolved KnownDep has no unique catalog entry: ${source.component}"
            }
            if (known.packagePrefixes.isEmpty()) return@mapNotNull null
            val kind = when (known.status) {
                "available", "compile_only_compat", "replacement" -> {
                    require(known.neoforgeCoords.isNotEmpty()) {
                        "Mapped KnownDep has no target coordinates: ${known.forgePrefix}"
                    }
                    BuildDependencyDecisionKind.MAPPED
                }
                "remove" -> BuildDependencyDecisionKind.REMOVE
                "runtime_absent" -> {
                    val removable = source.configurations.filter {
                        it in known.removeConfigurations
                    }.toSet()
                    if (removable.isEmpty()) return@mapNotNull null
                    require(removable == source.configurations) {
                        "Resolved source component mixes removable and retained configurations: " +
                            source.component
                    }
                    BuildDependencyDecisionKind.REMOVE
                }
                else -> return@mapNotNull null
            }
            BuildDependencyDecisionReceipt(
                source = source,
                sourceSelector = known.forgePrefix,
                kind = kind,
                targetCoordinates = if (kind == BuildDependencyDecisionKind.MAPPED) {
                    known.neoforgeCoords.map(BuildTargetCoordinate::from)
                } else {
                    emptyList()
                },
                packagePrefixes = known.packagePrefixes.distinct(),
                catalogEntrySha256 = entry.sha256
            )
        }
        val duplicateComponents = decisions.groupBy { it.source.component }
            .filterValues { it.size != 1 }
            .keys
        require(duplicateComponents.isEmpty()) {
            "Actionable source components have ambiguous resolved variants: $duplicateComponents"
        }
        return decisions.sortedBy { it.source.component }
    }

    private fun snapshotBuildFiles(projectDir: Path): Map<String, String> =
        snapshotFiles(projectDir) { relative, path ->
            val name = path.fileName.toString()
            name.endsWith(".gradle") ||
                name.endsWith(".gradle.kts") ||
                name in setOf("gradle.properties", "gradle-wrapper.properties") ||
                relative == "gradle/libs.versions.toml" ||
                relative.startsWith("buildSrc/")
        }

    private fun snapshotSourceFiles(projectDir: Path): Map<String, String> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyMap()
        return snapshotFiles(projectDir) { relative, path ->
            relative.startsWith("src/main/java/") &&
                path.fileName.toString().endsWith(".java")
        }
    }

    private fun snapshotResourceFiles(projectDir: Path): Map<String, String> =
        snapshotFiles(projectDir) { relative, _ ->
            relative.startsWith("src/main/resources/") ||
                relative.contains("/src/main/resources/")
        }

    private fun snapshotFiles(
        projectDir: Path,
        include: (String, Path) -> Boolean
    ): Map<String, String> {
        if (!projectDir.isDirectory()) return emptyMap()
        return Files.walk(projectDir).use { paths ->
            paths.filter { it.isRegularFile() }
                .map { path ->
                    path to path.relativeTo(projectDir).toString().replace('\\', '/')
                }
                .filter { (path, relative) -> include(relative, path) }
                .toList()
                .sortedBy { it.second }
                .associate { (path, relative) ->
                    relative to buildReceiptSha256(path.readBytes())
                }
        }
    }

    private fun findRemovedPackageReferences(
        projectDir: Path,
        packagePrefixes: List<String>
    ): List<String> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.isDirectory()) return emptyList()
        val prefixes = packagePrefixes.map { it.trimEnd('.') }
        val references = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".java") }
                .forEach { file ->
                    val relative = file.relativeTo(projectDir).toString().replace('\\', '/')
                    file.readText().lineSequence().forEachIndexed { index, line ->
                        prefixes.firstOrNull { line.contains(it) }?.let {
                            references += "$relative:${index + 1} ($it)"
                        }
                    }
                }
        }
        return references.sorted()
    }

    /** Updates a Gradle wrapper older than ModDevGradle 2.x needs (Gradle 8.8+). */
    private fun updateGradleWrapper(projectDir: Path, dryRun: Boolean, changes: MutableList<Change>) {
        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (wrapperProps.exists()) {
            val wrapperContent = wrapperProps.readText()
            val versionMatch = Regex("""gradle-(\d+)\.(\d+)""").find(wrapperContent)
            val majorVersion = versionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minorVersion = versionMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val needsUpdate = majorVersion < 8 || (majorVersion == 8 && minorVersion < 8)
            if (needsUpdate) {
                changes.add(Change(
                    file = wrapperProps, line = 0,
                    description = "Update Gradle wrapper from $majorVersion.$minorVersion to 8.14.4 (NeoForge ModDev 2.x requires Gradle 8.8+)",
                    before = "gradle-${majorVersion}.${minorVersion}.x",
                    after = "gradle-8.14.4",
                    confidence = Confidence.HIGH,
                    ruleId = "build-gradle-wrapper"
                ))
                if (!dryRun) {
                    wrapperProps.writeText(
                        wrapperContent.replace(
                            Regex("""gradle-[\d.]+-(?:bin|all)\.zip"""),
                            "gradle-8.14.4-bin.zip"
                        )
                    )
                }
            }
        }
    }

    private fun removeMainSourceBuildExcludes(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        for (buildFileName in listOf("build.gradle", "build.gradle.kts")) {
            val buildFile = projectDir.resolve(buildFileName)
            if (!buildFile.exists()) continue
            val original = buildFile.readText()
            val excludePattern = Regex("""(?m)^[ \t]*exclude[ \t]*(?:\([ \t]*)?['"]([^'"]+)['"][ \t]*\)?[ \t]*(?:\r?\n)?""")
            val removals = excludePattern.findAll(original)
                .filter { mainSourceOrResourcePathExists(projectDir, it.groupValues[1]) }
                .toList()
            if (removals.isEmpty()) continue
            for (match in removals) {
                changes.add(Change(
                    file = buildFile,
                    line = original.lineNumberAt(match.range.first),
                    description = "Remove build exclude that hides existing main source or resource content",
                    before = match.value.trim(),
                    after = "(removed)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-main-source-exclude"
                ))
            }
            if (!dryRun) {
                var content = original
                for (match in removals.asReversed()) {
                    content = content.removeRange(match.range)
                }
                buildFile.writeText(content)
            }
        }
        return changes
    }

    private fun mainSourceOrResourcePathExists(projectDir: Path, rawPattern: String): Boolean {
        val normalized = rawPattern.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return false
        val prefix = normalized.substringBefore('*').trimEnd('/')
        if (prefix.isBlank()) return false
        return listOf(
            projectDir.resolve("src/main/java").resolve(prefix),
            projectDir.resolve("src/main/resources").resolve(prefix)
        ).any { path ->
            path.exists() || runCatching {
                val parent = path.parent
                if (parent == null || !parent.exists()) {
                    false
                } else {
                    val stream = Files.walk(parent)
                    try {
                        val normalizedPath = path.normalize().toString().replace('\\', '/')
                        stream.anyMatch { candidate ->
                            candidate.normalize().toString().replace('\\', '/').startsWith(normalizedPath)
                        }
                    } finally {
                        stream.close()
                    }
                }
            }.getOrDefault(false)
        }
    }

    private fun transformBuildGradle(
        file: Path,
        dryRun: Boolean,
        dependencyOnly: Boolean
    ): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        var content = file.readText()
        val original = content
        val isKts = file.fileName.toString().endsWith(".kts")

        val forgeGradleImportPattern = Regex("""(?m)^[ \t]*import\s+net\.minecraftforge\.gradle\.[^\r\n]+(?:\r?\n)?""")
        val forgeGradleImport = forgeGradleImportPattern.find(content)
        if (forgeGradleImport != null) {
            changes.add(Change(
                file = file,
                line = content.lineNumberAt(forgeGradleImport.range.first),
                description = "Remove ForgeGradle task import after ModDev migration",
                before = forgeGradleImport.value.trim(),
                after = "(removed)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-forgegradle-import"
            ))
            content = forgeGradleImportPattern.replace(content, "")
        }

        val jarJarTaskTypePattern = Regex("""tasks\.named\((\s*['"]jarJar['"]\s*),\s*JarJar\s*\)\.configure""")
        val jarJarTaskType = jarJarTaskTypePattern.find(content)
        if (jarJarTaskType != null) {
            changes.add(Change(
                file = file,
                line = content.lineNumberAt(jarJarTaskType.range.first),
                description = "Remove ForgeGradle JarJar task type reference",
                before = jarJarTaskType.value,
                after = "tasks.named(${jarJarTaskType.groupValues[1]}).configure",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-forgegradle-jarjar-type"
            ))
            content = jarJarTaskTypePattern.replace(content) { match ->
                "tasks.named(${match.groupValues[1]}).configure"
            }
        }
        content = removeUnsupportedJarJarTaskClassifier(content, changes, file)
        content = normalizeJarJarPublicationReferences(content, changes, file)

        // 1. Handle buildscript { } + apply plugin pattern (old-style ForgeGradle)
        val hasBuildscript = content.contains("buildscript")
        val hasApplyForgeGradle = Regex("""apply\s+plugin:\s*['"]net\.minecraftforge\.gradle['"]""").containsMatchIn(content)
        val hasPluginsBlock = Regex("""^plugins\s*\{""", RegexOption.MULTILINE).containsMatchIn(content)

        if (hasBuildscript && hasApplyForgeGradle) {
            // Old-style and hybrid builds: remove buildscript/apply plugin wiring and merge ModDev into plugins { }.
            content = migrateOldStyleBuild(content, file, changes)
        } else {
            // Modern plugins { } style: just replace the plugin ID
            val forgeGradlePatterns = listOf(
                Regex("""id\s*\(?\s*['"]net\.minecraftforge\.gradle['"].*?\)?\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s+['"]net\.minecraftforge\.gradle['"].*"""),
                Regex("""apply\s+plugin:\s*['"]net\.minecraftforge\.gradle['"]"""),
                Regex("""id\s+['"]net\.neoforged\.moddev\.legacyforge['"]\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s*\(\s*['"]net\.neoforged\.moddev\.legacyforge['"]\s*\)\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s+['"]net\.neoforged\.gradle['"]\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s*\(\s*['"]net\.neoforged\.gradle['"]\s*\)\s*version\s*['"][^'"]+['"]"""),
            )
            for (pattern in forgeGradlePatterns) {
                if (pattern.containsMatchIn(content)) {
                    val match = pattern.find(content)!!
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Replace ForgeGradle/legacyForge plugin with NeoForge ModDev",
                        before = match.value,
                        after = "id(\"net.neoforged.moddev\") version \"2.0.140\"",
                        confidence = Confidence.HIGH,
                        ruleId = "build-plugin"
                    ))
                    content = content.replace(match.value, "id(\"net.neoforged.moddev\") version \"2.0.140\"")
                }
            }

            // Remove Forge-specific plugins that are incompatible with NeoForge ModDev
            val forgeOnlyPlugins = listOf(
                Regex("""^\s*id\s+['"]org\.parchmentmc\.librarian\.forgegradle['"]\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s*\(\s*['"]org\.parchmentmc\.librarian\.forgegradle['"]\s*\)\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s+['"]org\.spongepowered\.mixin['"]\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s*\(\s*['"]org\.spongepowered\.mixin['"]\s*\)\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^[ \t]*apply\s+plugin:\s*['"]org\.spongepowered\.mixin['"][ \t]*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s+['"]net\.neoforged\.gradle['"]\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s*\(\s*['"]net\.neoforged\.gradle['"]\s*\)\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
            )
            for (pluginPattern in forgeOnlyPlugins) {
                val match = pluginPattern.find(content)
                if (match != null) {
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Remove Forge-specific plugin: ${match.value.trim()}",
                        before = match.value.trim(),
                        after = "// Removed: handled by NeoForge ModDev",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-forge-plugin"
                    ))
                    content = content.replace(match.value, "")
                }
            }

            val moddingXForgeGradlePlugins = listOf(
                Regex("""(?m)^[ \t]*id\s*(?:\(\s*)?['"]org\.moddingx\.modgradle\.mapping['"]\s*(?:\))?\s*version\s*['"][^'"]+['"](?:\s*apply\s+false)?\s*$"""),
                Regex("""(?m)^[ \t]*id\s*(?:\(\s*)?['"]org\.moddingx\.modgradle\.sourcejar['"]\s*(?:\))?\s*version\s*['"][^'"]+['"](?:\s*apply\s+false)?\s*$"""),
            )
            for (pluginPattern in moddingXForgeGradlePlugins) {
                val matches = pluginPattern.findAll(content).toList()
                for (match in matches) {
                    changes.add(Change(
                        file = file,
                        line = content.lineNumberAt(match.range.first),
                        description = "Remove ForgeGradle companion plugin: ${match.value.trim()}",
                        before = match.value.trim(),
                        after = "(removed; handled by ModDev or local Gradle task)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-forgegradle-companion-plugin"
                    ))
                }
                content = pluginPattern.replace(content, "")
            }

            val moddingXApplyPlugin = Regex("""(?m)^[ \t]*apply\s+plugin:\s*['"]org\.moddingx\.modgradle\.(?:mapping|sourcejar)['"]\s*(?:\r?\n)?""")
            val applyPluginMatches = moddingXApplyPlugin.findAll(content).toList()
            for (match in applyPluginMatches) {
                changes.add(Change(
                    file = file,
                    line = content.lineNumberAt(match.range.first),
                    description = "Remove ForgeGradle companion apply plugin line",
                    before = match.value.trim(),
                    after = "(removed; sourceJar task is generated when needed)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-forgegradle-companion-apply"
                ))
            }
            content = moddingXApplyPlugin.replace(content, "")
        }

        // 2. Replace Forge dependency with neoForge block
        val forgeDependencyPatterns = listOf(
            Regex("""minecraft[^\S\r\n]*\(?[^\S\r\n]*['"]net\.(?:minecraftforge|neoforged):forge:[^'"]+['"][^\S\r\n]*\)?"""),
            Regex("""implementation\s+['"]net\.minecraftforge:forge:[^'"]+['"]"""),
            Regex("""implementation[^\S\r\n]*\([^\S\r\n]*['"]net\.minecraftforge:forge:[^'"]+['"][^\S\r\n]*\)"""),
        )
        for (pattern in forgeDependencyPatterns) {
            if (pattern.containsMatchIn(content)) {
                val matches = pattern.findAll(content).toList()
                for (match in matches) {
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Replace Forge dependency with NeoForge configuration",
                        before = match.value,
                        after = "// NeoForge dependency is now configured via neoForge { } block",
                        confidence = Confidence.HIGH,
                        ruleId = "build-dependency"
                    ))
                }
                content = pattern.replace(content, "// NeoForge dependency is now configured via neoForge { } block")
            }
        }

        // 3. Replace mappings channel configuration
        val mappingsPatterns = listOf(
            Regex("""mappings\s*\{[^}]*\}""", RegexOption.DOT_MATCHES_ALL),
            Regex("""mappings\s+channel:\s*['"][^'"]+['"],\s*version:\s*['"][^'"]+['"]"""),
        )
        for (pattern in mappingsPatterns) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Remove old mappings config (now handled by neoForge block)",
                    before = match.value,
                    after = "// Mappings now configured in neoForge { parchment { } }",
                    confidence = Confidence.HIGH,
                    ruleId = "build-mappings"
                ))
                content = content.replace(match.value,
                    "// Mappings now configured in neoForge { parchment { } }")
            }
        }

        // 4. Replace minecraft { } or legacyForge { } block with neoForge { }
        // Use a brace-counting approach to find the full block
        for (blockName in listOf("legacyForge", "minecraft")) {
            val blockStart = Regex("""(?:^|\n)\s*$blockName\s*\{""").find(content)
            if (blockStart != null) {
                val braceStart = content.indexOf('{', blockStart.range.first)
                val blockEnd = findMatchingBrace(content, braceStart)
                if (blockEnd > braceStart) {
                    val fullBlock = content.substring(blockStart.range.first, blockEnd + 1).trimStart('\n')
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(blockStart.range.first),
                        description = "Replace $blockName { } block with neoForge { } configuration",
                        before = fullBlock.take(80) + "...",
                        after = "neoForge { version = \"21.1.+\" ... }",
                        confidence = Confidence.HIGH,
                        ruleId = "build-minecraft-block"
                    ))
                    content = content.substring(0, blockStart.range.first) + "\n" + NEOFORGE_BLOCK + content.substring(blockEnd + 1)
                    break  // Only replace one block
                }
            }
        }

        // 4b. Remove legacyForge-specific blocks that don't exist in moddev
        for (removableBlock in listOf("obfuscation", "mixin")) {
            val blockStart = Regex("""(?:^|\n)\s*$removableBlock\s*\{""").find(content)
            if (blockStart != null) {
                val braceStart = content.indexOf('{', blockStart.range.first)
                val blockEnd = findMatchingBrace(content, braceStart)
                if (blockEnd > braceStart) {
                    val fullBlock = content.substring(blockStart.range.first, blockEnd + 1).trimStart('\n')
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(blockStart.range.first),
                        description = "Remove $removableBlock { } block (legacyForge-specific)",
                        before = fullBlock.take(60) + "...",
                        after = "// Removed: $removableBlock block not needed with moddev",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-legacy-block"
                    ))
                    content = content.substring(0, blockStart.range.first) + "\n// Removed: $removableBlock block not needed with moddev" + content.substring(blockEnd + 1)
                }
            }
        }

        // 4c. Replace legacyForge references in other parts of build.gradle
        content = content.replace("legacyForge.", "neoForge.")
        content = content.replace("legacyForge {", "neoForge {")

        // 4d. Run-scoped mod sources declared outside the minecraft { } block
        content = migrateTopLevelRunModSources(content, changes, errors, file)

        // 5. Replace maven repository URLs
        val forgeRepoPatterns = listOf(
            Pair(
                Regex("""maven\s*\{\s*url\s*=?\s*['"]https?://maven\.minecraftforge\.net/?['"].*?\}""",
                    RegexOption.DOT_MATCHES_ALL),
                """maven { url = "https://maven.neoforged.net/releases" }"""
            ),
            Pair(
                Regex("""maven\s*\{\s*url\s*['"]https?://maven\.minecraftforge\.net/?['"].*?\}""",
                    RegexOption.DOT_MATCHES_ALL),
                """maven { url = "https://maven.neoforged.net/releases" }"""
            ),
        )
        for ((pattern, replacement) in forgeRepoPatterns) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Replace Forge Maven URL with NeoForge Maven",
                    before = match.value,
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-maven-url"
                ))
                content = content.replace(match.value, replacement)
            }
        }

        // Also do simple string replacements for inline maven URLs
        if (content.contains("maven.minecraftforge.net")) {
            changes.add(Change(
                file = file, line = 0,
                description = "Replace remaining Forge Maven URL references",
                before = "maven.minecraftforge.net",
                after = "maven.neoforged.net/releases",
                confidence = Confidence.HIGH,
                ruleId = "build-maven-url-inline"
            ))
            content = content.replace("maven.minecraftforge.net", "maven.neoforged.net/releases")
        }

        content = addMavenRepositoryContentFilters(content, file, changes)

        // 6. Replace forge version property references (careful not to double-prefix).
        // GradleJavaVersionMigration owns Java target updates for this build and every applied or
        // subproject script so comments and string literals remain untouched.
        // Handle forge_version_range -> neoforge_version_range first (more specific)
        content = content.replace("forge_version_range", "neoforge_version_range")
        // Then forge_version -> neo_forge_version (avoid matching neo_forge_version again)
        content = content.replace(Regex("""(?<!neo_)\bforge_version\b"""), "neo_forge_version")
        content = content.replace(Regex("""\bneoforge_version\b"""), "neo_forge_version")
        content = content.replace(Regex("""\bforgeVersion\b"""), "neoForgeVersion")
        // Also replace property key neoforge_version -> neo_forge_version in maps/references
        content = content.replace("neoforge_version            : neoforge_version", "neo_forge_version            : neo_forge_version")

        // 8. Remove buildscript { } block if still present (hybrid builds have both buildscript and plugins)
        val buildscriptMatch = Regex("""(?:^|\n)\s*buildscript\s*\{""").find(content)
        if (buildscriptMatch != null) {
            val braceStart = content.indexOf('{', buildscriptMatch.range.first)
            val blockEnd = findMatchingBrace(content, braceStart)
            if (blockEnd > braceStart) {
                changes.add(Change(
                    file = file, line = content.lineNumberAt(buildscriptMatch.range.first),
                    description = "Remove buildscript { } block (not needed with plugins { } block)",
                    before = "buildscript { ... }",
                    after = "// Removed",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-buildscript"
                ))
                content = content.substring(0, buildscriptMatch.range.first) + content.substring(blockEnd + 1)
            }
        }

        // 9. Handle map-style Forge dependency: minecraft([ group: "net.minecraftforge", ... ])
        val mcDepMap = Regex("""\bminecraft\s*\(\s*\[""").find(content)
        if (mcDepMap != null) {
            val bracketStart = content.indexOf('[', mcDepMap.range.first)
            val bracketEnd = findClosing(content, bracketStart, '[', ']')
            if (bracketEnd > bracketStart) {
                val closeParen = content.indexOf(')', bracketEnd + 1)
                if (closeParen > bracketEnd) {
                    val fullMatch = content.substring(mcDepMap.range.first, closeParen + 1)
                    if (fullMatch.contains("net.minecraftforge")) {
                        changes.add(Change(
                            file = file, line = content.lineNumberAt(mcDepMap.range.first),
                            description = "Replace map-style Forge dependency",
                            before = fullMatch.take(60) + "...",
                            after = "// NeoForge dependency is now configured via neoForge { } block",
                            confidence = Confidence.HIGH,
                            ruleId = "build-dependency-map"
                        ))
                        content = content.replace(fullMatch, "// NeoForge dependency is now configured via neoForge { } block")
                    }
                }
            }
        }

        // 10. Remove fg.deobf() wrappers (handle both single-line and multi-line)
        var fgMatch = Regex("""fg\.deobf\(""").find(content)
        while (fgMatch != null) {
            val openParen = fgMatch.range.last
            val closeParen = findClosing(content, openParen, '(', ')')
            if (closeParen > openParen) {
                val inner = content.substring(openParen + 1, closeParen)
                content = content.substring(0, fgMatch.range.first) + inner + content.substring(closeParen + 1)
            } else break
            fgMatch = Regex("""fg\.deobf\(""").find(content)
        }

        // 10a. ForgeGradle contributes mod* dependency configurations; ModDev does not. They also
        // appear nested inside wrappers (jarJar(modImplementation(...))), so the rename is not
        // anchored to the start of a line. Run the notation helper over the executable mask so
        // documentation and string literals stay byte-for-byte unchanged.
        val executableDependencies = maskJavaCommentsAndLiterals(content)
        val (_, callConfigurationRenames) =
            GradleDependencyNotations.renameForgeModConfigurations(executableDependencies)
        val callConfigurationPattern = Regex(
            """\b(?:modCompileOnlyApi|modCompileOnly|modRuntimeOnly|modImplementation|modApi)\b(?=\s*\()"""
        )
        val callConfigurationMatches = callConfigurationPattern.findAll(executableDependencies).toList()
        val configurationRenames = callConfigurationRenames.toMutableList()
        if (callConfigurationMatches.size != callConfigurationRenames.size) {
            errors += "Cannot safely align ForgeGradle dependency configuration rewrites in $file"
        } else {
            for (index in callConfigurationMatches.indices.reversed()) {
                content = content.replaceRange(
                    callConfigurationMatches[index].range,
                    callConfigurationRenames[index].to
                )
            }
        }

        val forgeModConfigurations = linkedMapOf(
            "modCompileOnlyApi" to "compileOnlyApi",
            "modCompileOnly" to "compileOnly",
            "modRuntimeOnly" to "runtimeOnly",
            "modImplementation" to "implementation",
            "modApi" to "api",
        )
        for ((fromConfig, toConfig) in forgeModConfigurations) {
            val executable = maskJavaCommentsAndLiterals(content)
            val bareStatementPattern = Regex("""(?m)^(\s*)${Regex.escape(fromConfig)}\b(?!\s*\()""")
            for (match in bareStatementPattern.findAll(executable).toList().asReversed()) {
                val replacement = "${match.groupValues[1]}$toConfig"
                configurationRenames += GradleDependencyNotations.ConfigurationRename(
                    from = fromConfig,
                    to = toConfig,
                    line = content.lineNumberAt(match.range.first)
                )
                content = content.replaceRange(match.range, replacement)
            }
        }
        configurationRenames.groupBy { it.from }.forEach { (fromConfig, renames) ->
            changes.add(Change(
                file = file, line = renames.first().line,
                description = "Replace ForgeGradle dependency configuration $fromConfig",
                before = fromConfig,
                after = renames.first().to,
                confidence = Confidence.HIGH,
                ruleId = "build-mod-dependency-configuration"
            ))
        }
        for (fromConfig in forgeModConfigurations.keys) {
            if (Regex("""\b${Regex.escape(fromConfig)}\b""")
                    .containsMatchIn(maskJavaCommentsAndLiterals(content))) {
                errors += "Unmigrated ForgeGradle dependency configuration $fromConfig remains in $file"
            }
        }

        // 10b. Resolve third-party dependencies: check for NeoForge 1.21.1 versions
        val resolver = dependencyResolverFactory(offlineMode, mappingsPrefix)
        val resolvedPrefixes = mutableSetOf<String>()
        val newMavenRepos = mutableSetOf<String>()
        val runtimeArtifactStagings = linkedMapOf<String, RuntimeArtifactStagingPolicy>()
        validateExistingRuntimeArtifactStaging(content, resolver, isKts)
        val wrapped = GradleDependencyNotations.rewriteWrappedKnownNotations(content, resolver)
        wrapped.rewrites.forEach { rewrite ->
            changes.add(Change(
                file = file, line = rewrite.line,
                description = "Move a wrapped or templated dependency notation to its NeoForge artifact",
                before = rewrite.before,
                after = rewrite.after,
                confidence = Confidence.HIGH,
                ruleId = "build-dependency-wrapped-notation"
            ))
        }
        newMavenRepos.addAll(wrapped.mavenUrls)
        content = wrapped.content
        content = resolveDependencies(
            content,
            resolver,
            resolvedPrefixes,
            newMavenRepos,
            runtimeArtifactStagings,
            isKts,
            changes,
            file
        )
        content = addReflectedOptionalApiDependencies(
            content,
            file.parent,
            resolver,
            resolvedPrefixes,
            newMavenRepos,
            changes,
            file
        )

        // 10c. Add maven repositories for resolved dependencies
        if (newMavenRepos.isNotEmpty()) {
            content = addMavenRepositories(content, newMavenRepos, changes, file)
        }
        if (runtimeArtifactStagings.isNotEmpty()) {
            content = installRuntimeArtifactStaging(
                content,
                runtimeArtifactStagings.values.toList(),
                isKts,
                changes,
                file
            )
        }

        // 10d. NeoForge bundles Mixin/MixinExtras; old standalone processors can break Mojmap remapping.
        content = removeBundledMixinDependencies(content, changes, file)
        content = normalizeJarJarRangePinDsl(content, changes, file, resolver)

        // 11. Keep unresolved dependencies active while removing ForgeGradle-only wrappers.
        content = normalizeOldDependencyWrappers(content)

        // 11b. Guard optional run-preparation hooks whose task may not exist under ModDev
        val prepareGameTestTaskPattern = Regex("""tasks\.named\(\s*(['"])prepareGameTestServerRun\1\s*\)\.configure\s*\{""")
        val prepareGameTestTaskMatch = prepareGameTestTaskPattern.find(content)
        if (prepareGameTestTaskMatch != null) {
            val quote = prepareGameTestTaskMatch.groupValues[1]
            val replacement = "tasks.matching { it.name == ${quote}prepareGameTestServerRun${quote} }.configureEach {"
            changes.add(Change(
                file = file, line = content.lineNumberAt(prepareGameTestTaskMatch.range.first),
                description = "Guard prepareGameTestServerRun hook when the task is absent",
                before = prepareGameTestTaskMatch.value,
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-guard-optional-run-task"
            ))
            content = prepareGameTestTaskPattern.replace(content, replacement)
        }
        if (!dependencyOnly) {
            content = ensureGameTestDimensionDataPackTask(content, file.parent, changes, file)
        }

        // 13. ModDev remaps archive tasks itself. Create's project.publishing block already publishes
        // components.java, so its duplicate reobfJar artifact closure disappears; other publication
        // blocks keep their authored classifier and are retargeted to the ModDev jar task.
        val executableReobfuscationDsl = maskJavaCommentsAndLiterals(content)
        if (executableReobfuscationDsl.contains("project.publishing") &&
            executableReobfuscationDsl.contains("artifactId base.archivesName.get()") &&
            executableReobfuscationDsl.contains("from components.java")) {
            content = removeReobfArtifactBlocks(content)
        }
        content = migrateForgeGradleReobfuscationDsl(content, changes, errors, file)

        // 14. Replace old property references in build.gradle body
        content = content.replace(Regex("""\bproject\.mc_version\b"""), "project.minecraft_version")
        content = content.replace(Regex("""\bmc_version\b"""), "minecraft_version")
        content = content.replace(Regex("""\bmcversion\b"""), "minecraft_version")
        content = content.replace(Regex("""\bmcVersion\b"""), "minecraft_version")
        content = content.replace(Regex("""(?<!neo_)\bforgeversion\b"""), "neo_forge_version")

        // 15. Remove MixinConfigs from jar manifest
        content = content.replace(Regex("""^\s*"MixinConfigs"\s*:.*$""", RegexOption.MULTILINE), "")

        // 16. The replacement neoForge block contributes the generated-resource directory for
        // simple Forge projects. Preserve an authored nested sourceSets.main { resources { ... } }
        // declaration instead of registering the same directory twice with processResources.
        content = deduplicateGeneratedResourceSourceDirs(content)

        // 17. Update processResources filesMatching to use neoforge.mods.toml
        val modsTomlPattern = Regex("""META-INF/mods\.toml""")
        if (modsTomlPattern.containsMatchIn(content)) {
            changes.add(Change(
                file = file, line = content.lineNumberAt(modsTomlPattern.find(content)!!.range.first),
                description = "Update filesMatching to use neoforge.mods.toml",
                before = "META-INF/mods.toml",
                after = "META-INF/neoforge.mods.toml",
                confidence = Confidence.HIGH,
                ruleId = "build-mods-toml-reference"
            ))
            content = modsTomlPattern.replace(content, "META-INF/neoforge.mods.toml")
        }

        // 18. Clean up excessive blank lines
        content = ensureSourceJarTask(content, changes, file)
        content = content.replace(Regex("""\n{3,}"""), "\n\n")

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to errors
    }

    private fun deduplicateGeneratedResourceSourceDirs(content: String): String {
        val generatedSrcDirPattern = Regex(
            """srcDir\s*(?:\(\s*)?(['"])src/generated/resources/?\1\s*\)?"""
        )
        val declarations = generatedSrcDirPattern.findAll(content).toList()
        if (declarations.size <= 1) return content

        val simpleTopLevelBlocks = Regex(
            """(?ms)^[ \t]*sourceSets\.main\.resources\s*\{\s*""" +
                generatedSrcDirPattern.pattern +
                """\s*\}\s*"""
        ).findAll(content).toList()
        if (simpleTopLevelBlocks.isEmpty()) return content

        val hasAuthoredDeclarationOutsideSimpleBlocks = declarations.any { declaration ->
            simpleTopLevelBlocks.none { declaration.range.first in it.range }
        }
        val blocksToRemove = if (hasAuthoredDeclarationOutsideSimpleBlocks) {
            simpleTopLevelBlocks
        } else {
            simpleTopLevelBlocks.drop(1)
        }

        var result = content
        for (block in blocksToRemove.asReversed()) {
            result = result.removeRange(block.range)
        }
        return result
    }

    private fun migrateForgeGradleReobfuscationDsl(
        source: String,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        file: Path
    ): String {
        var result = source
        val invocationPattern = Regex("""\bobfuscation\s*\.\s*reobfuscate\s*\(""")
        while (true) {
            val executable = maskJavaCommentsAndLiterals(result)
            val invocation = invocationPattern.find(executable) ?: break
            val openParen = executable.indexOf('(', invocation.range.first)
            val closeParen = if (openParen >= 0) findMatchingParen(executable, openParen) else -1
            if (closeParen <= openParen) {
                errors += "Cannot parse ForgeGradle reobfuscation invocation in $file"
                break
            }
            var end = closeParen + 1
            while (end < result.length && result[end] in charArrayOf(' ', '\t')) end++
            if (result.getOrNull(end) == ';') end++
            while (end < result.length && result[end] in charArrayOf(' ', '\t')) end++
            if (result.startsWith("\r\n", end)) end += 2 else if (result.getOrNull(end) == '\n') end++
            val lineStart = result.lastIndexOf('\n', invocation.range.first - 1).let { it + 1 }
            val before = result.substring(lineStart, end).trim()
            changes += Change(
                file = file,
                line = result.lineNumberAt(lineStart),
                description = "Remove ForgeGradle archive reobfuscation hook now provided by ModDev",
                before = before,
                after = "(ModDev remaps the archive task)",
                confidence = Confidence.HIGH,
                ruleId = "build-migrate-reobfuscation-dsl"
            )
            result = result.removeRange(lineStart, end)
        }

        val replacements = listOf(
            Regex("""\bartifact\s*\(\s*tasks\.reobfJar\s*\)""") to "artifact(tasks.named(\"jar\"))",
            Regex("""\btasks\.reobfJar\.archiveFile\b""") to "tasks.named(\"jar\").flatMap { it.archiveFile }",
            Regex("""(?<![\w$.])reobfJar\.archiveFile\b""") to "tasks.named(\"jar\").flatMap { it.archiveFile }",
            Regex("""\btasks\.reobfJar\b""") to "tasks.named(\"jar\")"
        )
        for ((pattern, replacement) in replacements) {
            val matches = pattern.findAll(maskJavaCommentsAndLiterals(result)).toList()
            for (match in matches.asReversed()) {
                changes += Change(
                    file = file,
                    line = result.lineNumberAt(match.range.first),
                    description = "Retarget ForgeGradle reobfuscated publication artifact to the ModDev jar task",
                    before = result.substring(match.range),
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-migrate-reobfuscation-publication"
                )
                result = result.replaceRange(match.range, replacement)
            }
        }

        val remaining = Regex("""\breobf(?:Jar|uscate)\b""")
            .find(maskJavaCommentsAndLiterals(result))
        if (remaining != null) {
            errors += "Unmigrated ForgeGradle reobfuscation DSL remains in $file:${result.lineNumberAt(remaining.range.first)}"
        }
        return result
    }

    private fun removeUnsupportedJarJarTaskClassifier(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        var result = content
        val wholeBlockPattern = Regex(
            """(?ms)^[ \t]*tasks\.named\(\s*['"]jarJar['"]\s*\)\.configure\s*\{\s*archiveClassifier\s*=\s*['"][^'"]*['"]\s*\}\s*"""
        )
        val wholeBlockMatches = wholeBlockPattern.findAll(result).toList()
        for (match in wholeBlockMatches) {
            changes.add(Change(
                file = file,
                line = result.lineNumberAt(match.range.first),
                description = "Remove ForgeGradle JarJar archiveClassifier task configuration",
                before = match.value.trim(),
                after = "(removed; ModDev jarJar is not a Jar task)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-jarjar-archive-classifier"
            ))
        }
        result = wholeBlockPattern.replace(result, "")

        val linePattern = Regex("""(?m)^[ \t]*archiveClassifier\s*=\s*['"][^'"]*['"]\s*\r?\n?""")
        var searchFrom = 0
        while (true) {
            val blockMatch = Regex("""tasks\.named\(\s*['"]jarJar['"]\s*\)\.configure\s*\{""").find(result, searchFrom)
                ?: break
            val openBrace = result.indexOf('{', blockMatch.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) break
            val body = result.substring(openBrace + 1, closeBrace)
            val lineMatches = linePattern.findAll(body).toList()
            if (lineMatches.isNotEmpty()) {
                val before = result.substring(blockMatch.range.first, closeBrace + 1)
                val cleanedBody = linePattern.replace(body, "")
                result = result.substring(0, openBrace + 1) + cleanedBody + result.substring(closeBrace)
                changes.add(Change(
                    file = file,
                    line = result.lineNumberAt(blockMatch.range.first),
                    description = "Remove ForgeGradle JarJar archiveClassifier task configuration",
                    before = before.trim(),
                    after = result.substring(blockMatch.range.first, result.indexOf('}', openBrace) + 1).trim(),
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-jarjar-archive-classifier"
                ))
                searchFrom = openBrace + cleanedBody.length + 1
            } else {
                searchFrom = closeBrace + 1
            }
        }
        return result
    }

    private fun normalizeJarJarPublicationReferences(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        var result = content
        val replacements = listOf(
            Regex("""\bartifact\s+project\.tasks\.jarJar\b""") to "artifact tasks.named('jar')",
            Regex("""\bartifact\s+tasks\.jarJar\b""") to "artifact tasks.named('jar')",
            Regex("""\bmainArtifact\(\s*tasks\.jarJar\s*\)""") to "mainArtifact(tasks.jar)",
            Regex("""\buploadFile\s*=\s*tasks\.jarJar\b""") to "uploadFile = tasks.jar"
        )
        for ((pattern, replacement) in replacements) {
            val matches = pattern.findAll(result).toList()
            for (match in matches) {
                changes.add(Change(
                    file = file,
                    line = result.lineNumberAt(match.range.first),
                    description = "Publish ModDev archive jar instead of non-archive jarJar task",
                    before = match.value,
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-jarjar-publication-archive"
                ))
            }
            result = pattern.replace(result, replacement)
        }
        return result
    }

    private fun ensureSourceJarTask(content: String, changes: MutableList<Change>, file: Path): String {
        if (!Regex("""\bsourceJar\b""").containsMatchIn(content)) return content
        if (Regex("""\b(?:tasks\.(?:register|create)\(\s*['"]sourceJar['"]|task\s+sourceJar\b)""")
                .containsMatchIn(content)) {
            return content
        }

        val task = """
tasks.register('sourceJar', Jar) {
    archiveClassifier = 'sources'
    from sourceSets.main.allSource
}
""".trimIndent() + System.lineSeparator()
        val insertAt = Regex("""(?m)^publishing\s*\{""").find(content)?.range?.first
            ?: Regex("""(?m)^curseforge\s*\{""").find(content)?.range?.first
            ?: content.length
        changes.add(Change(
            file = file,
            line = content.lineNumberAt(insertAt.coerceAtMost(content.length)),
            description = "Create sourceJar task after removing ForgeGradle sourcejar plugin",
            before = "org.moddingx.modgradle.sourcejar plugin",
            after = "tasks.register('sourceJar', Jar) from sourceSets.main.allSource",
            confidence = Confidence.HIGH,
            ruleId = "build-sourcejar-task"
        ))
        return content.substring(0, insertAt) + task + content.substring(insertAt)
    }

    private fun ensureGameTestDimensionDataPackTask(
        content: String,
        projectDir: Path,
        changes: MutableList<Change>,
        file: Path
    ): String {
        if (file.fileName.toString().endsWith(".kts")) return content
        if (content.contains("modporter_gametest_dimensions")) return content
        if (!projectHasGameTests(projectDir)) return content
        if (!projectHasCustomDimensionResources(projectDir)) return content

        val insert = "\n\n$GAMETEST_DIMENSION_DATAPACK_TASK\n"
        changes.add(Change(
            file = file,
            line = content.lineNumberAt(content.length),
            description = "Generate a GameTest-only datapack that adds modded dimensions to the flat test world preset",
            before = "(missing GameTest dimension datapack task)",
            after = "prepareGameTestServerRun creates run/world/datapacks/modporter_gametest_dimensions",
            confidence = Confidence.HIGH,
            ruleId = "build-gametest-dimension-flat-preset"
        ))
        return content.trimEnd() + insert
    }

    private fun projectHasGameTests(projectDir: Path): Boolean {
        val srcDir = projectDir.resolve("src")
        if (!srcDir.exists()) return false
        Files.walk(srcDir).use { stream ->
            return stream.anyMatch { path ->
                path.isRegularFile() &&
                    path.extension == "java" &&
                    runCatching { path.readText().contains("@GameTest") }.getOrDefault(false)
            }
        }
    }

    private fun projectHasCustomDimensionResources(projectDir: Path): Boolean {
        return listOf(
            projectDir.resolve("src/main/resources/data"),
            projectDir.resolve("src/generated/resources/data")
        ).any { dataRoot ->
            if (!dataRoot.exists()) {
                false
            } else {
                Files.walk(dataRoot).use { stream ->
                    stream.anyMatch { path -> isDimensionJsonResource(dataRoot, path) }
                }
            }
        }
    }

    private fun isDimensionJsonResource(dataRoot: Path, path: Path): Boolean {
        if (!path.isRegularFile() || path.extension != "json") return false
        val parts = dataRoot.relativize(path).map { it.toString() }
        return parts.size >= 3 && parts[1] == "dimension"
    }

    private fun transformSettingsGradle(
        file: Path, dryRun: Boolean
    ): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        var content = file.readText()
        val original = content

        // Replace plugin repository for ForgeGradle
        val forgeGradleRepo = Regex(
            """maven\s*\{\s*url\s*=?\s*['"]https?://maven\.minecraftforge\.net/?['"].*?\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        if (forgeGradleRepo.containsMatchIn(content)) {
            val match = forgeGradleRepo.find(content)!!
            changes.add(Change(
                file = file, line = content.lineNumberAt(match.range.first),
                description = "Replace ForgeGradle plugin repo with NeoForge",
                before = match.value,
                after = """maven { url = "https://maven.neoforged.net/releases" }""",
                confidence = Confidence.HIGH,
                ruleId = "build-settings-repo"
            ))
            content = content.replace(match.value,
                """maven { url = "https://maven.neoforged.net/releases" }""")
        }

        // Replace ForgeGradle plugin marker
        if (content.contains("net.minecraftforge.gradle")) {
            changes.add(Change(
                file = file, line = 0,
                description = "Replace ForgeGradle plugin ID in settings",
                before = "net.minecraftforge.gradle",
                after = "net.neoforged.moddev",
                confidence = Confidence.HIGH,
                ruleId = "build-settings-plugin"
            ))
            content = content.replace("net.minecraftforge.gradle", "net.neoforged.moddev")
        }

        if (content.contains("maven.minecraftforge.net")) {
            content = content.replace("maven.minecraftforge.net", "maven.neoforged.net/releases")
            changes.add(Change(
                file = file, line = 0,
                description = "Replace Forge Maven URL in settings",
                before = "maven.minecraftforge.net",
                after = "maven.neoforged.net/releases",
                confidence = Confidence.HIGH,
                ruleId = "build-settings-maven"
            ))
        }

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to emptyList()
    }

    private fun addMissingEmptyGameTestStructures(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val declaredTemplates = mutableSetOf<String>()
        srcDir.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val content = file.readText()
                if (!content.contains("@GameTest")) return@forEach

                val constants = Regex("""(?:private|public|protected)?\s*(?:static\s+)?final\s+String\s+(\w+)\s*=\s*"([^"]+)"""")
                    .findAll(content)
                    .associate { it.groupValues[1] to it.groupValues[2] }

                Regex("""@GameTest\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(content)
                    .forEach { match ->
                        val args = match.groupValues[1]
                        val templateValue = Regex("""template\s*=\s*("([^"]+)"|(\w+))""").find(args) ?: return@forEach
                        val literal = templateValue.groupValues[2]
                        val constantName = templateValue.groupValues[3]
                        val template = literal.ifBlank { constants[constantName].orEmpty() }
                        if (template.isNotBlank()) declaredTemplates.add(template)
                    }
            }

        val emptyTemplates = declaredTemplates
            .filter { template ->
                val normalized = template.lowercase()
                "empty" in normalized || Regex("""(?:^|_)1x1(?:_|$)""").containsMatchIn(normalized)
            }
            .toSortedSet()
        if (emptyTemplates.isEmpty()) return emptyList()

        val structuresDir = projectDir.resolve("src/main/resources/gameteststructures")
        val changes = mutableListOf<Change>()
        for (template in emptyTemplates) {
            if (gameTestStructureExists(projectDir, template)) continue
            val target = structuresDir.resolve("$template.snbt")
            changes.add(Change(
                file = target,
                line = 0,
                description = "Create missing empty GameTest structure '$template'",
                before = "(missing)",
                after = "1x1 empty GameTest SNBT structure",
                confidence = Confidence.HIGH,
                ruleId = "build-gametest-empty-structure"
            ))
            if (!dryRun) {
                target.parent.createDirectories()
                target.writeText(EMPTY_GAMETEST_STRUCTURE_SNBT)
            }
        }
        return changes
    }

    private fun gameTestStructureExists(projectDir: Path, template: String): Boolean {
        val relative = template.replace('.', '/')
        val resourceRoots = listOf(
            projectDir.resolve("src/main/resources/gameteststructures/$relative.snbt"),
            projectDir.resolve("src/main/resources/gameteststructures/$template.snbt"),
            projectDir.resolve("src/main/resources/data"),
            projectDir.resolve("src/generated/resources/data")
        )
        if (resourceRoots.take(2).any { it.exists() }) return true

        for (dataRoot in resourceRoots.drop(2)) {
            if (!dataRoot.exists()) continue
            dataRoot.listDirectoryEntries().forEach { namespaceDir ->
                if (namespaceDir.resolve("structures/$relative.nbt").exists()) return true
                if (namespaceDir.resolve("structure/$relative.nbt").exists()) return true
            }
        }
        return false
    }

    private fun migrateStructureTemplatePoolReflectionFields(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val codeWithStringArguments = maskJavaCommentsAndTextBlocks(original)
                if (!codeWithStringArguments.contains("StructureTemplatePool.class") ||
                    (!codeWithStringArguments.contains("\"f_210560_\"") && !codeWithStringArguments.contains("\"f_210559_\""))) {
                    return@forEach
                }

                var modified = original
                modified = replaceStructurePoolFieldAccess(modified, "templatesField", "f_210560_", "templates")
                modified = replaceStructurePoolFieldAccess(modified, "rawTemplatesField", "f_210559_", "rawTemplates")
                val executableModified = maskJavaCommentsAndLiterals(modified)
                if (!executableModified.contains("ObfuscationReflectionHelper.findField(")) {
                    modified = modified.replace(
                        Regex("""(?m)^[ \t]*import\s+net\.neoforged\.fml\.util\.ObfuscationReflectionHelper;\s*\r?\n"""),
                        ""
                    )
                }
                if (!Regex("""\bField\s+\w+""").containsMatchIn(maskJavaCommentsAndLiterals(modified))) {
                    modified = removeJavaImport(modified, "java.lang.reflect.Field")
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "StructureTemplatePool internals: reflection -> access-transformer-backed direct fields",
                        before = "ObfuscationReflectionHelper.findField(... f_210560_/f_210559_)",
                        after = "pool.templates/pool.rawTemplates plus access transformer entries",
                        confidence = Confidence.HIGH,
                        ruleId = "build-structure-pool-at-direct-fields"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir,
                        listOf(
                            "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates",
                            "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"
                        ),
                        dryRun,
                        "build-structure-pool-at-entries",
                        "Expose StructureTemplatePool fields through Access Transformers instead of reflection"
                    ))
                }
            }
        return changes
    }

    private fun replaceStructurePoolFieldAccess(
        content: String,
        variableName: String,
        oldFieldName: String,
        newFieldName: String
    ): String {
        var result = content
        result = replaceExecutableRegex(result, Regex("""\(\s*[^()]+?\s*\)\s*$variableName\.get\(([^()]+)\)""")) { match ->
            "${match.groupValues[1].trim()}.$newFieldName"
        }
        result = replaceExecutableRegex(result, Regex("""\b$variableName\.get\(([^()]+)\)""")) { match ->
            "${match.groupValues[1].trim()}.$newFieldName"
        }
        result = replaceExecutableRegex(result, Regex("""\b$variableName\.set\(\s*([^,()]+)\s*,\s*([^;]+?)\s*\)\s*;""")) { match ->
            "${match.groupValues[1].trim()}.$newFieldName = ${match.groupValues[2].trim()};"
        }

        val multiline = Regex(
            """(?m)^([ \t]*)Field\s+$variableName\s*=\s*ObfuscationReflectionHelper\.findField\(\s*\r?\n[ \t]*StructureTemplatePool\.class,\s*"${Regex.escape(oldFieldName)}"\);\s*(?://[^\r\n]*)?"""
        )
        result = replaceCommentAndTextBlockMaskedRegex(result, multiline) { "" }

        val singleLine = Regex(
            """(?m)^([ \t]*)Field\s+$variableName\s*=\s*ObfuscationReflectionHelper\.findField\(\s*StructureTemplatePool\.class,\s*"${Regex.escape(oldFieldName)}"\s*\);\s*(?://[^\r\n]*)?"""
        )
        result = replaceCommentAndTextBlockMaskedRegex(result, singleLine) { "" }
        result = replaceExecutableRegex(result, Regex("""(?m)^[ \t]*$variableName\.setAccessible\(true\);\s*\r?\n""")) { "" }
        return result
    }

    private fun migratePendingBlockEntityReflectionFields(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("LevelChunk.class.getDeclaredFields()") ||
                    !executableOriginal.contains("pendingBlockEntitiesField") ||
                    !executableOriginal.contains("clearPendingBlockEntities")) {
                    return@forEach
                }

                var modified = original
                modified = removeJavaImport(modified, "java.lang.reflect.Field")
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""(?m)^[ \t]*private\s+static\s+Field\s+pendingBlockEntitiesField\s*=\s*null\s*;\s*\r?\n""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""(?m)^[ \t]*private\s+static\s+boolean\s+pendingBEFieldInitialized\s*=\s*false\s*;\s*\r?\n""")
                ) { "" }

                val executableModified = maskJavaCommentsAndLiterals(modified)
                val methodMatch = Regex(
                    """private\s+static\s+void\s+clearPendingBlockEntities\s*\(\s*LevelChunk\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
                ).find(executableModified) ?: return@forEach
                val chunkParam = methodMatch.groupValues[1]
                val openBrace = executableModified.indexOf('{', methodMatch.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(executableModified, openBrace) else -1
                if (closeBrace <= openBrace) return@forEach

                val methodBody = executableModified.substring(openBrace + 1, closeBrace)
                val logger = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.LOGGER)\.(?:trace|debug|info)\s*\(""")
                    .find(methodBody)
                    ?.groupValues
                    ?.get(1)
                    ?: "LOGGER"
                val replacement = """
private static void clearPendingBlockEntities(LevelChunk $chunkParam) {
        Map<BlockPos, CompoundTag> pendingBlockEntities = $chunkParam.pendingBlockEntities;
        if (!pendingBlockEntities.isEmpty()) {
            int count = pendingBlockEntities.size();
            pendingBlockEntities.clear();
            if (count > 0) {
                $logger.debug("Cleared {} pending block entities from chunk", count);
            }
        }
    }
""".trimIndent()
                modified = modified.substring(0, methodMatch.range.first) +
                    replacement +
                    modified.substring(closeBrace + 1)
                modified = ensureJavaImport(modified, "java.util.Map")
                modified = ensureJavaImport(modified, "net.minecraft.core.BlockPos")
                modified = ensureJavaImport(modified, "net.minecraft.nbt.CompoundTag")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "LevelChunk pending block entities: reflection -> access-transformer-backed direct field",
                        before = "LevelChunk.class.getDeclaredFields() + Field#setAccessible",
                        after = "chunk.pendingBlockEntities.clear() plus access transformer entry",
                        confidence = Confidence.HIGH,
                        ruleId = "build-levelchunk-pending-blockentities-at"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir,
                        listOf("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"),
                        dryRun,
                        "build-levelchunk-pending-blockentities-at-entry",
                        "Expose ChunkAccess pendingBlockEntities through Access Transformers instead of reflection"
                    ))
                }
            }
        return changes
    }

    private fun migrateEntityVisibilityReflectionHooks(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                val codeWithStringArguments = maskJavaCommentsAndTextBlocks(original)
                if (!codeWithStringArguments.contains("declaresVisibilityHook") ||
                    !executableOriginal.contains(".getMethod(") ||
                    !codeWithStringArguments.contains("startSeenByPlayer") ||
                    !codeWithStringArguments.contains("stopSeenByPlayer")) {
                    return@forEach
                }

                var modified = original
                val hookMethod = Regex(
                    """private\s+static\s+boolean\s+hasNativePlayerVisibilityHook\s*\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
                ).find(executableOriginal) ?: return@forEach
                val entityParam = hookMethod.groupValues[1]
                val hookOpenBrace = executableOriginal.indexOf('{', hookMethod.range.first)
                val hookCloseBrace = if (hookOpenBrace >= 0) findMatchingBrace(executableOriginal, hookOpenBrace) else -1
                if (hookCloseBrace <= hookOpenBrace) return@forEach

                val executableHookBody = executableOriginal.substring(hookOpenBrace + 1, hookCloseBrace)
                val hookBodyWithStringArguments = codeWithStringArguments.substring(hookOpenBrace + 1, hookCloseBrace)
                if (!executableHookBody.contains("declaresVisibilityHook") ||
                    !hookBodyWithStringArguments.contains("startSeenByPlayer") ||
                    !hookBodyWithStringArguments.contains("stopSeenByPlayer")) {
                    return@forEach
                }

                val replacementHook = """
private static boolean hasNativePlayerVisibilityHook(Entity $entityParam) {
        return $entityParam instanceof WitherBoss;
    }
""".trimIndent()
                modified = modified.substring(0, hookMethod.range.first) +
                    replacementHook +
                    modified.substring(hookCloseBrace + 1)

                val declaresMethod = Regex(
                    """private\s+static\s+boolean\s+declaresVisibilityHook\s*\(\s*Class<\?>\s+[A-Za-z_$][\w$]*\s*,\s*String\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
                ).find(maskJavaCommentsAndLiterals(modified))
                if (declaresMethod != null) {
                    val executableModified = maskJavaCommentsAndLiterals(modified)
                    val openBrace = executableModified.indexOf('{', declaresMethod.range.first)
                    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableModified, openBrace) else -1
                    if (closeBrace > openBrace) {
                        modified = modified.substring(0, declaresMethod.range.first).trimEnd() +
                            "\n\n" +
                            modified.substring(closeBrace + 1).trimStart()
                    }
                }

                modified = removeJavaImport(modified, "java.lang.reflect.Method")
                val modifiedWithoutImports = Regex("""(?m)^[ \t]*import\s+[^;]+;\s*\r?\n""").replace(modified, "")
                if (!Regex("""\bServerPlayer\b""").containsMatchIn(modifiedWithoutImports)) {
                    modified = removeJavaImport(modified, "net.minecraft.server.level.ServerPlayer")
                }
                modified = ensureJavaImport(modified, "net.minecraft.world.entity.boss.wither.WitherBoss")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Entity visibility hook reflection -> explicit vanilla boss visibility API check",
                        before = "Class#getMethod(... startSeenByPlayer/stopSeenByPlayer)",
                        after = "entity instanceof WitherBoss",
                        confidence = Confidence.HIGH,
                        ruleId = "build-entity-visibility-hook-no-reflection"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateClientEventPackageTargets(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("@Mixin(") || !executableOriginal.contains(".client.ColorHandler")) {
                    return@forEach
                }

                val importPattern = Regex("""(?m)^([ \t]*import\s+)([a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)*)\.client\.ColorHandler;\s*$""")
                val modified = replaceExecutableRegex(original, importPattern) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}.client.event.ColorHandler;"
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Retarget client ColorHandler mixin import to event package",
                        before = "<mod>.client.ColorHandler",
                        after = "<mod>.client.event.ColorHandler",
                        confidence = Confidence.HIGH,
                        ruleId = "build-client-event-colorhandler-target"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private data class JavaClassInfo(
        val path: Path,
        val packageName: String?,
        val className: String,
        val qualifiedName: String,
        val source: String,
        val isClientOnly: Boolean
    )

    private data class ProjectJavaClass(
        val path: Path,
        val packageName: String,
        val className: String,
        val qualifiedName: String,
        val source: String
    )

    private data class ProjectStaticFieldDeclaration(
        val range: IntRange,
        val indent: String,
        val type: String
    )

    private data class FieldAccessorEnsureResult(
        val source: String,
        val added: Boolean
    )

    private fun migrateSameProjectStaticFieldReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val classes = collectProjectJavaClasses(srcDir)
        if (classes.isEmpty()) return emptyList()

        val classesByQualifiedName = classes.associateBy { it.qualifiedName }
        val sources = classes.associate { it.path to it.source }.toMutableMap()
        val originalSources = sources.toMap()
        val changes = mutableListOf<Change>()
        val emittedAccessorChanges = mutableSetOf<Pair<Path, String>>()
        val id = """[A-Za-z_$][\w$]*"""
        val ownerReference = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""
        val assignmentType = """[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?(?:\s*<[^;=]+?>)?(?:\s*\[\s*\])*"""
        val reflectionPattern = Regex(
            """(?s)\b(?:java\.lang\.reflect\.)?Field\s+($id)\s*=\s*($ownerReference)\s*\.class\s*\.getDeclaredField\s*\(\s*"($id)"\s*\)\s*;\s*\1\s*\.setAccessible\s*\(\s*true\s*\)\s*;\s*($assignmentType)\s+($id)\s*=\s*(?:\(\s*[^;]+?\)\s*)?\1\s*\.get\s*\(\s*null\s*\)\s*;"""
        )

        for (caller in classes) {
            var callerSource = sources[caller.path] ?: continue
            if (!callerSource.contains("getDeclaredField") || !callerSource.contains(".get(null)")) continue

            var callerChanged = false
            while (true) {
                val masked = maskJavaCommentsAndTextBlocks(callerSource)
                val match = reflectionPattern.find(masked) ?: break
                val imports = javaSingleTypeImports(callerSource)
                val wildcardImports = javaWildcardImports(callerSource)
                val ownerReferenceText = callerSource.substring(match.groups[2]!!.range).trim()
                val fieldName = callerSource.substring(match.groups[3]!!.range).trim()
                val owner = resolveProjectJavaClassReference(
                    ownerReferenceText,
                    caller.packageName,
                    imports,
                    wildcardImports,
                    classesByQualifiedName
                ) ?: break
                if (owner.path == caller.path) break

                val currentOwnerSource = sources[owner.path] ?: break
                val fieldDeclaration = findPrivateStaticFieldDeclaration(currentOwnerSource, fieldName) ?: break
                val accessorName = sameProjectFieldAccessorName(fieldName)
                val ensured = ensureSameProjectFieldAccessor(currentOwnerSource, fieldDeclaration, fieldName, accessorName)
                    ?: break
                sources[owner.path] = ensured.source
                if (ensured.added && emittedAccessorChanges.add(owner.path to accessorName)) {
                    changes.add(Change(
                        file = owner.path,
                        line = currentOwnerSource.lineNumberAt(fieldDeclaration.range.first),
                        description = "Expose same-project private static field through explicit source accessor",
                        before = "private static ${fieldDeclaration.type} $fieldName",
                        after = "public static ${fieldDeclaration.type} $accessorName()",
                        confidence = Confidence.HIGH,
                        ruleId = "build-same-project-static-field-accessor"
                    ))
                }

                val targetType = normalizeJavaTypeWhitespace(callerSource.substring(match.groups[4]!!.range))
                val targetName = callerSource.substring(match.groups[5]!!.range).trim()
                val directAssignment = "$targetType $targetName = $ownerReferenceText.$accessorName();"
                callerSource = callerSource.replaceRange(match.range, directAssignment)
                callerSource = unwrapReflectiveOperationTryBlocksContaining(callerSource, accessorName)
                callerSource = removeJavaImportIfSimpleNameUnused(callerSource, "java.lang.reflect.Field")
                callerSource = removeJavaImportIfSimpleNameUnused(callerSource, "java.lang.ReflectiveOperationException")
                callerChanged = true
                changes.add(Change(
                    file = caller.path,
                    line = callerSource.lineNumberAt(match.range.first.coerceAtMost(callerSource.length)),
                    description = "Replace same-project static field reflection with explicit source accessor call",
                    before = "$ownerReferenceText.class.getDeclaredField(\"$fieldName\")",
                    after = "$ownerReferenceText.$accessorName()",
                    confidence = Confidence.HIGH,
                    ruleId = "build-same-project-static-field-reflection"
                ))
            }

            if (callerChanged) {
                sources[caller.path] = callerSource
            }
        }

        if (!dryRun) {
            for ((path, source) in sources) {
                if (source != originalSources[path]) {
                    path.writeText(source)
                }
            }
        }
        return changes
    }

    private fun collectProjectJavaClasses(srcDir: Path): List<ProjectJavaClass> {
        val result = mutableListOf<ProjectJavaClass>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val source = javaFile.readText()
                val packageName = javaPackageName(source).orEmpty()
                val className = javaFile.fileName.toString().removeSuffix(".java")
                val qualifiedName = if (packageName.isBlank()) className else "$packageName.$className"
                result.add(ProjectJavaClass(javaFile, packageName, className, qualifiedName, source))
            }
        return result
    }

    private fun javaSingleTypeImports(source: String): Map<String, String> =
        Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .findAll(source)
            .associate { match ->
                val qualifiedName = match.groupValues[1]
                qualifiedName.substringAfterLast('.') to qualifiedName
            }

    private fun javaWildcardImports(source: String): Set<String> =
        Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.\*\s*;""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

    private fun resolveProjectJavaClassReference(
        reference: String,
        packageName: String,
        imports: Map<String, String>,
        wildcardImports: Set<String>,
        classesByQualifiedName: Map<String, ProjectJavaClass>
    ): ProjectJavaClass? {
        val normalized = reference.replace(Regex("""\s+"""), "")
        if (normalized.contains('.')) {
            classesByQualifiedName[normalized]?.let { return it }
        }
        imports[normalized]?.let { imported ->
            classesByQualifiedName[imported]?.let { return it }
        }
        val samePackageName = if (packageName.isBlank()) normalized else "$packageName.$normalized"
        classesByQualifiedName[samePackageName]?.let { return it }
        val wildcardCandidates = wildcardImports
            .map { "$it.$normalized" }
            .mapNotNull { classesByQualifiedName[it] }
        return wildcardCandidates.singleOrNull()
    }

    private fun findPrivateStaticFieldDeclaration(source: String, fieldName: String): ProjectStaticFieldDeclaration? {
        val masked = maskJavaCommentsAndLiterals(source)
        val fieldPattern = Regex(
            """(?m)^([ \t]*)private\s+((?:(?:static|final|volatile|transient)\s+)*)((?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)(?:\s*<[^;\r\n=]+>)?(?:\s*\[\s*\])*)\s+${Regex.escape(fieldName)}\s*(?:=[^;\r\n]*)?;"""
        )
        val match = fieldPattern.find(masked) ?: return null
        if (!Regex("""\bstatic\b""").containsMatchIn(match.groupValues[2])) return null
        return ProjectStaticFieldDeclaration(
            range = match.range,
            indent = match.groupValues[1],
            type = normalizeJavaTypeWhitespace(source.substring(match.groups[3]!!.range))
        )
    }

    private fun ensureSameProjectFieldAccessor(
        source: String,
        fieldDeclaration: ProjectStaticFieldDeclaration,
        fieldName: String,
        accessorName: String
    ): FieldAccessorEnsureResult? {
        val masked = maskJavaCommentsAndLiterals(source)
        val existingName = Regex("""\b${Regex.escape(accessorName)}\s*\(""").containsMatchIn(masked)
        val existingAccessor = Regex(
            """\bpublic\s+static\s+[^;{}]+?\s+${Regex.escape(accessorName)}\s*\(\s*\)"""
        ).containsMatchIn(masked)
        if (existingName) {
            return if (existingAccessor) FieldAccessorEnsureResult(source, added = false) else null
        }

        val method = """

${fieldDeclaration.indent}public static ${fieldDeclaration.type} $accessorName() {
${fieldDeclaration.indent}    return $fieldName;
${fieldDeclaration.indent}}
""".trimEnd()
        val insertAt = fieldDeclaration.range.last + 1
        return FieldAccessorEnsureResult(
            source = source.substring(0, insertAt) + method + source.substring(insertAt),
            added = true
        )
    }

    private fun sameProjectFieldAccessorName(fieldName: String): String {
        val capitalized = fieldName.take(1).uppercase() + fieldName.drop(1)
        return "modporterAccess$capitalized"
    }

    private fun normalizeJavaTypeWhitespace(type: String): String =
        type.trim()
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*<\s*"""), "<")
            .replace(Regex("""\s*,\s*"""), ", ")
            .replace(Regex("""\s*>\s*"""), ">")
            .replace(Regex("""\s*\[\s*]\s*"""), "[]")

    private fun unwrapReflectiveOperationTryBlocksContaining(source: String, requiredToken: String): String {
        val id = """[A-Za-z_$][\w$]*"""
        var result = source
        var searchStart = 0
        while (searchStart < result.length) {
            val masked = maskJavaCommentsAndTextBlocks(result)
            val tryMatch = Regex("""(?m)^([ \t]*)try\s*\{""").find(masked, searchStart) ?: break
            val tryOpen = masked.indexOf('{', tryMatch.range.first)
            val tryClose = findMatchingBrace(result, tryOpen)
            if (tryClose <= tryOpen) {
                searchStart = tryMatch.range.last + 1
                continue
            }
            val afterTry = result.substring(tryClose + 1)
            val catchMatch = Regex(
                """^\s*catch\s*\(\s*(?:java\.lang\.)?ReflectiveOperationException\s+$id\s*\)\s*\{"""
            ).find(afterTry)
            if (catchMatch == null) {
                searchStart = tryClose + 1
                continue
            }
            val catchOpen = result.indexOf('{', tryClose + 1 + catchMatch.range.first)
            val catchClose = findMatchingBrace(result, catchOpen)
            if (catchClose <= catchOpen) {
                searchStart = tryClose + 1
                continue
            }
            val tryBody = result.substring(tryOpen + 1, tryClose)
            if (!tryBody.contains(requiredToken)) {
                searchStart = tryClose + 1
                continue
            }
            val replacement = unindentJavaBlockBody(tryBody, tryMatch.groupValues[1])
            result = result.substring(0, tryMatch.range.first) + replacement + result.substring(catchClose + 1)
            searchStart = tryMatch.range.first + replacement.length
        }
        return result
    }

    private fun unindentJavaBlockBody(body: String, targetIndent: String): String {
        val trimmed = body.trim('\r', '\n')
        if (trimmed.isBlank()) return ""
        val lines = trimmed.split(Regex("""\r?\n"""))
        val childIndent = lines
            .filter { it.isNotBlank() }
            .map { it.takeWhile { ch -> ch == ' ' || ch == '\t' } }
            .filter { it.length > targetIndent.length && it.startsWith(targetIndent) }
            .minByOrNull { it.length }
        val unindented = lines.joinToString("\n") { line ->
            if (childIndent != null && line.startsWith(childIndent)) {
                targetIndent + line.substring(childIndent.length)
            } else {
                line
            }
        }
        return "$unindented\n"
    }

    private fun guardClientOnlyEventRegistrations(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        if (javaFiles.isEmpty()) return emptyList()

        val classes = javaFiles.map { javaFile ->
            val source = javaFile.readText()
            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
            val className = javaFile.fileName.toString().removeSuffix(".java")
            JavaClassInfo(
                path = javaFile,
                packageName = packageName,
                className = className,
                qualifiedName = if (packageName.isNullOrBlank()) className else "$packageName.$className",
                source = source,
                isClientOnly = isClientOnlyJavaSource(source)
            )
        }
        val classesBySimpleName = classes.groupBy { it.className }
        val classesByQualifiedName = classes.associateBy { it.qualifiedName }
        val clientQualifiedNames = classes.filter { it.isClientOnly }.map { it.qualifiedName }.toSet()
        val changes = mutableListOf<Change>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            var modified = original
            val guarded = guardClientOnlyListenerReferences(
                modified,
                clientQualifiedNames,
                classesBySimpleName,
                classesByQualifiedName
            )
            if (guarded != modified) {
                modified = guarded
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Guard client-only event listener method references from dedicated-server class loading",
                    before = "eventBus.addListener(ClientOnlyClass::handler)",
                    after = "FMLLoader Dist.CLIENT guard around the original listener registration",
                    confidence = Confidence.HIGH,
                    ruleId = "build-client-only-listener-dist-guard"
                ))
            }

            val annotated = addClientDistToEventBusSubscribers(modified)
            if (annotated != modified) {
                modified = annotated
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Mark EventBusSubscriber classes that handle client lifecycle/events as Dist.CLIENT",
                    before = "@EventBusSubscriber without value = Dist.CLIENT",
                    after = "@EventBusSubscriber(..., value = Dist.CLIENT)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-client-eventbus-subscriber-dist"
                ))
            }

            if (modified != original && !dryRun) {
                javaFile.writeText(modified)
            }
        }

        return changes
    }

    private fun isClientOnlyJavaSource(source: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return code.contains("net.minecraft.client.") ||
            code.contains("net.neoforged.neoforge.client.event.") ||
            Regex("""(?m)^\s*package\s+.*\.client(?:\.|;)""").containsMatchIn(code) ||
            Regex("""@OnlyIn\s*\(\s*(?:Dist\.)?CLIENT\s*\)""").containsMatchIn(code) ||
            Regex("""@(?:Mod\.)?EventBusSubscriber\s*\([\s\S]*?\bvalue\s*=\s*(?:net\.neoforged\.api\.distmarker\.)?Dist\.CLIENT""")
                .containsMatchIn(code)
    }

    private fun guardClientOnlyListenerReferences(
        source: String,
        clientQualifiedNames: Set<String>,
        classesBySimpleName: Map<String, List<JavaClassInfo>>,
        classesByQualifiedName: Map<String, JavaClassInfo>
    ): String {
        val listenerPattern = Regex(
            """(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.addListener\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)\s*\)\s*;\s*(?://[^\r\n]*)?$"""
        )
        var result = source
        for (match in listenerPattern.findAll(source).toList().asReversed()) {
            val targetRef = match.groupValues[3]
            val methodName = match.groupValues[4]
            if (!shouldGuardClientOnlyListenerReference(
                    targetRef,
                    methodName,
                    source,
                    clientQualifiedNames,
                    classesBySimpleName,
                    classesByQualifiedName
                )
            ) {
                continue
            }
            if (isWithinDistClientGuard(result, match.range.first)) {
                continue
            }
            val indent = match.groupValues[1]
            val originalLine = match.value.trim()
            val replacement = buildString {
                append(indent)
                append("if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {")
                append(System.lineSeparator())
                append(indent)
                append("    ")
                append(originalLine)
                append(System.lineSeparator())
                append(indent)
                append("}")
            }
            result = result.replaceRange(match.range, replacement)
        }
        return result
    }

    private fun shouldGuardClientOnlyListenerReference(
        targetRef: String,
        methodName: String,
        source: String,
        clientQualifiedNames: Set<String>,
        classesBySimpleName: Map<String, List<JavaClassInfo>>,
        classesByQualifiedName: Map<String, JavaClassInfo>
    ): Boolean {
        val targetClass = resolveListenerTargetClass(targetRef, source, classesBySimpleName, classesByQualifiedName)
        if (targetClass != null) {
            return shouldGuardClientOnlyListenerMethod(targetClass, methodName)
        }

        return if (targetRef.contains(".")) {
            targetRef in clientQualifiedNames
        } else {
            val imported = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(targetRef)})\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
            if (imported != null) {
                imported in clientQualifiedNames
            } else {
                val localMatches = classesBySimpleName[targetRef].orEmpty()
                localMatches.size == 1 && localMatches.single().isClientOnly
            }
        }
    }

    private fun resolveListenerTargetClass(
        targetRef: String,
        source: String,
        classesBySimpleName: Map<String, List<JavaClassInfo>>,
        classesByQualifiedName: Map<String, JavaClassInfo>
    ): JavaClassInfo? {
        if (targetRef.contains(".")) {
            classesByQualifiedName[targetRef]?.let { return it }
        }

        val imported = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(targetRef)})\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
        if (imported != null) {
            classesByQualifiedName[imported]?.let { return it }
        }

        val currentPackage = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
        val localMatches = classesBySimpleName[targetRef].orEmpty()
        if (currentPackage != null) {
            localMatches.singleOrNull { it.packageName == currentPackage }?.let { return it }
        }
        return localMatches.singleOrNull()
    }

    private fun shouldGuardClientOnlyListenerMethod(targetClass: JavaClassInfo, methodName: String): Boolean {
        val methodSource = findJavaMethodSource(targetClass.source, methodName)
            ?: return targetClass.isClientOnly
        return javaMethodContainsClientOnlyApis(methodSource, targetClass.source)
    }

    private fun findJavaMethodSource(source: String, methodName: String): String? {
        val methodPattern = Regex(
            """(?m)(?:^|[\r\n])([ \t]*(?:@[^\r\n]+\s*)*(?:(?:public|protected|private|static|final|synchronized|native|abstract|strictfp)\s+)*(?:<[^>{};]+>\s*)?(?:[A-Za-z_$][\w$]*(?:\s*<[^>{};]+>)?(?:\s*\[\s*])?(?:\s*,\s*)?|\?|extends|super|&|\.)+(?:\s+)+${Regex.escape(methodName)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\s*\{)"""
        )
        val match = methodPattern.find(source) ?: return null
        val openBrace = source.indexOf('{', match.range.first)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace <= openBrace) return null
        return source.substring(match.range.first, closeBrace + 1)
    }

    private fun javaMethodContainsClientOnlyApis(methodSource: String, classSource: String): Boolean {
        val methodCode = maskJavaCommentsAndLiterals(methodSource)
        val classCode = maskJavaCommentsAndLiterals(classSource)
        if (
            methodCode.contains("net.minecraft.client.") ||
            methodCode.contains("net.neoforged.neoforge.client.event.")
        ) {
            return true
        }

        val clientImportedNames = Regex(
            """(?m)^\s*import\s+(net\.minecraft\.client\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|net\.neoforged\.neoforge\.client\.event\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"""
        ).findAll(classCode).map { it.groupValues[1].substringAfterLast('.') }.toSet()
        val knownClientEventNames = setOf(
            "FMLClientSetupEvent",
            "ClientTickEvent",
            "CustomizeGuiOverlayEvent",
            "EntityRenderersEvent",
            "InputEvent",
            "ModelEvent",
            "RegisterClientCommandsEvent",
            "RegisterClientExtensionsEvent",
            "RegisterClientReloadListenersEvent",
            "RegisterClientTooltipComponentFactoriesEvent",
            "RegisterColorHandlersEvent",
            "RegisterDimensionSpecialEffectsEvent",
            "RegisterGuiLayersEvent",
            "RegisterKeyMappingsEvent",
            "RegisterMenuScreensEvent",
            "RegisterNamedRenderTypesEvent",
            "RegisterParticleProvidersEvent",
            "RegisterPresetEditorsEvent",
            "RegisterRecipeBookCategoriesEvent",
            "RegisterShadersEvent",
            "RenderGuiEvent",
            "RenderLevelStageEvent",
            "RenderLivingEvent",
            "RenderNameTagEvent",
            "RenderPlayerEvent",
            "ScreenEvent",
            "TextureAtlasStitchedEvent",
            "ViewportEvent"
        )
        return (clientImportedNames + knownClientEventNames)
            .any { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(methodCode) }
    }

    private fun isWithinDistClientGuard(source: String, offset: Int): Boolean {
        val guardPattern = Regex(
            """if\s*\(\s*(?:net\.neoforged\.fml\.loading\.)?(?:FMLLoader\.getDist\(\)|FMLEnvironment\.dist)\s*==\s*(?:net\.neoforged\.api\.distmarker\.)?Dist\.CLIENT\s*\)\s*\{"""
        )
        return guardPattern.findAll(source.substring(0, offset.coerceAtMost(source.length)))
            .any { match ->
                val openBrace = source.indexOf('{', match.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
                closeBrace > offset
            }
    }

    private fun addClientDistToEventBusSubscribers(source: String): String {
        val annotationPattern = Regex("""@(?:Mod\.)?EventBusSubscriber\s*\(""")
        var result = source
        for (match in annotationPattern.findAll(source).toList().asReversed()) {
            val openParen = result.indexOf('(', match.range.first)
            val closeParen = if (openParen >= 0) findClosing(result, openParen, '(', ')') else -1
            if (closeParen <= openParen) continue
            val annotation = result.substring(match.range.first, closeParen + 1)
            if (Regex("""\bvalue\s*=""").containsMatchIn(annotation)) continue

            val classMatch = Regex(
                """\s*(?:public\s+|protected\s+|private\s+|static\s+|final\s+|abstract\s+)*class\s+([A-Za-z_$][\w$]*)\b"""
            ).find(result, closeParen + 1) ?: continue
            if (classMatch.range.first - closeParen > 240) continue
            val openBrace = result.indexOf('{', classMatch.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) continue
            val classBody = result.substring(openBrace + 1, closeBrace)
            if (!classBodyContainsClientOnlyApis(classBody)) continue

            result = result.replaceRange(
                match.range.first,
                closeParen + 1,
                addDistClientValueToEventBusSubscriberAnnotation(annotation)
            )
        }
        return result
    }

    private fun classBodyContainsClientOnlyApis(body: String): Boolean {
        val code = maskJavaCommentsAndLiterals(body)
        return code.contains("net.minecraft.client.") ||
            code.contains("net.neoforged.neoforge.client.event.") ||
            Regex("""\bFMLClientSetupEvent\b""").containsMatchIn(code) ||
            Regex("""\b(?:EntityRenderersEvent|ModelEvent|RegisterColorHandlersEvent|RegisterParticleProvidersEvent|RegisterKeyMappingsEvent|RegisterShadersEvent)\b""")
                .containsMatchIn(code)
    }

    private fun addDistClientValueToEventBusSubscriberAnnotation(annotation: String): String {
        val openParen = annotation.indexOf('(')
        val closeParen = annotation.lastIndexOf(')')
        if (openParen < 0 || closeParen <= openParen) return annotation
        val arguments = splitTopLevelArgs(annotation.substring(openParen + 1, closeParen))
        val positionalIndex = arguments.indexOfFirst { argument ->
            !Regex("""^[A-Za-z_$][\w$]*\s*=""").containsMatchIn(argument)
        }
        if (positionalIndex >= 0) {
            val normalizedArguments = arguments.mapIndexed { index, argument ->
                if (index == positionalIndex) "value = $argument" else argument
            }
            return annotation.substring(0, openParen + 1) +
                normalizedArguments.joinToString(", ") +
                annotation.substring(closeParen)
        }
        val beforeClose = annotation.substring(0, closeParen).trimEnd()
        val separator = if (beforeClose.endsWith("(")) "" else ", "
        return if (!annotation.contains('\n')) {
            beforeClose + separator + "value = net.neoforged.api.distmarker.Dist.CLIENT" + annotation.substring(closeParen)
        } else {
            val closeLineIndent = annotation.substringBeforeLast(")")
                .substringAfterLast('\n', "")
                .takeWhile { it == ' ' || it == '\t' }
            val multilineSeparator = if (beforeClose.endsWith("(") || beforeClose.endsWith(",")) "" else ","
            beforeClose + multilineSeparator +
                System.lineSeparator() +
                closeLineIndent +
                "    value = net.neoforged.api.distmarker.Dist.CLIENT" +
                annotation.substring(closeParen)
        }
    }

    private fun migrateCreativeModeInventorySelectedTabReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val methodMatch = Regex(
                    """private\s+static\s+CreativeModeTab\s+getSelectedTab\s*\(\s*\)\s*\{"""
                ).find(original) ?: return@forEach
                val openBrace = original.indexOf('{', methodMatch.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(original, openBrace) else -1
                if (closeBrace <= openBrace) return@forEach
                val methodSource = original.substring(methodMatch.range.first, closeBrace + 1)
                if (!containsCreativeSelectedTabReflection(methodSource)) return@forEach

                val replacement = """
private static CreativeModeTab getSelectedTab() {
    CreativeModeTab selectedTab = CreativeModeInventoryScreen.selectedTab;
    return selectedTab != null ? selectedTab : CreativeModeTabs.getDefaultTab();
  }
""".trimIndent()
                var modified = original.substring(0, methodMatch.range.first) +
                    replacement +
                    original.substring(closeBrace + 1)
                if (!Regex("""\bjava\.lang\.reflect\.""").containsMatchIn(modified)) {
                    modified = removeJavaImport(modified, "java.lang.reflect.Field")
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "CreativeModeInventoryScreen selectedTab: reflection -> access-transformer-backed direct field",
                        before = "CreativeModeInventoryScreen.class.getDeclaredField(\"selectedTab\")",
                        after = "CreativeModeInventoryScreen.selectedTab plus access transformer entry",
                        confidence = Confidence.HIGH,
                        ruleId = "build-creative-selectedtab-at"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir,
                        listOf("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab"),
                        dryRun,
                        "build-creative-selectedtab-at-entry",
                        "Expose CreativeModeInventoryScreen selectedTab through Access Transformers instead of reflection"
                    ))
                }
            }
        return changes
    }

    private fun containsCreativeSelectedTabReflection(methodSource: String): Boolean {
        val code = maskJavaComments(methodSource)
        val fieldMatch = Regex(
            """\b(?:java\.lang\.reflect\.)?Field\s+([A-Za-z_$][\w$]*)\s*=\s*(?:net\.minecraft\.client\.gui\.screens\.inventory\.)?CreativeModeInventoryScreen\.class\.getDeclaredField\s*\(\s*"selectedTab"\s*\)"""
        ).find(code) ?: return false
        val fieldName = fieldMatch.groupValues[1]
        return Regex("""\b${Regex.escape(fieldName)}\s*\.\s*setAccessible\s*\(\s*true\s*\)""").containsMatchIn(code) &&
            Regex("""\b${Regex.escape(fieldName)}\s*\.\s*get\s*\(\s*null\s*\)""").containsMatchIn(code)
    }

    private fun migrateEntityRenderersAddLayersReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val methodMatch = Regex(
                    """public\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*EntityRenderersEvent\.AddLayers\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
                ).find(original) ?: return@forEach
                val methodName = methodMatch.groupValues[1]
                val eventParam = methodMatch.groupValues[2]
                val openBrace = original.indexOf('{', methodMatch.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(original, openBrace) else -1
                if (closeBrace <= openBrace) return@forEach

                val methodSource = original.substring(methodMatch.range.first, closeBrace + 1)
                if (!containsEntityRenderersAddLayersReflection(methodSource, eventParam)) return@forEach

                val body = original.substring(openBrace + 1, closeBrace)
                val skinBlockStart = body.indexOf("$eventParam.getSkins().forEach")
                    .takeIf { it >= 0 }
                    ?: return@forEach
                val skinBlockEnd = findJavaStatementEnd(body, skinBlockStart)
                    .takeIf { it > skinBlockStart }
                    ?: return@forEach
                val skinBlock = body.substring(skinBlockStart, skinBlockEnd + 1).trim()
                val rendererStream = Regex(
                    """(?s)\(\(Map<[^;]+>\)\s*[A-Za-z_$][\w$]*\.get\(\s*${Regex.escape(eventParam)}\s*\)\)\.values\(\)\.stream\(\)\s*\.(.*?)\s*;"""
                ).find(body) ?: return@forEach
                val streamTail = rendererStream.groupValues[1].trim()
                val replacementMethod = """
public static void $methodName(EntityRenderersEvent.AddLayers $eventParam) {
        $skinBlock
        $eventParam.getEntityTypes().stream().map($eventParam::getRenderer).
                $streamTail;
    }
""".trimIndent()

                var modified = original.substring(0, methodMatch.range.first) +
                    replacementMethod +
                    original.substring(closeBrace + 1)
                modified = removeJavaImport(modified, "java.lang.reflect.Field")
                modified = Regex("""(?m)^[ \t]*private\s+static\s+Field\s+[A-Za-z_$][\w$]*\s*;\s*\r?\n""")
                    .replace(modified, "")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "EntityRenderersEvent.AddLayers renderers: reflection -> public renderer lookup API",
                        before = "EntityRenderersEvent.AddLayers.class.getDeclaredField(\"renderers\")",
                        after = "event.getEntityTypes().stream().map(event::getRenderer)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-entityrenderers-addlayers-api"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun containsEntityRenderersAddLayersReflection(methodSource: String, eventParam: String): Boolean {
        val code = maskJavaComments(methodSource)
        val fieldAssignments = Regex(
            """\b([A-Za-z_$][\w$]*)\s*=\s*(?:net\.neoforged\.neoforge\.client\.event\.)?EntityRenderersEvent\.AddLayers\.class\.getDeclaredField\s*\(\s*"renderers"\s*\)"""
        ).findAll(code)
        return fieldAssignments.any { match ->
            val fieldName = match.groupValues[1]
            Regex("""\b${Regex.escape(fieldName)}\s*\.\s*setAccessible\s*\(\s*true\s*\)""").containsMatchIn(code) &&
                Regex("""\b${Regex.escape(fieldName)}\s*\.\s*get\s*\(\s*${Regex.escape(eventParam)}\s*\)""").containsMatchIn(code)
        }
    }

    private fun migrateObfuscationReflectionMethodHandles(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val bindings = collectObfuscationMethodHandleBindings(original)
                if (bindings.isEmpty()) return@forEach

                var modified = original
                var touched = false
                val invokerImports = linkedSetOf<String>()
                bindings.firstOrNull {
                    it.targetClassSimple == "LivingEntity" &&
                        it.obfuscatedMethodName == "m_5592_" &&
                        it.parameterTypeSimples.isEmpty()
                }?.let { binding ->
                    val replaced = replaceLivingEntityDeathSoundMethodHandle(modified, binding.handleFieldName)
                    if (replaced == modified) return@let
                    modified = replaced
                    val packageName = javaPackageName(original) ?: return@forEach
                    invokerImports.add("${generatedMixinPackage(packageName)}.ModPorterLivingEntityInvoker")
                    changes.addAll(ensureMixinInvoker(
                        projectDir = projectDir,
                        sourceFile = javaFile,
                        packageName = packageName,
                        invokerName = "ModPorterLivingEntityInvoker",
                        targetClassName = "LivingEntity",
                        targetImport = "net.minecraft.world.entity.LivingEntity",
                        extraImports = listOf("net.minecraft.sounds.SoundEvent"),
                        methodSource = """
                            @Invoker("getDeathSound")
                            SoundEvent modporter${'$'}getDeathSound();
                        """.trimIndent(),
                        dryRun = dryRun
                    ))
                    touched = true
                }
                bindings.firstOrNull {
                    it.targetClassSimple == "HangingEntity" &&
                        it.obfuscatedMethodName == "m_6022_" &&
                        it.parameterTypeSimples == listOf("Direction")
                }?.let { binding ->
                    val replaced = replaceHangingEntitySetDirectionMethodHandle(modified, binding.handleFieldName)
                    if (replaced == modified) return@let
                    modified = replaced
                    val packageName = javaPackageName(original) ?: return@forEach
                    invokerImports.add("${generatedMixinPackage(packageName)}.ModPorterHangingEntityInvoker")
                    changes.addAll(ensureMixinInvoker(
                        projectDir = projectDir,
                        sourceFile = javaFile,
                        packageName = packageName,
                        invokerName = "ModPorterHangingEntityInvoker",
                        targetClassName = "HangingEntity",
                        targetImport = "net.minecraft.world.entity.decoration.HangingEntity",
                        extraImports = listOf("net.minecraft.core.Direction"),
                        methodSource = """
                            @Invoker("setDirection")
                            void modporter${'$'}setDirection(Direction direction);
                        """.trimIndent(),
                        dryRun = dryRun
                    ))
                    touched = true
                }
                if (!touched) return@forEach

                modified = removeObfuscationMethodHandleScaffolding(modified)
                modified = removeJavaImport(modified, "net.neoforged.fml.util.ObfuscationReflectionHelper")
                modified = removeJavaImport(modified, "net.minecraftforge.fml.util.ObfuscationReflectionHelper")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodHandle")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodHandles")
                modified = removeJavaImport(modified, "java.lang.reflect.Method")
                invokerImports.forEach { importName ->
                    modified = addJavaImportIfMissing(modified, importName)
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "ObfuscationReflectionHelper method handles -> mixin invoker method calls",
                        before = "ObfuscationReflectionHelper.findMethod(...) + MethodHandle.invoke",
                        after = "generated @Invoker interfaces and direct invoker calls",
                        confidence = Confidence.HIGH,
                        ruleId = "build-obfuscation-methodhandle-mixin-invoker"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateLivingBreatheRefillMethodHandles(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("LivingBreatheEvent.class") ||
                    !original.contains("\"setCanRefillAir\"") ||
                    !executableOriginal.contains("MethodHandle")) {
                    return@forEach
                }

                val handleField = Regex(
                    """(?m)^[ \t]*(?:(?://[^\r\n]*)\r?\n[ \t]*)*(?:@Nullable\s*)?private\s+static\s+final\s+MethodHandle\s+([A-Za-z_$][\w$]*)\s*;[ \t]*(?:\r?\n)?"""
                ).find(executableOriginal)?.groupValues?.get(1) ?: return@forEach
                val invocation = Regex(
                    """try\s*\{\s*if\s*\(\s*${Regex.escape(handleField)}\s*!=\s*null\s*\)\s*${Regex.escape(handleField)}\.invokeExact\(\s*([A-Za-z_$][\w$]*)\s*,\s*(true|false)\s*\)\s*;\s*}\s*catch\s*\(\s*Throwable\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*}""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(executableOriginal) ?: return@forEach
                val eventName = invocation.groupValues[1]
                val refillExpression = if (invocation.groupValues[2] == "true") {
                    "$eventName.getEntity().getMaxAirSupply()"
                } else {
                    "0"
                }

                val staticBlocks = Regex("""\bstatic\s*\{""").findAll(executableOriginal).toList()
                val bindingBlock = staticBlocks.firstNotNullOfOrNull { staticMatch ->
                    val openBrace = executableOriginal.indexOf('{', staticMatch.range.first)
                    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableOriginal, openBrace) else -1
                    if (closeBrace <= openBrace) return@firstNotNullOfOrNull null
                    val block = executableOriginal.substring(staticMatch.range.first, closeBrace + 1)
                    if (block.contains("LivingBreatheEvent.class") &&
                        original.substring(staticMatch.range.first, closeBrace + 1).contains("\"setCanRefillAir\"") &&
                        Regex("""\b${Regex.escape(handleField)}\s*=""").containsMatchIn(block)) {
                        val currentLineStart = original.lastIndexOf('\n', (staticMatch.range.first - 1).coerceAtLeast(0)) + 1
                        var start = currentLineStart
                        var scanEnd = (currentLineStart - 1).coerceAtLeast(0)
                        while (scanEnd > 0) {
                            val lineStart = original.lastIndexOf('\n', (scanEnd - 1).coerceAtLeast(0)) + 1
                            val line = original.substring(lineStart, scanEnd).trim()
                            if (!line.startsWith("//")) break
                            start = lineStart
                            scanEnd = (lineStart - 1).coerceAtLeast(0)
                        }
                        start..closeBrace
                    } else {
                        null
                    }
                } ?: return@forEach

                var modified = original.replaceRange(
                    invocation.range,
                    "$eventName.setRefillAirAmount($refillExpression);"
                )
                modified = modified.replaceRange(bindingBlock, "")
                modified = Regex(
                    """(?m)^[ \t]*(?:(?://[^\r\n]*)\r?\n[ \t]*)*(?:@Nullable\s*)?private\s+static\s+final\s+MethodHandle\s+${Regex.escape(handleField)}\s*;[ \t]*(?:\r?\n)?"""
                ).replace(modified, "")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodHandle")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodHandles")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodType")
                modified = removeJavaImportIfSimpleNameUnused(modified, "org.jetbrains.annotations.Nullable")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Replace LivingBreatheEvent setCanRefillAir MethodHandle compatibility with the 1.21 refill amount API",
                        before = "MethodHandle LivingBreatheEvent.setCanRefillAir(boolean)",
                        after = "event.setRefillAirAmount(event.getEntity().getMaxAirSupply())",
                        confidence = Confidence.HIGH,
                        ruleId = "build-living-breathe-refill-direct-api"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private data class ReflectionFieldMapping(
        val sourceOwner: String,
        val sourceNames: Set<String>,
        val targetOwner: String,
        val targetName: String,
        val targetType: String?
    )

    private data class VarHandleBinding(
        val handleName: String,
        val mapping: ReflectionFieldMapping,
        val bindingRange: IntRange
    )

    private data class DirectFieldRewrite(
        val content: String,
        val changed: Boolean,
        val writesField: Boolean,
        val requiresAccessTransformer: Boolean = true
    )

    private fun loadReflectionFieldMappings(): List<ReflectionFieldMapping> {
        val resourcePath = "$mappingsPrefix/reflection-fields.json"
        val text = BuildSystemPass::class.java.getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing reflection field mapping resource: $resourcePath")
        val fields = Json.parseToJsonElement(text).jsonObject.getValue("fields").jsonArray
        return fields.map { element ->
            val field = element.jsonObject
            ReflectionFieldMapping(
                sourceOwner = field.getValue("sourceOwner").jsonPrimitive.content,
                sourceNames = field.getValue("sourceNames").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                targetOwner = field.getValue("targetOwner").jsonPrimitive.content,
                targetName = field.getValue("targetName").jsonPrimitive.content,
                targetType = field["targetType"]?.jsonPrimitive?.content
            )
        }
    }

    private fun migrateVarHandleFieldAccesses(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val codeWithStrings = maskJavaCommentsAndTextBlocks(original)
                if (!codeWithStrings.contains("VarHandle") || !codeWithStrings.contains("findVarHandle(")) {
                    return@forEach
                }

                val bindings = mutableListOf<VarHandleBinding>()
                val declarationPattern = Regex(
                    """(?m)^[ \t]*(?:public|protected|private)?\s*static\s+final\s+VarHandle\s+([A-Za-z_$][\w$]*)\s*;"""
                )
                for (declaration in declarationPattern.findAll(codeWithStrings)) {
                    val handleName = declaration.groupValues[1]
                    val bindingPattern = Regex(
                        """\b${Regex.escape(handleName)}\s*=\s*[A-Za-z_$][\w$]*\.findVarHandle\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class\s*,\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.class\s*\)\s*;"""
                    )
                    val binding = bindingPattern.find(codeWithStrings) ?: continue
                    val owner = resolveImportedJavaType(binding.groupValues[1], original) ?: continue
                    val mapping = resolveReflectionFieldMapping(owner, binding.groupValues[2]) ?: continue
                    bindings += VarHandleBinding(handleName, mapping, binding.range)
                }
                if (bindings.isEmpty()) return@forEach

                var modified = original
                val migratedBindings = mutableListOf<VarHandleBinding>()
                val atEntries = linkedSetOf<String>()
                for (binding in bindings) {
                    val rewrite = if (
                        binding.mapping.targetOwner == "net.neoforged.neoforge.client.model.generators.ModelBuilder" &&
                        binding.mapping.targetName == "textures"
                    ) {
                        rewriteModelBuilderTextureVarHandleCalls(modified, binding)
                    } else {
                        rewriteVarHandleCalls(modified, binding)
                    }
                    if (!rewrite.changed) continue
                    modified = rewrite.content
                    migratedBindings += binding
                    if (rewrite.requiresAccessTransformer) {
                        val access = if (rewrite.writesField) "public-f" else "public"
                        atEntries += "$access ${binding.mapping.targetOwner} ${binding.mapping.targetName}"
                    }
                }
                if (migratedBindings.isEmpty()) return@forEach

                for (binding in migratedBindings) {
                    modified = Regex(
                        """(?m)^[ \t]*(?:public|protected|private)?\s*static\s+final\s+VarHandle\s+${Regex.escape(binding.handleName)}\s*;[ \t]*(?:\r?\n)?"""
                    ).replace(modified, "")
                }
                modified = removePureVarHandleBindingBlocks(modified, migratedBindings.map { it.handleName }.toSet())
                modified = removeJavaImportIfSimpleNameUnused(modified, "java.lang.invoke.VarHandle")
                modified = removeJavaImportIfSimpleNameUnused(modified, "java.lang.invoke.MethodHandles")
                modified = removeJavaImportIfSimpleNameUnused(modified, "java.util.Map")

                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Replace source-declared VarHandle field access with access-transformer-backed direct access",
                    before = migratedBindings.joinToString { "${it.handleName}.get/set" },
                    after = migratedBindings.joinToString { "receiver.${it.mapping.targetName}" },
                    confidence = Confidence.HIGH,
                    ruleId = "build-varhandle-at-direct-field"
                ))
                if (!dryRun) javaFile.writeText(modified)
                if (atEntries.isNotEmpty()) {
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir = projectDir,
                        requiredEntries = atEntries.toList(),
                        dryRun = dryRun,
                        ruleId = "build-varhandle-at-entries",
                        description = "Expose fields that were accessed through source-declared VarHandles"
                    ))
                }
            }
        return changes
    }

    private fun rewriteModelBuilderTextureVarHandleCalls(
        source: String,
        binding: VarHandleBinding
    ): DirectFieldRewrite {
        val executable = maskJavaCommentsAndLiterals(source)
        val declaration = Regex(
            """(?m)^[ \t]*Map\s*<\s*String\s*,\s*String\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*\(\s*Map\s*<\s*String\s*,\s*String\s*>\s*\)\s*${Regex.escape(binding.handleName)}\.get\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;[ \t]*(?:\r?\n)?"""
        ).find(executable) ?: return DirectFieldRewrite(source, false, false)
        val textureMap = declaration.groupValues[1]
        val modelBuilder = declaration.groupValues[2]
        val replacements = mutableListOf<Pair<IntRange, String>>()
        var searchFrom = 0
        val token = "$textureMap.put"
        while (true) {
            val callStart = executable.indexOf(token, searchFrom)
            if (callStart < 0) break
            val openParen = executable.indexOf('(', callStart + token.length)
            val closeParen = if (openParen >= 0) findMatchingParen(executable, openParen) else -1
            if (closeParen <= openParen) return DirectFieldRewrite(source, false, false)
            val arguments = splitTopLevelArgs(source.substring(openParen + 1, closeParen))
            if (arguments.size != 2) return DirectFieldRewrite(source, false, false)
            val resourceVariable = Regex("""^([A-Za-z_$][\w$]*)\.toString\(\)$""")
                .matchEntire(arguments[1].trim())
                ?.groupValues
                ?.get(1)
                ?: return DirectFieldRewrite(source, false, false)
            if (!Regex("""\b(?:net\.minecraft\.resources\.)?ResourceLocation\s+${Regex.escape(resourceVariable)}\b""")
                    .containsMatchIn(executable)) {
                return DirectFieldRewrite(source, false, false)
            }
            replacements += (callStart..closeParen) to
                "$modelBuilder.texture(${arguments[0].trim()}, $resourceVariable)"
            searchFrom = closeParen + 1
        }
        if (replacements.isEmpty()) return DirectFieldRewrite(source, false, false)
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range, replacement)
        }
        result = result.replaceRange(declaration.range, "")
        if (Regex("""\b${Regex.escape(textureMap)}\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(result))) {
            return DirectFieldRewrite(source, false, false)
        }
        return DirectFieldRewrite(
            content = result,
            changed = true,
            writesField = false,
            requiresAccessTransformer = false
        )
    }

    private fun rewriteVarHandleCalls(source: String, binding: VarHandleBinding): DirectFieldRewrite {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        var writesField = false
        for (operation in listOf("get", "set")) {
            val token = "${binding.handleName}.$operation"
            var searchFrom = 0
            while (true) {
                val callStart = executableCode.indexOf(token, searchFrom)
                if (callStart < 0) break
                val openParen = executableCode.indexOf('(', callStart + token.length)
                if (openParen < 0) break
                val closeParen = findMatchingParen(executableCode, openParen)
                if (closeParen < 0) break
                val args = splitTopLevelArgs(source.substring(openParen + 1, closeParen))
                val replacement = when {
                    operation == "get" && args.size == 1 -> "(${args[0]}).${binding.mapping.targetName}"
                    operation == "set" && args.size == 2 -> {
                        writesField = true
                        "(${args[0]}).${binding.mapping.targetName} = ${args[1]}"
                    }
                    else -> null
                }
                if (replacement != null) replacements += (callStart..closeParen) to replacement
                searchFrom = closeParen + 1
            }
        }
        if (replacements.isEmpty()) return DirectFieldRewrite(source, changed = false, writesField = false)
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range, replacement)
        }
        return DirectFieldRewrite(result, changed = true, writesField = writesField)
    }

    private fun removePureVarHandleBindingBlocks(source: String, migratedHandles: Set<String>): String {
        val codeWithStrings = maskJavaCommentsAndTextBlocks(source)
        val removals = mutableListOf<IntRange>()
        for (staticMatch in Regex("""\bstatic\s*\{""").findAll(codeWithStrings)) {
            val openBrace = codeWithStrings.indexOf('{', staticMatch.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(codeWithStrings, openBrace) else -1
            if (closeBrace <= openBrace) continue
            val block = source.substring(staticMatch.range.first, closeBrace + 1)
            if (!block.contains("findVarHandle(")) continue
            val referencedHandles = migratedHandles.filter { handle ->
                Regex("""\b${Regex.escape(handle)}\s*=""").containsMatchIn(block)
            }
            if (referencedHandles.isEmpty()) continue

            var residue = maskJavaCommentsAndTextBlocks(block)
            residue = Regex("""\bstatic\s*\{""").replaceFirst(residue, "")
            residue = Regex("""\btry\s*\{""").replace(residue, "")
            residue = Regex("""}\s*catch\s*\([^)]*\)\s*\{""").replace(residue, "")
            residue = Regex("""MethodHandles\.Lookup\s+[A-Za-z_$][\w$]*\s*=\s*MethodHandles\.privateLookupIn\([^;]+;""").replace(residue, "")
            residue = Regex("""\b(?:${referencedHandles.joinToString("|") { Regex.escape(it) }})\s*=\s*[A-Za-z_$][\w$]*\.findVarHandle\([^;]+;""").replace(residue, "")
            residue = Regex("""throw\s+new\s+RuntimeException\([^;]+;""").replace(residue, "")
            residue = residue.replace("}", "").trim()
            if (residue.isEmpty()) removals += staticMatch.range.first..closeBrace
        }
        var result = source
        removals.asReversed().forEach { result = result.replaceRange(it, "") }
        return result
    }

    private fun resolveImportedJavaType(reference: String, source: String): String? {
        if ('.' in reference) return reference
        return Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$.]*\.${Regex.escape(reference)})\s*;""")
            .find(maskJavaCommentsAndLiterals(source))
            ?.groupValues
            ?.get(1)
    }

    private fun resolveReflectionFieldMapping(owner: String, sourceFieldName: String): ReflectionFieldMapping? {
        reflectionFieldMappings.firstOrNull { mapping ->
            mapping.sourceOwner == owner && sourceFieldName in mapping.sourceNames
        }?.let { return it }
        if (owner.startsWith("net.minecraft.") ||
            owner.startsWith("net.neoforged.") ||
            owner.startsWith("net.minecraftforge.")) {
            return null
        }
        return ReflectionFieldMapping(owner, setOf(sourceFieldName), owner, sourceFieldName, null)
    }

    private data class ReflectionFieldRewrite(
        val content: String,
        val entries: Set<String>,
        val migratedCalls: Int
    )

    private fun migrateObfuscationReflectionFieldAccesses(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("ObfuscationReflectionHelper.getPrivateValue") &&
                    !executableOriginal.contains("ObfuscationReflectionHelper.setPrivateValue")) {
                    return@forEach
                }

                val rewrite = rewriteObfuscationReflectionFieldCalls(original)
                if (rewrite.migratedCalls == 0) return@forEach
                var modified = rewrite.content
                if (!maskJavaCommentsAndLiterals(modified).contains("ObfuscationReflectionHelper.")) {
                    modified = removeJavaImport(modified, "net.neoforged.fml.util.ObfuscationReflectionHelper")
                    modified = removeJavaImport(modified, "net.minecraftforge.fml.util.ObfuscationReflectionHelper")
                }
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Replace typed ObfuscationReflectionHelper field access with access-transformer-backed direct access",
                    before = "${rewrite.migratedCalls} reflection field call(s)",
                    after = "direct owner field access",
                    confidence = Confidence.HIGH,
                    ruleId = "build-obfuscation-reflection-at-direct-field"
                ))
                if (!dryRun) javaFile.writeText(modified)
                changes.addAll(ensureAccessTransformerEntries(
                    projectDir = projectDir,
                    requiredEntries = rewrite.entries.toList(),
                    dryRun = dryRun,
                    ruleId = "build-obfuscation-reflection-at-entries",
                    description = "Expose fields previously accessed through ObfuscationReflectionHelper"
                ))
            }
        return changes
    }

    private fun rewriteObfuscationReflectionFieldCalls(source: String): ReflectionFieldRewrite {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val atEntries = linkedSetOf<String>()

        for (operation in listOf("getPrivateValue", "setPrivateValue")) {
            val token = "ObfuscationReflectionHelper.$operation"
            var searchFrom = 0
            while (true) {
                val callStart = executableCode.indexOf(token, searchFrom)
                if (callStart < 0) break
                val openParen = executableCode.indexOf('(', callStart + token.length)
                if (openParen < 0) break
                val closeParen = findMatchingParen(executableCode, openParen)
                if (closeParen < 0) break
                val args = splitTopLevelArgs(source.substring(openParen + 1, closeParen))
                val requiredSize = if (operation == "getPrivateValue") 3 else 4
                if (args.size != requiredSize) {
                    searchFrom = closeParen + 1
                    continue
                }
                val ownerReference = Regex(
                    """^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class$"""
                ).matchEntire(args[0].trim())?.groupValues?.get(1)
                val fieldName = Regex("""^"([A-Za-z_$][\w$]*)"$""")
                    .matchEntire(args.last().trim())?.groupValues?.get(1)
                if (ownerReference == null || fieldName == null) {
                    searchFrom = closeParen + 1
                    continue
                }
                val owner = resolveImportedJavaType(ownerReference, source)
                val mapping = owner?.let { resolveReflectionFieldMapping(it, fieldName) }
                if (mapping == null) {
                    searchFrom = closeParen + 1
                    continue
                }

                val receiver = args[1].trim()
                val directField = "($receiver).${mapping.targetName}"
                var replacementRange = callStart..closeParen
                val replacement = if (operation == "getPrivateValue") {
                    val primitiveTail = mapping.targetType?.let { primitiveType ->
                        boxedPrimitiveType(primitiveType)?.let { boxedType ->
                            val tail = source.substring(closeParen + 1)
                            val tailPattern = Regex(
                                """^\s+instanceof\s+${Regex.escape(boxedType)}\s+([A-Za-z_$][\w$]*)\s+&&\s+\1\s*>\s*0"""
                            )
                            tailPattern.find(tail)?.takeIf { it.range.first == 0 }
                        }
                    }
                    if (primitiveTail != null) {
                        replacementRange = callStart..(closeParen + primitiveTail.range.last + 1)
                        "$directField > 0"
                    } else {
                        directField
                    }
                } else {
                    "$directField = ${args[2].trim()}"
                }
                replacements += replacementRange to replacement
                val access = if (operation == "setPrivateValue") "public-f" else "public"
                atEntries += "$access ${mapping.targetOwner} ${mapping.targetName}"
                searchFrom = closeParen + 1
            }
        }

        if (replacements.isEmpty()) return ReflectionFieldRewrite(source, emptySet(), 0)
        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range, replacement)
        }
        return ReflectionFieldRewrite(result, atEntries, replacements.size)
    }

    private fun boxedPrimitiveType(type: String): String? = when (type) {
        "boolean" -> "Boolean"
        "byte" -> "Byte"
        "short" -> "Short"
        "int" -> "Integer"
        "long" -> "Long"
        "float" -> "Float"
        "double" -> "Double"
        "char" -> "Character"
        else -> null
    }

    private fun migrateRemovedStairStateSupplierReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val codeWithStrings = maskJavaCommentsAndTextBlocks(original)
                if (!codeWithStrings.contains("ObfuscationReflectionHelper.setPrivateValue") ||
                    !codeWithStrings.contains("StairBlock.class") ||
                    !original.contains("\"stateSupplier\"")) {
                    return@forEach
                }

                val rewrite = rewriteRemovedStairStateSupplierReflection(original)
                if (rewrite.second == 0) return@forEach
                var modified = rewrite.first
                if (!maskJavaCommentsAndLiterals(modified).contains("ObfuscationReflectionHelper.")) {
                    modified = removeJavaImport(modified, "net.neoforged.fml.util.ObfuscationReflectionHelper")
                    modified = removeJavaImport(modified, "net.minecraftforge.fml.util.ObfuscationReflectionHelper")
                }
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Move removed StairBlock stateSupplier reflection semantics into 1.21 BlockState constructors",
                    before = "construct stair with an intermediate state, then reflectively replace stateSupplier",
                    after = "construct stair directly with the source-declared Supplier<BlockState>.get() value",
                    confidence = Confidence.HIGH,
                    ruleId = "build-stair-state-supplier-constructor"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        return changes
    }

    private fun rewriteRemovedStairStateSupplierReflection(source: String): Pair<String, Int> {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val supplierNames = Regex(
            """\b(?:java\.util\.function\.)?Supplier\s*<\s*BlockState\s*>\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(executableCode).map { it.groupValues[1] }.toSet()
        if (supplierNames.isEmpty()) return source to 0

        val replacements = mutableListOf<Pair<IntRange, String>>()
        var migratedReflectionCalls = 0
        val token = "ObfuscationReflectionHelper.setPrivateValue"
        var searchFrom = 0
        while (true) {
            val callStart = executableCode.indexOf(token, searchFrom)
            if (callStart < 0) break
            val openParen = executableCode.indexOf('(', callStart + token.length)
            if (openParen < 0) break
            val closeParen = findMatchingParen(executableCode, openParen)
            if (closeParen < 0) break
            val args = splitTopLevelArgs(source.substring(openParen + 1, closeParen))
            if (args.size != 4 || args[0].trim() != "StairBlock.class" ||
                args[3].trim() != "\"stateSupplier\"") {
                searchFrom = closeParen + 1
                continue
            }
            val receiver = Regex("""^[A-Za-z_$][\w$]*$""").matchEntire(args[1].trim())?.value
            val supplier = Regex("""^[A-Za-z_$][\w$]*$""").matchEntire(args[2].trim())?.value
            if (receiver == null || supplier == null || supplier !in supplierNames) {
                searchFrom = closeParen + 1
                continue
            }

            val declarationPattern = Regex(
                """\b[A-Za-z_$][\w$]*(?:\s*<[^;=]+>)?\s+${Regex.escape(receiver)}\s*=\s*new\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\("""
            )
            val declaration = declarationPattern.findAll(executableCode.substring(0, callStart)).lastOrNull()
            if (declaration == null) {
                searchFrom = closeParen + 1
                continue
            }
            val constructorOpen = executableCode.indexOf('(', declaration.range.last)
            val constructorClose = if (constructorOpen >= 0) findMatchingParen(executableCode, constructorOpen) else -1
            if (constructorClose <= constructorOpen || constructorClose >= callStart) {
                searchFrom = closeParen + 1
                continue
            }
            val constructorArgs = splitTopLevelArgs(source.substring(constructorOpen + 1, constructorClose))
            val stateIndexes = constructorArgs.indices.filter { index ->
                Regex("""(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*\.defaultBlockState\(\)""")
                    .matches(constructorArgs[index].trim())
            }
            if (stateIndexes.size != 1) {
                searchFrom = closeParen + 1
                continue
            }
            val migratedArgs = constructorArgs.toMutableList()
            migratedArgs[stateIndexes.single()] = "$supplier.get()"
            val constructorName = declaration.groupValues[1]
            replacements += (executableCode.indexOf("new", declaration.range.first)..constructorClose) to
                "new $constructorName(${migratedArgs.joinToString(", ")})"

            var statementEnd = closeParen + 1
            while (statementEnd < source.length && source[statementEnd].isWhitespace() && source[statementEnd] != '\n') {
                statementEnd++
            }
            if (source.getOrNull(statementEnd) == ';') statementEnd++
            if (source.getOrNull(statementEnd) == '\r') statementEnd++
            if (source.getOrNull(statementEnd) == '\n') statementEnd++
            val lineStart = source.lastIndexOf('\n', callStart).let { if (it < 0) 0 else it + 1 }
            val removalStart = if (source.substring(lineStart, callStart).isBlank()) lineStart else callStart
            replacements += (removalStart until statementEnd) to ""
            migratedReflectionCalls++
            searchFrom = closeParen + 1
        }
        if (migratedReflectionCalls == 0) return source to 0

        var result = source
        replacements.sortedByDescending { it.first.first }.forEach { (range, replacement) ->
            result = result.replaceRange(range, replacement)
        }
        result = rewriteStairBlockSupplierConstructorCalls(result, supplierNames)
        return result to migratedReflectionCalls
    }

    private fun rewriteStairBlockSupplierConstructorCalls(source: String, supplierNames: Set<String>): String {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        val token = "new StairBlock"
        var searchFrom = 0
        while (true) {
            val callStart = executableCode.indexOf(token, searchFrom)
            if (callStart < 0) break
            val openParen = executableCode.indexOf('(', callStart + token.length)
            if (openParen < 0) break
            val closeParen = findMatchingParen(executableCode, openParen)
            if (closeParen < 0) break
            val args = splitTopLevelArgs(source.substring(openParen + 1, closeParen))
            val supplier = args.firstOrNull()?.trim()
            if (args.size == 2 && supplier in supplierNames) {
                replacements += (callStart..closeParen) to
                    "new StairBlock($supplier.get(), ${args[1].trim()})"
            }
            searchFrom = closeParen + 1
        }
        var result = source
        replacements.asReversed().forEach { (range, replacement) -> result = result.replaceRange(range, replacement) }
        return result
    }

    private data class ObfuscationMethodHandleBinding(
        val targetClassSimple: String,
        val obfuscatedMethodName: String,
        val parameterTypeSimples: List<String>,
        val methodFieldName: String,
        val handleFieldName: String
    )

    private fun collectObfuscationMethodHandleBindings(source: String): List<ObfuscationMethodHandleBinding> {
        val code = maskJavaComments(source)
        val declaredHandleFields = Regex(
            """(?m)\b(?:private|protected|public)?\s*static\s+final\s+MethodHandle\s+([A-Za-z_$][\w$]*)\s*;"""
        ).findAll(code).map { it.groupValues[1] }.toSet()
        if (declaredHandleFields.isEmpty()) return emptyList()

        val methodFields = Regex(
            """(?m)\b(?:private|protected|public)?\s*static\s+final\s+Method\s+([A-Za-z_$][\w$]*)\s*=\s*(?:ObfuscationReflectionHelper|net\.neoforged\.fml\.util\.ObfuscationReflectionHelper|net\.minecraftforge\.fml\.util\.ObfuscationReflectionHelper)\.findMethod\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class\s*,\s*"([^"]+)"([^;]*)\)\s*;"""
        ).findAll(code)

        return methodFields.flatMap { methodMatch ->
            val methodFieldName = methodMatch.groupValues[1]
            val targetClassSimple = methodMatch.groupValues[2].substringAfterLast('.')
            val obfuscatedMethodName = methodMatch.groupValues[3]
            val parameterTypeSimples = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class""")
                .findAll(methodMatch.groupValues[4])
                .map { it.groupValues[1].substringAfterLast('.') }
                .toList()
            methodHandleFieldsForUnreflectedMethod(code, methodFieldName, declaredHandleFields)
                .map { handleFieldName ->
                    ObfuscationMethodHandleBinding(
                        targetClassSimple = targetClassSimple,
                        obfuscatedMethodName = obfuscatedMethodName,
                        parameterTypeSimples = parameterTypeSimples,
                        methodFieldName = methodFieldName,
                        handleFieldName = handleFieldName
                    )
                }
        }.toList()
    }

    private fun methodHandleFieldsForUnreflectedMethod(
        code: String,
        methodFieldName: String,
        declaredHandleFields: Set<String>
    ): Set<String> {
        val unreflectedVariables = Regex(
            """\b([A-Za-z_$][\w$]*)\s*=\s*(?:[A-Za-z_$][\w$]*|MethodHandles\.lookup\(\))\.unreflect\s*\(\s*${Regex.escape(methodFieldName)}\s*\)"""
        ).findAll(code).map { it.groupValues[1] }.toSet()

        return unreflectedVariables.flatMap { variable ->
            buildList {
                if (variable in declaredHandleFields) add(variable)
                Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(variable)}\s*;""")
                    .findAll(code)
                    .map { it.groupValues[1] }
                    .filter { it in declaredHandleFields }
                    .forEach { add(it) }
            }
        }.toSet()
    }

    private fun replaceLivingEntityDeathSoundMethodHandle(source: String, handleName: String): String {
        var searchStart = 0
        while (true) {
            val code = maskJavaComments(source)
            val methodMatch = Regex(
                """(?m)(?:^[ \t]*(?:@[^\r\n]+)\r?\n)*[ \t]*(?:public|protected|private)\s+static\s+(?:@Nullable\s+)?SoundEvent\s+[A-Za-z_$][\w$]*\s*\(\s*LivingEntity\s+([A-Za-z_$][\w$]*)\s*\)\s*(?:throws\s+[^{;]+)?\{"""
            ).find(code, searchStart) ?: return source
            val param = methodMatch.groupValues[1]
            val openBrace = code.indexOf('{', methodMatch.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
            if (closeBrace <= openBrace) return source
            val body = code.substring(openBrace + 1, closeBrace)
            val invokesBoundHandle = Regex(
                """\b${Regex.escape(handleName)}\s*\.\s*(?:invokeExact|invoke)\s*\(\s*${Regex.escape(param)}\s*\)"""
            ).containsMatchIn(body)
            if (!invokesBoundHandle) {
                searchStart = closeBrace + 1
                continue
            }
            val header = source.substring(methodMatch.range.first, openBrace + 1).trimEnd()
            val replacement = """
$header
        return ((ModPorterLivingEntityInvoker) $param).modporter${'$'}getDeathSound();
    }
""".trimIndent()
            return source.substring(0, methodMatch.range.first) +
                replacement +
                source.substring(closeBrace + 1)
        }
    }

    private fun replaceHangingEntitySetDirectionMethodHandle(source: String, handleName: String): String {
        var result = source
        var searchStart = 0
        while (true) {
            val code = maskJavaComments(result)
            val match = Regex(
                """(?s)try\s*\{\s*${Regex.escape(handleName)}\s*\.\s*(?:invokeExact|invoke)\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\);\s*\}\s*catch\s*\(\s*Throwable\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*[A-Za-z_$][\w$]*\.printStackTrace\(\);\s*\}"""
            ).find(code, searchStart) ?: return result
            val entityArg = match.groupValues[1]
            val directionArg = match.groupValues[2]
            val enclosingMethod = findJavaMethodSourceContaining(code, match.range.first)
            val hasTypedArguments = enclosingMethod != null &&
                javaMethodDeclaresAssignableType(
                    enclosingMethod,
                    entityArg,
                    setOf("HangingEntity", "Painting", "ItemFrame", "GlowItemFrame")
                ) &&
                javaMethodDeclaresAssignableType(enclosingMethod, directionArg, setOf("Direction"))
            if (!hasTypedArguments) {
                searchStart = match.range.last + 1
                continue
            }
            val replacement = "((ModPorterHangingEntityInvoker) $entityArg).modporter${'$'}setDirection($directionArg);"
            result = result.substring(0, match.range.first) +
                replacement +
                result.substring(match.range.last + 1)
            searchStart = match.range.first + replacement.length
        }
    }

    private fun findJavaMethodSourceContaining(source: String, offset: Int): String? {
        val methodPattern = Regex(
            """(?m)(?:^|[\r\n])([ \t]*(?:@[^\r\n]+\s*)*(?:(?:public|protected|private|static|final|synchronized|native|abstract|strictfp)\s+)*(?:<[^>{};]+>\s*)?(?:[A-Za-z_$][\w$]*(?:\s*<[^>{};]+>)?(?:\s*\[\s*])?(?:\s*,\s*)?|\?|extends|super|&|\.)+(?:\s+)+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\s*\{)"""
        )
        return methodPattern.findAll(source)
            .mapNotNull { match ->
                val openBrace = source.indexOf('{', match.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
                if (closeBrace > openBrace && offset in openBrace..closeBrace) {
                    source.substring(match.range.first, closeBrace + 1)
                } else {
                    null
                }
            }
            .lastOrNull()
    }

    private fun javaMethodHeaderDeclaresParameter(methodSource: String, typeSimpleName: String, variableName: String): Boolean {
        val header = methodSource.substringBefore("{")
        return Regex(
            """(?:^|[,(]\s*)(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(typeSimpleName)}\s+${Regex.escape(variableName)}\b"""
        ).containsMatchIn(header)
    }

    private fun javaMethodDeclaresAssignableType(
        methodSource: String,
        variableName: String,
        assignableSimpleTypes: Set<String>
    ): Boolean {
        return assignableSimpleTypes.any { typeSimpleName ->
            javaMethodHeaderDeclaresParameter(methodSource, typeSimpleName, variableName) ||
                Regex(
                    """(?:^|[;{}\r\n]\s*)(?:final\s+)?(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(typeSimpleName)}\s+${Regex.escape(variableName)}\b"""
                ).containsMatchIn(methodSource)
        }
    }

    private fun removeObfuscationMethodHandleScaffolding(source: String): String {
        var result = source
        result = Regex("""(?m)^[ \t]*private\s+static\s+final\s+MethodHandles\.Lookup\s+[A-Za-z_$][\w$]*\s*=.*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*private\s+static\s+final\s+Method\s+[A-Za-z_$][\w$]*\s*=.*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*private\s+static\s+final\s+MethodHandle\s+[A-Za-z_$][\w$]*\s*;\s*\r?\n""")
            .replace(result, "")
        while (true) {
            val staticMatch = Regex("""(?m)^[ \t]*static\s*\{""").find(result) ?: break
            val openBrace = result.indexOf('{', staticMatch.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) break
            val block = result.substring(staticMatch.range.first, closeBrace + 1)
            if (!block.contains("MethodHandle") || !block.contains("unreflect(")) break
            result = result.substring(0, staticMatch.range.first).trimEnd() +
                System.lineSeparator() +
                result.substring(closeBrace + 1).trimStart()
        }
        return result
    }

    private fun restoreNonItemStackGetTagCalls(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val replacement = ".getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains(replacement) ||
                    !executableOriginal.contains("TagKey<") ||
                    !executableOriginal.contains("getTag()")) {
                    return@forEach
                }
                val tagGetterOwners = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\b""")
                    .findAll(executableOriginal)
                    .mapNotNull { match ->
                        val openBrace = executableOriginal.indexOf('{', match.range.last)
                        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableOriginal, openBrace) else -1
                        if (closeBrace <= openBrace) return@mapNotNull null
                        val body = executableOriginal.substring(openBrace + 1, closeBrace)
                        if (Regex("""public\s+TagKey\s*<[^>]+>\s+getTag\s*\(\s*\)\s*\{""").containsMatchIn(body)) {
                            match.groupValues[1]
                        } else {
                            null
                        }
                    }
                    .toSet()
                if (tagGetterOwners.isEmpty()) return@forEach

                var modified = original
                for (owner in tagGetterOwners) {
                    val variables = Regex("""\b${Regex.escape(owner)}\s+([A-Za-z_$][\w$]*)\b""")
                        .findAll(executableOriginal)
                        .map { it.groupValues[1] }
                        .toSet()
                    for (variable in variables) {
                        modified = replaceExecutableRegex(
                            modified,
                            Regex("""\b${Regex.escape(variable)}${Regex.escape(replacement)}""")
                        ) { "$variable.getTag()" }
                    }
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Restore getTag calls on local TagKey holder types after ItemStack NBT migration",
                        before = "holder.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()",
                        after = "holder.getTag()",
                        confidence = Confidence.HIGH,
                        ruleId = "build-restore-non-itemstack-gettag"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateModifyBakingResultModelLocations(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("ModelEvent.ModifyBakingResult") ||
                    !executableOriginal.contains("event.getModels().replaceAll")) {
                    return@forEach
                }

                val lambdaMatch = Regex("""replaceAll\s*\(\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*[A-Za-z_$][\w$]*\s*\)\s*->""")
                    .find(executableOriginal)
                    ?: return@forEach
                val locationVar = lambdaMatch.groupValues[1]
                val callPattern = Regex("""(\.[A-Za-z_$][\w$]*\(\s*)${Regex.escape(locationVar)}(\s*\))""")
                val modified = replaceExecutableRegex(original, callPattern) { match ->
                    "${match.groupValues[1]}$locationVar.id()${match.groupValues[2]}"
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Pass ResourceLocation id() from ModelResourceLocation keys in ModifyBakingResult",
                        before = "model predicate receives ModelResourceLocation record",
                        after = "model predicate receives location.id() ResourceLocation",
                        confidence = Confidence.HIGH,
                        ruleId = "build-modelresource-location-id"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateClassForNameReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("Class.forName(")) return@forEach

                var modified = original
                modified = removeRedundantClassForNameOnClassObjects(modified)
                modified = rewriteClassForNamePresenceChecksWithoutReflection(modified)
                modified = rewriteStringApiVerificationWithoutReflection(modified)
                modified = rewriteClassForNameIsInstanceChecks(modified)
                modified = rewriteSeasonStateReflectionWithoutReflection(modified)
                val enumRewrite = rewriteClassForNameEnumValueOf(projectDir, javaFile, modified, dryRun)
                modified = enumRewrite.first
                changes.addAll(enumRewrite.second)

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Class.forName reflection -> static class/API checks",
                        before = "Class.forName(...)",
                        after = "direct Class<?> registration, ModList loaded check, or direct enum reference",
                        confidence = Confidence.HIGH,
                        ruleId = "build-class-forname-no-reflection"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun rewriteClassForNameIsInstanceChecks(source: String): String {
        val code = maskJavaComments(source)
        val forNameAssignments = Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)\s*;""")
            .findAll(code)

        for (forNameAssignment in forNameAssignments) {
            val classVariable = forNameAssignment.groupValues[1]
            val binaryName = forNameAssignment.groupValues[2]
            val tryStart = findEnclosingTryStartForStatement(code, forNameAssignment.range.first)
            if (tryStart < 0) continue
            val openBrace = code.indexOf('{', tryStart)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
            if (closeBrace <= openBrace) continue
            val tryBody = code.substring(openBrace + 1, closeBrace)
            val searchFrom = (forNameAssignment.range.last - openBrace).coerceAtLeast(0)
            val returnPattern = Regex(
                """return\s+${Regex.escape(classVariable)}\s*!=\s*null\s*&&\s*${Regex.escape(classVariable)}\.isInstance\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;"""
            )
            val returnMatch = returnPattern.find(tryBody, searchFrom) ?: continue
            val valueExpression = returnMatch.groupValues[1]
            val catchEnd = findFollowingCatchBlockEnd(code, closeBrace + 1)
            if (catchEnd <= closeBrace) continue

            var result = source.substring(0, tryStart) +
                "return modporterRuntimeInstanceOf($valueExpression, \"$binaryName\");" +
                source.substring(catchEnd + 1)

            result = Regex("""(?m)^[ \t]*private\s+static\s+Class<\?>\s+${Regex.escape(classVariable)}\s*=\s*null\s*;\s*\r?\n""")
                .replace(result, "")
            result = Regex("""(?m)^[ \t]*private\s+static\s+boolean\s+[A-Za-z_$][\w$]*Resolved\s*=\s*false\s*;\s*\r?\n""")
                .replace(result, "")

            return ensureRuntimeInstanceHelper(result)
        }
        return source
    }

    private fun findEnclosingTryStartForStatement(code: String, offset: Int): Int {
        var searchFrom = offset
        while (searchFrom >= 0) {
            val tryStart = code.lastIndexOf("try", searchFrom)
            if (tryStart < 0) return -1
            val before = code.getOrNull(tryStart - 1)
            val after = code.getOrNull(tryStart + 3)
            if ((before != null && Character.isJavaIdentifierPart(before)) ||
                (after != null && Character.isJavaIdentifierPart(after))) {
                searchFrom = tryStart - 1
                continue
            }
            val openBrace = code.indexOf('{', tryStart)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
            if (openBrace in 0 until offset && closeBrace >= offset) return tryStart
            searchFrom = tryStart - 1
        }
        return -1
    }

    private fun ensureRuntimeInstanceHelper(source: String): String {
        if (source.contains("modporterRuntimeInstanceOf(") &&
            Regex("""private\s+static\s+boolean\s+modporterRuntimeInstanceOf\s*\(""").containsMatchIn(source)) {
            return source
        }
        val insertAt = source.lastIndexOf('}')
        if (insertAt < 0) return source
        val helper = """

    private static boolean modporterRuntimeInstanceOf(Object value, String binaryClassName) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        while (type != null) {
            if (modporterRuntimeTypeMatches(type, binaryClassName)) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean modporterRuntimeTypeMatches(Class<?> type, String binaryClassName) {
        if (binaryClassName.equals(type.getName())) return true;
        for (Class<?> iface : type.getInterfaces()) {
            if (modporterRuntimeTypeMatches(iface, binaryClassName)) return true;
        }
        return false;
    }
""".trimEnd()
        return source.substring(0, insertAt).trimEnd() + helper + System.lineSeparator() + source.substring(insertAt)
    }

    private fun findFollowingCatchBlockEnd(source: String, searchFrom: Int): Int {
        val catchMatch = Regex("""\G\s*catch\s*\([^)]*\)\s*\{""")
            .find(source, searchFrom)
            ?: Regex("""\s*catch\s*\([^)]*\)\s*\{""").find(source, searchFrom)
            ?: return -1
        val openBrace = source.indexOf('{', catchMatch.range.first)
        return if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    }

    private fun rewriteSeasonStateReflectionWithoutReflection(source: String): String {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!code.contains("getSeasonState") ||
            !code.contains("getSubSeason") ||
            !executableCode.contains("Class.forName(") ||
            !executableCode.contains(".getMethod(")) {
            return source
        }

        val resolveMethodMatch = Regex("""private\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{""")
            .findAll(code)
            .firstOrNull { match ->
                val openBrace = code.indexOf('{', match.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
                if (closeBrace <= openBrace) return@firstOrNull false
                val methodBody = code.substring(openBrace + 1, closeBrace)
                val executableMethodBody = executableCode.substring(openBrace + 1, closeBrace)
                methodBody.contains("getSeasonState") &&
                    methodBody.contains("getSubSeason") &&
                    executableMethodBody.contains("Class.forName(") &&
                    executableMethodBody.contains(".getMethod(")
            }
            ?: return source
        val resolveOpenBrace = code.indexOf('{', resolveMethodMatch.range.first)
        val resolveCloseBrace = if (resolveOpenBrace >= 0) findMatchingBrace(code, resolveOpenBrace) else -1
        if (resolveCloseBrace <= resolveOpenBrace) return source
        val resolveMethodName = resolveMethodMatch.groupValues[1]
        val resolveBody = code.substring(resolveOpenBrace + 1, resolveCloseBrace)
        val executableResolveBody = executableCode.substring(resolveOpenBrace + 1, resolveCloseBrace)

        fun MatchResult.hasExecutableClassForNameGetMethod(): Boolean {
            val executableSegment = executableResolveBody.substring(range.first, range.last + 1)
            return executableSegment.contains("Class.forName(") &&
                executableSegment.contains(".getMethod(")
        }

        val helperMatch = Regex("""Class<\?>\s+[A-Za-z_$][\w$]*\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getMethod\(\s*"getSeasonState"\s*,\s*Level\.class\s*\)""")
            .findAll(resolveBody)
            .firstOrNull { it.hasExecutableClassForNameGetMethod() }
            ?: return source
        val helperClass = helperMatch.groupValues[1]
        val helperMethodField = helperMatch.groupValues[2]
        val stateMatch = Regex("""Class<\?>\s+[A-Za-z_$][\w$]*\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getMethod\(\s*"getSubSeason"\s*\)""")
            .findAll(resolveBody)
            .firstOrNull { it.hasExecutableClassForNameGetMethod() }
            ?: return source
        val stateClass = stateMatch.groupValues[1]
        val stateMethodField = stateMatch.groupValues[2]
        val subSeasonMatch = Regex("""Class<\?>\s+[A-Za-z_$][\w$]*\s*=\s*Class\.forName\(\s*"([^"]+\$[A-Za-z_$][\w$]*)"\s*\)\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getMethod\(\s*"name"\s*\)""")
            .findAll(resolveBody)
            .firstOrNull { it.hasExecutableClassForNameGetMethod() }
            ?: return source
        val subSeasonBinary = subSeasonMatch.groupValues[1]
        val subSeasonNameField = subSeasonMatch.groupValues[2]

        val helperSimple = helperClass.substringAfterLast('.')
        val stateSimple = stateClass.substringAfterLast('.')
        val subSeasonType = subSeasonBinary.substringAfter('$')

        var result = source.substring(0, resolveMethodMatch.range.first).trimEnd() +
            System.lineSeparator() + System.lineSeparator() +
            source.substring(resolveCloseBrace + 1).trimStart()

        result = Regex("""(?m)^[ \t]*private\s+static\s+boolean\s+[A-Za-z_$][\w$]*Resolved\s*=\s*false\s*;\s*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*private\s+static\s+Method\s+[A-Za-z_$][\w$]*\s*;\s*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*${Regex.escape(resolveMethodName)}\(\);\s*\r?\n""").replace(result, "")
        result = Regex("""(?m)^[ \t]*if\s*\(\s*${Regex.escape(helperMethodField)}\s*==\s*null\s*\)\s*return\s+[A-Za-z_$][\w$]*\.[A-Za-z_$][\w$]*;\s*\r?\n""")
            .replace(result, "")
        result = Regex("""Object\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(helperMethodField)}\.invoke\(\s*null\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match -> "$stateSimple ${match.groupValues[1]} = $helperSimple.getSeasonState(${match.groupValues[2]});" }
        result = Regex("""Object\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(stateMethodField)}\.invoke\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match -> "$subSeasonType ${match.groupValues[1]} = ${match.groupValues[2]}.getSubSeason();" }
        result = Regex("""String\s+([A-Za-z_$][\w$]*)\s*=\s*\(String\)\s*${Regex.escape(subSeasonNameField)}\.invoke\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match -> "String ${match.groupValues[1]} = ${match.groupValues[2]}.name();" }

        result = removeJavaImport(result, "java.lang.reflect.Method")
        result = ensureJavaImport(result, helperClass)
        result = ensureJavaImport(result, stateClass)
        result = ensureJavaImport(result, subSeasonBinary.replace('$', '.'))
        return result
    }

    private fun removeRedundantClassForNameOnClassObjects(source: String): String {
        val code = maskJavaCommentsAndLiterals(source)
        val pattern = Regex("""(?m)^[ \t]*Class\.forName\(\s*([A-Za-z_$][\w$]*)\.getName\(\)\s*,\s*true\s*,\s*\1\.getClassLoader\(\)\s*\);\s*\r?\n""")
        val matches = pattern.findAll(code).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            result = result.substring(0, match.range.first) + result.substring(match.range.last + 1)
        }
        return result
    }

    private fun rewriteClassForNamePresenceChecksWithoutReflection(source: String): String {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val pattern = Regex(
            """try\s*\{\s*Class\.forName\(\s*"([^"]+)"(?:\s*,\s*false\s*,\s*.*?)?\s*\)\s*;\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=\s*true\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*(?:\2\s*=\s*false\s*;)?\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val executablePattern = Regex(
            """try\s*\{\s*Class\.forName\(\s*(?:,\s*false\s*,\s*.*?)?\)\s*;\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=\s*true\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*(?:\1\s*=\s*false\s*;)?\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val matches = pattern.findAll(code)
            .filter { match ->
                val executableMatch = executablePattern.matchEntire(
                    executableCode.substring(match.range.first, match.range.last + 1)
                )
                executableMatch?.groupValues?.get(1) == match.groupValues[2]
            }
            .toList()
        if (matches.isEmpty()) return source

        var rewritten = source
        for (match in matches.asReversed()) {
            val replacement = """${match.groupValues[2]} = modporterClassResourcePresent("${match.groupValues[1]}");"""
            rewritten = rewritten.substring(0, match.range.first) +
                replacement +
                rewritten.substring(match.range.last + 1)
        }
        return ensureClassResourcePresenceHelper(rewritten)
    }

    private fun ensureClassResourcePresenceHelper(source: String): String {
        if (Regex("""private\s+static\s+boolean\s+modporterClassResourcePresent\s*\(""").containsMatchIn(source)) {
            return source
        }
        val insertAt = source.lastIndexOf('}')
        if (insertAt < 0) return source
        val helper = """

    private static boolean modporterClassResourcePresent(String binaryClassName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();
        return classLoader != null && classLoader.getResource(binaryClassName.replace('.', '/') + ".class") != null;
    }
""".trimEnd()
        return source.substring(0, insertAt).trimEnd() + helper + System.lineSeparator() + source.substring(insertAt)
    }

    private fun rewriteStringApiVerificationWithoutReflection(source: String): String {
        val code = maskJavaCommentsAndLiterals(source)
        val methodMatch = Regex(
            """public\s+static\s+void\s+verifyApiClasses\s*\(\s*String\s+([A-Za-z_$][\w$]*)\s*,\s*String\.\.\.\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
        ).find(code) ?: return source

        val openBrace = code.indexOf('{', methodMatch.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
        if (closeBrace <= openBrace) return source
        val body = code.substring(openBrace + 1, closeBrace)
        if (!body.contains("Class.forName(")) return source

        val modIdParam = methodMatch.groupValues[1]
        val classNamesParam = methodMatch.groupValues[2]
        val replacement = """
public static void verifyApiClasses(String $modIdParam, String... $classNamesParam) {
        if (!ModList.get().isLoaded($modIdParam)) {
            throw new RuntimeException("API verification failed for mod '" + $modIdParam + "': mod is not loaded.");
        }
        LOGGER.debug("API verification for '{}' will be enforced by static linkage during compat initialization ({} declared classes).", $modIdParam, $classNamesParam.length);
    }
""".trimIndent()

        return ensureJavaImport(
            source.substring(0, methodMatch.range.first) + replacement + source.substring(closeBrace + 1),
            "net.neoforged.fml.ModList"
        )
    }

    private fun rewriteClassForNameEnumValueOf(
        projectDir: Path,
        javaFile: Path,
        source: String,
        dryRun: Boolean
    ): Pair<String, List<Change>> {
        var result = source
        val changes = mutableListOf<Change>()
        val packageName = Regex("""(?m)^package\s+([^;]+);""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: return source to emptyList()
        val tryPattern = Regex(
            """try\s*\{\s*Class<\?>\s+([A-Za-z_$][\w$]*)\s*=\s*Class\.forName\(\s*"([^"]+\$([A-Za-z_$][\w$]*))"\s*\)\s*;\s*return\s+Enum\.valueOf\(\s*\(Class<\? extends Enum>\)\s*\1\.asSubclass\(Enum\.class\)\s*,\s*"([A-Za-z_$][\w$]*)"\s*\)\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{[^{}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val executablePattern = Regex(
            """try\s*\{\s*Class<\?>\s+([A-Za-z_$][\w$]*)\s*=\s*Class\.forName\(\s*\)\s*;\s*return\s+Enum\.valueOf\(\s*\(Class<\? extends Enum>\)\s*\1\.asSubclass\(Enum\.class\)\s*,\s*\)\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{[^{}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val matches = tryPattern.findAll(code)
            .filter { match ->
                val executableMatch = executablePattern.matchEntire(
                    executableCode.substring(match.range.first, match.range.last + 1)
                )
                executableMatch?.groupValues?.get(1) == match.groupValues[1]
            }
            .toList()
        if (matches.isEmpty()) return source to emptyList()
        val accessorImports = linkedSetOf<String>()

        for (match in matches.asReversed()) {
            val binaryName = match.groupValues[2]
            val simpleNestedName = match.groupValues[3]
            val enumConstant = match.groupValues[4]
            val accessorName = "ModPorter${simpleNestedName}Accessor"
            accessorImports.add("${generatedMixinPackage(packageName)}.$accessorName")
            result = result.substring(0, match.range.first) +
                "return $accessorName.modporter${'$'}valueOf(\"$enumConstant\");" +
                result.substring(match.range.last + 1)
            changes.addAll(ensureNestedEnumMixinInvoker(
                projectDir = projectDir,
                sourceFile = javaFile,
                packageName = packageName,
                accessorName = accessorName,
                targetBinaryName = binaryName,
                dryRun = dryRun
            ))
        }

        accessorImports.forEach { importName ->
            result = addJavaImportIfMissing(result, importName)
        }
        if (!result.contains("Class<? extends Enum>") && !result.contains("Enum.valueOf(")) {
            result = Regex("""(?m)^[ \t]*@SuppressWarnings\(\s*\{?\s*"rawtypes"\s*,\s*"unchecked"\s*\}?\s*\)\s*\r?\n""")
                .replace(result, "")
        }
        return result to changes
    }

    private fun rewriteDeferredHolderReflectionCollectors(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val filesByClassName = javaFiles.associateBy { it.fileName.toString().removeSuffix(".java") }
        val changes = mutableListOf<Change>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("getDeclaredFields()") ||
                !original.contains("DeferredHolder.class.isAssignableFrom") ||
                !original.contains("java.lang.reflect.Field")) {
                continue
            }

            var modified = original
            val methodPattern = Regex(
                """(?m)^([ \t]*)private\s+static\s+List\s*<\s*DeferredHolder\s*<\s*([^>]+?)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{"""
            )
            for (methodMatch in methodPattern.findAll(original).toList().asReversed()) {
                val openBrace = modified.indexOf('{', methodMatch.range.last)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(modified, openBrace) else -1
                if (closeBrace <= openBrace) continue
                val body = modified.substring(openBrace + 1, closeBrace)
                val registryClass = Regex(
                    """for\s*\(\s*Field\s+[A-Za-z_$][\w$]*\s*:\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class\.getDeclaredFields\(\)\s*\)"""
                ).find(body)?.groupValues?.get(1) ?: continue
                val registrySimpleName = registryClass.substringAfterLast('.')
                val registryFile = filesByClassName[registrySimpleName] ?: continue
                val deferredHolderFields = collectPublicStaticDeferredHolderFields(registryFile.readText())
                if (deferredHolderFields.isEmpty()) continue

                val indent = methodMatch.groupValues[1]
                val generic = methodMatch.groupValues[2].trim()
                val methodName = methodMatch.groupValues[3]
                val listType = "List<DeferredHolder<$generic>>"
                val addLines = deferredHolderFields.joinToString(System.lineSeparator()) { fieldName ->
                    "$indent    out.add($registryClass.$fieldName);"
                }
                val replacement = buildString {
                    append(indent)
                    append("private static ")
                    append(listType)
                    append(" ")
                    append(methodName)
                    append("() {")
                    append(System.lineSeparator())
                    append(indent)
                    append("    ")
                    append(listType)
                    append(" out = new ArrayList<>();")
                    append(System.lineSeparator())
                    append(addLines)
                    append(System.lineSeparator())
                    append(indent)
                    append("    return out;")
                    append(System.lineSeparator())
                    append(indent)
                    append("}")
                }
                modified = modified.substring(0, methodMatch.range.first) +
                    replacement +
                    modified.substring(closeBrace + 1)
            }

            if (modified != original) {
                modified = removeJavaImport(modified, "java.lang.reflect.Field")
                modified = removeJavaImport(modified, "java.lang.reflect.Modifier")
                if (!modified.contains("new ArrayList<>()")) {
                    modified = removeJavaImport(modified, "java.util.ArrayList")
                }
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Rewrite DeferredHolder registry field reflection collector to explicit source-derived list",
                    before = "Registry.class.getDeclaredFields() + java.lang.reflect.Field",
                    after = "explicit registry DeferredHolder field list",
                    confidence = Confidence.HIGH,
                    ruleId = "build-deferredholder-reflection-collector"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        return changes
    }

    private fun collectPublicStaticDeferredHolderFields(source: String): List<String> =
        Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+DeferredHolder\s*<[^;=]+>\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(source)
            .map { it.groupValues[1] }
            .toList()

    private fun ensureNestedEnumMixinInvoker(
        projectDir: Path,
        sourceFile: Path,
        packageName: String,
        accessorName: String,
        targetBinaryName: String,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val generatedPackageName = generatedMixinPackage(packageName)
        val accessorFile = sourceFile.parent.resolve("modporter").resolve("mixin").resolve("$accessorName.java")
        val accessorSource = """
package $generatedPackageName;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "$targetBinaryName", remap = false)
public interface $accessorName {
    @Invoker(value = "valueOf", remap = false)
    static Object modporter${'$'}valueOf(String name) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
""".trimIndent()

        requireMixinConfigRegistrationTarget(projectDir, generatedPackageName, accessorName)
        if (!accessorFile.exists() || accessorFile.readText() != accessorSource) {
            changes.add(Change(
                file = accessorFile,
                line = 1,
                description = "Generate mixin invoker for package-private nested enum access",
                before = "(missing invoker)",
                after = "$accessorName targets $targetBinaryName",
                confidence = Confidence.HIGH,
                ruleId = "build-class-forname-enum-mixin-invoker"
            ))
            if (!dryRun) {
                accessorFile.parent.createDirectories()
                accessorFile.writeText(accessorSource)
            }
        }

        changes.addAll(ensureMixinConfigEntry(projectDir, generatedPackageName, accessorName, dryRun))
        return changes
    }

    private fun javaPackageName(source: String): String? =
        Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)

    private fun generatedMixinPackage(packageName: String): String =
        "$packageName.modporter.mixin"

    private fun ensureMixinInvoker(
        projectDir: Path,
        sourceFile: Path,
        packageName: String,
        invokerName: String,
        targetClassName: String,
        targetImport: String,
        extraImports: List<String>,
        methodSource: String,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val generatedPackageName = generatedMixinPackage(packageName)
        val invokerFile = sourceFile.parent.resolve("modporter").resolve("mixin").resolve("$invokerName.java")
        val imports = (extraImports + targetImport + listOf(
            "org.spongepowered.asm.mixin.Mixin",
            "org.spongepowered.asm.mixin.gen.Invoker"
        )).distinct().sorted().joinToString(System.lineSeparator()) { "import $it;" }
        val invokerSource = """
package $generatedPackageName;

$imports

@Mixin($targetClassName.class)
public interface $invokerName {
    ${methodSource.prependIndent("    ").trimStart()}
}
""".trimIndent()

        requireMixinConfigRegistrationTarget(projectDir, generatedPackageName, invokerName)
        if (!invokerFile.exists() || invokerFile.readText() != invokerSource) {
            changes.add(Change(
                file = invokerFile,
                line = 1,
                description = "Generate mixin invoker for protected vanilla method access",
                before = "(missing invoker)",
                after = "$invokerName targets $targetImport",
                confidence = Confidence.HIGH,
                ruleId = "build-protected-method-mixin-invoker"
            ))
            if (!dryRun) {
                invokerFile.parent.createDirectories()
                invokerFile.writeText(invokerSource)
            }
        }

        changes.addAll(ensureMixinConfigEntry(projectDir, generatedPackageName, invokerName, dryRun))
        return changes
    }

    private fun requireMixinConfigRegistrationTarget(
        projectDir: Path,
        packageName: String,
        mixinClassName: String
    ) {
        if (hasMixinConfigRegistrationTarget(projectDir, packageName, mixinClassName)) return
        generatedMixinConfigName(projectDir)
    }

    private fun hasMixinConfigRegistrationTarget(
        projectDir: Path,
        packageName: String,
        mixinClassName: String
    ): Boolean {
        val resourcesDir = projectDir.resolve("src/main/resources")
        if (!resourcesDir.exists()) return false
        return java.nio.file.Files.walk(resourcesDir).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".mixins.json") }
                .anyMatch { mixinConfig ->
                    val source = mixinConfig.readText()
                    val configPackage = Regex(""""package"\s*:\s*"([^"]+)"""")
                        .find(source)
                        ?.groupValues
                        ?.get(1)
                        ?: return@anyMatch false
                    relativeMixinNameForConfigPackage(packageName, mixinClassName, configPackage) != null &&
                        (packageName == configPackage || Regex(""""plugin"\s*:""").containsMatchIn(source))
                }
        }
    }

    private fun ensureMixinConfigEntry(
        projectDir: Path,
        packageName: String,
        mixinClassName: String,
        dryRun: Boolean
    ): List<Change> {
        val resourcesDir = projectDir.resolve("src/main/resources")
        if (!resourcesDir.exists()) {
            if (dryRun) return emptyList()
            resourcesDir.createDirectories()
        }

        val changes = mutableListOf<Change>()
        var registeredInExistingConfig = false
        val candidates = mutableListOf<MixinConfigCandidate>()
        java.nio.file.Files.walk(resourcesDir)
            .filter { it.fileName.toString().endsWith(".mixins.json") }
            .forEach { mixinConfig ->
                val original = mixinConfig.readText()
                val configPackage = Regex(""""package"\s*:\s*"([^"]+)"""")
                    .find(original)
                    ?.groupValues
                    ?.get(1)
                    ?: return@forEach
                val relativeMixinName = relativeMixinNameForConfigPackage(packageName, mixinClassName, configPackage)
                    ?: return@forEach
                if (packageName != configPackage && !Regex(""""plugin"\s*:""").containsMatchIn(original)) {
                    return@forEach
                }
                candidates.add(MixinConfigCandidate(mixinConfig, original, configPackage, relativeMixinName))
            }

        val selected = candidates.maxWithOrNull(
            compareBy<MixinConfigCandidate> { it.configPackage.length }
                .thenBy { if (it.configPackage == packageName) 1 else 0 }
        )
        if (selected != null) {
            val modified = if (Regex(""""${Regex.escape(selected.relativeMixinName)}"""").containsMatchIn(selected.source)) {
                selected.source
            } else {
                addMixinClassToConfig(selected.source, selected.configPackage, selected.relativeMixinName) ?: selected.source
            }
            if (modified != selected.source) {
                changes.add(Change(
                    file = selected.file,
                    line = selected.source.lineNumberAt(selected.source.indexOf(""""mixins"""").coerceAtLeast(0)),
                    description = "Register generated mixin invoker in mixin config",
                    before = "mixins array without ${selected.relativeMixinName}",
                    after = "mixins array includes ${selected.relativeMixinName}",
                    confidence = Confidence.HIGH,
                    ruleId = "build-register-generated-mixin-invoker"
                ))
                if (!dryRun) selected.file.writeText(modified)
            }
            registeredInExistingConfig = true
            candidates
                .filter { it.file != selected.file }
                .forEach { stale ->
                    val cleaned = removeMixinClassFromConfig(stale.source, stale.relativeMixinName)
                    if (cleaned != stale.source) {
                        changes.add(Change(
                            file = stale.file,
                            line = stale.source.lineNumberAt(stale.source.indexOf(stale.relativeMixinName).coerceAtLeast(0)),
                            description = "Remove generated mixin invoker from less-specific mixin config",
                            before = "mixins array includes ${stale.relativeMixinName}",
                            after = "mixins array omits ${stale.relativeMixinName}",
                            confidence = Confidence.HIGH,
                            ruleId = "build-prune-generated-mixin-invoker"
                        ))
                        if (!dryRun) stale.file.writeText(cleaned)
                    }
                }
        }
        if (!registeredInExistingConfig) {
            val configName = generatedMixinConfigName(projectDir)
            val mixinConfig = resourcesDir.resolve(configName)
            val original = if (mixinConfig.exists()) mixinConfig.readText() else ""
            val modified = if (original.isBlank()) {
                """
{
  "required": true,
  "minVersion": "0.8",
  "package": "$packageName",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "$mixinClassName"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
""".trimIndent() + System.lineSeparator()
            } else {
                addMixinClassToConfig(original, packageName, mixinClassName) ?: original
            }
            if (modified != original) {
                changes.add(Change(
                    file = mixinConfig,
                    line = 1,
                    description = "Create or update generated mixin config",
                    before = "(missing mixin config entry)",
                    after = "$configName includes $mixinClassName",
                    confidence = Confidence.HIGH,
                    ruleId = "build-generated-mixin-config"
                ))
                if (!dryRun) mixinConfig.writeText(modified)
            }
            changes.addAll(ensureNeoForgeModsTomlMixinEntry(projectDir, configName, dryRun))
        }
        return changes
    }

    private data class MixinConfigCandidate(
        val file: Path,
        val source: String,
        val configPackage: String,
        val relativeMixinName: String
    )

    private fun relativeMixinNameForConfigPackage(
        generatedPackageName: String,
        mixinClassName: String,
        configPackageName: String
    ): String? {
        return when {
            generatedPackageName == configPackageName -> mixinClassName
            generatedPackageName.startsWith("$configPackageName.") ->
                generatedPackageName.removePrefix("$configPackageName.") + "." + mixinClassName
            else -> null
        }
    }

    private fun addMixinClassToConfig(source: String, packageName: String, mixinClassName: String): String? {
        if (!Regex(""""package"\s*:\s*"${Regex.escape(packageName)}"""").containsMatchIn(source)) return null
        if (Regex(""""${Regex.escape(mixinClassName)}"""").containsMatchIn(source)) return source
        val mixinsMatch = Regex(""""mixins"\s*:\s*\[""").find(source) ?: return null
        val openBracket = source.indexOf('[', mixinsMatch.range.first)
        val closeBracket = if (openBracket >= 0) findClosing(source, openBracket, '[', ']') else -1
        if (closeBracket <= openBracket) return null
        val inside = source.substring(openBracket + 1, closeBracket)
        val insertion = if (inside.trim().isEmpty()) {
            "\n    \"$mixinClassName\"\n  "
        } else {
            ",\n    \"$mixinClassName\""
        }
        return source.substring(0, closeBracket) + insertion + source.substring(closeBracket)
    }

    private fun removeMixinClassFromConfig(source: String, mixinClassName: String): String {
        val mixinsMatch = Regex(""""mixins"\s*:\s*\[""").find(source) ?: return source
        val openBracket = source.indexOf('[', mixinsMatch.range.first)
        val closeBracket = if (openBracket >= 0) findClosing(source, openBracket, '[', ']') else -1
        if (closeBracket <= openBracket) return source
        val inside = source.substring(openBracket + 1, closeBracket)
        val entries = Regex(""""([^"]+)"""")
            .findAll(inside)
            .map { it.groupValues[1] }
            .toList()
        if (mixinClassName !in entries) return source
        val remaining = entries.filterNot { it == mixinClassName }
        val indent = Regex("\\r?\\n([ \\t]*)\"").find(inside)?.groupValues?.get(1) ?: "    "
        val replacement = if (remaining.isEmpty()) {
            "\n  "
        } else {
            "\n" + remaining.joinToString(",\n") { "$indent\"$it\"" } + "\n  "
        }
        return source.substring(0, openBracket + 1) + replacement + source.substring(closeBracket)
    }

    private fun generatedMixinConfigName(projectDir: Path): String {
        val modId = detectUniqueProjectModId(projectDir)
            ?: error("Cannot derive generated mixin config name: missing or ambiguous @Mod annotation and mod metadata mod id")
        return "$modId.modporter.mixins.json"
    }

    /**
     * `src/testFixtures/resources` is Gradle's test-fixtures source set location. Mods that keep their
     * game tests in the main source set (excluded from the jar) put the data packs those tests read
     * there (ShowerCore: Hot Bath's example fluids, documented in its README as regression fixtures),
     * so the run configurations load that tree with the mod while the jar stays without it.
     */
    private fun migrateTestFixtureResources(projectDir: Path, dryRun: Boolean): List<Change> {
        val fixtures = projectDir.resolve("src/testFixtures/resources")
        if (!fixtures.isDirectory()) return emptyList()
        val hasFiles = java.nio.file.Files.walk(fixtures).use { paths -> paths.anyMatch { it.isRegularFile() } }
        if (!hasFiles) return emptyList()
        val buildFile = projectDir.resolve("build.gradle").takeIf { it.exists() } ?: return emptyList()
        val original = buildFile.readText()
        if (Regex("""\btestFixtures\b""").containsMatchIn(original) || original.contains("java-test-fixtures")) return emptyList()
        if (!original.contains("neoForge {")) return emptyList()
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val block = """

// Gradle test-fixture resources: the data packs the game tests read (kept out of the jar, loaded with the mod in every run)
sourceSets {
    testFixtures {
        resources.setSrcDirs(['src/testFixtures/resources'])
    }
}
neoForge {
    mods {
        "${'$'}{project.mod_id}" {
            sourceSet(sourceSets.testFixtures)
        }
    }
}
""".trimEnd().replace("\n", newline)
        val modified = original.trimEnd() + block + newline
        if (!dryRun) buildFile.writeText(modified)
        return listOf(Change(
            file = buildFile,
            line = original.lines().size,
            description = "Load src/testFixtures/resources with the mod in the run configurations",
            before = "src/testFixtures/resources not on any run classpath",
            after = "sourceSets.testFixtures resources attached to neoForge.mods",
            confidence = Confidence.HIGH,
            ruleId = "build-test-fixture-resources"
        ))
    }

    private fun registerExistingMixinConfigs(
        projectDir: Path,
        dryRun: Boolean,
        plannedChanges: List<Change>
    ): List<Change> {
        val resourcesDir = projectDir.resolve("src/main/resources")
        if (!resourcesDir.exists()) return emptyList()
        val ownedResourcesDir = resourcesDir.toAbsolutePath().normalize()
        val configsByPath = linkedMapOf<Path, String>()

        Files.walk(resourcesDir).use { paths ->
            paths.filter { it.isRegularFile() && hasMixinConfigName(it) }
                .forEach { config ->
                    configsByPath[config.toAbsolutePath().normalize()] = config.readText()
                }
        }
        plannedChanges.forEach { change ->
            val ownedPath = ownedPlannedResourcePath(projectDir, ownedResourcesDir, change.file) ?: return@forEach
            if (!hasMixinConfigName(ownedPath)) return@forEach
            if (!isStructuredMixinConfig(change.after)) return@forEach
            configsByPath[ownedPath] = change.after
        }

        val configNames = configsByPath.entries.asSequence()
            .filter { (_, source) -> isStructuredMixinConfig(source) }
            .map { (path, _) -> ownedResourcesDir.relativize(path).joinToString("/") }
            .distinct()
            .sorted()
            .toList()
        if (configNames.isEmpty()) return emptyList()

        return ensureNeoForgeModsTomlMixinEntries(
            projectDir = projectDir,
            configNames = configNames,
            dryRun = dryRun,
            description = "Register existing mixin config in NeoForge mods metadata",
            ruleId = "build-register-existing-mixin-config"
        )
    }

    private fun ownedPlannedResourcePath(
        projectDir: Path,
        ownedResourcesDir: Path,
        file: Path
    ): Path? {
        val candidates = if (file.isAbsolute) {
            listOf(file)
        } else {
            listOf(file, projectDir.resolve(file))
        }
        return candidates.asSequence()
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .firstOrNull { candidate ->
                candidate != ownedResourcesDir && candidate.startsWith(ownedResourcesDir)
            }
    }

    private fun hasMixinConfigName(path: Path): Boolean =
        path.extension.equals("json", ignoreCase = true) &&
            path.fileName.toString().contains("mixin", ignoreCase = true)

    private fun isStructuredMixinConfig(source: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(source).jsonObject
        val packageName = root["package"]?.jsonPrimitive
        if (packageName == null || !packageName.isString || packageName.content.isBlank()) {
            return@runCatching false
        }
        val memberLists = listOf("mixins", "client", "server").mapNotNull(root::get)
        memberLists.isNotEmpty() && memberLists.all { members ->
            members.jsonArray.all { member ->
                val name = member.jsonPrimitive
                name.isString && name.content.isNotBlank()
            }
        }
    }.getOrDefault(false)

    private fun ensureNeoForgeModsTomlMixinEntry(projectDir: Path, configName: String, dryRun: Boolean): List<Change> {
        return ensureNeoForgeModsTomlMixinEntries(
            projectDir = projectDir,
            configNames = listOf(configName),
            dryRun = dryRun,
            description = "Register generated mixin config in NeoForge mods metadata",
            ruleId = "build-register-generated-mixin-config"
        )
    }

    private fun ensureNeoForgeModsTomlMixinEntries(
        projectDir: Path,
        configNames: List<String>,
        dryRun: Boolean,
        description: String,
        ruleId: String
    ): List<Change> {
        val modsToml = listOf(
            projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
            projectDir.resolve("src/main/resources/META-INF/mods.toml")
        ).firstOrNull { it.exists() } ?: return emptyList()
        val original = modsToml.readText()
        val missing = configNames
            .distinct()
            .filterNot { configName ->
                Regex("""(?m)^\s*config\s*=\s*["']${Regex.escape(configName)}["']""").containsMatchIn(original)
            }
        if (missing.isEmpty()) {
            return emptyList()
        }
        val entries = missing.joinToString(System.lineSeparator() + System.lineSeparator()) { configName ->
            """
[[mixins]]
config="$configName"
""".trimIndent()
        } + System.lineSeparator()
        val modified = original.trimEnd() + System.lineSeparator() + System.lineSeparator() + entries
        if (!dryRun) modsToml.writeText(modified)
        return missing.map { configName ->
            Change(
                file = modsToml,
                line = original.lines().size,
                description = description,
                before = "neoforge.mods.toml without [[mixins]] $configName",
                after = "[[mixins]] config=\"$configName\"",
                confidence = Confidence.HIGH,
                ruleId = ruleId
            )
        }
    }

    private fun migrateRemovedTitleScreenAccessors(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val changes = migrateRemovedTitleScreenPanoramaField(javaFiles, dryRun).toMutableList()
        val removedMethods = linkedSetOf<String>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("@Mixin(TitleScreen.class)") || !original.contains("@Accessor")) continue

            val (withoutAccessors, methods) = removeRemovedTitleScreenAccessorMethods(original)
            if (withoutAccessors == original) continue

            val modified = cleanupTitleScreenRemovedAccessorImports(withoutAccessors)
            if (modified != original) {
                removedMethods.addAll(methods)
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Remove Mixin accessors for TitleScreen fields and inner classes removed in 1.21",
                    before = methods.joinToString(", "),
                    after = "removed accessors for non-existent TitleScreen targets",
                    confidence = Confidence.HIGH,
                    ruleId = "build-title-screen-removed-accessors"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        if (removedMethods.isNotEmpty()) {
            for (javaFile in javaFiles) {
                val original = javaFile.readText()
                val modified = cleanupTitleScreenRemovedAccessorImports(
                    removeRemovedTitleScreenAccessorCallStatements(original, removedMethods)
                )
                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove direct calls that only target removed TitleScreen accessor methods",
                        before = removedMethods.joinToString(", "),
                        after = "removed direct obsolete accessor call statements",
                        confidence = Confidence.HIGH,
                        ruleId = "build-title-screen-removed-accessor-calls"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        }

        changes.addAll(removeUnusedTitleScreenUpdateIndicatorClasses(javaFiles, removedMethods, dryRun))
        return changes
    }

    private fun migrateRemovedTitleScreenPanoramaField(
        javaFiles: List<Path>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            val executable = maskJavaCommentsAndLiterals(original)
            val titleScreenVariables = linkedSetOf<String>()
            Regex("""\bTitleScreen\s+([A-Za-z_$][\w$]*)\b""")
                .findAll(executable)
                .forEach { titleScreenVariables += it.groupValues[1] }
            Regex("""\binstanceof\s+TitleScreen\s+([A-Za-z_$][\w$]*)\b""")
                .findAll(executable)
                .forEach { titleScreenVariables += it.groupValues[1] }
            if (titleScreenVariables.isEmpty()) continue

            var modified = original
            var replacements = 0
            for (variable in titleScreenVariables) {
                val matches = Regex("""\b${Regex.escape(variable)}\s*\.\s*panorama\b""")
                    .findAll(maskJavaCommentsAndLiterals(modified))
                    .toList()
                for (match in matches.asReversed()) {
                    modified = modified.replaceRange(match.range, "Screen.PANORAMA")
                    replacements++
                }
            }
            if (replacements == 0) continue
            modified = addJavaImportIfMissing(modified, "net.minecraft.client.gui.screens.Screen")
            changes += Change(
                file = javaFile,
                line = 1,
                description = "Replace removed TitleScreen panorama instance field with the shared Screen panorama renderer",
                before = "typed TitleScreen.panorama access",
                after = "Screen.PANORAMA",
                confidence = Confidence.HIGH,
                ruleId = "build-title-screen-panorama-screen-api"
            )
            if (!dryRun) javaFile.writeText(modified)
        }
        return changes
    }

    private fun removeRemovedTitleScreenAccessorMethods(source: String): Pair<String, Set<String>> {
        val removed = linkedSetOf<String>()
        val methodPattern = Regex(
            """(?ms)^([ \t]*(?:@[^\r\n]+(?:\r?\n|[ \t]+))*[ \t]*(?:[\w.$<>\[\], ?]+\s+)+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*;\s*)"""
        )
        var modified = methodPattern.replace(source) { match ->
            val block = match.groupValues[1]
            val methodName = match.groupValues[2]
            val isRemovedTitleScreenAccessor = block.contains("@Accessor") && (
                block.contains("TitleScreen.WarningLabel") ||
                    block.contains("TitleScreenModUpdateIndicator") ||
                    Regex("""@Accessor\s*\(\s*(?:value\s*=\s*)?"panorama"""").containsMatchIn(block)
                )
            if (isRemovedTitleScreenAccessor) {
                removed.add(methodName)
                ""
            } else {
                match.value
            }
        }
        val removedTypeAccessorPatterns = listOf(
            Regex("""(?ms)^[ \t]*(?:@[^\r\n]+\r?\n)+[ \t]*TitleScreenModUpdateIndicator\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*;\s*"""),
            Regex("""(?ms)^[ \t]*(?:@[^\r\n]+\r?\n)+[ \t]*void\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*TitleScreenModUpdateIndicator[^;{}]*\)\s*;\s*"""),
            Regex("""(?ms)^[ \t]*(?:@[^\r\n]+\r?\n)+[ \t]*TitleScreen\.WarningLabel\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*;\s*""")
        )
        for (pattern in removedTypeAccessorPatterns) {
            modified = pattern.replace(modified) { match ->
                removed.add(match.groupValues[1])
                ""
            }
        }
        return modified to removed
    }

    private fun removeRemovedTitleScreenAccessorCallStatements(source: String, removedMethods: Set<String>): String {
        var result = source
        for (method in removedMethods) {
            val callStatement = Regex(
                """(?m)^[ \t]*[^\r\n]*\.${Regex.escape(method)}\s*\([^\r\n]*\)[^\r\n]*;\s*(?:\r?\n)?"""
            )
            result = callStatement.replace(result, "")
        }
        return result
    }

    private fun removeUnusedTitleScreenUpdateIndicatorClasses(
        javaFiles: List<Path>,
        removedMethods: Set<String>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val currentFiles = javaFiles.filter { it.exists() }
        val sources = currentFiles.associateWith {
            removeRemovedTitleScreenAccessorCallStatements(it.readText(), removedMethods)
        }
        val updateIndicatorClasses = sources.mapNotNull { (file, source) ->
            val executableCode = maskJavaCommentsAndLiterals(source)
            val className = Regex(
                """\bclass\s+([A-Za-z_$][\w$]*)\b[\s\S]*?\bextends\s+(?:[A-Za-z_$][\w$]*\.)*TitleScreenModUpdateIndicator\b"""
            )
                .find(executableCode)
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            file to className
        }

        for ((file, className) in updateIndicatorClasses) {
            val hasExternalReference = sources.any { (otherFile, source) ->
                otherFile != file && Regex("""\b${Regex.escape(className)}\b""").containsMatchIn(source)
            }
            if (hasExternalReference) continue

            changes.add(Change(
                file = file,
                line = 1,
                description = "Remove unused TitleScreenModUpdateIndicator subclass after the NeoForge title-screen update widget was removed",
                before = className,
                after = "(deleted unused removed-API subclass)",
                confidence = Confidence.HIGH,
                ruleId = "build-title-screen-update-indicator-class"
            ))
            if (!dryRun) file.deleteIfExists()
        }

        return changes
    }

    private fun cleanupTitleScreenRemovedAccessorImports(source: String): String {
        var result = source
        result = removeJavaImportIfSimpleNameUnused(result, "net.neoforged.neoforge.client.gui.TitleScreenModUpdateIndicator")
        result = removeJavaImportIfSimpleNameUnused(result, "net.minecraft.client.renderer.PanoramaRenderer")
        return result
    }

    private fun migrateAccessTransformers(projectDir: Path, dryRun: Boolean): List<Change> {
        val requiredEntries = collectRequiredAccessTransformerEntries(projectDir)
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        if (!atFile.exists() && requiredEntries.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        val original = if (atFile.exists()) atFile.readText() else ""
        val normalizedLines = original.lines().map { originalLine ->
            val migrated = migrateAccessTransformerLine(originalLine)
            originalLine to migrated
        }
        val entries = linkedSetOf<String>()
        val output = mutableListOf<String>()
        var lineChanged = false

        for ((originalLine, line) in normalizedLines) {
            if (line != originalLine) {
                lineChanged = true
            }
            if (line.isEmpty() && normalizeAccessTransformerEntry(originalLine) != null) {
                changes.add(Change(
                    file = atFile,
                    line = original.lines().indexOf(originalLine) + 1,
                    description = "Drop an access transformer whose target no longer exists on NeoForge 1.21.1",
                    before = originalLine.trim(),
                    after = "",
                    confidence = Confidence.HIGH,
                    ruleId = "build-access-transformer-missing-target"
                ))
            }
            val entry = normalizeAccessTransformerEntry(line)
            if (entry != null) {
                if (entries.add(entry)) {
                    output.add(line)
                } else {
                    lineChanged = true
                }
            } else {
                output.add(line)
            }
        }

        for (entry in requiredEntries) {
            if (entries.add(entry)) {
                if (output.isNotEmpty() && output.last().isNotBlank()) output.add("")
                output.add(entry)
            }
        }

        val modified = output.joinToString(System.lineSeparator()).trimEnd() +
            if (output.isNotEmpty()) System.lineSeparator() else ""
        if (modified != original || lineChanged) {
            changes.add(Change(
                file = atFile,
                line = 1,
                description = "Migrate and complete Access Transformer entries for 1.21 named members",
                before = "1.20 SRG access transformer entries or missing AT file",
                after = "1.21 named access transformer entries",
                confidence = Confidence.HIGH,
                ruleId = "build-access-transformer-entries-121"
            ))
            if (!dryRun) {
                atFile.parent.createDirectories()
                atFile.writeText(modified)
            }
        }

        return changes
    }

    private fun migrateAccessTransformerLine(line: String): String {
        val entry = normalizeAccessTransformerEntry(line) ?: return line
        val migrated = migrateMappedAccessTransformerEntry(entry) ?: when (entry) {
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73379_" ->
                "protected net.minecraft.world.level.levelgen.structure.StructurePiece rotation"
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73378_" ->
                "protected net.minecraft.world.level.levelgen.structure.StructurePiece mirror"
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73377_" ->
                "protected net.minecraft.world.level.levelgen.structure.StructurePiece orientation"
            "public net.minecraft.world.entity.LivingEntity m_21275_(Lnet/minecraft/world/damagesource/DamageSource;)Z" ->
                "public net.minecraft.world.entity.LivingEntity isDamageSourceBlocked(Lnet/minecraft/world/damagesource/DamageSource;)Z"
            "protected net.minecraft.world.entity.animal.Sheep m_29823_(Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/animal/Animal;)Lnet/minecraft/world/item/DyeColor;" ->
                "protected net.minecraft.world.entity.animal.Sheep getOffspringColor(Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/animal/Animal;)Lnet/minecraft/world/item/DyeColor;"
            "public net.minecraft.world.entity.LivingEntity m_21278_(Lnet/minecraft/world/item/ItemStack;)V" ->
                "public net.minecraft.world.entity.LivingEntity breakItem(Lnet/minecraft/world/item/ItemStack;)V"
            "public net.minecraft.client.model.HumanoidModel m_102875_(Lnet/minecraft/world/entity/LivingEntity;)V" ->
                "public net.minecraft.client.model.HumanoidModel poseRightArm(Lnet/minecraft/world/entity/LivingEntity;)V"
            "public net.minecraft.client.model.HumanoidModel m_102878_(Lnet/minecraft/world/entity/LivingEntity;)V" ->
                "public net.minecraft.client.model.HumanoidModel poseLeftArm(Lnet/minecraft/world/entity/LivingEntity;)V"
            "public net.minecraft.client.model.HumanoidModel m_102856_(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/entity/HumanoidArm;" ->
                "public net.minecraft.client.model.HumanoidModel getAttackArm(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/entity/HumanoidArm;"
            "public net.minecraft.world.entity.ai.goal.GoalSelector f_25345_" ->
                "public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals"
            "public net.minecraft.world.level.block.state.BlockBehaviour f_60442_" ->
                "public net.minecraft.world.level.block.state.BlockBehaviour material"
            "public net.minecraft.world.level.block.FireBlock m_53444_(Lnet/minecraft/world/level/block/Block;II)V" ->
                "public net.minecraft.world.level.block.FireBlock setFlammable(Lnet/minecraft/world/level/block/Block;II)V"
            "public net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77894_" ->
                "public net.minecraft.world.level.saveddata.maps.MapItemSavedData decorations"
            "public net.minecraft.client.multiplayer.ClientAdvancements f_104390_" ->
                "public net.minecraft.client.multiplayer.ClientAdvancements progress"
            "public net.minecraft.world.entity.Entity f_19815_" ->
                "public net.minecraft.world.entity.Entity dimensions"
            "public net.minecraft.world.entity.Mob m_21424_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V" ->
                "public net.minecraft.world.entity.Mob maybeDisableShield(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"
            "public net.minecraft.client.gui.Gui f_92980_" ->
                "public net.minecraft.client.gui.Gui vignetteBrightness"
            "public net.minecraft.world.entity.ai.control.MoveControl f_24981_" ->
                "public net.minecraft.world.entity.ai.control.MoveControl operation"
            "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70263_" ->
                "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer baseHeight"
            "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70264_" ->
                "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer heightRandA"
            "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70265_" ->
                "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer heightRandB"
            "public net.minecraft.world.entity.animal.Parrot f_29358_" ->
                "public net.minecraft.world.entity.animal.Parrot MOB_SOUND_MAP"
            "public net.minecraft.entity.ai.brain.task.GiveHeroGiftsTask field_220403_a" ->
                "public net.minecraft.world.entity.ai.behavior.GiveGiftToHero GIFTS"
            "public net.minecraft.world.level.block.ComposterBlock m_51920_(FLnet/minecraft/world/level/ItemLike;)V" ->
                "public net.minecraft.world.level.block.ComposterBlock add(FLnet/minecraft/world/level/ItemLike;)V"
            "public-f net.minecraft.world.item.AxeItem f_150683_" ->
                "public-f net.minecraft.world.item.AxeItem STRIPPABLES"
            "public-f net.minecraft.world.entity.player.Inventory f_35978_" ->
                "public-f net.minecraft.world.entity.player.Inventory player"
            "public net.minecraft.world.level.BaseSpawner f_45451_" ->
                "public net.minecraft.world.level.BaseSpawner maxNearbyEntities"
            "public net.minecraft.world.level.BaseSpawner f_45449_" ->
                "public net.minecraft.world.level.BaseSpawner spawnCount"
            "public net.minecraft.world.level.BaseSpawner f_45453_" ->
                "public net.minecraft.world.level.BaseSpawner spawnRange"
            "public net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration f_159157_" ->
                "public net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration floorLevel"
            "public net.minecraft.world.level.block.FlowerPotBlock m_153267_()Z" ->
                "public net.minecraft.world.level.block.FlowerPotBlock isEmpty()Z"
            "public net.minecraft.server.level.ChunkMap m_183719_()Lnet/minecraft/world/level/chunk/ChunkGenerator;" ->
                "public net.minecraft.server.level.ChunkMap generator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            "public net.minecraft.world.level.biome.Biome f_47435_" ->
                "public net.minecraft.world.level.biome.Biome TEMPERATURE_NOISE"
            "public-f net.minecraft.world.level.biome.BiomeSource f_47891_" ->
                "public-f net.minecraft.world.level.biome.BiomeSource possibleBiomes"
            "public net.minecraft.world.entity.decoration.Painting m_218891_(Lnet/minecraft/core/Holder;)V" ->
                "public net.minecraft.world.entity.decoration.Painting setVariant(Lnet/minecraft/core/Holder;)V"
            "public net.minecraft.world.level.block.DispenserBlock f_52661_" ->
                "public net.minecraft.world.level.block.DispenserBlock DISPENSER_REGISTRY"
            "public net.minecraft.client.gui.Gui m_93024_(Lnet/minecraft/world/phys/HitResult;)Z" ->
                "public net.minecraft.client.gui.Gui canRenderCrosshairForSpectator(Lnet/minecraft/world/phys/HitResult;)Z"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77885_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerX"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77886_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerZ"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77897_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData bannerMarkers"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77898_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData frameMarkers"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_181308_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData trackedDecorationCount"
            "public net.minecraft.world.level.levelgen.feature.TreeFeature m_225251_(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;)Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape;" ->
                "public net.minecraft.world.level.levelgen.feature.TreeFeature updateLeaves(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;)Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape;"
            "public net.minecraft.world.level.levelgen.Heightmap m_64245_(III)V" ->
                "public net.minecraft.world.level.levelgen.Heightmap setHeight(III)V"
            "public net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator f_64316_" ->
                "public net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator defaultBlock"
            "public net.minecraft.world.level.levelgen.carver.WorldCarver m_159423_(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;)Z" ->
                "public net.minecraft.world.level.levelgen.carver.WorldCarver isDebugEnabled(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;)Z"
            "public net.minecraft.world.level.levelgen.carver.WorldCarver m_159418_(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/Aquifer;)Lnet/minecraft/world/level/block/state/BlockState;" ->
                "public net.minecraft.world.level.levelgen.carver.WorldCarver getCarveState(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/Aquifer;)Lnet/minecraft/world/level/block/state/BlockState;"
            "protected net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor f_74075_" ->
                "protected net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor integrity"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_164290_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise mainNoise"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_164289_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise maxLimitNoise"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_164288_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise minLimitNoise"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_192799_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise xzScale"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_192800_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise yScale"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_230458_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise xzFactor"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_230459_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise yFactor"
            "public net.minecraft.server.level.ServerLevel m_143288_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;" ->
                "public net.minecraft.server.level.ServerLevel findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
            "public net.minecraft.client.renderer.LevelRenderer f_109450_" ->
                "public net.minecraft.client.renderer.LevelRenderer rainSoundTime"
            "public net.minecraft.client.renderer.LevelRenderer f_109472_" ->
                "public net.minecraft.client.renderer.LevelRenderer skyBuffer"
            "public net.minecraft.client.renderer.LevelRenderer f_109473_" ->
                "public net.minecraft.client.renderer.LevelRenderer darkBuffer"
            "public net.minecraft.world.entity.projectile.FishingHook f_37089_" ->
                "public net.minecraft.world.entity.projectile.FishingHook nibble"
            "public net.minecraft.world.entity.projectile.FishingHook f_37094_" ->
                "public net.minecraft.world.entity.projectile.FishingHook hookedIn"
            "protected-f net.minecraft.world.entity.projectile.FishingHook f_37096_" ->
                "protected-f net.minecraft.world.entity.projectile.FishingHook luck"
            "protected-f net.minecraft.world.entity.projectile.FishingHook f_37097_" ->
                "protected-f net.minecraft.world.entity.projectile.FishingHook lureSpeed"
            "public net.minecraft.world.inventory.ItemCombinerMenu f_39769_" ->
                "public net.minecraft.world.inventory.ItemCombinerMenu inputSlots"
            "public net.minecraft.world.inventory.ItemCombinerMenu f_39770_" ->
                "public net.minecraft.world.inventory.ItemCombinerMenu access"
            "public net.minecraft.world.level.block.state.properties.IntegerProperty f_223001_" ->
                "public net.minecraft.world.level.block.state.properties.IntegerProperty max"
            "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210560_" ->
                "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"
            "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210559_" ->
                "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"
            "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210560_" ->
                "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"
            "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210559_" ->
                "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"
            "public net.minecraft.world.level.block.entity.BrushableBlockEntity f_276563_" ->
                "public net.minecraft.world.level.block.entity.BrushableBlockEntity item"
            "public net.minecraft.world.entity.item.PrimedTnt f_32072_" ->
                "public net.minecraft.world.entity.item.PrimedTnt owner"
            "public net.minecraft.world.level.block.SaplingBlock f_55975_" ->
                "public net.minecraft.world.level.block.SaplingBlock treeGrower"
            "public net.minecraft.client.resources.model.ModelBakery f_119234_" ->
                "public net.minecraft.client.resources.model.ModelBakery UNREFERENCED_TEXTURES"
            "public net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder f_192778_" ->
                "public net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder pieces"
            "public net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator\$Context f_226046_" ->
                "public net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator\$Context decorationSetter"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_256718_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerX"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_256789_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerZ"
            "public net.minecraft.world.effect.MobEffectInstance f_19504_" ->
                "public net.minecraft.world.effect.MobEffectInstance amplifier"
            "public net.minecraft.world.level.block.entity.SignBlockEntity f_276598_" ->
                "public net.minecraft.world.level.block.entity.SignBlockEntity frontText"
            "public net.minecraft.world.level.biome.BiomeManager f_47863_" ->
                "public net.minecraft.world.level.biome.BiomeManager biomeZoomSeed"
            "public net.minecraft.data.models.ItemModelGenerators f_265952_" ->
                "public net.minecraft.data.models.ItemModelGenerators GENERATED_TRIM_MODELS"
            "public net.minecraft.world.level.chunk.ChunkGenerator m_223138_(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/RandomState;)Ljava/util/List;" ->
                "public net.minecraft.world.level.chunk.ChunkGenerator getPlacementsForStructure(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/RandomState;)Ljava/util/List;"
            "public net.minecraft.world.level.chunk.ChunkGenerator m_223104_(Lnet/minecraft/world/level/levelgen/structure/StructureSet\$StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z" ->
                "public net.minecraft.world.level.chunk.ChunkGenerator tryGenerateStructure(Lnet/minecraft/world/level/levelgen/structure/StructureSet\$StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z"
            "public net.minecraft.world.level.storage.loot.LootTable m_230924_(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V" ->
                "public net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V"
            "public net.minecraft.client.Camera m_90568_(DDD)V" ->
                "public net.minecraft.client.Camera move(FFF)V"
            "public net.minecraft.client.Camera m_90572_(FF)V" ->
                "public net.minecraft.client.Camera setRotation(FF)V"
            "public net.minecraft.client.Camera m_90566_(D)D" ->
                "public net.minecraft.client.Camera getMaxZoom(F)F"
            "public net.minecraft.client.renderer.entity.EntityRenderDispatcher f_114363_" ->
                "public net.minecraft.client.renderer.entity.EntityRenderDispatcher playerRenderers"
            "public net.minecraft.client.renderer.entity.EntityRenderDispatcher f_114362_" ->
                "public net.minecraft.client.renderer.entity.EntityRenderDispatcher renderers"
            "public net.minecraft.client.renderer.entity.player.PlayerRenderer m_117775_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V" ->
                "public net.minecraft.client.renderer.entity.player.PlayerRenderer renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V"
            else -> entry
        }
        // Entries outside the explicit legacy table resolve through the MCPConfig + Mojang mapping
        // table; an SRG id without a Mojang name on that owner is left for target validation.
        val resolved = if (migrated == entry)
            com.modporter.mapping.SrgMemberNames.migrateAccessTransformerEntry(entry) ?: entry else migrated
        val finalized = finalizeAccessTransformerEntry(resolved) ?: return ""
        if (finalized == entry && resolved == entry) return line

        val comment = line.substringAfter("#", "").trim()
        return if (comment.isNotEmpty()) "$finalized # $comment" else finalized
    }

    private data class AccessTransformerMemberMapping(
        val owner: String,
        val sourceMember: String,
        val sourceDescriptor: String,
        val targetOwner: String,
        val targetMember: String,
        val targetDescriptor: String
    )

    private fun loadAccessTransformerMemberMappings(): Map<String, AccessTransformerMemberMapping> {
        val resource = "$mappingsPrefix/access-transformer-members.json"
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing access transformer member mapping resource: $resource")
        val root = Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
        return root.getValue("mappings").jsonArray.associate { element ->
            val item = element.jsonObject
            val owner = item.getValue("owner").jsonPrimitive.content
            val sourceMember = item.getValue("sourceMember").jsonPrimitive.content
            val sourceDescriptor = item["sourceDescriptor"]?.jsonPrimitive?.content.orEmpty()
            val targetOwner = item["targetOwner"]?.jsonPrimitive?.content ?: owner
            val targetMember = item.getValue("targetMember").jsonPrimitive.content
            val targetDescriptor = item["targetDescriptor"]?.jsonPrimitive?.content ?: sourceDescriptor
            val mapping = AccessTransformerMemberMapping(
                owner,
                sourceMember,
                sourceDescriptor,
                targetOwner,
                targetMember,
                targetDescriptor
            )
            accessTransformerMappingKey(owner, sourceMember, sourceDescriptor) to mapping
        }
    }

    private fun migrateMappedAccessTransformerEntry(entry: String): String? {
        val parsed = Regex(
            """^(\S+)\s+(\S+)\s+([A-Za-z_$][\w$]*)(\(.*)?$"""
        ).matchEntire(entry) ?: return null
        val access = parsed.groupValues[1]
        val owner = parsed.groupValues[2]
        val member = parsed.groupValues[3]
        val descriptor = parsed.groupValues[4]
        val mapping = accessTransformerMemberMappings[
            accessTransformerMappingKey(owner, member, descriptor)
        ] ?: return null
        return buildString {
            append(access).append(' ').append(mapping.targetOwner).append(' ').append(mapping.targetMember)
            append(mapping.targetDescriptor)
        }
    }

    private fun accessTransformerMappingKey(owner: String, member: String, descriptor: String): String =
        "$owner#$member#$descriptor"

    private fun finalizeAccessTransformerEntry(entry: String): String? {
        val descriptorAdjusted = entry
            .replace(
                "net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/Codec;)V",
                "net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/MapCodec;)V"
            )
            .replace(
                "net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V",
                "net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V"
            )

        return if (AccessTransformerTargets.exists(descriptorAdjusted) == false) AccessTransformerTargets.relocated(descriptorAdjusted) else descriptorAdjusted
    }

    private fun normalizeAccessTransformerEntry(line: String): String? {
        val withoutComment = line.substringBefore("#").trim()
        return withoutComment.ifBlank { null }?.replace(Regex("""\s+"""), " ")
    }

    private fun collectRequiredAccessTransformerEntries(projectDir: Path): Set<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptySet()

        val entries = linkedSetOf<String>()
        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val abstractArrowSubclassFiles = collectJavaFilesExtending(javaFiles, setOf("AbstractArrow", "net.minecraft.world.entity.projectile.AbstractArrow"))
        javaFiles
            .forEach { javaFile ->
                val source = javaFile.readText()
                if (source.contains("nextTickTimeNanos") && Regex("""\.nextTickTimeNanos\s*=""").containsMatchIn(maskJavaCommentsAndLiterals(source))) {
                    entries.add("public net.minecraft.server.MinecraftServer nextTickTimeNanos")
                }
                if (containsGoalSelectorAvailableGoalsAccess(source)) {
                    entries.add("public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals")
                }
                if (containsServerLevelFindLightningTargetCall(source)) {
                    entries.add("public net.minecraft.server.level.ServerLevel findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;")
                }
                if (containsStructureTemplatePoolFieldAccess(source, "templates")) {
                    entries.add("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates")
                }
                if (containsStructureTemplatePoolFieldAccess(source, "rawTemplates")) {
                    entries.add("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates")
                }
                if (containsChunkPendingBlockEntitiesAccess(source)) {
                    entries.add("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities")
                }
                if (containsAbstractArrowFieldAccess(source, "firedFromWeapon")) {
                    entries.add("public net.minecraft.world.entity.projectile.AbstractArrow firedFromWeapon")
                }
                if (containsAbstractArrowMethodCall(source, "setPierceLevel") ||
                    (javaFile in abstractArrowSubclassFiles && containsLocalJavaMethodCall(source, "setPierceLevel"))) {
                    entries.add("public net.minecraft.world.entity.projectile.AbstractArrow setPierceLevel(B)V")
                }
                if (containsCreativeModeInventorySelectedTabAccess(source)) {
                    entries.add("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab")
                }
                if (containsLevelRendererFieldAccess(source, "skyBuffer")) {
                    entries.add("public net.minecraft.client.renderer.LevelRenderer skyBuffer")
                }
                if (containsLevelRendererFieldAccess(source, "darkBuffer")) {
                    entries.add("public net.minecraft.client.renderer.LevelRenderer darkBuffer")
                }
                if (containsLevelRendererFieldAccess(source, "rainSoundTime")) {
                    entries.add("public net.minecraft.client.renderer.LevelRenderer rainSoundTime")
                }
                if (containsEntityRenderDispatcherRenderersAccess(source)) {
                    entries.add("public net.minecraft.client.renderer.entity.EntityRenderDispatcher renderers")
                }
                if (containsDefaultAttributesSuppliersAccess(source)) {
                    entries.add("public net.minecraft.world.entity.ai.attributes.DefaultAttributes SUPPLIERS")
                }
                if (containsServerPlayerInitMenuCall(source)) {
                    entries.add("public net.minecraft.server.level.ServerPlayer initMenu(Lnet/minecraft/world/inventory/AbstractContainerMenu;)V")
                }
            }
        return entries
    }

    private fun containsGoalSelectorAvailableGoalsAccess(source: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.entity\.ai\.goal\.)?GoalSelector""",
            "availableGoals"
        )
    }

    private fun containsServerLevelFindLightningTargetCall(source: String): Boolean {
        return containsTypedJavaMethodCall(
            source,
            """(?:net\.minecraft\.server\.level\.)?ServerLevel""",
            "findLightningTargetAround"
        )
    }

    private fun containsStructureTemplatePoolFieldAccess(source: String, fieldName: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.level\.levelgen\.structure\.pools\.)?StructureTemplatePool""",
            fieldName
        )
    }

    private fun containsChunkPendingBlockEntitiesAccess(source: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.level\.chunk\.)?(?:LevelChunk|ChunkAccess)""",
            "pendingBlockEntities"
        )
    }

    private fun containsAbstractArrowFieldAccess(source: String, fieldName: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.entity\.projectile\.)?AbstractArrow""",
            fieldName
        )
    }

    private fun containsAbstractArrowMethodCall(source: String, methodName: String): Boolean {
        return containsTypedJavaMethodCall(
            source,
            """(?:net\.minecraft\.world\.entity\.projectile\.)?AbstractArrow""",
            methodName
        )
    }

    private fun containsLocalJavaMethodCall(source: String, methodName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return Regex("""(?:\bthis\s*\.\s*)?${Regex.escape(methodName)}\s*\(""")
            .containsMatchIn(code)
    }

    private fun containsCreativeModeInventorySelectedTabAccess(source: String): Boolean {
        return containsJavaStaticFieldAccess(
            source,
            """(?:net\.minecraft\.client\.gui\.screens\.inventory\.)?CreativeModeInventoryScreen""",
            "selectedTab"
        )
    }

    private fun containsLevelRendererFieldAccess(source: String, fieldName: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.client\.renderer\.)?LevelRenderer""",
            fieldName
        )
    }

    private fun containsEntityRenderDispatcherRenderersAccess(source: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.client\.renderer\.entity\.)?EntityRenderDispatcher""",
            "renderers"
        ) ||
            Regex("""\bgetEntityRenderDispatcher\s*\(\s*\)\s*\.\s*renderers\b""").containsMatchIn(code)
    }

    private fun containsDefaultAttributesSuppliersAccess(source: String): Boolean =
        containsJavaStaticFieldAccess(
            source,
            """(?:net\.minecraft\.world\.entity\.ai\.attributes\.)?DefaultAttributes""",
            "SUPPLIERS"
        )

    private fun containsServerPlayerInitMenuCall(source: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        if (!code.contains(".initMenu(")) return false
        if (containsTypedJavaMethodCall(
                code,
                """(?:net\.minecraft\.server\.level\.)?ServerPlayer""",
                "initMenu"
            )) {
            return true
        }
        return Regex("""\b[A-Za-z_$][\w$]*\s*\.\s*player\s*\.\s*initMenu\s*\(""").containsMatchIn(code)
    }

    private fun containsTypedJavaFieldAccess(source: String, typePattern: String, fieldName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        val variables = collectTypedJavaVariables(code, typePattern)
        return variables.any { variable ->
            Regex("""\b${Regex.escape(variable)}\s*\.\s*${Regex.escape(fieldName)}\b""").containsMatchIn(code)
        }
    }

    private fun containsTypedJavaMethodCall(source: String, typePattern: String, methodName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        val variables = collectTypedJavaVariables(code, typePattern)
        return variables.any { variable ->
            Regex("""\b${Regex.escape(variable)}\s*\.\s*${Regex.escape(methodName)}\s*\(""").containsMatchIn(code)
        }
    }

    private fun containsJavaStaticFieldAccess(source: String, ownerTypePattern: String, fieldName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return Regex("""\b(?:$ownerTypePattern)\s*\.\s*${Regex.escape(fieldName)}\b""").containsMatchIn(code)
    }

    private fun collectTypedJavaVariables(code: String, typePattern: String): Set<String> {
        return Regex("""\b(?:$typePattern)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun maskJavaComments(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun maskJavaCommentsAndTextBlocks(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                index + 2 < chars.size && chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    chars[index + 2] = ' '
                    index += 3
                    while (index + 2 < chars.size && !(chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 2 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        chars[index + 2] = ' '
                        index += 3
                    }
                }
                chars[index] == '"' || chars[index] == '\'' -> {
                    val quote = chars[index++]
                    var escaped = false
                    while (index < chars.size) {
                        val current = chars[index++]
                        if (escaped) {
                            escaped = false
                        } else if (current == '\\') {
                            escaped = true
                        } else if (current == quote) {
                            break
                        }
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                index + 2 < chars.size && chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    chars[index + 2] = ' '
                    index += 3
                    while (index + 2 < chars.size && !(chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 2 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        chars[index + 2] = ' '
                        index += 3
                    }
                }
                chars[index] == '"' || chars[index] == '\'' -> {
                    val quote = chars[index]
                    chars[index++] = ' '
                    var escaped = false
                    while (index < chars.size) {
                        val current = chars[index]
                        if (current != '\n' && current != '\r') chars[index] = ' '
                        index++
                        if (escaped) {
                            escaped = false
                        } else if (current == '\\') {
                            escaped = true
                        } else if (current == quote) {
                            break
                        }
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private data class ExecutableRegexMatch(
        val groupValues: List<String>
    )

    private fun replaceExecutableRegex(
        source: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String = replaceMaskedRegex(source, maskJavaCommentsAndLiterals(source), pattern, replacement)

    private fun replaceCommentAndTextBlockMaskedRegex(
        source: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String = replaceMaskedRegex(source, maskJavaCommentsAndTextBlocks(source), pattern, replacement)

    private fun replaceMaskedRegex(
        source: String,
        maskedSource: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String {
        val matches = pattern.findAll(maskedSource).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            val groupValues = (0 until match.groups.size).map { index ->
                match.groups[index]?.range?.let { range ->
                    source.substring(range.first, range.last + 1)
                } ?: ""
            }
            result = result.replaceRange(
                match.range,
                replacement(ExecutableRegexMatch(groupValues))
            )
        }
        return result
    }

    private fun ensureAccessTransformerEntries(
        projectDir: Path,
        requiredEntries: List<String>,
        dryRun: Boolean,
        ruleId: String,
        description: String
    ): List<Change> {
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        val original = if (atFile.exists()) atFile.readText() else ""
        val existing = original.lines().mapNotNull(::normalizeAccessTransformerEntry).toMutableSet()
        val missing = requiredEntries.filter { existing.add(it) }
        if (missing.isEmpty()) return emptyList()

        val separator = System.lineSeparator()
        val modified = buildString {
            append(original.trimEnd())
            if (isNotEmpty()) append(separator).append(separator)
            append(missing.joinToString(separator))
            append(separator)
        }
        if (!dryRun) {
            atFile.parent.createDirectories()
            atFile.writeText(modified)
        }
        return listOf(Change(
            file = atFile,
            line = 1,
            description = description,
            before = "missing access transformer entries",
            after = missing.joinToString(", "),
            confidence = Confidence.HIGH,
            ruleId = ruleId
        ))
    }

    private fun migrateBlockPropertiesNoParticlesOnBreak(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val changedFiles = mutableListOf<Path>()
        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            val executableOriginal = maskJavaCommentsAndLiterals(original)
            if (!executableOriginal.contains(".noParticlesOnBreak()")) continue
            val migrated = rewriteNoParticlesOnBreakCalls(original)
            if (migrated != original) {
                changedFiles.add(javaFile)
                if (!dryRun) {
                    javaFile.writeText(migrated)
                }
            }
        }
        if (changedFiles.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        val helperFile = projectDir.resolve("src/main/java/com/modporter/compat/ModPorterBlockProperties.java")
        if (!helperFile.exists()) {
            changes.add(Change(
                file = helperFile,
                line = 1,
                description = "Add Access-Transformer-backed block properties helper for removed noParticlesOnBreak builder API",
                before = "BlockBehaviour.Properties.noParticlesOnBreak()",
                after = "ModPorterBlockProperties.noParticlesOnBreak(properties)",
                confidence = Confidence.HIGH,
                ruleId = "build-block-properties-no-particles-helper"
            ))
            if (!dryRun) {
                helperFile.parent.createDirectories()
                helperFile.writeText(blockPropertiesHelperSource())
            }
        }
        changes.add(Change(
            file = changedFiles.first(),
            line = 1,
            description = "Rewrite removed noParticlesOnBreak builder calls to an AT-backed helper",
            before = ".noParticlesOnBreak()",
            after = "ModPorterBlockProperties.noParticlesOnBreak(properties)",
            confidence = Confidence.HIGH,
            ruleId = "build-block-properties-no-particles"
        ))
        changes.addAll(ensureAccessTransformerEntries(
            projectDir = projectDir,
            requiredEntries = listOf("public net.minecraft.world.level.block.state.BlockBehaviour\$Properties spawnTerrainParticles"),
            dryRun = dryRun,
            ruleId = "build-block-properties-no-particles-at",
            description = "Expose BlockBehaviour.Properties spawnTerrainParticles through Access Transformers instead of reflection"
        ))
        return changes
    }

    private fun rewriteNoParticlesOnBreakCalls(source: String): String {
        val token = ".noParticlesOnBreak()"
        val executableSource = maskJavaCommentsAndLiterals(source)
        val result = StringBuilder()
        var cursor = 0
        while (cursor < source.length) {
            val tokenIndex = executableSource.indexOf(token, cursor)
            if (tokenIndex < 0) break
            val receiverStart = findFluentReceiverStart(executableSource, tokenIndex)
            if (receiverStart < 0 || receiverStart >= tokenIndex) {
                result.append(source, cursor, tokenIndex + token.length)
                cursor = tokenIndex + token.length
                continue
            }
            val receiver = source.substring(receiverStart, tokenIndex).trim()
            val leading = source.substring(receiverStart, tokenIndex).takeWhile { it.isWhitespace() }
            result.append(source, cursor, receiverStart)
            result.append(leading)
            result.append("com.modporter.compat.ModPorterBlockProperties.noParticlesOnBreak($receiver)")
            cursor = tokenIndex + token.length
        }
        if (cursor == 0) return source
        result.append(source, cursor, source.length)
        return result.toString()
    }

    private fun findFluentReceiverStart(source: String, tokenIndex: Int): Int {
        var index = tokenIndex - 1
        var parenDepth = 0
        var bracketDepth = 0
        while (index >= 0) {
            val char = source[index]
            when (char) {
                ')' -> parenDepth++
                '(' -> {
                    if (parenDepth == 0) return index + 1
                    parenDepth--
                }
                ']' -> bracketDepth++
                '[' -> {
                    if (bracketDepth == 0) return index + 1
                    bracketDepth--
                }
                ',', ';', '{', '}', '=' -> if (parenDepth == 0 && bracketDepth == 0) return index + 1
                '\n', '\r' -> {
                    if (parenDepth == 0 && bracketDepth == 0) {
                        val linePrefix = source.substring(index + 1, tokenIndex)
                        if (!linePrefix.trimStart().startsWith(".")) return index + 1
                    }
                }
            }
            index--
        }
        return 0
    }

    private fun blockPropertiesHelperSource(): String = """
        package com.modporter.compat;

        import net.minecraft.world.level.block.state.BlockBehaviour;

        public final class ModPorterBlockProperties {
            private ModPorterBlockProperties() {
            }

            public static BlockBehaviour.Properties noParticlesOnBreak(BlockBehaviour.Properties properties) {
                properties.spawnTerrainParticles = false;
                return properties;
            }
        }
    """.trimIndent()

    private fun configureAccessTransformers(projectDir: Path, dryRun: Boolean): List<Change> {
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        if (!atFile.exists()) return emptyList()

        val buildFiles = listOf(projectDir.resolve("build.gradle"), projectDir.resolve("build.gradle.kts"))
            .filter { it.exists() }
        val changes = mutableListOf<Change>()
        for (buildFile in buildFiles) {
            val original = buildFile.readText()
            if (original.contains("accessTransformers")) continue

            val isKts = buildFile.fileName.toString().endsWith(".kts")
            val insertion = if (isKts) {
                """
    validateAccessTransformers = true
    accessTransformers.files.setFrom("src/main/resources/META-INF/accesstransformer.cfg")

                """.trimIndent()
            } else {
                """
    validateAccessTransformers = true
    accessTransformers {
        file('src/main/resources/META-INF/accesstransformer.cfg')
    }

                """.trimIndent()
            }

            val neoForgeMatch = Regex("""(?m)^(\s*neoForge\s*\{\s*\r?\n)""").find(original)
            val modified = if (neoForgeMatch != null) {
                val insertAt = neoForgeMatch.range.last + 1
                original.substring(0, insertAt) + insertion + original.substring(insertAt)
            } else {
                val block = if (isKts) {
                    """

neoForge {
$insertion}
                    """.trimIndent()
                } else {
                    """

neoForge {
$insertion}
                    """.trimIndent()
                }
                original.trimEnd() + System.lineSeparator() + block + System.lineSeparator()
            }
            if (modified != original) {
                changes.add(Change(
                    file = buildFile,
                    line = 1,
                    description = "Wire Access Transformers into ModDevGradle",
                    before = "neoForge block without accessTransformers",
                    after = "validateAccessTransformers plus accessTransformers cfg",
                    confidence = Confidence.HIGH,
                    ruleId = "build-access-transformer-config"
                ))
                if (!dryRun) buildFile.writeText(modified)
            }
        }
        return changes
    }

    /**
     * Reflection that names a Forge 1.20.1 SRG member id (`"f_91078_"`) runs against Mojang names
     * on NeoForge, so the literal becomes the Mojang member the MCPConfig + Mojang mapping table
     * declares on that owner, when the 1.21.1 class table still has that member. Ids without a
     * mapping or without a surviving member stay for the hard gate below.
     */
    private fun migrateSrgReflectionLiterals(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val changes = mutableListOf<Change>()
        val importPattern = Regex("""(?m)^\s*import\s+(?:static\s+)?([\w.]+)\.(\w+)\s*;""")
        val wildcardImportPattern = Regex("""(?m)^\s*import\s+([\w.]+)\.\*\s*;""")
        val packagePattern = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
        val calls = listOf(
            Regex("""\b([A-Za-z_][\w.]*)\.class\s*\.\s*(?:getDeclaredField|getField)\s*\(\s*"([fm]_\d+_)"""") to "field",
            Regex("""\b([A-Za-z_][\w.]*)\.class\s*\.\s*(?:getDeclaredMethod|getMethod)\s*\(\s*"([fm]_\d+_)"""") to "method",
            Regex("""\bObfuscationReflectionHelper\s*\.\s*(?:findField|getPrivateValue|setPrivateValue)\s*\(\s*([A-Za-z_][\w.]*)\.class\s*,(?:[^;"]*,)?\s*"([fm]_\d+_)"""") to "field",
            Regex("""\bObfuscationReflectionHelper\s*\.\s*findMethod\s*\(\s*([A-Za-z_][\w.]*)\.class\s*,\s*"([fm]_\d+_)"""") to "method"
        )
        java.nio.file.Files.walk(srcDir).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.forEach { javaFile ->
                val raw = javaFile.readText()
                if (!Regex("""["'][fm]_\d+_["']""").containsMatchIn(raw)) return@forEach
                val imports = importPattern.findAll(raw).associate { it.groupValues[2] to "${it.groupValues[1]}.${it.groupValues[2]}" }
                val wildcardImports = wildcardImportPattern.findAll(raw).map { it.groupValues[1] }.toList()
                val packageName = packagePattern.find(raw)?.groupValues?.get(1)
                val edits = mutableListOf<Triple<IntRange, String, String>>()
                for ((pattern, kind) in calls) {
                    for (match in pattern.findAll(raw)) {
                        val owner = resolveReflectedOwner(match.groupValues[1], imports, wildcardImports, packageName) ?: continue
                        val srg = match.groupValues[2]
                        val mojang = if (kind == "field") com.modporter.mapping.SrgMemberNames.field(owner, srg)
                            else com.modporter.mapping.SrgMemberNames.method(owner, srg)
                        if (mojang == null) continue
                        val survives = com.modporter.mapping.TargetMembers.hasClass(owner) &&
                            (if (kind == "field") com.modporter.mapping.TargetMembers.hasField(owner, mojang)
                            else com.modporter.mapping.TargetMembers.hasMethod(owner, mojang))
                        if (!survives) continue
                        edits += Triple(match.groups[2]!!.range, srg, "$owner#$mojang")
                    }
                }
                if (edits.isEmpty()) return@forEach
                var modified = raw
                edits.sortedByDescending { it.first.first }.forEach { (range, _, target) ->
                    modified = modified.replaceRange(range, target.substringAfter('#'))
                }
                if (!dryRun) javaFile.writeText(modified)
                edits.forEach { (range, srg, target) ->
                    changes.add(Change(
                        file = javaFile,
                        line = raw.lineNumberAt(range.first),
                        description = "Reflect the Mojang member name instead of the Forge SRG id",
                        before = "\"$srg\"",
                        after = "\"${target.substringAfter('#')}\" ($target)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-srg-reflection-names"
                    ))
                }
            }
        }
        return changes
    }

    private fun removeReobfArtifactBlocks(content: String): String {
        var result = content
        val artifact = Regex("""(?m)^[ \t]*artifact\s*\(\s*(?:tasks\.)?reobfJar\s*\)\s*\{""")
        while (true) {
            val match = artifact.find(result) ?: break
            val openBrace = result.indexOf('{', match.range.first)
            val closeBrace = findMatchingBrace(result, openBrace)
            if (closeBrace <= openBrace) break
            var end = closeBrace + 1
            while (end < result.length && (result[end] == ' ' || result[end] == '\t')) end++
            if (end < result.length && result[end] == '\r') end++
            if (end < result.length && result[end] == '\n') end++
            result = result.removeRange(match.range.first, end)
        }
        return result
    }

    private fun detectForbiddenReflection(projectDir: Path): List<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        // Reflection is ordinary Java and survives the port unchanged. What cannot survive are
        // Forge 1.20.1 SRG member ids (runtime names on NeoForge are the Mojang names), Forge
        // class names, and members that no longer exist on the NeoForge 1.21.1 classes. The last
        // check is made against the real target class table, not a hand-written list.
        val memberCalls = listOf(
            Regex("""\b([A-Za-z_][\w.]*)\.class\s*\.\s*(?:getDeclaredField|getField)\s*\(""") to "field",
            Regex("""\b([A-Za-z_][\w.]*)\.class\s*\.\s*(?:getDeclaredMethod|getMethod)\s*\(""") to "method",
            Regex("""\bObfuscationReflectionHelper\s*\.\s*(?:findField|getPrivateValue|setPrivateValue)\s*\(\s*([A-Za-z_][\w.]*)\.class\s*,""") to "field",
            Regex("""\bObfuscationReflectionHelper\s*\.\s*findMethod\s*\(\s*([A-Za-z_][\w.]*)\.class\s*,""") to "method",
            Regex("""\b(Class)\s*\.\s*forName\s*\(""") to "class"
        )
        val importPattern = Regex("""(?m)^\s*import\s+(?:static\s+)?([\w.]+)\.(\w+)\s*;""")
        val wildcardImportPattern = Regex("""(?m)^\s*import\s+([\w.]+)\.\*\s*;""")
        val packagePattern = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
        val errors = mutableListOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val raw = javaFile.readText()
                val code = maskJavaCommentsAndLiterals(javaFile.readText())
                val relative = projectDir.relativize(javaFile).toString().replace('\\', '/')
                val imports = importPattern.findAll(raw).associate { it.groupValues[2] to "${it.groupValues[1]}.${it.groupValues[2]}" }
                val wildcardImports = wildcardImportPattern.findAll(raw).map { it.groupValues[1] }.toList()
                val packageName = packagePattern.find(raw)?.groupValues?.get(1)
                var offset = 0
                code.lines().forEachIndexed { index, line ->
                    val lineNumber = index + 1
                    val lineStart = code.indexOf(line, offset).coerceAtLeast(offset)
                    val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
                    val permittedLegacyRanges = mutableMapOf<String, MutableList<IntRange>>()
                    for ((pattern, kind) in memberCalls) {
                        for (match in pattern.findAll(line)) {
                            val literalStart = raw.indexOf('"', lineStart + match.range.last)
                            val literalEnd = if (literalStart >= 0) raw.indexOf('"', literalStart + 1) else -1
                            if (literalStart < 0 || literalEnd < 0 || literalStart >= lineEnd) continue
                            val member = raw.substring(literalStart + 1, literalEnd)
                            if (kind == "class") {
                                if (member.startsWith("net.minecraftforge.")) {
                                    errors.add("Forbidden reflection in $relative:$lineNumber: Forge class name literal $member")
                                }
                                continue
                            }
                            if (com.modporter.mapping.SrgMemberNames.isSrgName(member)) {
                                errors.add("Forbidden reflection in $relative:$lineNumber: unmapped Forge SRG member id $member")
                                continue
                            }
                            val owner = resolveReflectedOwner(match.groupValues[1], imports, wildcardImports, packageName)
                                ?: continue
                            if (!com.modporter.mapping.TargetMembers.isPlatformClass(owner)) {
                                if (kind == "field" && !match.value.contains("ObfuscationReflectionHelper")) {
                                    val label = if (match.value.contains("getDeclaredField")) {
                                        "getDeclaredField"
                                    } else {
                                        "getField"
                                    }
                                    permittedLegacyRanges.getOrPut(label) { mutableListOf() }.add(match.range)
                                }
                                continue
                            }
                            val exists = when {
                                !com.modporter.mapping.TargetMembers.hasClass(owner) -> false
                                kind == "field" -> com.modporter.mapping.TargetMembers.hasField(owner, member)
                                else -> com.modporter.mapping.TargetMembers.hasMethod(owner, member)
                            }
                            if (!exists) {
                                errors.add("Forbidden reflection in $relative:$lineNumber: $owner#$member does not exist on NeoForge 1.21.1")
                            } else {
                                val labels = when {
                                    match.value.contains("ObfuscationReflectionHelper") ->
                                        listOf("ObfuscationReflectionHelper")
                                    kind == "field" && match.value.contains("getDeclaredField") ->
                                        listOf("getDeclaredField")
                                    kind == "method" && match.value.contains("getDeclaredMethod") ->
                                        listOf("getDeclaredMethod")
                                    kind == "method" -> listOf("getMethod")
                                    else -> emptyList()
                                }
                                labels.forEach { label ->
                                    permittedLegacyRanges.getOrPut(label) { mutableListOf() }.add(match.range)
                                }
                            }
                        }
                    }

                    fun isPermittedLegacyMatch(label: String, range: IntRange): Boolean =
                        permittedLegacyRanges[label].orEmpty().any { range.first in it }

                    val memberLookupLabels = setOf(
                        "ObfuscationReflectionHelper",
                        "getDeclaredField",
                        "getDeclaredMethod",
                        "getMethod"
                    )
                    val hasForbiddenMemberLookup = LegacyReflectionSyntax.forbiddenPatterns.any { (label, pattern) ->
                        label in memberLookupLabels &&
                            pattern.findAll(line).any { !isPermittedLegacyMatch(label, it.range) }
                    }
                    val reportedLabels = mutableSetOf<String>()
                    for ((label, pattern) in LegacyReflectionSyntax.forbiddenPatterns) {
                        for (match in pattern.findAll(line)) {
                            val permitted = when (label) {
                                "ObfuscationReflectionHelper",
                                "getDeclaredField",
                                "getDeclaredMethod",
                                "getMethod" -> isPermittedLegacyMatch(label, match.range)
                                // Class.forName is valid for optional non-Forge integrations, and
                                // java.lang.reflect Field remains valid for target members proven above.
                                "Class.forName",
                                "java.lang.reflect member access" -> true
                                // A separate setAccessible call is valid after a proven field lookup.
                                // Chained access on a forbidden lookup remains a hard-gate diagnostic.
                                "setAccessible" -> !hasForbiddenMemberLookup
                                else -> false
                            }
                            if (!permitted && reportedLabels.add(label)) {
                                errors.add("Forbidden reflection in $relative:$lineNumber: $label")
                            }
                        }
                    }
                    offset = lineStart + line.length
                }
            }
        return errors
    }

    private fun resolveReflectedOwner(
        reference: String,
        imports: Map<String, String>,
        wildcardImports: List<String>,
        packageName: String?
    ): String? {
        val parts = reference.split('.')
        if (parts.any { it.isEmpty() }) return null
        if (parts.size > 1 && parts[0][0].isLowerCase()) {
            val classIndex = parts.indexOfFirst { it[0].isUpperCase() }
            if (classIndex < 0) return null
            return parts.subList(0, classIndex + 1).joinToString(".") + parts.drop(classIndex + 1).joinToString("") { "$$it" }
        }
        val outer = parts[0]
        val nested = parts.drop(1).joinToString("") { "$$it" }
        imports[outer]?.let { return it + nested }
        wildcardImports.map { "$it.$outer" }.firstOrNull { com.modporter.mapping.TargetMembers.hasClass(it) }?.let { return it + nested }
        return if (packageName != null) "$packageName.$outer$nested" else outer + nested
    }

    /**
     * ForgeGradle lets a script add mod sources to a single run with a top-level
     * `minecraft.runs.<run> { mods { <id> { source <sourceSet> } } }` statement. ModDev declares
     * mods under `neoForge.mods` and every run loads all declared mods unless `loadedMods` narrows
     * them, so the other runs keep their original selection explicitly.
     */
    private fun migrateTopLevelRunModSources(
        content: String,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        file: Path
    ): String {
        var result = content
        val statement = Regex("""(?m)^[ \t]*minecraft\s*\.\s*runs\s*\.\s*(\w+)\s*\{""")
        var searchFrom = 0
        while (true) {
            val match = statement.find(result, searchFrom) ?: break
            val runName = match.groupValues[1]
            val braceStart = result.indexOf('{', match.range.first)
            val blockEnd = findMatchingBrace(result, braceStart)
            if (blockEnd <= braceStart) break
            val body = result.substring(braceStart + 1, blockEnd)
            val modsMatch = Regex("""\bmods\s*\{""").find(body)
            val modsBraceStart = modsMatch?.let { body.indexOf('{', it.range.first) } ?: -1
            val modsEnd = if (modsBraceStart >= 0) findMatchingBrace(body, modsBraceStart) else -1
            val remainder = if (modsMatch != null && modsEnd > modsBraceStart)
                (body.substring(0, modsMatch.range.first) + body.substring(modsEnd + 1)).trim() else body.trim()
            val entryPattern = Regex("""(\w+)\s*\{\s*source\s+([^\s{}]+)\s*\}""")
            val modsBody = if (modsMatch != null && modsEnd > modsBraceStart) body.substring(modsBraceStart + 1, modsEnd) else ""
            val entries = entryPattern.findAll(modsBody).map { it.groupValues[1] to it.groupValues[2] }.toList()
            val leftover = entryPattern.replace(modsBody, "").trim()
            if (modsMatch == null || remainder.isNotEmpty() || entries.isEmpty() || leftover.isNotEmpty()) {
                errors.add("Unsupported top-level run configuration 'minecraft.runs.$runName' in ${file.fileName}:" +
                    "${result.lineNumberAt(match.range.first)}; only mods { <id> { source <sourceSet> } } entries are migrated")
                searchFrom = blockEnd + 1
                continue
            }
            val excluded = entries.joinToString(" && ") { "it.name != '${it.first}'" }
            val replacement = buildString {
                append("neoForge {\n")
                append("    mods {\n")
                entries.forEach { (id, source) ->
                    append("        $id {\n")
                    append("            sourceSet($source)\n")
                    append("        }\n")
                }
                append("    }\n")
                append("    // Only the '$runName' run loaded these mod sources under ForgeGradle.\n")
                append("    runs.configureEach { run ->\n")
                append("        if (run.name != '$runName') run.loadedMods.set(neoForge.mods.matching { $excluded })\n")
                append("    }\n")
                append("}")
            }
            val original = result.substring(match.range.first, blockEnd + 1)
            changes.add(Change(
                file = file,
                line = result.lineNumberAt(match.range.first),
                description = "Declare run-scoped mod sources through neoForge.mods and loadedMods",
                before = original.trim(),
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-run-mod-sources"
            ))
            result = result.substring(0, match.range.first) + replacement + result.substring(blockEnd + 1)
            searchFrom = match.range.first + replacement.length
        }
        return result
    }

    private fun migrateCoremodScripts(projectDir: Path, dryRun: Boolean): List<Change> {
        val asmDir = projectDir.resolve("src/main/resources/META-INF/asm")
        if (!asmDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(asmDir)
            .filter { it.toString().endsWith(".js") }
            .forEach { script ->
                val original = script.readText()
                val modified = original.replace(
                    "net.minecraftforge.coremod.api.ASMAPI",
                    "net.neoforged.coremod.api.ASMAPI"
                )
                if (modified != original) {
                    changes.add(Change(
                        file = script,
                        line = 1,
                        description = "Migrate Forge coremod ASMAPI scripts to NeoForge coremods API",
                        before = "net.minecraftforge.coremod.api.ASMAPI",
                        after = "net.neoforged.coremod.api.ASMAPI",
                        confidence = Confidence.HIGH,
                        ruleId = "build-coremod-asmapi-neoforge"
                    ))
                    if (!dryRun) script.writeText(modified)
                }
            }
        return changes
    }

    private fun detectLegacyCoremodApiReferences(projectDir: Path): List<String> {
        val asmDir = projectDir.resolve("src/main/resources/META-INF/asm")
        if (!asmDir.exists()) return emptyList()

        val errors = mutableListOf<String>()
        java.nio.file.Files.walk(asmDir)
            .filter { it.toString().endsWith(".js") }
            .forEach { script ->
                script.readLines().forEachIndexed { index, line ->
                    if (line.contains("net.minecraftforge.coremod.api.ASMAPI")) {
                        val relative = projectDir.relativize(script).toString().replace('\\', '/')
                        errors.add("Legacy Forge coremod ASMAPI reference in $relative:${index + 1}")
                    }
                }
            }
        return errors
    }

    private data class AnimalSpawnPlacementTarget(val fieldName: String, val entityClass: String)

    private fun migrateAnimalSpawnPlacements(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()

        val changes = mutableListOf<Change>()
        val registries = mutableListOf<String>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (original.contains("RegisterSpawnPlacementsEvent") &&
                Regex("""\bstatic\s+void\s+registerSpawnPlacements\s*\(""").containsMatchIn(original)
            ) {
                registries.add(fullyQualifiedJavaClassName(javaFile, original))
                continue
            }
            if (!original.contains("EntityType.Builder.of") || !original.contains("MobCategory.CREATURE")) continue
            if (original.contains("registerSpawnPlacements(")) continue

            val targets = findAnimalSpawnPlacementTargets(original, javaFiles)
            if (targets.isEmpty()) continue

            var modified = original
            modified = ensureJavaImport(modified, "net.minecraft.world.entity.SpawnPlacementTypes")
            modified = ensureJavaImport(modified, "net.minecraft.world.entity.animal.Animal")
            modified = ensureJavaImport(modified, "net.minecraft.world.level.levelgen.Heightmap")
            modified = ensureJavaImport(modified, "net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent")

            val body = targets.joinToString("\n") { target ->
                "        event.register(${target.fieldName}.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Animal::checkAnimalSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);"
            }
            val method = """

    public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
$body
    }
""".trimEnd()

            val insertAt = modified.lastIndexOf('}')
            if (insertAt < 0) continue
            modified = modified.substring(0, insertAt) + method + "\n" + modified.substring(insertAt)

            val registryName = fullyQualifiedJavaClassName(javaFile, modified)
            registries.add(registryName)
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Register animal spawn placement predicates on the NeoForge mod bus",
                before = "MobCategory.CREATURE entity without RegisterSpawnPlacementsEvent handler",
                after = "RegisterSpawnPlacementsEvent with Animal::checkAnimalSpawnRules",
                confidence = Confidence.HIGH,
                ruleId = "build-entity-animal-spawn-placement"
            ))
            if (!dryRun) javaFile.writeText(modified)
        }

        if (registries.isEmpty()) return changes
        val modFiles = javaFiles.filter { file ->
            val text = file.readText()
            text.contains("@Mod(") && text.contains("IEventBus")
        }
        for (modFile in modFiles) {
            var modified = modFile.readText()
            val original = modified
            val eventBusNames = Regex("""\bIEventBus\s+([A-Za-z_$][\w$]*)\s*=""")
                .findAll(modified)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            for (registryName in registries.distinct()) {
                if (modified.contains("$registryName::registerSpawnPlacements")) continue
                for (eventBusName in eventBusNames) {
                    val listenerLine = "$eventBusName.addListener($registryName::registerSpawnPlacements);"
                    val inserted = insertModBusLineAfterBusDeclaration(modified, eventBusName, listenerLine)
                    if (inserted != null) {
                        modified = inserted
                        break
                    }
                }
            }
            if (modified != original) {
                changes.add(Change(
                    file = modFile,
                    line = 0,
                    description = "Register animal spawn placement listener on the mod event bus",
                    before = "@Mod constructor without registerSpawnPlacements listener",
                    after = "modEventBus.addListener(EntityRegistry::registerSpawnPlacements)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-modbus-animal-spawn-placement-listener"
                ))
                if (!dryRun) modFile.writeText(modified)
            }
        }

        return changes
    }

    private fun findAnimalSpawnPlacementTargets(registrySource: String, javaFiles: List<Path>): List<AnimalSpawnPlacementTarget> {
        val pattern = Regex(
            """public\s+static\s+final\s+DeferredHolder[\s\S]*?\s+([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.register\(\s*"[^"]+"\s*,\s*\(\)\s*->\s*EntityType\.Builder\.of\(\s*([A-Za-z_$][\w$]*)::new\s*,\s*MobCategory\.CREATURE\s*\)""",
            setOf(RegexOption.MULTILINE)
        )
        return pattern.findAll(registrySource)
            .mapNotNull { match ->
                val fieldName = match.groupValues[1]
                val entityClass = match.groupValues[2]
                if (javaClassExtends(javaFiles, entityClass, "Animal")) {
                    AnimalSpawnPlacementTarget(fieldName, entityClass)
                } else {
                    null
                }
            }
            .toList()
    }

    private fun javaClassExtends(javaFiles: List<Path>, className: String, parentName: String): Boolean {
        return javaFiles.any { file ->
            file.fileName.toString() == "$className.java" &&
                Regex("""\bclass\s+${Regex.escape(className)}\s+extends\s+${Regex.escape(parentName)}\b""")
                    .containsMatchIn(file.readText())
        }
    }

    private fun fullyQualifiedJavaClassName(file: Path, source: String): String {
        val className = file.fileName.toString().removeSuffix(".java")
        val packageName = Regex("""(?m)^package\s+([\w.]+);""").find(source)?.groupValues?.get(1)
        return if (packageName.isNullOrBlank()) className else "$packageName.$className"
    }

    private fun transformGradleProperties(
        file: Path, dryRun: Boolean
    ): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        var content = file.readText()
        val original = content

        // Replace forge version property (various naming conventions)
        var foundForgeVersion = false
        for (pattern in listOf(
            Regex("""(?m)^forge_version\s*=\s*.+$"""),
            Regex("""(?m)^neo_forge_version\s*=\s*.+$"""),
            Regex("""(?m)^neoforge_version\s*=\s*.+$"""),
            Regex("""(?m)^neoneo_forge_version\s*=\s*.+$"""),
            Regex("""(?m)^forgeversion\s*=\s*.+$"""),
            Regex("""(?m)^forgeVersion\s*=\s*.+$"""),
        )) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                val replacement = "neo_forge_version=$targetNeoForgeVersion"
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Replace forge/neoforge version with $replacement",
                    before = match.value,
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-version"
                ))
                content = content.replace(match.value, replacement)
                foundForgeVersion = true
                break  // Only replace first match
            }
        }
        // Ensure neo_forge_version exists even if no forge version property was found
        if (!foundForgeVersion && !Regex("""(?m)^neo_forge_version\s*=""").containsMatchIn(content)) {
            val replacement = "neo_forge_version=$targetNeoForgeVersion"
            content += "\n# Added by modporter\n$replacement\n"
            changes.add(Change(
                file = file, line = content.lines().size,
                description = "Add neo_forge_version property (required by neoForge block)",
                before = "(missing)",
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-props-version-add"
            ))
        }

        // Replace Minecraft version (handles various naming conventions)
        var foundMcVersion = false
        for (mcProp in listOf(
            Regex("""(?m)^minecraft_version\s*=\s*1\.20\.\d+\s*$"""),
            Regex("""(?m)^mc_version\s*=\s*1\.20\.\d+\s*$"""),
            Regex("""(?m)^mcversion\s*=\s*1\.20\.\d+\s*$"""),
            Regex("""(?m)^mcVersion\s*=\s*1\.20\.\d+\s*$"""),
        )) {
            if (mcProp.containsMatchIn(content)) {
                val match = mcProp.find(content)!!
                val propName = match.value.substringBefore("=").trim()
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Update Minecraft version to 1.21.1",
                    before = match.value,
                    after = "minecraft_version=1.21.1",
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-mc-version"
                ))
                // Normalize the property name to minecraft_version
                content = content.replace(match.value, "minecraft_version=1.21.1")
                foundMcVersion = true
                break
            }
        }
        // Version properties that embed the game version (mod_version=1.20.1-4.2.0) move with it, as the
        // real ports do (hotBath 1.21.1-4.2.0, ShowerCore 1.21.1-1.2.0); dependents compare these ranges.
        content = Regex("""(?m)^([A-Za-z_][\w.]*)(\s*=\s*)1\.20(?:\.\d+)?-(\S.*)$""").replace(content) { match ->
            val name = match.groupValues[1]
            if (name == "minecraft_version" || name == "minecraft_version_range") return@replace match.value
            val replacement = "${name}${match.groupValues[2]}1.21.1-${match.groupValues[3]}"
            changes.add(Change(
                file = file, line = content.lineNumberAt(match.range.first),
                description = "Move the game version embedded in $name to the target game version",
                before = match.value.trim(),
                after = replacement.trim(),
                confidence = Confidence.HIGH,
                ruleId = "build-props-game-version-prefix"
            ))
            replacement
        }
        // Ensure minecraft_version exists
        if (!foundMcVersion && !Regex("""(?m)^minecraft_version\s*=""").containsMatchIn(content)) {
            content += "minecraft_version=1.21.1\n"
            changes.add(Change(
                file = file, line = content.lines().size,
                description = "Add minecraft_version property",
                before = "(missing)",
                after = "minecraft_version=1.21.1",
                confidence = Confidence.HIGH,
                ruleId = "build-props-mc-version-add"
            ))
        }

        val projectBuildText = listOf(
            file.parent.resolve("build.gradle"),
            file.parent.resolve("build.gradle.kts")
        )
            .filter { it.exists() }
            .joinToString("\n") { it.readText() }
        val dependencyVersionProperties = DependencyResolver(
            offlineMode = true,
            mappingsPrefix = mappingsPrefix
        ).targetVersionPropertiesForBuild(projectBuildText)
        for (versionProperty in dependencyVersionProperties) {
            val pattern = Regex("""(?m)^${Regex.escape(versionProperty.name)}\s*=\s*.+$""")
            val match = pattern.find(content) ?: continue
            val replacement = "${versionProperty.name}=${versionProperty.value}"
            if (match.value.trim() == replacement) continue
            changes.add(Change(
                file = file,
                line = content.lineNumberAt(match.range.first),
                description = "Update dependency version property ${versionProperty.name} for NeoForge 1.21.1",
                before = match.value,
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-props-dependency-version"
            ))
            content = content.replaceRange(match.range, replacement)
        }

        val baseMcVersion = Regex("""(?m)^base_minecraft_version\s*=\s*1\.20(?:\.\d+)?\s*$""").find(content)
        if (baseMcVersion != null) {
            changes.add(Change(
                file = file, line = content.lineNumberAt(baseMcVersion.range.first),
                description = "Update base Minecraft version to 1.21 for derived runtime dependency coordinates",
                before = baseMcVersion.value,
                after = "base_minecraft_version=1.21",
                confidence = Confidence.HIGH,
                ruleId = "build-props-base-mc-version"
            ))
            content = content.replaceRange(baseMcVersion.range, "base_minecraft_version=1.21")
        }

        // Update version ranges
        val rangeReplacements = listOf(
            Regex("""minecraft_version_range\s*=\s*.+""") to "minecraft_version_range=[1.21.1,1.22)",
            Regex("""forge_version_range\s*=\s*.+""") to "neoforge_version_range=[21.1,)",
            Regex("""neoforge_version_range\s*=\s*\[47[^)\r\n]*\)""") to "neoforge_version_range=[21.1,)",
            Regex("""loader_version_range\s*=\s*.+""") to "loader_version_range=[1,)",
        )
        for ((pattern, replacement) in rangeReplacements) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                if (match.value.trim() != replacement) {
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Update version range: ${match.value.trim()} -> $replacement",
                        before = match.value.trim(),
                        after = replacement,
                        confidence = Confidence.HIGH,
                        ruleId = "build-props-range"
                    ))
                    content = content.replace(match.value, replacement)
                }
            }
        }

        // Update parchment mappings version
        val parchmentMappings = Regex("""parchment_mappings_version\s*=\s*.+""")
        if (parchmentMappings.containsMatchIn(content)) {
            val match = parchmentMappings.find(content)!!
            content = content.replace(match.value, "parchment_mappings_version=2024.11.17")
        }
        val parchmentMc = Regex("""parchment_minecraft_version\s*=\s*.+""")
        if (parchmentMc.containsMatchIn(content)) {
            val match = parchmentMc.find(content)!!
            content = content.replace(match.value, "parchment_minecraft_version=1.21.1")
        }

        // Replace forge_group or similar
        if (content.contains("net.minecraftforge")) {
            changes.add(Change(
                file = file, line = 0,
                description = "Replace Forge references in gradle.properties",
                before = "net.minecraftforge",
                after = "net.neoforged",
                confidence = Confidence.HIGH,
                ruleId = "build-props-forge-ref"
            ))
            content = content.replace("net.minecraftforge", "net.neoforged")
        }

        // Add mod_id property if missing (needed by neoForge { mods { } } block)
        // Also check for "modid" (no underscore) and remap it to "mod_id"
        if (!content.contains("mod_id=") && !content.contains("mod_id =")) {
            val modIdFromProps = Regex("""^modid\s*=\s*(\S+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)
            val modId = modIdFromProps ?: detectModId(file.parent)
            if (modId != null) {
                content += "\n# Added by modporter\nmod_id=$modId\n"
                changes.add(Change(
                    file = file, line = content.lines().size,
                    description = "Add mod_id property for NeoForge configuration",
                    before = "(missing)",
                    after = "mod_id=$modId",
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-mod-id"
                ))
            }
        }

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to emptyList()
    }

    /**
     * Detect a unique concrete mod ID from executable @Mod evidence in Java source files.
     */
    /** The single mod id the module's mods.toml (or neoforge.mods.toml) declares. */
    private fun declaredModId(projectDir: Path): String? {
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        val toml = listOf("neoforge.mods.toml", "mods.toml").map { metaInf.resolve(it) }.firstOrNull { it.exists() } ?: return null
        val active = toml.readText().lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        return Regex("""(?m)^\s*modId\s*=\s*"([a-z][a-z0-9_]{1,63})"""").findAll(active)
            .map { it.groupValues[1] }
            .filter { it != "minecraft" && it != "forge" && it != "neoforge" }
            .toSet()
            .singleOrNull()
    }

    private fun detectModId(projectDir: Path): String? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        val candidates = linkedSetOf<String>()
        try {
            val javaFiles = java.nio.file.Files.walk(srcDir)
                .filter { it.toString().endsWith(".java") }
                .toList()
            for (file in javaFiles) {
                val text = file.toFile().readText()
                val code = maskJavaComments(text)
                val executableCode = maskJavaCommentsAndLiterals(text)

                Regex("""@Mod\s*\(\s*"([A-Za-z0-9_.-]+)"\s*\)""")
                    .findAll(code)
                    .filter { match ->
                        executableCode
                            .substring(match.range.first, match.range.last + 1)
                            .contains("@Mod")
                    }
                    .forEach { candidates.add(it.groupValues[1]) }

                Regex("""@Mod\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?([A-Za-z_$][\w$]*)\s*\)""")
                    .findAll(code)
                    .filter { match ->
                        executableCode
                            .substring(match.range.first, match.range.last + 1)
                            .contains("@Mod")
                    }
                    .forEach { match ->
                        findJavaStringConstant(text, match.groupValues[1])?.let(candidates::add)
                    }
            }
        } catch (_: Exception) {}
        return candidates.singleOrNull()
    }

    private fun addMavenRepositoryContentFilters(
        input: String,
        file: Path,
        changes: MutableList<Change>
    ): String {
        var content = input
        var searchStart = 0
        while (true) {
            val match = Regex("""(?m)^[ \t]*maven\s*\{""").find(content, searchStart) ?: break
            val openBrace = content.indexOf('{', match.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(content, openBrace) else -1
            if (closeBrace <= openBrace) break

            val block = content.substring(match.range.first, closeBrace + 1)
            val needsTamaizedFilter = block.contains("maven.tamaized.com/releases") &&
                !Regex("""\bcontent\s*\{""").containsMatchIn(block)
            if (needsTamaizedFilter) {
                val baseIndent = content.substring(match.range.first, openBrace).takeWhile { it == ' ' || it == '\t' }
                val childIndent = "$baseIndent    "
                val filter = "\n${childIndent}content {\n${childIndent}    includeGroup \"tamaized\"\n$childIndent}"
                content = content.substring(0, closeBrace) + filter + content.substring(closeBrace)
                changes.add(Change(
                    file = file,
                    line = content.lineNumberAt(match.range.first),
                    description = "Restrict Tamaized Maven repository to the tamaized group so CurseMaven dependencies resolve from CurseMaven",
                    before = "maven.tamaized.com/releases without content filter",
                    after = "content { includeGroup \"tamaized\" }",
                    confidence = Confidence.HIGH,
                    ruleId = "build-repository-content-filter"
                ))
                searchStart = closeBrace + filter.length + 1
            } else {
                searchStart = closeBrace + 1
            }
        }
        return content
    }

    private fun addJavaImportIfMissing(source: String, importName: String): String {
        if (source.contains("import $importName;")) return source
        val importLine = "import $importName;\n"
        val lastImport = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE)
            .findAll(source)
            .lastOrNull()
        if (lastImport != null) {
            val insertPos = lastImport.range.last + 1
            return source.substring(0, insertPos) + "\n" + importLine + source.substring(insertPos)
        }

        val packageDecl = Regex("""^package\s+[^;]+;""", RegexOption.MULTILINE).find(source)
        if (packageDecl != null) {
            val insertPos = packageDecl.range.last + 1
            return source.substring(0, insertPos) + "\n\n" + importLine + source.substring(insertPos)
        }

        return importLine + source
    }

    private fun ensureJavaImport(source: String, importName: String): String =
        addJavaImportIfMissing(source, importName)

    private fun removeJavaImport(source: String, importName: String): String =
        source.replace(Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n"""), "")

    private fun removeJavaImportIfSimpleNameUnused(source: String, importName: String): String {
        val withoutImport = removeJavaImport(source, importName)
        if (withoutImport == source) return source
        val simpleName = importName.substringAfterLast('.')
        return if (usesJavaSimpleNameOutsideImports(withoutImport, simpleName)) source else withoutImport
    }

    private fun usesJavaSimpleNameOutsideImports(source: String, simpleName: String): Boolean {
        val withoutImports = Regex("""(?m)^[ \t]*import\s+[^;]+;\s*\r?\n""").replace(source, "")
        return Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(withoutImports)
    }

    private fun detectWorldCarverModIdExpression(source: String, projectDir: Path, javaFile: Path): String? {
        Regex("""@(?:Mod\.)?EventBusSubscriber\s*\([^)]*\bmodid\s*=\s*([^,)]+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val namespaces = extractWorldCarverRegisterIdExpressions(source)
            .mapNotNull { idExpression ->
                resolveResourceLocationNamespaceExpression(idExpression, source, projectDir, javaFile)
            }
            .distinct()

        return namespaces.singleOrNull()
    }

    private fun extractWorldCarverRegisterIdExpressions(source: String): List<String> {
        val expressions = mutableListOf<String>()
        var searchStart = 0
        while (searchStart < source.length) {
            val callStart = source.indexOf(".register(", searchStart)
            if (callStart < 0) break
            val openParen = source.indexOf('(', callStart)
            val closeParen = findMatchingParen(source, openParen)
            if (closeParen > openParen) {
                val args = splitTopLevel(source.substring(openParen + 1, closeParen), ',')
                    .map { it.trim() }
                if (args.size >= 2 && Regex("""[A-Za-z_$][\w$]*""").matches(args[1])) {
                    expressions.add(args[0])
                }
                searchStart = callStart + ".register(".length
            } else {
                searchStart = callStart + ".register(".length
            }
        }
        return expressions
    }

    private fun resolveResourceLocationNamespaceExpression(
        idExpression: String,
        source: String,
        projectDir: Path,
        javaFile: Path
    ): String? {
        resourceLocationFactoryNamespace(idExpression)?.let { return it }

        val call = parseResourceLocationFactoryCall(idExpression) ?: return null
        val ownerSource = resolveJavaSourceForType(call.owner, source, projectDir, javaFile) ?: return null
        val methodBody = findStaticResourceLocationMethodBody(ownerSource.source, call.methodName) ?: return null
        val namespace = resourceLocationFactoryNamespaceFromReturn(methodBody) ?: return null
        return qualifyFactoryNamespaceExpression(namespace, ownerSource.reference, ownerSource.source)
    }

    private data class JavaSourceReference(
        val source: String,
        val reference: String
    )

    private data class ResourceLocationFactoryCall(
        val owner: String,
        val methodName: String
    )

    private fun parseResourceLocationFactoryCall(idExpression: String): ResourceLocationFactoryCall? {
        val openParen = idExpression.indexOf('(')
        if (openParen <= 0 || findMatchingParen(idExpression, openParen) != idExpression.length - 1) return null
        val callee = idExpression.substring(0, openParen).trim()
        val owner = callee.substringBeforeLast('.', missingDelimiterValue = "")
        val methodName = callee.substringAfterLast('.')
        if (owner.isBlank() ||
            !Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").matches(owner) ||
            !Regex("""[A-Za-z_$][\w$]*""").matches(methodName)
        ) {
            return null
        }
        return ResourceLocationFactoryCall(owner, methodName)
    }

    private fun resourceLocationFactoryNamespace(idExpression: String): String? {
        val trimmed = idExpression.trim()
        val knownFactories = listOf(
            "ResourceLocation.fromNamespaceAndPath",
            "ResourceLocation.tryBuild"
        )
        for (factory in knownFactories) {
            val prefix = "$factory("
            if (!trimmed.startsWith(prefix) || !trimmed.endsWith(")")) continue
            val openParen = trimmed.indexOf('(')
            val closeParen = findMatchingParen(trimmed, openParen)
            if (closeParen != trimmed.length - 1) continue
            val args = splitTopLevel(trimmed.substring(openParen + 1, closeParen), ',')
                .map { it.trim() }
            return args.firstOrNull()?.takeIf { it.isNotBlank() }
        }

        if (trimmed.startsWith("new ResourceLocation(") && trimmed.endsWith(")")) {
            val openParen = trimmed.indexOf('(')
            val closeParen = findMatchingParen(trimmed, openParen)
            if (closeParen == trimmed.length - 1) {
                return splitTopLevel(trimmed.substring(openParen + 1, closeParen), ',')
                    .map { it.trim() }
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun resourceLocationFactoryNamespaceFromReturn(methodBody: String): String? {
        val namespaces = mutableListOf<String>()
        var searchStart = 0
        while (searchStart < methodBody.length) {
            val returnStart = methodBody.indexOf("return", searchStart)
            if (returnStart < 0) break
            val semicolon = methodBody.indexOf(';', returnStart)
            if (semicolon < 0) break
            resourceLocationFactoryNamespace(methodBody.substring(returnStart + "return".length, semicolon).trim())
                ?.let(namespaces::add)
            searchStart = semicolon + 1
        }
        return namespaces.distinct().singleOrNull()
    }

    private fun resolveJavaSourceForType(
        owner: String,
        currentSource: String,
        projectDir: Path,
        currentFile: Path
    ): JavaSourceReference? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null

        val currentPackage = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(maskJavaCommentsAndLiterals(currentSource))
            ?.groupValues
            ?.get(1)
        val imports = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .findAll(maskJavaCommentsAndLiterals(currentSource))
            .map { it.groupValues[1] }
            .toList()

        val candidates = linkedSetOf<Pair<String, String>>()
        if (owner.contains(".")) {
            val firstSegment = owner.substringBefore('.')
            if (firstSegment.firstOrNull()?.isLowerCase() == true) {
                candidates.add(owner to owner)
            } else {
                imports.firstOrNull { it.substringAfterLast('.') == firstSegment }?.let { imported ->
                    candidates.add(imported to firstSegment)
                }
                currentPackage?.let { candidates.add("$it.$firstSegment" to firstSegment) }
            }
        } else {
            imports.firstOrNull { it.substringAfterLast('.') == owner }?.let { imported ->
                candidates.add(imported to owner)
            }
            currentPackage?.let { candidates.add("$it.$owner" to owner) }
        }

        val matches = candidates.mapNotNull { (qualifiedName, reference) ->
            val path = srcDir.resolve(qualifiedName.replace('.', '/') + ".java")
            path.takeIf { it.exists() }?.let { JavaSourceReference(it.readText(), reference) }
        }
        return matches.singleOrNull()
            ?: currentFile.takeIf { owner == it.fileName.toString().removeSuffix(".java") && it.exists() }
                ?.let { JavaSourceReference(it.readText(), owner) }
    }

    private fun findStaticResourceLocationMethodBody(source: String, methodName: String): String? {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val candidates = mutableListOf<String>()
        Regex("""\b${Regex.escape(methodName)}\s*\(""")
            .findAll(executableSource)
            .forEach { match ->
                val lineStart = executableSource.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
                val openParen = executableSource.indexOf('(', match.range.first)
                val closeParen = findMatchingParen(executableSource, openParen)
                if (closeParen < 0) return@forEach
                val openBrace = executableSource.indexOf('{', closeParen)
                if (openBrace < 0) return@forEach
                val signature = executableSource.substring(lineStart, openBrace)
                if (!Regex("""\bstatic\b""").containsMatchIn(signature) ||
                    !Regex("""\bResourceLocation\b""").containsMatchIn(signature)
                ) {
                    return@forEach
                }
                val closeBrace = findMatchingBrace(executableSource, openBrace)
                if (closeBrace > openBrace) {
                    candidates.add(source.substring(openBrace + 1, closeBrace))
                }
            }
        return candidates.distinct().singleOrNull()
    }

    private fun qualifyFactoryNamespaceExpression(namespace: String, ownerReference: String, ownerSource: String): String? {
        val trimmed = namespace.trim()
        if (Regex(""""[^"]*"""").matches(trimmed)) return trimmed
        if (Regex("""[A-Za-z_$][\w$]*""").matches(trimmed)) {
            findJavaStringConstant(ownerSource, trimmed) ?: return null
            return "$ownerReference.$trimmed"
        }
        if (Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""").matches(trimmed)) return trimmed
        return null
    }

    private fun camelOrConstantToRegistryPath(name: String): String {
        val raw = if (name.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
            name.lowercase()
        } else {
            name.replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2").lowercase()
        }
        return raw.replace("__", "_").trim('_')
    }

    /**
     * Migrate old-style buildscript { } + apply plugin: builds to modern plugins { } style.
     * This handles the pattern used by many Forge mods that pre-date the MDK 1.20.1 template.
     */
    private fun migrateOldStyleBuild(
        input: String, file: Path, changes: MutableList<Change>
    ): String {
        var content = input
        val isKts = file.fileName.toString().endsWith(".kts")

        // 1. Remove the buildscript { } block
        val buildscriptStart = Regex("""(?:^|\n)\s*buildscript\s*\{""").find(content)
        if (buildscriptStart != null) {
            val braceStart = content.indexOf('{', buildscriptStart.range.first)
            val blockEnd = findMatchingBrace(content, braceStart)
            if (blockEnd > braceStart) {
                changes.add(Change(
                    file = file, line = content.lineNumberAt(buildscriptStart.range.first),
                    description = "Remove buildscript { } block (ForgeGradle classpath deps no longer needed)",
                    before = "buildscript { ... }",
                    after = "// Removed: buildscript block replaced by plugins { }",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-buildscript"
                ))
                content = content.substring(0, buildscriptStart.range.first) + content.substring(blockEnd + 1)
            }
        }

        // 2. Remove apply plugin: lines for forge-related plugins
        val applyPluginPatterns = listOf(
            Regex("""^\s*apply\s+plugin:\s*['"]net\.minecraftforge\.gradle['"]\s*$""", RegexOption.MULTILINE),
            Regex("""^\s*apply\s+plugin:\s*['"]org\.parchmentmc\.librarian\.forgegradle['"]\s*$""", RegexOption.MULTILINE),
            Regex("""^\s*apply\s+plugin:\s*['"]org\.spongepowered\.mixin['"]\s*$""", RegexOption.MULTILINE),
        )
        for (pattern in applyPluginPatterns) {
            if (pattern.containsMatchIn(content)) {
                content = pattern.replace(content, "")
            }
        }

        // 3. Remove mixin { } block (NeoForge has built-in mixin support)
        val mixinBlock = Regex("""(?:^|\n)\s*mixin\s*\{[^}]*\}""").find(content)
        if (mixinBlock != null) {
            content = content.replace(mixinBlock.value, "")
        }

        // 4. Add or merge plugins { } block, with Java 21 toolchain
        content = mergeModernPluginsBlock(content, file, isKts, changes)

        // 5. Remove remaining apply plugin lines that are already in plugins { }
        for (pluginId in listOf("eclipse", "maven-publish", "java-library", "java")) {
            content = content.replace(Regex("""^\s*apply\s+plugin:\s*['"]$pluginId['"]\s*$""", RegexOption.MULTILINE), "")
        }

        // Steps 5b-8 (fg.deobf, dep commenting, reobfJar, cleanup) are now handled
        // by shared cleanup in transformBuildGradle() to cover both old-style and hybrid builds.

        return content
    }

    private fun mergeModernPluginsBlock(
        input: String,
        file: Path,
        isKts: Boolean,
        changes: MutableList<Change>
    ): String {
        val existingPlugins = Regex("""(?m)^[ \t]*plugins\s*\{""").find(input)
        val required = listOf(
            GradlePluginRequirement("java-library"),
            GradlePluginRequirement("eclipse"),
            GradlePluginRequirement("maven-publish"),
            GradlePluginRequirement("net.neoforged.moddev", "2.0.140")
        )

        if (existingPlugins == null) {
            val pluginLines = required.joinToString("\n") { "    ${gradlePluginDeclaration(it, isKts)}" }
            val pluginsBlock = """
plugins {
$pluginLines
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
""".trimStart()
            changes.add(Change(
                file = file, line = 1,
                description = "Add modern plugins { } block with NeoForge ModDev",
                before = "buildscript { ... } + apply plugin: ...",
                after = "plugins { id(\"net.neoforged.moddev\") ... }",
                confidence = Confidence.HIGH,
                ruleId = "build-add-plugins-block"
            ))
            return pluginsBlock + input.trimStart()
        }

        val openBrace = input.indexOf('{', existingPlugins.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(input, openBrace) else -1
        if (closeBrace < 0) return input
        val block = input.substring(openBrace + 1, closeBrace)
        val additions = required.filterNot { requirement ->
            if (requirement.id == "java-library") {
                gradlePluginsBlockHasPlugin(block, "java") || gradlePluginsBlockHasPlugin(block, "java-library")
            } else {
                gradlePluginsBlockHasPlugin(block, requirement.id)
            }
        }
        if (additions.isEmpty()) return input

        val linePrefix = "\n" + additions.joinToString("\n") { "    ${gradlePluginDeclaration(it, isKts)}" } + "\n"
        changes.add(Change(
            file = file,
            line = input.lineNumberAt(existingPlugins.range.first),
            description = "Merge NeoForge ModDev plugin into existing plugins block",
            before = "plugins { ... } with ForgeGradle applied outside the block",
            after = "plugins { ... id(\"net.neoforged.moddev\") ... }",
            confidence = Confidence.HIGH,
            ruleId = "build-merge-plugins-block"
        ))
        return input.substring(0, closeBrace) + linePrefix + input.substring(closeBrace)
    }

    private data class GradlePluginRequirement(val id: String, val version: String? = null)

    private fun gradlePluginDeclaration(requirement: GradlePluginRequirement, isKts: Boolean): String =
        if (isKts) {
            if (requirement.version == null) {
                """id("${requirement.id}")"""
            } else {
                """id("${requirement.id}") version "${requirement.version}""""
            }
        } else {
            if (requirement.version == null) {
                """id '${requirement.id}'"""
            } else {
                """id '${requirement.id}' version '${requirement.version}'"""
            }
        }

    private fun gradlePluginsBlockHasPlugin(block: String, pluginId: String): Boolean =
        Regex("""\bid\s*(?:\(\s*)?['"]${Regex.escape(pluginId)}['"]""").containsMatchIn(block)

    /**
     * Resolve third-party dependencies: find NeoForge 1.21.1 versions and rewrite coordinates.
     * Dependencies that can be resolved are rewritten in-place; unresolved ones stay active.
     */
    private fun resolveDependencies(
        content: String,
        resolver: DependencyResolver,
        resolvedPrefixes: MutableSet<String>,
        newMavenRepos: MutableSet<String>,
        runtimeArtifactStagings: MutableMap<String, RuntimeArtifactStagingPolicy>,
        isKts: Boolean,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val lines = content.lines().toMutableList()
        // Already-emitted "<config>:<coord>" keys: a Forge build declares the same mod as modCompileOnly
        // and modRuntimeOnly (a library its runtime needs), and each line resolves to one coordinate in its own
        // configuration, so the runtime line is a duplicate only when the same configuration (or a
        // covering implementation/api line) was already written.
        val emittedCoords = mutableSetOf<String>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || !GradleDependencyNotations.startsDependencyStatement(trimmed)) {
                i++
                continue
            }
            // Accumulate multi-line dependency
            val blockStart = i
            var depth = 0
            var j = i
            do {
                for (ch in lines[j]) {
                    when (ch) { '(', '[' -> depth++; ')', ']' -> depth-- }
                }
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")

            // Top-level jarJar statements belong to the range/pin migration, jarJar-wrapped statements
            // keep their wrapper and were moved to the target artifact in place, and a notation
            // already on a NeoForge artifact needs no resolution either
            if (trimmed.startsWith("jarJar") || blockText.contains("jarJar(") ||
                GradleDependencyNotations.isOnTargetArtifact(blockText, resolver) ||
                resolver.isKnownTargetStatement(blockText)
            ) {
                i = j
                continue
            }

            // Try to resolve this dependency
            val resolution = resolver.resolve(blockText)
            if (resolution is DepResolution.Resolved) {
                resolution.runtimeArtifactStaging?.let { staging ->
                    val previous = runtimeArtifactStagings.putIfAbsent(staging.id, staging)
                    require(previous == null || previous == staging) {
                        "Conflicting runtime artifact staging policies for ${staging.id}"
                    }
                }
                // Skip if already resolved (idempotency: dep already contains a NeoForge coord)
                val alreadyResolved = resolution.coords.any { blockText.contains(it.coord) }
                if (alreadyResolved) {
                    resolvedPrefixes.addAll(resolution.coords.map { it.coord.substringBefore(":") })
                    emittedCoords.addAll(resolution.coords.map { emittedDependencyKey(it) })
                    i = j
                    continue
                }

                // Filter out coords already emitted (e.g., multiple JEI deps -> single resolved set)
                val newCoords = resolution.coords.filter { !isCoveredByEmitted(emittedCoords, it) }

                val indent = lines[blockStart].takeWhile { it == ' ' || it == '\t' }
                if (newCoords.isNotEmpty()) {
                    // Replace the entire dep block with resolved NeoForge coordinates
                    val replacementLines = newCoords.map { coord ->
                        renderResolvedDependency(
                            indent,
                            coord,
                            resolution.runtimeArtifactStaging,
                            isKts
                        )
                    }
                    for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                    for ((idx, line) in replacementLines.withIndex()) {
                        lines.add(blockStart + idx, line)
                    }
                    changes.add(Change(
                        file = file, line = blockStart + 1,
                        description = "Resolved dependency to NeoForge 1.21.1: ${resolution.notes}",
                        before = blockText.trim(),
                        after = replacementLines.joinToString("\n").trim(),
                        confidence = Confidence.HIGH,
                        ruleId = "build-resolve-dep"
                    ))
                    i = blockStart + replacementLines.size
                } else {
                    // All coords already emitted �?just remove the duplicate Forge dep
                    for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                    i = blockStart
                }

                // Track what was resolved
                resolvedPrefixes.addAll(resolution.coords.map { it.coord.substringBefore(":") })
                emittedCoords.addAll(resolution.coords.map { emittedDependencyKey(it) })
                resolution.coords.mapNotNull { it.mavenRepositoryUrl() }.forEach(newMavenRepos::add)
                resolution.mavenUrl?.let { newMavenRepos.add(it) }
            } else if (resolution is DepResolution.Remove) {
                resolver.knownDependencyForStatement(blockText)?.packagePrefixes?.let(removedDependencyPackages::addAll)
                for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                changes.add(Change(
                    file = file, line = blockStart + 1,
                    description = "Removed dependency with no NeoForge 1.21.1 runtime target: ${resolution.reason}",
                    before = blockText.trim(),
                    after = "",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-dep"
                ))
                i = blockStart
            } else {
                i = j
            }
        }
        return lines.joinToString("\n")
    }

    private fun addReflectedOptionalApiDependencies(
        content: String,
        projectDir: Path,
        resolver: DependencyResolver,
        resolvedPrefixes: MutableSet<String>,
        newMavenRepos: MutableSet<String>,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val referencedClasses = reflectedBinaryClassNames(projectDir)
        if (referencedClasses.isEmpty()) return content

        val coords = linkedSetOf<NeoForgeCoord>()
        for (binaryName in referencedClasses) {
            val resolution = resolver.resolveReferencedClass(binaryName)
            if (resolution !is DepResolution.Resolved) continue
            resolution.coords
                .filter { coord -> coord.config != "runtimeOnly" }
                .map { coord -> if (coord.config == "compileOnly") coord else coord.copy(config = "compileOnly") }
                .forEach { coord ->
                    coords.add(coord)
                    coord.mavenRepositoryUrl()?.let(newMavenRepos::add)
                }
            resolution.mavenUrl?.let(newMavenRepos::add)
        }
        val missing = coords.filter { coord -> !content.contains(coord.coord) }
        if (missing.isEmpty()) return content

        val modified = insertDependencies(content, missing)
        changes.add(Change(
            file = file,
            line = 1,
            description = "Add compileOnly dependencies for statically migrated optional API references",
            before = "Class.forName optional API references without compile classpath",
            after = missing.joinToString(", ") { it.coord },
            confidence = Confidence.HIGH,
            ruleId = "build-reflected-optional-api-dependencies"
        ))
        resolvedPrefixes.addAll(missing.map { it.coord.substringBefore(":") })
        return modified
    }

    private fun reflectedBinaryClassNames(projectDir: Path): Set<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptySet()

        val pattern = Regex("""Class\.forName\(\s*"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+(?:\$[A-Za-z_$][\w$]*)?)"\s*\)""")
        val names = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val source = javaFile.readText()
                val code = maskJavaComments(source)
                val executableCode = maskJavaCommentsAndLiterals(source)
                pattern.findAll(code)
                    .filter { match ->
                        executableCode.substring(match.range.first, match.range.last + 1)
                            .contains("Class.forName(")
                    }
                    .forEach { match ->
                        names.add(match.groupValues[1])
                    }
            }
        return names
    }

    private fun insertDependencies(content: String, coords: List<NeoForgeCoord>): String {
        val dependencyLines = coords.joinToString(System.lineSeparator()) { coord ->
            renderResolvedDependency("    ", coord)
        }
        val dependenciesMatch = Regex("""dependencies\s*\{""").find(content)
        if (dependenciesMatch != null) {
            val insertPos = dependenciesMatch.range.last + 1
            return content.substring(0, insertPos) +
                System.lineSeparator() +
                dependencyLines +
                content.substring(insertPos)
        }

        val insertion = "dependencies {" +
            System.lineSeparator() +
            dependencyLines +
            System.lineSeparator() +
            "}" +
            System.lineSeparator() +
            System.lineSeparator()
        val insertAt = Regex("""(?m)^tasks\.""").find(content)?.range?.first ?: content.length
        return content.substring(0, insertAt) + insertion + content.substring(insertAt)
    }

    private fun runtimeArtifactVariablePrefix(policy: RuntimeArtifactStagingPolicy): String =
        "modporter" + policy.id.replaceFirstChar { it.titlecase() }

    private fun validateExistingRuntimeArtifactStaging(
        content: String,
        resolver: DependencyResolver,
        isKts: Boolean
    ) {
        val startPattern = Regex(
            """(?m)^[ \t]*// modporter-runtime-artifact-stage:([a-z][a-z0-9]*):([0-9a-fA-F]{64})[ \t]*$"""
        )
        val endPattern = Regex(
            """(?m)^[ \t]*// modporter-runtime-artifact-stage:end:([a-z][a-z0-9]*)[ \t]*$"""
        )
        val starts = startPattern.findAll(content).toList()
        val ends = endPattern.findAll(content).toList()
        if (starts.isEmpty() && ends.isEmpty()) return

        val policies = resolver.runtimeArtifactStagingPolicies().associateBy { it.id }
        require(starts.map { it.groupValues[1] }.sorted() == ends.map { it.groupValues[1] }.sorted()) {
            "Runtime artifact staging start/end markers do not match"
        }
        require(starts.map { it.groupValues[1] }.distinct().size == starts.size) {
            "Runtime artifact staging contains duplicate managed blocks"
        }

        for (start in starts) {
            val id = start.groupValues[1]
            val policy = policies[id]
                ?: error("Runtime artifact staging block has no current policy: $id")
            val matchingEnds = ends.filter { it.groupValues[1] == id }
            require(matchingEnds.size == 1) {
                "Runtime artifact staging block must have exactly one end marker: $id"
            }
            val end = matchingEnds.single()
            require(end.range.first > start.range.last) {
                "Runtime artifact staging end marker precedes its start: $id"
            }

            val actual = content.substring(start.range.first, end.range.last + 1)
                .replace("\r\n", "\n")
            val expected = renderRuntimeArtifactStaging(policy, isKts)
                .replace("\r\n", "\n")
            require(actual == expected) {
                "Runtime artifact staging block drifted from policy: $id"
            }

            val filesVariable = runtimeArtifactVariablePrefix(policy) + "RuntimeFiles"
            val stagedDependency = if (isKts) {
                Regex("""(?m)^[ \t]*runtimeOnly\(\s*${Regex.escape(filesVariable)}\s*\)[ \t]*$""")
            } else {
                Regex("""(?m)^[ \t]*runtimeOnly\s+${Regex.escape(filesVariable)}[ \t]*$""")
            }
            require(stagedDependency.findAll(content).count() == 1) {
                "Runtime artifact staging must own exactly one runtimeOnly file dependency: $id"
            }
            val directRuntime = Regex(
                """(?m)^[ \t]*runtimeOnly\s*(?:\(\s*)?["']${Regex.escape(policy.coordinate)}["']"""
            )
            require(!directRuntime.containsMatchIn(content)) {
                "Runtime artifact staging retains a direct runtime coordinate duplicate: $id"
            }
        }
    }

    private fun installRuntimeArtifactStaging(
        content: String,
        policies: List<RuntimeArtifactStagingPolicy>,
        isKts: Boolean,
        changes: MutableList<Change>,
        file: Path
    ): String {
        require(policies.map { it.id }.distinct().size == policies.size) {
            "Runtime artifact staging ids must be unique"
        }
        val dependencyBlock = Regex("""(?m)^[ \t]*dependencies\s*\{""").find(content)
            ?: error("Runtime artifact staging requires a dependencies block")
        val newline = if ("\r\n" in content) "\r\n" else "\n"
        val blocks = policies.map { policy ->
            val marker = "modporter-runtime-artifact-stage:${policy.id}:${policy.sha256.lowercase()}"
            require(marker !in content) {
                "Runtime artifact staging marker already exists while resolving ${policy.coordinate}"
            }
            changes.add(
                Change(
                    file = file,
                    line = content.lineNumberAt(dependencyBlock.range.first),
                    description = "Stage byte-identical runtime artifact under a URLDecoder-safe path",
                    before = """runtimeOnly "${policy.coordinate}"""",
                    after = "runtimeOnly ${runtimeArtifactVariablePrefix(policy)}RuntimeFiles",
                    confidence = Confidence.HIGH,
                    ruleId = "build-stage-runtime-artifact"
                )
            )
            renderRuntimeArtifactStaging(policy, isKts)
        }
        val insertion = blocks.joinToString(newline + newline) + newline + newline
        return content.substring(0, dependencyBlock.range.first) +
            insertion +
            content.substring(dependencyBlock.range.first)
    }

    private fun renderRuntimeArtifactStaging(
        policy: RuntimeArtifactStagingPolicy,
        isKts: Boolean
    ): String {
        val prefix = runtimeArtifactVariablePrefix(policy)
        val taskName = "stageModporter${policy.id.replaceFirstChar { it.titlecase() }}RuntimeArtifact"
        val sourceConfigurationName = "modporter${policy.id.replaceFirstChar { it.titlecase() }}RuntimeSource"
        val expectedSha256 = policy.sha256.lowercase()
        val nestedGroovy = policy.expectedNestedJars.joinToString(",\n") { "                '$it'" }
        val nestedKotlin = policy.expectedNestedJars.joinToString(",\n") { "                \"$it\"" }
        return if (isKts) {
            """
            // modporter-runtime-artifact-stage:${policy.id}:$expectedSha256
            val ${prefix}Coordinate = "${policy.coordinate}"
            val ${prefix}ExpectedSha256 = "$expectedSha256"
            val ${prefix}ExpectedNestedJars = setOf(
$nestedKotlin
            )
            val ${prefix}RuntimeFileName = "${policy.runtimeFileName}"
            val ${prefix}RuntimeRoot = layout.buildDirectory.dir(
                "modporter/runtime-artifacts/${policy.id}/$expectedSha256"
            )
            val ${prefix}RuntimeFile = ${prefix}RuntimeRoot.map { it.file(${prefix}RuntimeFileName) }
            val ${prefix}RuntimeReceipt = ${prefix}RuntimeRoot.map { it.file("receipt.properties") }
            val ${prefix}RuntimeSource = configurations.create("$sourceConfigurationName") {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = true
            }
            dependencies {
                add(${prefix}RuntimeSource.name, ${prefix}Coordinate)
            }
            fun ${prefix}Sha256(input: java.io.File): String {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                input.inputStream().buffered().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
            val ${prefix}StageTask = tasks.register("$taskName") {
                inputs.files(${prefix}RuntimeSource)
                inputs.property("coordinate", ${prefix}Coordinate)
                inputs.property("expectedSha256", ${prefix}ExpectedSha256)
                inputs.property("expectedNestedJars", ${prefix}ExpectedNestedJars.sorted())
                outputs.file(${prefix}RuntimeFile)
                outputs.file(${prefix}RuntimeReceipt)
                doLast { task ->
                    val artifacts = ${prefix}RuntimeSource.resolvedConfiguration.resolvedArtifacts.toList()
                    if (artifacts.size != 1) {
                        throw GradleException(
                            "Expected exactly one artifact and no external dependencies for " +
                                ${prefix}Coordinate + ", got " + artifacts.size
                        )
                    }
                    val artifact = artifacts.single()
                    val resolvedCoordinate = artifact.moduleVersion.id.group + ":" +
                        artifact.name + ":" + artifact.moduleVersion.id.version
                    if (resolvedCoordinate != ${prefix}Coordinate) {
                        throw GradleException(
                            "Resolved unexpected runtime artifact " + resolvedCoordinate +
                                " for " + ${prefix}Coordinate
                        )
                    }
                    val sourceFile = artifact.file.canonicalFile
                    val stagedFile = ${prefix}RuntimeFile.get().asFile.canonicalFile
                    if (sourceFile == stagedFile) {
                        throw GradleException("Runtime artifact staging source and destination are identical")
                    }
                    val stagedPath = stagedFile.toPath().toString()
                    if ('+' in stagedPath || '%' in stagedPath) {
                        throw GradleException(
                            "Runtime artifact staging path is unsafe for URLDecoder-based readers: " + stagedPath
                        )
                    }
                    val sourceSha256 = ${prefix}Sha256(sourceFile)
                    if (!sourceSha256.equals(${prefix}ExpectedSha256, ignoreCase = true)) {
                        throw GradleException(
                            "Runtime artifact source hash mismatch for " + ${prefix}Coordinate +
                                ": expected " + ${prefix}ExpectedSha256 + ", got " + sourceSha256
                        )
                    }
                    val nestedJars = linkedSetOf<String>()
                    java.util.jar.JarFile(sourceFile).use { jar ->
                        val entries = jar.entries()
                        while (entries.hasMoreElements()) {
                            val entryName = entries.nextElement().name
                            if (entryName.startsWith("META-INF/jarjar/") && entryName.endsWith(".jar")) {
                                nestedJars.add(entryName)
                            }
                        }
                    }
                    if (nestedJars != ${prefix}ExpectedNestedJars) {
                        throw GradleException(
                            "Runtime artifact nested jars changed for " + ${prefix}Coordinate +
                                ": expected " + ${prefix}ExpectedNestedJars.sorted() +
                                ", got " + nestedJars.sorted()
                        )
                    }
                    java.nio.file.Files.createDirectories(stagedFile.parentFile.toPath())
                    java.nio.file.Files.copy(
                        sourceFile.toPath(),
                        stagedFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    val stagedSha256 = ${prefix}Sha256(stagedFile)
                    if (!stagedSha256.equals(sourceSha256, ignoreCase = true) ||
                        sourceFile.length() != stagedFile.length() ||
                        java.nio.file.Files.mismatch(sourceFile.toPath(), stagedFile.toPath()) != -1L
                    ) {
                        throw GradleException("Runtime artifact staged bytes differ from resolved source")
                    }
                    val receiptFile = ${prefix}RuntimeReceipt.get().asFile
                    java.nio.file.Files.createDirectories(receiptFile.parentFile.toPath())
                    receiptFile.writeText(
                        listOf(
                            "schemaVersion=2",
                            "projectPath=" + project.path,
                            "taskPath=" + task.path,
                            "sourceConfiguration=" + ${prefix}RuntimeSource.name,
                            "runtimeConfiguration=runtimeOnly",
                            "coordinate=" + ${prefix}Coordinate,
                            "sourceSha256=" + sourceSha256,
                            "stagedSha256=" + stagedSha256,
                            "byteLength=" + stagedFile.length(),
                            "runtimeFilename=" + stagedFile.name,
                            "nestedJars=" + nestedJars.sorted().joinToString(",")
                        ).joinToString(System.lineSeparator(), postfix = System.lineSeparator()),
                        Charsets.UTF_8
                    )
                }
            }
            val ${prefix}RuntimeFiles = files(${prefix}RuntimeFile).apply {
                builtBy(${prefix}StageTask)
            }
            // modporter-runtime-artifact-stage:end:${policy.id}
            """.trimIndent()
        } else {
            """
            // modporter-runtime-artifact-stage:${policy.id}:$expectedSha256
            final String ${prefix}Coordinate = '${policy.coordinate}'
            def ${prefix}ExpectedSha256 = '$expectedSha256'
            def ${prefix}ExpectedNestedJars = [
$nestedGroovy
            ] as Set
            def ${prefix}RuntimeFileName = '${policy.runtimeFileName}'
            def ${prefix}RuntimeRoot = layout.buildDirectory.dir(
                'modporter/runtime-artifacts/${policy.id}/$expectedSha256'
            )
            def ${prefix}RuntimeFile = ${prefix}RuntimeRoot.map { it.file(${prefix}RuntimeFileName) }
            def ${prefix}RuntimeReceipt = ${prefix}RuntimeRoot.map { it.file('receipt.properties') }
            def ${prefix}RuntimeSource = configurations.create('$sourceConfigurationName') {
                canBeConsumed = false
                canBeResolved = true
                transitive = true
            }
            dependencies {
                add(${prefix}RuntimeSource.name, ${prefix}Coordinate)
            }
            def ${prefix}Sha256 = { File input ->
                def digest = java.security.MessageDigest.getInstance('SHA-256')
                input.withInputStream { stream ->
                    byte[] buffer = new byte[8192]
                    int read
                    while ((read = stream.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                digest.digest().collect { String.format('%02x', it & 0xff) }.join()
            }
            def ${prefix}StageTask = tasks.register('$taskName') {
                inputs.files(${prefix}RuntimeSource)
                inputs.property('coordinate', ${prefix}Coordinate)
                inputs.property('expectedSha256', ${prefix}ExpectedSha256)
                inputs.property('expectedNestedJars', ${prefix}ExpectedNestedJars.toList().sort())
                outputs.file(${prefix}RuntimeFile)
                outputs.file(${prefix}RuntimeReceipt)
                doLast { task ->
                    def artifacts = ${prefix}RuntimeSource.resolvedConfiguration.resolvedArtifacts.toList()
                    if (artifacts.size() != 1) {
                        throw new GradleException(
                            'Expected exactly one artifact and no external dependencies for ' +
                                ${prefix}Coordinate + ', got ' + artifacts.size()
                        )
                    }
                    def artifact = artifacts[0]
                    def resolvedCoordinate = artifact.moduleVersion.id.group + ':' +
                        artifact.name + ':' + artifact.moduleVersion.id.version
                    if (resolvedCoordinate != ${prefix}Coordinate) {
                        throw new GradleException(
                            'Resolved unexpected runtime artifact ' + resolvedCoordinate +
                                ' for ' + ${prefix}Coordinate
                        )
                    }
                    def sourceFile = artifact.file.canonicalFile
                    def stagedFile = ${prefix}RuntimeFile.get().asFile.canonicalFile
                    if (sourceFile == stagedFile) {
                        throw new GradleException('Runtime artifact staging source and destination are identical')
                    }
                    def stagedPath = stagedFile.toPath().toString()
                    if (stagedPath.contains('+') || stagedPath.contains('%')) {
                        throw new GradleException(
                            'Runtime artifact staging path is unsafe for URLDecoder-based readers: ' + stagedPath
                        )
                    }
                    def sourceSha256 = ${prefix}Sha256(sourceFile)
                    if (!sourceSha256.equalsIgnoreCase(${prefix}ExpectedSha256)) {
                        throw new GradleException(
                            'Runtime artifact source hash mismatch for ' + ${prefix}Coordinate +
                                ': expected ' + ${prefix}ExpectedSha256 + ', got ' + sourceSha256
                        )
                    }
                    def nestedJars = new LinkedHashSet<String>()
                    def jar = new java.util.jar.JarFile(sourceFile)
                    try {
                        def entries = jar.entries()
                        while (entries.hasMoreElements()) {
                            def entryName = entries.nextElement().name
                            if (entryName.startsWith('META-INF/jarjar/') && entryName.endsWith('.jar')) {
                                nestedJars.add(entryName)
                            }
                        }
                    } finally {
                        jar.close()
                    }
                    if (nestedJars != ${prefix}ExpectedNestedJars) {
                        throw new GradleException(
                            'Runtime artifact nested jars changed for ' + ${prefix}Coordinate +
                                ': expected ' + ${prefix}ExpectedNestedJars.toList().sort() +
                                ', got ' + nestedJars.toList().sort()
                        )
                    }
                    java.nio.file.Files.createDirectories(stagedFile.parentFile.toPath())
                    java.nio.file.Files.copy(
                        sourceFile.toPath(),
                        stagedFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    def stagedSha256 = ${prefix}Sha256(stagedFile)
                    if (!stagedSha256.equalsIgnoreCase(sourceSha256) ||
                        sourceFile.length() != stagedFile.length() ||
                        java.nio.file.Files.mismatch(sourceFile.toPath(), stagedFile.toPath()) != -1L
                    ) {
                        throw new GradleException('Runtime artifact staged bytes differ from resolved source')
                    }
                    def receiptFile = ${prefix}RuntimeReceipt.get().asFile
                    java.nio.file.Files.createDirectories(receiptFile.parentFile.toPath())
                    receiptFile.setText([
                        'schemaVersion=2',
                        'projectPath=' + project.path,
                        'taskPath=' + task.path,
                        'sourceConfiguration=' + ${prefix}RuntimeSource.name,
                        'runtimeConfiguration=runtimeOnly',
                        'coordinate=' + ${prefix}Coordinate,
                        'sourceSha256=' + sourceSha256,
                        'stagedSha256=' + stagedSha256,
                        'byteLength=' + stagedFile.length(),
                        'runtimeFilename=' + stagedFile.name,
                        'nestedJars=' + nestedJars.toList().sort().join(',')
                    ].join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
                }
            }
            def ${prefix}RuntimeFiles = files(${prefix}RuntimeFile)
            ${prefix}RuntimeFiles.builtBy(${prefix}StageTask)
            // modporter-runtime-artifact-stage:end:${policy.id}
            """.trimIndent()
        }
    }

    private fun NeoForgeCoord.mavenRepositoryUrl(): String? =
        when {
            coord.startsWith("curse.maven:") -> "https://www.cursemaven.com"
            coord.startsWith("maven.modrinth:") -> "https://api.modrinth.com/maven"
            coord.startsWith("net.fabricmc:") -> "https://maven.fabricmc.net/"
            else -> null
        }

    internal fun emittedDependencyKey(coord: NeoForgeCoord): String = "${coord.config}:${coord.coord.trim()}"

    /** implementation/api lines already give the coordinate to both classpaths; otherwise the configuration must match. */
    internal fun isCoveredByEmitted(emitted: Set<String>, coord: NeoForgeCoord): Boolean {
        val dependency = coord.coord.trim()
        return emittedDependencyKey(coord) in emitted ||
            "implementation:$dependency" in emitted ||
            "api:$dependency" in emitted
    }

    private fun renderResolvedDependency(
        indent: String,
        coord: NeoForgeCoord,
        runtimeArtifactStaging: RuntimeArtifactStagingPolicy? = null,
        isKts: Boolean = false
    ): String {
        if (runtimeArtifactStaging != null &&
            coord.config == "runtimeOnly" &&
            coord.coord == runtimeArtifactStaging.coordinate
        ) {
            val filesVariable = runtimeArtifactVariablePrefix(runtimeArtifactStaging) + "RuntimeFiles"
            return if (isKts) {
                "${indent}runtimeOnly($filesVariable)"
            } else {
                "${indent}runtimeOnly $filesVariable"
            }
        }
        val dependency = coord.coord.trim()
        val declaration = if (dependency.startsWith("files(")) {
            "${indent}${coord.config} $dependency"
        } else if (!coord.transitive) {
            "${indent}${coord.config}(\"${coord.coord}\") { transitive = false }"
        } else {
            "${indent}${coord.config} \"${coord.coord}\""
        }
        return when (coord.wrapper) {
            null -> declaration
            "jarJar" -> "${indent}jarJar(${coord.config}(\"${coord.coord}\"))"
            else -> error("Unsupported dependency wrapper '${coord.wrapper}' for ${coord.coord}")
        }
    }

    private fun removeBundledMixinDependencies(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "testAnnotationProcessor", "testImplementation", "jarJar")
        val bundledPrefixes = listOf("org.spongepowered:mixin", "io.github.llamalad7:mixinextras")
        val lines = content.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || depKeywords.none { trimmed.startsWith(it) }) {
                i++
                continue
            }

            val blockStart = i
            var depth = 0
            var j = i
            do {
                depth += delimiterDepthDeltaOutsideStrings(lines[j])
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")
            if (bundledPrefixes.any { prefix -> blockText.contains(prefix) }) {
                changes.add(Change(
                    file = file,
                    line = blockStart + 1,
                    description = "Remove standalone Mixin/MixinExtras dependency bundled by NeoForge",
                    before = blockText.trim(),
                    after = "(dependency removed; NeoForge provides Mixin runtime)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-bundled-mixin-dependency"
                ))
                for (k in (j - 1) downTo blockStart) {
                    lines.removeAt(k)
                }
                i = blockStart
            } else {
                i = j
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * ForgeGradle's `jarJar.ranged(it, range)` / `jarJar.pin(it, version)` describe the JarJar
     * metadata range and the embedded version. ModDevGradle expresses the same contract through
     * Gradle rich versions on the `jarJar` dependency: `strictly` is the compatibility range and
     * `prefer` the version that is resolved and embedded. This mirrors the official 1.21.1 ports
     * (for example The Aether's `jarJar(...) { version { strictly ...; prefer ... } }`).
     */
    private fun normalizeJarJarRangePinDsl(
        content: String,
        changes: MutableList<Change>,
        file: Path,
        resolver: DependencyResolver? = null
    ): String {
        val lines = content.lines().toMutableList()
        val rangedPattern = Regex("""^jarJar\s*\.\s*ranged\s*\(\s*it\s*,\s*(.+?)\s*\)\s*;?$""")
        val pinPattern = Regex("""^jarJar\s*\.\s*pin\s*\(\s*it\s*,\s*(.+?)\s*\)\s*;?$""")
        // `jarJar "g:a:v" { ... }` is parsed by Groovy as a call on the string; ModDev needs `jarJar("g:a:v") { ... }`.
        val headerPattern = Regex("""^(\s*)jarJar\s*\(?\s*(["'])(.+?)\2\s*\)?\s*\{\s*$""")
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || !trimmed.startsWith("jarJar") || !trimmed.contains("{")) {
                i++
                continue
            }

            val blockStart = i
            var depth = 0
            var j = i
            do {
                depth += braceDepthDeltaOutsideStrings(lines[j])
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")
            if (!blockText.contains("jarJar.ranged(") && !blockText.contains("jarJar.pin(")) {
                i = j
                continue
            }

            val bodyLines = lines.subList(blockStart + 1, (j - 1).coerceAtLeast(blockStart + 1))
            var range: String? = null
            var pin: String? = null
            val retained = mutableListOf<String>()
            var unsupported = false
            for (bodyLine in bodyLines) {
                val body = bodyLine.trim()
                val rangedMatch = rangedPattern.matchEntire(body)
                val pinMatch = pinPattern.matchEntire(body)
                when {
                    rangedMatch != null && range == null -> range = rangedMatch.groupValues[1]
                    pinMatch != null && pin == null -> pin = pinMatch.groupValues[1]
                    body.startsWith("jarJar.ranged(") || body.startsWith("jarJar.pin(") -> unsupported = true
                    else -> retained.add(bodyLine)
                }
            }
            val headerMatch = headerPattern.matchEntire(lines[blockStart])
            if (unsupported || headerMatch == null) {
                // An unrecognized shape is left untouched so the converted script fails visibly
                // instead of silently losing the original range or pinned version.
                i = j
                continue
            }

            val indent = headerMatch.groupValues[1]
            val quote = headerMatch.groupValues[2]
            var coordinate = headerMatch.groupValues[3]
            // The same artifact mapping that rewrote the compile/runtime declarations (for example
            // curios-forge -> curios-neoforge) applies to the bundled artifact; the resolved literal
            // version then also replaces the Forge-era property references in range and pin.
            var literalVersion: String? = null
            val coordinateParts = coordinate.split(':')
            if (coordinateParts.size >= 3 && coordinateParts[0].none { it == '$' || it == '{' } && coordinateParts[1].none { it == '$' || it == '{' }) {
                val resolution = resolver?.resolve("implementation \"$coordinate\"")
                if (resolution is DepResolution.Resolved) {
                    val replacement = resolution.coords
                        .map { it.coord.trim() }
                        .firstOrNull { candidate -> candidate.split(':').size == 3 && !candidate.startsWith("files(") }
                    if (replacement != null) {
                        coordinate = replacement
                        literalVersion = replacement.substringAfterLast(':')
                    }
                }
                if (literalVersion == null && !coordinate.contains("\${")) literalVersion = coordinateParts.drop(2).joinToString(":")
            }
            if (literalVersion != null) {
                val rangeMatch = range?.let { Regex("""^(["'])\[(.+),\)\1$""").matchEntire(it) }
                if (rangeMatch != null && rangeMatch.groupValues[2].contains("\${")) {
                    range = "${rangeMatch.groupValues[1]}[$literalVersion,)${rangeMatch.groupValues[1]}"
                }
                if (pin != null && pin.contains("\${")) pin = "\"$literalVersion\""
            }
            val header = "${indent}jarJar($quote$coordinate$quote) {"
            val inner = "$indent    "
            val replacementLines = mutableListOf(header)
            replacementLines.addAll(retained)
            replacementLines.add("${inner}version {")
            range?.let { replacementLines.add("$inner    strictly $it") }
            pin?.let { replacementLines.add("$inner    prefer $it") }
            replacementLines.add("$inner}")
            replacementLines.add(lines[j - 1])

            for (k in (j - 1) downTo blockStart) lines.removeAt(k)
            for ((offset, replacementLine) in replacementLines.withIndex()) {
                lines.add(blockStart + offset, replacementLine)
            }
            changes.add(Change(
                file = file,
                line = blockStart + 1,
                description = "Map ForgeGradle JarJar range/pin to ModDev jarJar version constraints (strictly/prefer)",
                before = blockText.trim(),
                after = replacementLines.joinToString("\n").trim(),
                confidence = Confidence.HIGH,
                ruleId = "build-jarjar-version-constraints"
            ))
            i = blockStart + replacementLines.size
        }
        return lines.joinToString("\n")
    }

    private fun delimiterDepthDeltaOutsideStrings(line: String): Int {
        var depth = 0
        scanOutsideGradleStrings(line) { ch ->
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return depth
    }

    private fun braceDepthDeltaOutsideStrings(line: String): Int {
        var depth = 0
        scanOutsideGradleStrings(line) { ch ->
            when (ch) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    private fun indexOfOutsideStrings(line: String, target: Char): Int {
        var found = -1
        scanOutsideGradleStrings(line) { ch, index ->
            if (found < 0 && ch == target) found = index
        }
        return found
    }

    private fun scanOutsideGradleStrings(line: String, visit: (Char) -> Unit) {
        scanOutsideGradleStrings(line) { ch, _ -> visit(ch) }
    }

    private fun scanOutsideGradleStrings(line: String, visit: (Char, Int) -> Unit) {
        var inSingle = false
        var inDouble = false
        var escaped = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            val next = line.getOrNull(i + 1)
            if (!inSingle && !inDouble && ch == '/' && next == '/') break
            if (inSingle || inDouble) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (inSingle && ch == '\'') {
                    inSingle = false
                } else if (inDouble && ch == '"') {
                    inDouble = false
                }
                i++
                continue
            }
            when (ch) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                else -> visit(ch, i)
            }
            i++
        }
    }

    /**
     * Add maven repositories for resolved NeoForge dependencies.
     */
    private fun addMavenRepositories(
        content: String,
        repos: Set<String>,
        changes: MutableList<Change>,
        file: Path
    ): String {
        var result = content
        val repoBlock = Regex("""repositories\s*\{""")
        val repoMatch = repoBlock.find(result)
        fun renderRepo(url: String): String =
            when {
                url.contains("modrinth") -> "    maven {\n        name = \"Modrinth\"\n        url = \"$url\"\n        content { includeGroup \"maven.modrinth\" }\n    }"
                url.contains("cursemaven") -> "    maven {\n        name = \"CurseMaven\"\n        url = \"$url\"\n        content { includeGroup \"curse.maven\" }\n    }"
                else -> "    maven { url = \"$url\" }"
            }
        if (repoMatch != null) {
            val insertPos = repoMatch.range.last + 1
            val newRepoLines = repos
                .filter { url -> !result.contains(url) }
                .joinToString("\n") { url -> "\n${renderRepo(url)}" }
            if (newRepoLines.isNotBlank()) {
                result = result.substring(0, insertPos) + newRepoLines + result.substring(insertPos)
                changes.add(Change(
                    file = file, line = result.lineNumberAt(insertPos),
                    description = "Add maven repositories for resolved NeoForge dependencies",
                    before = "(no additional repos)",
                    after = repos.joinToString(", "),
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-maven-repos"
                ))
            }
        } else {
            val newRepoLines = repos
                .filter { url -> !result.contains(url) }
                .joinToString("\n") { renderRepo(it) }
            if (newRepoLines.isNotBlank()) {
                val repoSection = "repositories {\n$newRepoLines\n}\n\n"
                val insertPos = result.indexOf("dependencies {").takeIf { it >= 0 } ?: result.length
                result = result.substring(0, insertPos) + repoSection + result.substring(insertPos)
                changes.add(Change(
                    file = file, line = result.lineNumberAt(insertPos),
                    description = "Create maven repositories block for resolved NeoForge dependencies",
                    before = "(no repositories block)",
                    after = repos.joinToString(", "),
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-maven-repos"
                ))
            }
        }
        return result
    }

    /**
     * Keep third-party dependencies active while removing ForgeGradle-only wrappers.
     * Dependency incompatibility must surface as a real resolution/compile failure.
     */
    private fun normalizeOldDependencyWrappers(content: String): String {
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "jarJar", "def ")

        val lines = content.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || depKeywords.none { trimmed.startsWith(it) }) {
                i++
                continue
            }
            // Accumulate multi-line dependency (track bracket/paren depth)
            val blockStart = i
            var depth = 0
            var j = i
            do {
                for (ch in lines[j]) {
                    when (ch) { '(', '[' -> depth++; ')', ']' -> depth-- }
                }
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")
            if (blockText.contains("fg.deobf")) {
                val replacementLines = blockText.lines().map { removeFgDeobfWrapper(it) }
                for (k in blockStart until j) {
                    lines[k] = replacementLines[k - blockStart]
                }
            }
            i = j
        }
        return lines.joinToString("\n")
    }

    private fun removeFgDeobfWrapper(line: String): String =
        line
            .replace(
                Regex("""\b([A-Za-z_$][\w$]*)\s+fg\.deobf\s*\(\s*([^)]+?)\s*\)"""),
                "$1 $2"
            )
            .replace(
                Regex("""\b([A-Za-z_$][\w$]*)\s+fg\.deobf\s+("[^"]+"|'[^']+'|[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)"""),
                "$1 $2"
            )

    private data class LegacyTreeGrower(
        val className: String,
        val path: Path
    )

    private data class LegacyArmorMaterial(
        val enumName: String,
        val path: Path,
        val packageName: String,
        val modIdExpr: String,
        val modImport: String?,
        val extraImports: List<String>,
        val constants: List<LegacyArmorMaterialConstant>
    )

    private data class LegacyArmorMaterialConstant(
        val fieldName: String,
        val registryName: String,
        val textureName: String,
        val protections: List<String>,
        val enchantmentValue: String,
        val equipSound: String,
        val repairIngredient: String,
        val toughness: String,
        val knockbackResistance: String,
        val bodyProtection: String? = null
    )

    private data class JavaInheritanceDeclaration(
        val file: Path,
        val packageName: String,
        val className: String,
        val parentType: String,
        val imports: Map<String, String>,
        val wildcardImports: Set<String>
    ) {
        val fqn: String
            get() = if (packageName.isBlank()) className else "$packageName.$className"
    }

    private data class CustomStatDeclaration(
        val fieldName: String,
        val modIdExpr: String,
        val pathName: String
    )

    private data class JavaTopLevelTypeOpening(
        val name: String,
        val range: IntRange
    )

    private fun rewriteLegacyArmorMaterials(
        projectDir: Path,
        dryRun: Boolean,
        errors: MutableList<String>
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val materials = mutableListOf<LegacyArmorMaterial>()
        val evaluatedEnums = mutableListOf<String>()
        val dyeableConstants = dyeableArmorMaterialConstants(srcDir)
        val javaFiles = java.nio.file.Files.walk(srcDir).use { paths ->
            paths.filter { it.toString().endsWith(".java") }
                .sorted()
                .toList()
        }
        val javaSources = javaFiles.associateWith { it.readText() }
            .toMutableMap()
        val soundHolderFields = legacyArmorSoundHolderFields(javaSources.values)
        for (javaFile in javaFiles) {
                val original = javaSources.getValue(javaFile)
                // an enum declaring every 1.20.1 getter keeps computing its values (LegacyArmorMaterialEnums)
                val modIdExpr = if (Regex("""\benum\s+[A-Za-z_$][\w$]*\s+implements\s+[^{]*\bArmorMaterial\b""").containsMatchIn(original)) {
                    legacyArmorMaterialModIdExpression(original, projectDir)
                } else {
                    null
                }
                val soundPlan = modIdExpr?.let {
                    LegacyArmorMaterialSoundHolderGraph.resolve(
                        original,
                        javaSources
                    )
                }
                val evaluated = modIdExpr?.let {
                    LegacyArmorMaterialEnums.render(
                        original,
                        it,
                        dyeableConstants,
                        soundPlan?.holderSuppliers.orEmpty(),
                        soundHolderFields
                    )
                }
                if (evaluated != null) {
                    for ((producerFile, rewritten) in
                        soundPlan?.rewrittenSources.orEmpty()
                    ) {
                        val producerOriginal =
                            javaSources[producerFile] ?: continue
                        if (producerOriginal == rewritten) continue
                        changes.add(Change(
                            file = producerFile,
                            line = 1,
                            description =
                                "Expose the existing sound-event holder " +
                                    "without dereferencing it",
                            before = "SoundEvent getMainEvent()",
                            after =
                                "Holder<SoundEvent> getMainEventHolder()",
                            confidence = Confidence.HIGH,
                            ruleId =
                                "build-sound-event-holder-accessor"
                        ))
                        javaSources[producerFile] = rewritten
                        if (!dryRun) producerFile.writeText(rewritten)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Register the legacy ArmorMaterial enum's constants as 1.21 ArmorMaterial holders computed by its own getters",
                        before = "enum ${evaluated.enumName} implements ArmorMaterial",
                        after = "${evaluated.enumName}.ARMOR_MATERIALS holders over ${evaluated.enumName}.Legacy",
                        confidence = Confidence.HIGH,
                        ruleId = "build-legacy-armor-material-registry"
                    ))
                    javaSources[javaFile] = evaluated.source
                    if (!dryRun) javaFile.writeText(evaluated.source)
                    materials.add(LegacyArmorMaterial(evaluated.enumName, javaFile, evaluated.packageName, modIdExpr, null, emptyList(), emptyList()))
                    evaluatedEnums.add(evaluated.enumName)
                    continue
                }
                val material =
                    parseLegacyArmorMaterial(
                        javaFile,
                        original,
                        projectDir,
                        errors
                    ) ?: continue
                val replacement = renderArmorMaterialRegistry(material)
                if (replacement != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite legacy ArmorMaterial enum as 1.21 registry-backed ArmorMaterial holder class",
                        before = "enum ${material.enumName} implements ArmorMaterial",
                        after = "${material.enumName}.ARMOR_MATERIALS DeferredRegister",
                        confidence = Confidence.HIGH,
                        ruleId = "build-legacy-armor-material-registry"
                    ))
                    javaSources[javaFile] = replacement
                    if (!dryRun) {
                        javaFile.writeText(replacement)
                    }
                }
                materials.add(material)
        }

        if (materials.isEmpty()) return changes

        changes.addAll(rewriteLegacyArmorMaterialConsumers(srcDir, materials, dryRun))
        if (evaluatedEnums.isNotEmpty()) changes.addAll(restoreArmorDurability(srcDir, materials, evaluatedEnums, dryRun))
        changes.addAll(registerLegacyArmorMaterials(srcDir, materials, dryRun))
        return changes
    }

    /**
     * `Owner.FIELD` for every project field declared `DeferredHolder<SoundEvent, SoundEvent>` or
     * `Holder<SoundEvent>`: a legacy armor enum may pass such a holder along without dereferencing
     * it before the sound registers (LegacyArmorMaterialEnums).
     */
    private fun legacyArmorSoundHolderFields(sources: Collection<String>): Set<String> {
        val declaration = Regex(
            """\b(?:DeferredHolder\s*<\s*SoundEvent\s*,\s*(?:\?\s+extends\s+)?SoundEvent\s*>|Holder(?:\.Reference)?\s*<\s*SoundEvent\s*>)\s+([A-Za-z_$][\w$]*)\s*="""
        )
        return sources.flatMap { source ->
            val masked = com.modporter.core.transforms.util.JavaSourceText.maskCommentsAndLiterals(source)
            val owner = Regex("""\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)""").find(masked)?.groupValues?.get(1)
                ?: return@flatMap emptyList()
            declaration.findAll(masked).map { "$owner.${it.groupValues[1]}" }.toList()
        }.toSet()
    }

    /** The mod id expression an armor material enum registers under: its own MODID/ID reference, else the project's. */
    private fun legacyArmorMaterialModIdExpression(content: String, projectDir: Path): String? =
        Regex("""return\s+([A-Za-z_$][\w$.]*\.MODID)\s*\+""").find(content)?.groupValues?.get(1)
            ?: Regex("""\b([A-Za-z_$][\w$]*\.MODID)\b""").find(content)?.groupValues?.get(1)
            ?: Regex("""\b([A-Za-z_$][\w$]*\.ID)\b""").find(content)?.groupValues?.get(1)
            ?: projectModIdExpression(projectDir)

    /**
     * `Enum.CONSTANT` materials of armor items that implemented 1.20.1's `DyeableLeatherItem` (passed
     * to their constructor or to their `super(...)`): 1.20.1 rendered those tinted with an overlay.
     */
    private fun dyeableArmorMaterialConstants(srcDir: Path): Set<String> {
        val files = java.nio.file.Files.walk(srcDir).use { paths -> paths.filter { it.toString().endsWith(".java") }.toList() }
        val sources = files.associateWith { runCatching { it.readText() }.getOrDefault("") }
        val dyeableClasses = sources.values.flatMap { source ->
            Regex("""\bclass\s+([A-Za-z_$][\w$]*)[^{;]*\bimplements\s+[^{;]*\bDyeableLeatherItem\b""").findAll(source).map { it.groupValues[1] }.toList()
        }.toSet()
        if (dyeableClasses.isEmpty()) return emptySet()
        val constant = """[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)"""
        return sources.flatMap { (_, source) ->
            val constructed = dyeableClasses.flatMap { name ->
                Regex("""\bnew\s+${Regex.escape(name)}\s*\(\s*$constant""").findAll(source).map { it.groupValues[1] }.toList()
            }
            val declared = dyeableClasses.filter { Regex("""\bclass\s+${Regex.escape(it)}\b""").containsMatchIn(source) }
                .flatMap { Regex("""\bsuper\s*\(\s*$constant""").findAll(source).map { match -> match.groupValues[1] }.toList() }
            constructed + declared
        }.toSet()
    }

    /** Armor items of the evaluated enums take their 1.20.1 durability back and compare materials by key. */
    private fun restoreArmorDurability(
        srcDir: Path,
        materials: List<LegacyArmorMaterial>,
        evaluatedEnums: List<String>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val directArmorItemSubclass = Regex("""\bclass\s+[A-Za-z_$][\w$]*(?:\s*<[^{]*?>)?\s+extends\s+(?:net\.minecraft\.world\.item\.)?ArmorItem\b""")
        java.nio.file.Files.walk(srcDir).use { paths -> paths.filter { it.toString().endsWith(".java") }.toList() }
            .filter { file -> materials.none { it.path == file && it.enumName in evaluatedEnums } }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (evaluatedEnums.none { original.contains(it) } && !original.contains("ArmorItem")) return@forEach
                var modified = LegacyArmorMaterialEnums.rewriteConsumer(original, evaluatedEnums, directArmorItemSubclass.containsMatchIn(original))
                if (modified == original) return@forEach
                materials.filter { it.enumName in evaluatedEnums }.forEach { material ->
                    if (modified.contains("${material.enumName}.") && !modified.contains("package ${material.packageName};")) {
                        modified = ensureJavaImport(modified, "${material.packageName}.${material.enumName}")
                    }
                }
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Armor items take their legacy material durability back; materials compare by key",
                    before = "super(material, type, properties)",
                    after = "super(material, type, Material.withDurability(properties, material, type))",
                    confidence = Confidence.HIGH,
                    ruleId = "build-legacy-armor-material-durability"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        return changes
    }

    private fun migrateDataGenerationApis(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val recipeProviders = linkedSetOf<String>()
        val abstractRecipeProviders = linkedSetOf<String>()
        val abstractRecipeProviderModIds = mutableMapOf<String, String>()
        val lootTableProviders = linkedSetOf<String>()
        val lootModifierProviders = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val content = javaFile.readText()
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+RecipeProvider\b""")
                    .find(content)
                    ?.let { recipeProviders.add(it.groupValues[1]) }
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+AbstractRecipeProvider\b""")
                    .find(content)
                    ?.let {
                        val providerClass = it.groupValues[1]
                        abstractRecipeProviders.add(providerClass)
                        inferModIdExpression(content, projectDir)?.let { modIdExpr ->
                            abstractRecipeProviderModIds[providerClass] = modIdExpr
                        }
                    }
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+LootTableProvider\b""")
                    .find(content)
                    ?.let { lootTableProviders.add(it.groupValues[1]) }
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+GlobalLootModifierProvider\b""")
                    .find(content)
                    ?.let { lootModifierProviders.add(it.groupValues[1]) }
            }
        if (recipeProviders.isEmpty() &&
            abstractRecipeProviders.isEmpty() &&
            lootTableProviders.isEmpty() &&
            lootModifierProviders.isEmpty()) return changes

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                var modified = original

                for (providerClass in recipeProviders) {
                    val constructorPattern = Regex(
                        """(?m)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*([A-Za-z_$][\w$]*)\s*\);\s*}"""
                    )
                    modified = constructorPattern.replace(modified) { match ->
                        val indent = match.groupValues[1]
                        val packOutputParam = match.groupValues[2]
                        val superArg = match.groupValues[3]
                        if (packOutputParam != superArg) {
                            match.value
                        } else {
                            "$indent" +
                                "public $providerClass(PackOutput $packOutputParam, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n" +
                                "$indent    super($packOutputParam, lookupProvider);\n" +
                                "$indent}"
                        }
                    }

                }

                for (providerClass in abstractRecipeProviders) {
                    val constructorPattern = Regex(
                        """(?m)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*([A-Za-z_$][\w$]*)\s*\);\s*}"""
                    )
                    val providerModIdExpr = abstractRecipeProviderModIds[providerClass]
                    if (providerModIdExpr == null) {
                        if (constructorPattern.containsMatchIn(modified)) {
                            throw IllegalStateException(
                                "Cannot derive mod id for AbstractRecipeProvider $providerClass from Java source, Gradle properties, or mod metadata"
                            )
                        }
                        continue
                    }
                    modified = constructorPattern.replace(modified) { match ->
                        val indent = match.groupValues[1]
                        val packOutputParam = match.groupValues[2]
                        val superArg = match.groupValues[3]
                        if (packOutputParam != superArg) {
                            match.value
                        } else {
                            "$indent" +
                                "public $providerClass(PackOutput $packOutputParam, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n" +
                                "$indent    super($packOutputParam, $providerModIdExpr, lookupProvider);\n" +
                                "$indent}"
                        }
                    }
                }

                for (providerClass in lootTableProviders) {
                    modified = migrateLootTableProviderConstructor(modified, providerClass)

                }

                for (providerClass in lootModifierProviders) {
                    val constructorPattern = Regex(
                        """(?m)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*,\s*String\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\);\s*}"""
                    )
                    modified = constructorPattern.replace(modified) { match ->
                        val indent = match.groupValues[1]
                        val outputParam = match.groupValues[2]
                        val modidParam = match.groupValues[3]
                        val superOutput = match.groupValues[4]
                        val superModid = match.groupValues[5]
                        if (outputParam != superOutput || modidParam != superModid) {
                            match.value
                        } else {
                            "$indent" +
                                "public $providerClass(PackOutput $outputParam, CompletableFuture<HolderLookup.Provider> lookupProvider, String $modidParam) {\n" +
                                "$indent    super($outputParam, lookupProvider, $modidParam);\n" +
                                "$indent}"
                        }
                    }
                }

                if (modified.contains("extends RecipeProvider")) {
                    modified = Regex(
                        """private\s+void\s+specialRecipe\s*\(\s*RecipeOutput\s+consumer\s*,\s*SimpleCraftingRecipeSerializer<[^>]+>\s+serializer\s*\)"""
                    ).replace(
                        modified,
                        "private void specialRecipe(RecipeOutput consumer, java.util.function.Function<net.minecraft.world.item.crafting.CraftingBookCategory, net.minecraft.world.item.crafting.Recipe<?>> recipeFactory, ResourceLocation name)"
                    )
                    modified = modified.replace(
                        Regex("""(?m)^[ \t]*ResourceLocation\s+name\s*=\s*(?:BuiltInRegistries|Registries)\.RECIPE_SERIALIZER\.getKey\(serializer\);\s*\r?\n"""),
                        ""
                    )
                    modified = modified.replace("SpecialRecipeBuilder.special(serializer)", "SpecialRecipeBuilder.special(recipeFactory)")
                    modified = Regex(
                        """specialRecipe\(([^,\r\n]+),\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.SERIALIZER\s*\)"""
                    ).replace(modified) { match ->
                        "specialRecipe(${match.groupValues[1]}, ${match.groupValues[2]}::new, BuiltInRegistries.RECIPE_SERIALIZER.getKey(${match.groupValues[2]}.SERIALIZER))"
                    }
                }

                if (modified != original) {
                    if (modified.contains("CompletableFuture<HolderLookup.Provider>")) {
                        modified = ensureJavaImport(modified, "java.util.concurrent.CompletableFuture")
                        modified = ensureJavaImport(modified, "net.minecraft.core.HolderLookup")
                    }
                    if (modified.contains("BuiltInRegistries.")) {
                        modified = ensureJavaImport(modified, "net.minecraft.core.registries.BuiltInRegistries")
                    }
                    if (!dryRun) javaFile.writeText(modified)
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Migrate 1.21 data generation provider APIs",
                        before = "(legacy datagen provider API)",
                        after = "(lookup-provider aware datagen API)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-datagen-api-121"
                    ))
                }
            }

        return changes
    }

    private fun inferModIdExpression(source: String, projectDir: Path): String? {
        modIdExpressionFromModClass(source, className = null, packageName = null)?.let { return it }

        return projectModIdExpression(projectDir)
    }

    private fun projectModIdExpression(projectDir: Path): String? =
        projectModAnnotationExpression(projectDir)
            ?: detectUniqueProjectModId(projectDir)?.let(::javaStringLiteral)

    private fun projectModAnnotationExpression(projectDir: Path): String? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val expressions = javaFiles
            .mapNotNull { file ->
                val source = file.readText()
                val executableSource = maskJavaCommentsAndLiterals(source)
                val className = file.fileName.toString().removeSuffix(".java")
                val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                    .find(executableSource)
                    ?.groupValues
                    ?.get(1)
                modIdExpressionFromModClass(source, className, packageName)
            }
            .distinct()
            .toList()
        return expressions.singleOrNull()
    }

    private fun modIdExpressionFromModClass(source: String, className: String?, packageName: String?): String? {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val rawArgument = Regex("""@Mod\s*\(\s*([^)]+?)\s*\)""")
            .find(code)
            ?.takeIf { match ->
                executableCode
                    .substring(match.range.first, match.range.last + 1)
                    .contains("@Mod")
            }
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: return null
        val argument = Regex("""(?:value\s*=\s*)?(.+)""")
            .matchEntire(rawArgument)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: return null
        if (argument.isBlank() || argument.any { it == ';' || it == '{' || it == '}' || it == '\n' || it == '\r' }) {
            return null
        }
        Regex(""""([^"]+)"""").matchEntire(argument)?.let { literal ->
            return javaStringLiteral(literal.groupValues[1])
        }

        val localClassName = className ?: Regex("""(?m)\bclass\s+([A-Za-z_$][\w$]*)\b""")
            .find(executableCode)
            ?.groupValues
            ?.get(1)
        val constantName = when {
            Regex("""[A-Za-z_$][\w$]*""").matches(argument) -> argument
            localClassName != null -> Regex("""${Regex.escape(localClassName)}\.([A-Za-z_$][\w$]*)""")
                .matchEntire(argument)
                ?.groupValues
                ?.get(1)
            else -> null
        } ?: return null
        findJavaStringConstant(source, constantName) ?: return null
        val owner = listOfNotNull(packageName?.takeIf { it.isNotBlank() }, localClassName)
            .joinToString(".")
            .takeIf { it.isNotBlank() }
            ?: return null
        return "$owner.$constantName"
    }

    private fun detectUniqueProjectModId(projectDir: Path): String? {
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            val value = raw?.trim()?.trim('"') ?: return
            if (value.isNotBlank() &&
                !value.contains('$') &&
                !value.contains('{') &&
                Regex("""[a-z0-9_.-]+""", RegexOption.IGNORE_CASE).matches(value)
            ) {
                candidates.add(value)
            }
        }

        projectDir.resolve("gradle.properties")
            .takeIf { it.exists() }
            ?.readText()
            ?.let { properties ->
                Regex("""(?m)^\s*(?:mod_id|modid)\s*=\s*([^\s#]+)""")
                    .findAll(properties)
                    .forEach { addCandidate(it.groupValues[1]) }
            }

        listOf(
            projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
            projectDir.resolve("src/main/resources/META-INF/mods.toml")
        ).forEach { toml ->
            toml.takeIf { it.exists() }
                ?.readText()
                ?.let { text ->
                    projectModIdsFromToml(text).forEach(::addCandidate)
                }
        }

        val srcDir = projectDir.resolve("src/main/java")
        if (srcDir.exists()) {
            java.nio.file.Files.walk(srcDir)
                .filter { it.toString().endsWith(".java") }
                .forEach { file ->
                    val text = file.readText()
                    val code = maskJavaComments(text)
                    val executableCode = maskJavaCommentsAndLiterals(text)
                    Regex("""@Mod\s*\(\s*"([a-z0-9_.-]+)"\s*\)""", RegexOption.IGNORE_CASE)
                        .findAll(code)
                        .filter { match ->
                            executableCode
                                .substring(match.range.first, match.range.last + 1)
                                .contains("@Mod")
                        }
                        .forEach { addCandidate(it.groupValues[1]) }

                    Regex("""@Mod\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?([A-Za-z_$][\w$]*)\s*\)""")
                        .findAll(code)
                        .filter { match ->
                            executableCode
                                .substring(match.range.first, match.range.last + 1)
                                .contains("@Mod")
                        }
                        .forEach { match ->
                            findJavaStringConstant(text, match.groupValues[1])?.let(::addCandidate)
                        }
                }
        }

        return candidates.singleOrNull()
    }

    private fun projectModIdsFromToml(text: String): List<String> {
        val ids = mutableListOf<String>()
        var inModsTable = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[[") -> inModsTable = trimmed == "[[mods]]"
                trimmed.startsWith("[") -> inModsTable = false
                inModsTable -> {
                    Regex("""^modId\s*=\s*"([^"]+)"""")
                        .find(trimmed)
                        ?.groupValues
                        ?.get(1)
                        ?.let(ids::add)
                }
            }
        }
        return ids
    }

    private fun findJavaStringConstant(source: String, constantName: String): String? {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        return Regex("(?m)\\b(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?(?:final\\s+)?String\\s+${Regex.escape(constantName)}\\s*=\\s*\"([^\"]+)\"")
            .find(code)
            ?.takeIf { match ->
                val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                executableSegment.contains("String") &&
                    executableSegment.contains("=")
            }
            ?.groupValues
            ?.get(1)
    }

    private fun javaStringLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun migrateLootTableProviderConstructor(source: String, providerClass: String): String {
        val constructorPattern = Regex(
            """(?ms)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\((.*?)\);\s*}"""
        )
        return constructorPattern.replace(source) { match ->
            val indent = match.groupValues[1]
            val outputParam = match.groupValues[2]
            val superArgs = splitTopLevel(match.groupValues[3], ',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (superArgs.firstOrNull() != outputParam || superArgs.any { it.contains("lookupProvider") }) {
                match.value
            } else {
                "$indent" +
                    "public $providerClass(PackOutput $outputParam, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n" +
                    "$indent    super(${(superArgs + "lookupProvider").joinToString(", ")});\n" +
                    "$indent}"
            }
        }
    }

    private fun migrateCustomStatRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val migratedStatClasses = linkedSetOf<String>()

        val statDeclarationPattern = Regex(
            """public\s+static\s+final\s+ResourceLocation\s+([A-Za-z_$][\w$]*)\s*=\s*(?:ResourceLocation\.fromNamespaceAndPath\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\)|new\s+ResourceLocation\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\))\s*;"""
        )

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("BuiltInRegistries.CUSTOM_STAT") || !original.contains("Stats.CUSTOM.get")) {
                continue
            }

            val declarations = statDeclarationPattern.findAll(original)
                .mapNotNull { match ->
                    val modIdExpr = match.groupValues[2].ifBlank { match.groupValues[4] }.trim()
                    val pathName = match.groupValues[3].ifBlank { match.groupValues[5] }.trim()
                    if (modIdExpr.isBlank() || pathName.isBlank()) {
                        null
                    } else {
                        CustomStatDeclaration(match.groupValues[1], modIdExpr, pathName)
                    }
            }
                .toList()
            if (declarations.isEmpty()) continue
            val customStatNamespaces = declarations.map { it.modIdExpr }.distinct()
            if (customStatNamespaces.size != 1) {
                throw IllegalStateException(
                    "Cannot migrate custom stat registration in " +
                        projectDir.relativize(javaFile).toString().replace('\\', '/') +
                        ": mixed custom stat namespaces ${customStatNamespaces.joinToString(", ")}"
                )
            }
            val customStatNamespace = customStatNamespaces.single()
            val declaredStatOwner = javaTopLevelTypeOpening(original)?.name
                ?: throw IllegalStateException(
                    "Cannot migrate custom stat registration in " +
                        projectDir.relativize(javaFile).toString().replace('\\', '/') +
                        ": missing top-level Java type declaration"
                )

            var modified = original
            val oldImports = listOf(
                "net.minecraft.core.Registry",
                "net.minecraft.core.registries.BuiltInRegistries",
                "net.minecraft.stats.StatFormatter",
                "net.minecraft.stats.Stats"
            )
            for (importName in oldImports) {
                modified = modified.replace(
                    Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n"""),
                    ""
                )
            }
            modified = ensureJavaImport(modified, "net.minecraft.core.registries.Registries")
            modified = ensureJavaImport(modified, "net.neoforged.bus.api.IEventBus")
            modified = ensureJavaImport(modified, "net.neoforged.neoforge.registries.DeferredRegister")

            if (!modified.contains("DeferredRegister<ResourceLocation> CUSTOM_STATS")) {
                val classMatch = javaTopLevelTypeOpening(modified)
                    ?: throw IllegalStateException(
                        "Cannot migrate custom stat registration in " +
                            projectDir.relativize(javaFile).toString().replace('\\', '/') +
                            ": missing top-level Java type declaration"
                    )
                val declaration = "\n    private static final DeferredRegister<ResourceLocation> CUSTOM_STATS =\n" +
                    "            DeferredRegister.create(Registries.CUSTOM_STAT, $customStatNamespace);\n"
                modified = modified.substring(0, classMatch.range.last + 1) +
                    declaration +
                    modified.substring(classMatch.range.last + 1)
            }

            val missingRegistrations = declarations.filterNot {
                modified.contains("CUSTOM_STATS.register(\"${it.pathName}\"")
            }
            if (missingRegistrations.isNotEmpty()) {
                val latestConstant = statDeclarationPattern.findAll(modified).lastOrNull()
                if (latestConstant != null) {
                    val staticBlock = buildString {
                        append("\n\n    static {\n")
                        for (declaration in missingRegistrations) {
                            append("        CUSTOM_STATS.register(\"${declaration.pathName}\", () -> ${declaration.fieldName});\n")
                        }
                        append("    }")
                    }
                    modified = modified.substring(0, latestConstant.range.last + 1) +
                        staticBlock +
                        modified.substring(latestConstant.range.last + 1)
                }
            }

            modified = Regex(
                """(?s)\n([ \t]*)public\s+static\s+void\s+register\s*\(\s*\)\s*\{.*?\n\1\}"""
            ).replace(modified) { match ->
                "\n${match.groupValues[1]}public static void register(IEventBus modEventBus) {\n" +
                    "${match.groupValues[1]}    CUSTOM_STATS.register(modEventBus);\n" +
                    "${match.groupValues[1]}}"
            }
            modified = Regex(
                """(?s)\n[ \t]*private\s+static\s+void\s+registerStat\s*\([^)]*\)\s*\{.*?\n[ \t]*\}"""
            ).replace(modified, "")

            if (modified != original) {
                migratedStatClasses.add(declaredStatOwner)
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Migrate legacy custom stat registration to DeferredRegister",
                    before = "Registry.register(BuiltInRegistries.CUSTOM_STAT, ...)",
                    after = "DeferredRegister.create(Registries.CUSTOM_STAT, ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-custom-stat-deferred-register"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        if (migratedStatClasses.isEmpty()) return changes

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            var modified = original
            for (className in migratedStatClasses) {
                val oldCallPattern = Regex("""(?m)^[ \t]*${Regex.escape(className)}\.register\(\);\s*(?://.*)?\r?\n?""")
                if (!oldCallPattern.containsMatchIn(modified)) continue

                modified = oldCallPattern.replace(modified, "")
                val newCall = "$className.register(modEventBus);"
                if (!modified.contains(newCall)) {
                    val lines = modified.lines().toMutableList()
                    val registerIdx = lines.indexOfLast {
                        it.contains(".register(modEventBus);") && !it.contains(newCall)
                    }
                    val eventBusIdx = lines.indexOfFirst { it.contains("IEventBus modEventBus") && it.contains("{") }
                    val insertIdx = when {
                        registerIdx >= 0 -> registerIdx + 1
                        eventBusIdx >= 0 -> eventBusIdx + 1
                        else -> -1
                    }
                    if (insertIdx >= 0) {
                        val indent = lines.getOrNull((insertIdx - 1).coerceAtLeast(0))
                            ?.takeWhile { it.isWhitespace() }
                            ?.ifBlank { "        " }
                            ?: "        "
                        lines.add(insertIdx, "$indent$newCall")
                        modified = lines.joinToString("\n")
                    }
                }
            }

            if (modified != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Register custom stats on the mod event bus before registry freeze",
                    before = "ModStats.register() during setup",
                    after = "ModStats.register(modEventBus) during construction",
                    confidence = Confidence.HIGH,
                    ruleId = "build-custom-stat-mod-event-bus"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        return changes
    }

    private fun javaTopLevelTypeOpening(source: String): JavaTopLevelTypeOpening? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val match = Regex(
            """(?m)^[ \t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b[^{]*\{"""
        ).find(executableCode) ?: return null
        return JavaTopLevelTypeOpening(match.groupValues[1], match.range)
    }

    private fun migrateRegisterEventResourceLocations(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("RegisterEvent") ||
                    (!original.contains("registry.register(ResourceLocation.parse(") &&
                        !Regex("""registry\.register\(\s*"[A-Za-z0-9_./:-]+"\s*,""").containsMatchIn(original))) {
                    return@forEach
                }

                var resolvedModIdExpr: String? = null
                fun sourceModIdExpr(): String? {
                    resolvedModIdExpr?.let { return it }
                    val detected = Regex("""@EventBusSubscriber\s*\([^)]*\bmodid\s*=\s*([^,)]+)""")
                        .find(original)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?: Regex("""@Mod\s*\(\s*([^)]+?)\s*\)""")
                            .find(original)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                        ?: Regex("""(?:public\s+)?(?:static\s+)?final\s+String\s+(?:MODID|MOD_ID)\s*=\s*"([^"]+)"""")
                            .find(original)
                            ?.groupValues
                            ?.get(1)
                            ?.let { "\"$it\"" }
                        ?: detectModId(projectDir)?.let { "\"$it\"" }
                    resolvedModIdExpr = detected
                    return detected
                }

                var modified = Regex("""registry\.register\(\s*ResourceLocation\.parse\("([A-Za-z0-9_./:-]+)"\)\s*,""")
                    .replace(original) { match ->
                        val path = match.groupValues[1]
                        if (path.contains(":")) {
                            match.value
                        } else {
                            sourceModIdExpr()
                                ?.let { modIdExpr -> """registry.register(ResourceLocation.fromNamespaceAndPath($modIdExpr, "$path"),""" }
                                ?: match.value
                        }
                    }
                modified = Regex("""registry\.register\(\s*"([A-Za-z0-9_./:-]+)"\s*,""")
                    .replace(modified) { match ->
                        val id = match.groupValues[1]
                        if (id.contains(":")) {
                            """registry.register(ResourceLocation.parse("$id"),"""
                        } else {
                            sourceModIdExpr()
                                ?.let { modIdExpr -> """registry.register(ResourceLocation.fromNamespaceAndPath($modIdExpr, "$id"),""" }
                                ?: match.value
                        }
                    }

                if (modified != original) {
                    modified = ensureJavaImport(modified, "net.minecraft.resources.ResourceLocation")
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Namespace RegisterEvent resource location IDs with the current mod id",
                        before = "registry.register(\"name\", ...) or registry.register(ResourceLocation.parse(\"name\"), ...)",
                        after = "registry.register(ResourceLocation.fromNamespaceAndPath(MODID, \"name\"), ...)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-registerevent-resource-location-namespace"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun migrateWorldCarverRegisterEvents(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes
        val migratedCarverClasses = linkedSetOf<String>()

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("RegisterEvent") ||
                    (!original.contains("WORLD_CARVERS") && !original.contains("Registries.CARVER"))) {
                    return@forEach
                }

                var modified = original
                val fieldIds = Regex(""""([A-Za-z0-9_./-]+)"\s*\)\s*,\s*([A-Za-z_$][\w$]*)""")
                    .findAll(original)
                    .associate { it.groupValues[2] to it.groupValues[1] }
                    .toMutableMap()
                val migratedFields = linkedSetOf<String>()
                val modIdExpr = detectWorldCarverModIdExpression(original, projectDir, javaFile)
                    ?: throw IllegalStateException(
                        "Cannot derive mod id for world carver registration in " +
                            projectDir.relativize(javaFile).toString().replace('\\', '/')
                    )

                modified = Regex("""(?m)^[ \t]*@(?:Mod\.)?EventBusSubscriber\([^\r\n]*\)\s*\r?\n""")
                    .replace(modified, "")

                val carverFieldPattern = Regex(
                    """(?ms)^([ \t]*)public\s+static\s+final\s+([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+\2\s*\((.*?)\);"""
                )
                modified = carverFieldPattern.replace(modified) { match ->
                    val indent = match.groupValues[1]
                    val type = match.groupValues[2]
                    val field = match.groupValues[3]
                    val args = match.groupValues[4].trim()
                    if (!type.endsWith("Carver")) {
                        match.value
                    } else {
                        val registryName = fieldIds[field] ?: camelOrConstantToRegistryPath(field)
                        migratedFields.add(field)
                        "$indent" +
                            "public static final DeferredHolder<WorldCarver<?>, $type> $field = " +
                            "CARVER_TYPES.register(\"$registryName\", () -> new $type($args));"
                    }
                }

                if (migratedFields.isNotEmpty()) {
                    val classMatch = Regex("""(?m)^[ \t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*class\s+([A-Za-z_$][\w$]*)[^{]*\{""").find(modified)
                    if (classMatch != null) {
                        val className = classMatch.groupValues[1]
                        migratedCarverClasses.add(className)
                        Regex("""(?m)^package\s+([\w.]+);""").find(modified)?.groupValues?.get(1)?.let { packageName ->
                            migratedCarverClasses.add("$packageName.$className")
                        }
                        if (!modified.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES")) {
                            val insertPos = classMatch.range.last + 1
                            modified = modified.substring(0, insertPos) +
                                "\n\tpublic static final DeferredRegister<WorldCarver<?>> CARVER_TYPES = DeferredRegister.create(Registries.CARVER, $modIdExpr);\n" +
                                modified.substring(insertPos)
                        }
                    }
                }

                val registerMethodPattern = Regex(
                    """(?m)^[ \t]*(?://[^\r\n]*\r?\n\s*)*(?:@[^\r\n]+\r?\n\s*)*public\s+static\s+void\s+register\s*\(\s*RegisterEvent\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
                )
                val methodMatch = registerMethodPattern.find(modified)
                if (methodMatch != null) {
                    val openBrace = modified.indexOf('{', methodMatch.range.first)
                    val closeBrace = if (openBrace >= 0) findMatchingBrace(modified, openBrace) else -1
                    if (closeBrace > openBrace) {
                        var removeEnd = closeBrace + 1
                        if (removeEnd < modified.length && modified[removeEnd] == '\r') removeEnd++
                        if (removeEnd < modified.length && modified[removeEnd] == '\n') removeEnd++
                        modified = modified.substring(0, methodMatch.range.first) + modified.substring(removeEnd)
                    }
                }

                for (field in migratedFields) {
                    modified = Regex("""\b${Regex.escape(field)}\.configured\(""")
                        .replace(modified, "$field.value().configured(")
                }

                if (migratedFields.isNotEmpty()) {
                    modified = addJavaImportIfMissing(modified, "net.minecraft.world.level.levelgen.carver.WorldCarver")
                    modified = addJavaImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredHolder")
                    modified = addJavaImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredRegister")
                    modified = removeJavaImport(modified, "net.minecraftforge.eventbus.api.SubscribeEvent")
                    modified = removeJavaImport(modified, "net.neoforged.bus.api.SubscribeEvent")
                    modified = removeJavaImport(modified, "net.minecraftforge.fml.common.Mod")
                    modified = removeJavaImport(modified, "net.neoforged.fml.common.Mod")
                    modified = removeJavaImport(modified, "net.minecraftforge.registries.ForgeRegistries")
                    modified = removeJavaImport(modified, "net.neoforged.neoforge.registries.ForgeRegistries")
                    modified = removeJavaImport(modified, "net.minecraftforge.registries.RegisterEvent")
                    modified = removeJavaImport(modified, "net.neoforged.neoforge.registries.RegisterEvent")
                    if (!Regex("""\bObjects\.""").containsMatchIn(modified)) {
                        modified = removeJavaImport(modified, "java.util.Objects")
                    }
                    if (!Regex("""\bResourceLocation\b""").containsMatchIn(modified)) {
                        modified = removeJavaImport(modified, "net.minecraft.resources.ResourceLocation")
                    }
                    if (!Regex("""\bBuiltInRegistries\b""").containsMatchIn(modified)) {
                        modified = removeJavaImport(modified, "net.minecraft.core.registries.BuiltInRegistries")
                    }
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Migrate world carver RegisterEvent registration to DeferredRegister",
                        before = "RegisterEvent + ForgeRegistries.WORLD_CARVERS",
                        after = "DeferredRegister<WorldCarver<?>> CARVER_TYPES",
                        confidence = Confidence.HIGH,
                        ruleId = "build-world-carver-deferred-register"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val listenerPattern = Regex(
                    """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.addListener\(\s*((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)::register\s*\);\s*$"""
                )
                val modified = listenerPattern.replace(original) { match ->
                    val indent = match.groupValues[1]
                    val bus = match.groupValues[2]
                    val carverClass = match.groupValues[3]
                    val simpleName = carverClass.substringAfterLast('.')
                    if (carverClass in migratedCarverClasses || simpleName in migratedCarverClasses) {
                        "$indent$carverClass.CARVER_TYPES.register($bus);"
                    } else {
                        match.value
                    }
                }
                if (modified != original) {
                    val match = listenerPattern.find(original)
                    changes.add(Change(
                        file = javaFile,
                        line = match?.let { original.lineNumberAt(it.range.first) } ?: 1,
                        description = "Register migrated world carver DeferredRegister on the mod event bus",
                        before = match?.value?.trim() ?: "CarverTypes::register listener",
                        after = "CarverTypes.CARVER_TYPES.register(modEventBus)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-world-carver-modbus-register"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun parseLegacyArmorMaterial(
        path: Path,
        content: String,
        projectDir: Path,
        errors: MutableList<String>
    ): LegacyArmorMaterial? {
        val enumMatch = Regex("""public\s+enum\s+([A-Za-z_$][\w$]*)\s+implements\s+ArmorMaterial\b""")
            .find(content) ?: return null
        val enumName = enumMatch.groupValues[1]
        val bodyStart = content.indexOf('{', enumMatch.range.last)
        if (bodyStart < 0) return null
        val constantsEnd = findLegacyEnumConstantsEnd(content, bodyStart + 1)
        if (constantsEnd < 0) return null

        val constants = splitTopLevel(content.substring(bodyStart + 1, constantsEnd), ',')
            .mapNotNull { parseLegacyArmorMaterialConstant(it) }
        if (constants.isEmpty()) return null

        val packageName = Regex("""(?m)^package\s+([\w.]+);""")
            .find(content)?.groupValues?.get(1) ?: return null
        val modIdExpr = Regex("""return\s+([A-Za-z_$][\w$.]*\.MODID)\s*\+""")
            .find(content)?.groupValues?.get(1)
            ?: Regex("""\b([A-Za-z_$][\w$]*\.MODID)\b""").find(content)?.groupValues?.get(1)
            ?: Regex("""\b([A-Za-z_$][\w$]*\.ID)\b""").find(content)?.groupValues?.get(1)
            ?: projectModIdExpression(projectDir)
        if (modIdExpr == null) {
            val relative = projectDir.relativize(path).toString().replace('\\', '/')
            errors.add(
                "Cannot derive mod id expression for legacy ArmorMaterial enum $enumName in $relative: " +
                    "expected a source MODID/ID reference in the enum or a unique project @Mod/mod metadata id"
            )
            return null
        }
        val modClass = when {
            modIdExpr.endsWith(".MODID") -> modIdExpr.substringBefore(".MODID")
            modIdExpr.endsWith(".ID") -> modIdExpr.substringBefore(".ID")
            else -> ""
        }
        val modImport = if (modClass.isNotBlank() && !modClass.contains("\"") && !modClass.contains(".")) {
            Regex("""(?m)^import\s+([\w.]+\.$modClass);""").find(content)?.groupValues?.get(1)
        } else {
            null
        }
        val sourceImports = Regex("""(?m)^import\s+([\w.]+);""")
            .findAll(content)
            .map { it.groupValues[1] }
            .toList()
        val referencedTopLevelSymbols = constants
            .flatMap { listOf(it.equipSound, it.repairIngredient) }
            .flatMap { Regex("""\b([A-Z][A-Za-z0-9_$]*)\.""").findAll(it).map { match -> match.groupValues[1] }.toList() }
            .toSet()
        val extraImports = sourceImports
            .filter { importName -> referencedTopLevelSymbols.contains(importName.substringAfterLast('.')) }
            .filterNot { it == modImport }

        return LegacyArmorMaterial(enumName, path, packageName, modIdExpr, modImport, extraImports, constants)
    }

    private fun parseLegacyArmorMaterialConstant(raw: String): LegacyArmorMaterialConstant? {
        val withoutLineComments = raw.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
            .trim()
            .trimEnd(',')
            .trim()
        if (withoutLineComments.isEmpty()) return null
        val match = Regex("""(?s)^([A-Za-z_$][\w$]*)\s*\((.*)\)$""").find(withoutLineComments) ?: return null
        val args = splitTopLevel(match.groupValues[2], ',').map { it.trim() }
        if ((args.size == 7 || args.size == 8) && args[2].contains("EnumMap") && args[2].contains("ArmorItem.Type")) {
            return parseEnumMapArmorMaterialConstant(match.groupValues[1], args)
        }
        if (args.size < 9) return null

        val protections = Regex("""new\s+int\s*\[\]\s*\{([^}]*)\}""")
            .find(args[3])
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (protections.size < 4) return null

        return LegacyArmorMaterialConstant(
            fieldName = match.groupValues[1],
            registryName = stripJavaString(args[0]),
            textureName = stripJavaString(args[1]),
            protections = protections,
            enchantmentValue = args[4],
            equipSound = args[5],
            toughness = args[6],
            knockbackResistance = args[7],
            repairIngredient = args.drop(8).joinToString(", ")
        )
    }

    private fun parseEnumMapArmorMaterialConstant(
        fieldName: String,
        args: List<String>
    ): LegacyArmorMaterialConstant? {
        val protectionsByType = Regex("""ArmorItem\.Type\.(BOOTS|LEGGINGS|CHESTPLATE|HELMET|BODY)\s*,\s*([^) ;]+)\s*\)""")
            .findAll(args[2])
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val protections = listOf("BOOTS", "LEGGINGS", "CHESTPLATE", "HELMET")
            .map { protectionsByType[it] ?: return null }
        val registryName = stripJavaString(args[0])
        val knockbackResistance = if (args.size >= 8) args[6] else "0.0F"
        val repairIngredient = if (args.size >= 8) args[7] else args[6]
        return LegacyArmorMaterialConstant(
            fieldName = fieldName,
            registryName = registryName,
            textureName = registryName,
            protections = protections,
            enchantmentValue = args[3],
            equipSound = args[4],
            toughness = args[5],
            knockbackResistance = knockbackResistance,
            repairIngredient = repairIngredient,
            bodyProtection = protectionsByType["BODY"] ?: protections[2]
        )
    }

    private fun findLegacyEnumConstantsEnd(content: String, start: Int): Int {
        var parenDepth = 0
        var braceDepth = 0
        var inString = false
        var escaped = false
        for (i in start until content.length) {
            val ch = content[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                ';' -> if (parenDepth == 0 && braceDepth == 0) return i
            }
        }
        return -1
    }

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var escaped = false
        for (i in value.indices) {
            val ch = value[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                delimiter -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    result.add(value.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(value.substring(start))
        return result
    }

    private fun splitTopLevelArgs(value: String): List<String> =
        splitTopLevel(value, ',').map { it.trim() }.filter { it.isNotBlank() }

    private fun migrateLegacyTreeGrowerSubclass(source: String, compatPackage: String): String {
        if (!source.contains("getConfiguredFeature(") && !source.contains("getConfiguredMegaFeature(")) return source
        val classPattern = Regex("""\bclass\s+(\w+)\s+extends\s+(AbstractMegaTreeGrower|AbstractTreeGrower|TreeGrower)\b""")
        if (!classPattern.containsMatchIn(source)) return source

        var result = source
        result = removeJavaImport(result, "net.minecraft.world.level.block.grower.AbstractTreeGrower")
        result = removeJavaImport(result, "net.minecraft.world.level.block.grower.AbstractMegaTreeGrower")
        result = removeJavaImport(result, "net.minecraft.world.level.block.grower.TreeGrower")
        result = ensureJavaImport(result, "$compatPackage.ModPorterAbstractTreeGrower")
        result = classPattern.replace(result) { match ->
            "class ${match.groupValues[1]} extends ModPorterAbstractTreeGrower"
        }
        return result
    }

    private fun ensureLegacyTreeGrowerCompatBase(projectDir: Path, compatPackage: String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        val compatFile = srcDir.resolve(compatPackage.replace('.', '/')).resolve("ModPorterAbstractTreeGrower.java")
        val source = legacyTreeGrowerCompatSource(compatPackage)
        if (compatFile.exists() && compatFile.readText() == source) return emptyList()
        if (!dryRun) {
            compatFile.parent.createDirectories()
            compatFile.writeText(source)
        }
        return listOf(Change(
            file = compatFile,
            line = 1,
            description = "Generate compatibility base for legacy AbstractTreeGrower subclasses",
            before = "AbstractTreeGrower/AbstractMegaTreeGrower inheritance",
            after = "ModPorterAbstractTreeGrower extends TreeGrower and preserves overridden feature selection",
            confidence = Confidence.HIGH,
            ruleId = "build-tree-grower-compat-base"
        ))
    }

    private fun legacyTreeGrowerCompatSource(packageName: String): String = """
package $packageName;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

public abstract class ModPorterAbstractTreeGrower extends TreeGrower {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    protected ModPorterAbstractTreeGrower() {
        super("modporter_legacy_tree_" + NEXT_ID.getAndIncrement(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    protected abstract ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers);

    protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredMegaFeature(RandomSource random) {
        return null;
    }

    @Override
    public boolean growTree(ServerLevel level, ChunkGenerator chunkGenerator, BlockPos pos, BlockState state, RandomSource random) {
        ResourceKey<ConfiguredFeature<?, ?>> megaFeature = this.getConfiguredMegaFeature(random);
        if (megaFeature != null) {
            Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                    .registryOrThrow(Registries.CONFIGURED_FEATURE)
                    .getHolder(megaFeature)
                    .orElse(null);
            var event = net.neoforged.neoforge.event.EventHooks.fireBlockGrowFeature(level, random, pos, holder);
            holder = event.getFeature();
            if (event.isCanceled()) return false;
            if (holder != null) {
                for (int x = 0; x >= -1; x--) {
                    for (int z = 0; z >= -1; z--) {
                        if (isTwoByTwoSapling(state, level, pos, x, z)) {
                            ConfiguredFeature<?, ?> configuredFeature = holder.value();
                            BlockState air = Blocks.AIR.defaultBlockState();
                            level.setBlock(pos.offset(x, 0, z), air, 4);
                            level.setBlock(pos.offset(x + 1, 0, z), air, 4);
                            level.setBlock(pos.offset(x, 0, z + 1), air, 4);
                            level.setBlock(pos.offset(x + 1, 0, z + 1), air, 4);
                            if (configuredFeature.place(level, chunkGenerator, random, pos.offset(x, 0, z))) {
                                return true;
                            }
                            level.setBlock(pos.offset(x, 0, z), state, 4);
                            level.setBlock(pos.offset(x + 1, 0, z), state, 4);
                            level.setBlock(pos.offset(x, 0, z + 1), state, 4);
                            level.setBlock(pos.offset(x + 1, 0, z + 1), state, 4);
                            return false;
                        }
                    }
                }
            }
        }

        ResourceKey<ConfiguredFeature<?, ?>> feature = this.getConfiguredFeature(random, this.hasFlowers(level, pos));
        if (feature == null) return false;
        Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolder(feature)
                .orElse(null);
        var event = net.neoforged.neoforge.event.EventHooks.fireBlockGrowFeature(level, random, pos, holder);
        holder = event.getFeature();
        if (event.isCanceled() || holder == null) return false;

        ConfiguredFeature<?, ?> configuredFeature = holder.value();
        BlockState fluidState = level.getFluidState(pos).createLegacyBlock();
        level.setBlock(pos, fluidState, 4);
        if (configuredFeature.place(level, chunkGenerator, random, pos)) {
            if (level.getBlockState(pos) == fluidState) {
                level.sendBlockUpdated(pos, state, fluidState, 2);
            }
            return true;
        }
        level.setBlock(pos, state, 4);
        return false;
    }

    private static boolean isTwoByTwoSapling(BlockState state, BlockGetter level, BlockPos pos, int xOffset, int zOffset) {
        Block block = state.getBlock();
        return level.getBlockState(pos.offset(xOffset, 0, zOffset)).is(block)
                && level.getBlockState(pos.offset(xOffset + 1, 0, zOffset)).is(block)
                && level.getBlockState(pos.offset(xOffset, 0, zOffset + 1)).is(block)
                && level.getBlockState(pos.offset(xOffset + 1, 0, zOffset + 1)).is(block);
    }

    private boolean hasFlowers(LevelAccessor level, BlockPos pos) {
        for (BlockPos candidate : BlockPos.MutableBlockPos.betweenClosed(pos.below().north(2).west(2), pos.above().south(2).east(2))) {
            if (level.getBlockState(candidate).is(BlockTags.FLOWERS)) {
                return true;
            }
        }
        return false;
    }
}
""".trimIndent()

    private fun stripJavaString(value: String): String =
        value.trim().removePrefix("\"").removeSuffix("\"")

    private fun renderArmorMaterialRegistry(material: LegacyArmorMaterial): String {
        val imports = buildList {
            material.modImport?.let { add("import $it;") }
            material.extraImports.forEach { add("import $it;") }
            add("import net.minecraft.Util;")
            add("import net.minecraft.core.Holder;")
            add("import net.minecraft.core.registries.Registries;")
            add("import net.minecraft.resources.ResourceLocation;")
            add("import net.minecraft.sounds.SoundEvent;")
            add("import net.minecraft.sounds.SoundEvents;")
            add("import net.minecraft.world.item.ArmorItem;")
            add("import net.minecraft.world.item.ArmorMaterial;")
            add("import net.minecraft.world.item.Items;")
            add("import net.minecraft.world.item.crafting.Ingredient;")
            add("import net.neoforged.neoforge.registries.DeferredHolder;")
            add("import net.neoforged.neoforge.registries.DeferredRegister;")
            add("import java.util.EnumMap;")
            add("import java.util.HashMap;")
            add("import java.util.List;")
            add("import java.util.Map;")
            add("import java.util.function.Supplier;")
        }.distinct().joinToString("\n")

        val constants = material.constants.joinToString("\n\n") { renderArmorMaterialConstant(it) }
        return """
            package ${material.packageName};

            $imports

            public class ${material.enumName} {

                public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
                        DeferredRegister.create(Registries.ARMOR_MATERIAL, ${material.modIdExpr});

                private static final Map<Holder<ArmorMaterial>, String> TEXTURE_NAMES = new HashMap<>();

            $constants

                public static String getTextureName(Holder<ArmorMaterial> holder) {
                    return TEXTURE_NAMES.get(holder);
                }

                private static DeferredHolder<ArmorMaterial, ArmorMaterial> registerWithTexture(
                        String name,
                        String textureName,
                        EnumMap<ArmorItem.Type, Integer> defense,
                        int enchantmentValue,
                        Holder<SoundEvent> equipSound,
                        Supplier<Ingredient> repairIngredient,
                        float toughness,
                        float knockbackResistance
                ) {
                    List<ArmorMaterial.Layer> layers = "arcticarmor".equals(textureName)
                            ? List.of(
                                    new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(${material.modIdExpr}, textureName), "_dyed", true),
                                    new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(${material.modIdExpr}, textureName), "_overlay", false)
                            )
                            : List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(${material.modIdExpr}, textureName)));
                    DeferredHolder<ArmorMaterial, ArmorMaterial> holder = ARMOR_MATERIALS.register(name, () -> new ArmorMaterial(
                            defense,
                            enchantmentValue,
                            equipSound,
                            repairIngredient,
                            layers,
                            toughness,
                            knockbackResistance
                    ));
                    TEXTURE_NAMES.put(holder, textureName);
                    return holder;
                }

                private ${material.enumName}() {
                }
            }
        """.trimIndent()
    }

    private fun renderArmorMaterialConstant(constant: LegacyArmorMaterialConstant): String {
        val rawSound = constant.equipSound.removeSuffix(".get()")
        val sound = if (rawSound.startsWith("SoundEvents.") &&
            !rawSound.contains("ARMOR_EQUIP_")) {
            "Holder.direct($rawSound)"
        } else {
            rawSound
        }
        val repairIngredient = armorMaterialRepairIngredientSupplier(constant.repairIngredient)
        return """
                public static final DeferredHolder<ArmorMaterial, ArmorMaterial> ${constant.fieldName} =
                        registerWithTexture("${constant.registryName}", "${constant.textureName}",
                                Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                                    map.put(ArmorItem.Type.BOOTS, ${constant.protections[0]});
                                    map.put(ArmorItem.Type.LEGGINGS, ${constant.protections[1]});
                                    map.put(ArmorItem.Type.CHESTPLATE, ${constant.protections[2]});
                                    map.put(ArmorItem.Type.HELMET, ${constant.protections[3]});
                                    map.put(ArmorItem.Type.BODY, ${constant.bodyProtection ?: constant.protections[2]});
                                }),
                                ${constant.enchantmentValue},
                                $sound,
                                $repairIngredient,
                                ${constant.toughness},
                                ${constant.knockbackResistance}
                        );
        """.trimIndent()
    }

    private fun armorMaterialRepairIngredientSupplier(expression: String): String {
        val trimmed = expression.trim()
        if (trimmed.startsWith("() ->") ||
            trimmed.startsWith("Supplier.") ||
            Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*::""").containsMatchIn(trimmed)) {
            return trimmed
        }
        return if (trimmed == "Ingredient.EMPTY" ||
            trimmed.startsWith("Ingredient.of(") ||
            trimmed.startsWith("net.minecraft.world.item.crafting.Ingredient.")) {
            "() -> $trimmed"
        } else {
            trimmed
        }
    }

    private fun rewriteLegacyArmorMaterialConsumers(
        srcDir: Path,
        materials: List<LegacyArmorMaterial>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val armorItemConsumerFiles = collectArmorItemDerivedFiles(srcDir)
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                if (materials.any { it.path == javaFile }) return@forEach
                val original = javaFile.readText()
                var modified = original
                var touched = false

                for (material in materials) {
                    if (modified.contains(material.enumName)) {
                        modified = modified.replace(
                            Regex("""\(\(${Regex.escape(material.enumName)}\)\s*getMaterial\(\)\)\.getTextureName\(\)"""),
                            "${material.enumName}.getTextureName(this.material)"
                        )
                        modified = modified.replace(
                            Regex("""\(\(${Regex.escape(material.enumName)}\)\s*this\.material\)\.getTextureName\(\)"""),
                            "${material.enumName}.getTextureName(this.material)"
                        )
                        modified = modified.replace(
                            Regex("""\b${Regex.escape(material.enumName)}\s+([A-Za-z_$][\w$]*)"""),
                            "Holder<ArmorMaterial> $1"
                        )
                        touched = true
                    }
                }

                if (javaFile in armorItemConsumerFiles) {
                    val before = modified
                    modified = modified.replace(
                        Regex("""\bArmorMaterial\s+([A-Za-z_$][\w$]*)"""),
                        "Holder<ArmorMaterial> $1"
                    )
                    modified = rewriteLegacyArmorTextureOverride(modified)
                    if (modified != before) touched = true
                }

                if (touched && modified != original) {
                    if (modified.contains("Holder<ArmorMaterial>")) {
                        modified = ensureJavaImport(modified, "net.minecraft.core.Holder")
                        modified = ensureJavaImport(modified, "net.minecraft.world.item.ArmorMaterial")
                    }
                    if (Regex("""\bResourceLocation\b""").containsMatchIn(modified)) {
                        modified = ensureJavaImport(modified, "net.minecraft.resources.ResourceLocation")
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite ArmorItem consumers for 1.21 Holder<ArmorMaterial> and texture hook",
                        before = "legacy ArmorMaterial item constructor/texture hook",
                        after = "Holder<ArmorMaterial> constructor/ResourceLocation texture hook",
                        confidence = Confidence.HIGH,
                        ruleId = "build-legacy-armor-material-consumer"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(modified)
                    }
                }
            }
        return changes
    }

    private fun collectArmorItemDerivedFiles(srcDir: Path): Set<Path> {
        if (!srcDir.exists()) return emptySet()
        val declarations = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .flatMap { file -> parseJavaInheritanceDeclarations(file).stream() }
            .toList()
        if (declarations.isEmpty()) return emptySet()
        val declarationsByFqn = declarations.associateBy { it.fqn }
        val derived = linkedSetOf("net.minecraft.world.item.ArmorItem")

        var changed: Boolean
        do {
            changed = false
            for (declaration in declarations) {
                if (declaration.fqn in derived) continue
                val parentFqn = resolveJavaParentFqn(declaration, declarationsByFqn.keys)
                if (parentFqn in derived) {
                    derived += declaration.fqn
                    changed = true
                }
            }
        } while (changed)

        return declarations
            .filter { it.fqn in derived && it.fqn != "net.minecraft.world.item.ArmorItem" }
            .map { it.file }
            .toSet()
    }

    private fun collectJavaFilesExtending(javaFiles: List<Path>, baseTypes: Set<String>): Set<Path> {
        val declarations = javaFiles.flatMap { file -> parseJavaInheritanceDeclarations(file) }
        if (declarations.isEmpty()) return emptySet()
        val declarationsByFqn = declarations.associateBy { it.fqn }
        val derived = linkedSetOf<String>()
        baseTypes.forEach { base ->
            derived += base
            derived += base.substringAfterLast('.')
        }

        var changed: Boolean
        do {
            changed = false
            for (declaration in declarations) {
                if (declaration.fqn in derived || declaration.className in derived) continue
                val parentFqn = resolveJavaParentFqn(declaration, declarationsByFqn.keys)
                if (parentFqn in derived || parentFqn.substringAfterLast('.') in derived) {
                    derived += declaration.fqn
                    derived += declaration.className
                    changed = true
                }
            }
        } while (changed)

        return declarations
            .filter { it.fqn in derived || it.className in derived }
            .map { it.file }
            .toSet()
    }

    private fun parseJavaInheritanceDeclarations(file: Path): List<JavaInheritanceDeclaration> {
        val source = file.readText()
        val packageName = Regex("""(?m)^package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: ""
        val imports = linkedMapOf<String, String>()
        val wildcardImports = linkedSetOf<String>()
        Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;""")
            .findAll(source)
            .forEach { match ->
                val importName = match.groupValues[1]
                if (importName.endsWith(".*")) {
                    wildcardImports += importName.removeSuffix(".*")
                } else {
                    imports[importName.substringAfterLast('.')] = importName
                }
            }
        val id = """[A-Za-z_$][\w$]*"""
        return Regex("""(?m)\bclass\s+($id)(?:\s*<[^>{};]+>)?\s+extends\s+($id(?:\.$id)*)""")
            .findAll(source)
            .map { match ->
                JavaInheritanceDeclaration(
                    file = file,
                    packageName = packageName,
                    className = match.groupValues[1],
                    parentType = match.groupValues[2],
                    imports = imports,
                    wildcardImports = wildcardImports
                )
            }
            .toList()
    }

    private fun resolveJavaParentFqn(
        declaration: JavaInheritanceDeclaration,
        knownSourceTypes: Set<String>
    ): String {
        val rawParent = declaration.parentType.trim()
        if (rawParent == "ArmorItem" &&
            (declaration.imports["ArmorItem"] == "net.minecraft.world.item.ArmorItem" ||
                "net.minecraft.world.item" in declaration.wildcardImports)) {
            return "net.minecraft.world.item.ArmorItem"
        }
        if (rawParent.contains(".")) {
            if (rawParent.startsWith("net.")) return rawParent
            val firstSegment = rawParent.substringBefore('.')
            declaration.imports[firstSegment]?.let { imported ->
                return "$imported.${rawParent.substringAfter('.')}"
            }
            val samePackage = if (declaration.packageName.isBlank()) {
                rawParent
            } else {
                "${declaration.packageName}.$rawParent"
            }
            if (samePackage in knownSourceTypes) return samePackage
            return rawParent
        }
        declaration.imports[rawParent]?.let { return it }
        val samePackage = if (declaration.packageName.isBlank()) {
            rawParent
        } else {
            "${declaration.packageName}.$rawParent"
        }
        if (samePackage in knownSourceTypes) return samePackage
        if (rawParent == "ArmorItem") return "net.minecraft.world.item.ArmorItem"
        return rawParent
    }

    private fun rewriteLegacyArmorTextureOverride(content: String): String {
        var modified = content.replace(
            Regex("""public\s+(@Nullable\s+)?String\s+getArmorTexture\s*\(\s*ItemStack\s+([A-Za-z_$][\w$]*)\s*,\s*Entity\s+([A-Za-z_$][\w$]*)\s*,\s*(?:net\.minecraft\.world\.entity\.)?EquipmentSlot\s+([A-Za-z_$][\w$]*)\s*,\s*String\s+[A-Za-z_$][\w$]*\s*\)""")
        ) { match ->
            val nullable = match.groupValues[1].ifBlank { "" }
            "public ${nullable}ResourceLocation getArmorTexture(ItemStack ${match.groupValues[2]}, Entity ${match.groupValues[3]}, EquipmentSlot ${match.groupValues[4]}, ArmorMaterial.Layer layer, boolean innerModel)"
        }
        modified = modified.replace(
            Regex("""return\s+([A-Za-z_$][\w$.]*\.MODID)\s*\+\s*":textures/models/armor/"\s*\+\s*([^;]+?)\s*\+\s*"\.png";"""),
            """return ResourceLocation.fromNamespaceAndPath($1, "textures/models/armor/" + $2 + ".png");"""
        )
        modified = modified.replace(
            Regex("""return\s+([A-Za-z_$][\w$.]*\.ARMOR_DIR\s*\+\s*"[^"]+"\s*);"""),
            """return ResourceLocation.parse($1);"""
        )
        return modified
    }

    private fun registerLegacyArmorMaterials(
        srcDir: Path,
        materials: List<LegacyArmorMaterial>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("@Mod(") || !original.contains("IEventBus")) return@forEach
                val busName = Regex("""\bIEventBus\s+([A-Za-z_$][\w$]*)""")
                    .find(original)
                    ?.groupValues
                    ?.get(1)
                    ?: return@forEach

                var modified = original
                val packageName = Regex("""(?m)^package\s+([\w.]+);""")
                    .find(original)?.groupValues?.get(1)

                for (material in materials) {
                    val registration = "${material.enumName}.ARMOR_MATERIALS.register($busName);"
                    if (modified.contains(registration)) continue
                    if (packageName != material.packageName) {
                        modified = ensureJavaImport(modified, "${material.packageName}.${material.enumName}")
                    }
                    val lines = modified.lines().toMutableList()
                    val insertIdx = modBusDeclarationInsertIndex(lines, busName)
                    if (insertIdx >= 0) {
                        val indent = modBusDeclarationInsertionIndent(lines, insertIdx)
                        lines.add(insertIdx, "$indent$registration")
                        modified = lines.joinToString("\n")
                    }
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Register generated ArmorMaterial DeferredRegister on mod event bus",
                        before = "(missing ArmorMaterial registry registration)",
                        after = "ArmorMaterials.ARMOR_MATERIALS.register(modEventBus)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-register-armor-materials"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(modified)
                    }
                }
            }
        return changes
    }

    private fun insertModBusLineAfterBusDeclaration(content: String, busName: String, line: String): String? {
        val lines = content.lines().toMutableList()
        val insertIdx = modBusDeclarationInsertIndex(lines, busName)
        if (insertIdx < 0) return null
        val indent = modBusDeclarationInsertionIndent(lines, insertIdx)
        lines.add(insertIdx, "$indent$line")
        return lines.joinToString("\n")
    }

    private fun modBusDeclarationInsertIndex(lines: List<String>, busName: String): Int {
        val eventBusIdx = lines.indexOfFirst {
            Regex("""\bIEventBus\s+${Regex.escape(busName)}\b""").containsMatchIn(it)
        }
        if (eventBusIdx < 0) return -1
        var idx = eventBusIdx
        while (idx < lines.size && !lines[idx].contains("{") && !lines[idx].contains(";")) {
            idx++
        }
        return if (idx < lines.size) idx + 1 else -1
    }

    private fun modBusDeclarationInsertionIndent(lines: List<String>, insertIdx: Int): String {
        val anchor = lines.getOrNull(insertIdx - 1) ?: return "        "
        val base = anchor.takeWhile { it.isWhitespace() }
        return if (anchor.trimEnd().endsWith("{")) base + "    " else base
    }

    private fun rewriteLegacyTreeGrowers(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val growers = mutableListOf<LegacyTreeGrower>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.readText()
                val classMatch = Regex("""\bclass\s+(\w+)\s+extends\s+(AbstractMegaTreeGrower|AbstractTreeGrower|TreeGrower)\b""")
                    .find(text)
                    ?: return@forEach
                if (!text.contains("getConfiguredFeature(") && !text.contains("getConfiguredMegaFeature(")) return@forEach
                val className = classMatch.groupValues[1]
                growers.add(LegacyTreeGrower(className, javaFile))
            }

        if (growers.isEmpty()) return changes

        val compatPackage = detectRequiredCompatShimPackage(projectDir, "legacy tree grower compatibility base")

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .filter { javaFile -> growers.none { it.path == javaFile } }
            .forEach { javaFile ->
                val original = javaFile.readText()
                var modified = original
                var touched = false

                for (grower in growers) {
                    if (modified.contains("AbstractTreeGrower") || modified.contains("AbstractMegaTreeGrower")) {
                        touched = true
                        modified = modified
                            .replace(
                                "import net.minecraft.world.level.block.grower.AbstractTreeGrower;",
                                "import net.minecraft.world.level.block.grower.TreeGrower;"
                            )
                            .replace(
                                "import net.minecraft.world.level.block.grower.AbstractMegaTreeGrower;",
                                "import net.minecraft.world.level.block.grower.TreeGrower;"
                            )
                            .replace(Regex("""\bAbstractMegaTreeGrower\b"""), "TreeGrower")
                            .replace(Regex("""\bAbstractTreeGrower\b"""), "TreeGrower")
                    }
                }

                if (touched) {
                    modified = ensureJavaImport(modified, "net.minecraft.world.level.block.grower.TreeGrower")

                    if (!dryRun) javaFile.writeText(modified)
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite legacy AbstractTreeGrower call-site types to TreeGrower",
                        before = "AbstractTreeGrower/AbstractMegaTreeGrower type references",
                        after = "TreeGrower type references",
                        confidence = Confidence.HIGH,
                        ruleId = "build-tree-grower-compat"
                    ))
                }
            }

        for (grower in growers) {
            val original = grower.path.readText()
            val migrated = migrateLegacyTreeGrowerSubclass(original, compatPackage)
            if (migrated != original) {
                if (!dryRun) grower.path.writeText(migrated)
                changes.add(Change(
                    file = grower.path,
                    line = 1,
                    description = "Retarget legacy AbstractTreeGrower subclass to generated TreeGrower compatibility base",
                    before = "class ${grower.className} extends AbstractTreeGrower/AbstractMegaTreeGrower",
                    after = "class ${grower.className} extends ModPorterAbstractTreeGrower",
                    confidence = Confidence.HIGH,
                    ruleId = "build-tree-grower-helper"
                ))
            }
        }

        changes.addAll(ensureLegacyTreeGrowerCompatBase(projectDir, compatPackage, dryRun))
        changes.addAll(ensureAccessTransformerEntries(
            projectDir,
            listOf("public-f net.minecraft.world.level.block.grower.TreeGrower"),
            dryRun,
            "build-tree-grower-unfinal-at",
            "Allow legacy custom tree growers to extend TreeGrower through a generated compatibility base"
        ))

        val obsoleteShim = srcDir.resolve("net/minecraft/world/level/block/grower/AbstractTreeGrower.java")
        if (obsoleteShim.exists()) {
            if (!dryRun) java.nio.file.Files.delete(obsoleteShim)
            changes.add(Change(
                file = obsoleteShim,
                line = 1,
                description = "Remove obsolete AbstractTreeGrower shim from Minecraft package",
                before = "net.minecraft.world.level.block.grower.AbstractTreeGrower shim",
                after = "(removed)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-abstract-tree-grower-shim"
            ))
        }

        return changes
    }

    private fun addLegacyMmlibShims(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val localRecipeBasePackage = detectLocalRecipeBasePackage(srcDir)
        if (localRecipeBasePackage != null) {
            var needsLocalRecipeBase = false
            java.nio.file.Files.walk(srcDir)
                .filter { it.extension == "java" }
                .forEach { javaFile ->
                    val original = javaFile.readText()
                    var migrated = original
                        .replace("import cn.mcmod_mmf.mmlib.fluid.FluidIngredient;", "import $localRecipeBasePackage.FluidIngredient;")
                        .replace("import cn.mcmod_mmf.mmlib.recipe.AbstractRecipe;", "import $localRecipeBasePackage.AbstractRecipe;")
                        .replace("import cn.mcmod_mmf.mmlib.recipe.AbstractRecipeSerializer;", "import $localRecipeBasePackage.AbstractRecipeSerializer;")
                        .replace("import cn.mcmod_mmf.mmlib.recipe.ChanceResult;", "import $localRecipeBasePackage.ChanceResult;")
                        .replace("cn.mcmod_mmf.mmlib.fluid.FluidIngredient", "$localRecipeBasePackage.FluidIngredient")
                        .replace("cn.mcmod_mmf.mmlib.recipe.AbstractRecipeSerializer", "$localRecipeBasePackage.AbstractRecipeSerializer")
                        .replace("cn.mcmod_mmf.mmlib.recipe.AbstractRecipe", "$localRecipeBasePackage.AbstractRecipe")
                        .replace("cn.mcmod_mmf.mmlib.recipe.ChanceResult", "$localRecipeBasePackage.ChanceResult")

                    if (migrated != original) {
                        needsLocalRecipeBase = true
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate removed MMLib recipe/fluid helpers to a project-local recipe base package",
                            before = "cn.mcmod_mmf.mmlib.recipe / cn.mcmod_mmf.mmlib.fluid",
                            after = localRecipeBasePackage,
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-mmlib-recipe-base"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(migrated)
                        }
                    }
                }

            if (needsLocalRecipeBase) {
                changes.addAll(addLocalMmlibRecipeBase(srcDir, localRecipeBasePackage, dryRun))
            }
        }

        val obsoleteShimDir = srcDir.resolve("cn/mcmod_mmf/mmlib")
        if (obsoleteShimDir.exists()) {
            changes.add(Change(
                file = obsoleteShimDir,
                line = 1,
                description = "Remove generated MMLib package shims and use the real MMLib dependency",
                before = "src/main/java/cn/mcmod_mmf/mmlib/* generated shims",
                after = "(removed)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-mmlib-package-shims"
            ))
            if (!dryRun) {
                java.nio.file.Files.walk(obsoleteShimDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
        return changes
    }

    private fun detectLocalRecipeBasePackage(srcDir: Path): String? {
        val ownerPackages = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.extension == "java" }
            .forEach { javaFile ->
                val source = javaFile.readText()
                val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                    .find(source)
                    ?.groupValues
                    ?.get(1)
                    ?: return@forEach
                if (usesRemovedMmlibRecipeBaseType(source)) {
                    ownerPackages.add(packageName)
                }
            }
        val baseOwnerPackage = commonJavaPackagePrefix(ownerPackages) ?: return null
        return "$baseOwnerPackage.base"
    }

    private fun usesRemovedMmlibRecipeBaseType(source: String): Boolean {
        if (listOf("AbstractRecipe", "FluidIngredient", "ChanceResult").none { source.contains(it) }) return false
        val code = maskJavaCommentsAndLiterals(source)
        val removedType = """(?:cn\.mcmod_mmf\.mmlib\.(?:recipe|fluid)\.)?(?:AbstractRecipe|AbstractRecipeSerializer|FluidIngredient|ChanceResult)"""
        val importsRemovedType = Regex("""(?m)^\s*import\s+cn\.mcmod_mmf\.mmlib\.(?:recipe|fluid)\.(?:AbstractRecipe|AbstractRecipeSerializer|FluidIngredient|ChanceResult)\s*;""")
            .containsMatchIn(code)
        return Regex("""\b(?:extends|implements)\s+$removedType\b""").containsMatchIn(code) ||
            Regex("""\b$removedType\s*<""").containsMatchIn(code) ||
            Regex("""\b$removedType\s+[A-Za-z_$][\w$]*\b""").containsMatchIn(code) ||
            (importsRemovedType && Regex("""\b(?:AbstractRecipe|AbstractRecipeSerializer|FluidIngredient|ChanceResult)\b""").containsMatchIn(code))
    }

    private fun commonJavaPackagePrefix(packages: Collection<String>): String? {
        if (packages.isEmpty()) return null
        val splitPackages = packages.map { it.split('.') }
        val first = splitPackages.first()
        val common = mutableListOf<String>()
        for ((index, segment) in first.withIndex()) {
            if (splitPackages.all { index < it.size && it[index] == segment }) {
                common.add(segment)
            } else {
                break
            }
        }
        return common.takeIf { it.size >= 2 }?.joinToString(".")
    }

    private fun addLocalMmlibRecipeBase(srcDir: Path, basePackage: String, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val packageDir = srcDir.resolve(basePackage.replace('.', '/'))
        val sources = linkedMapOf(
            "AbstractRecipe.java" to localMmlibAbstractRecipeSource(basePackage),
            "ChanceResult.java" to localMmlibChanceResultSource(basePackage),
            "FluidIngredient.java" to localMmlibFluidIngredientSource(basePackage),
            "AbstractRecipeSerializer.java" to localMmlibRecipeSerializerSource(basePackage)
        )

        for ((fileName, source) in sources) {
            val file = packageDir.resolve(fileName)
            if (file.exists()) continue
            changes.add(Change(
                file = file,
                line = 1,
                description = "Add project-local replacement for removed MMLib recipe helper $fileName",
                before = "(missing)",
                after = "$basePackage.$fileName",
                confidence = Confidence.HIGH,
                ruleId = "build-add-local-mmlib-recipe-base"
            ))
            if (!dryRun) {
                packageDir.createDirectories()
                file.writeText(source)
            }
        }
        return changes
    }

    private fun localMmlibAbstractRecipeSource(basePackage: String): String = """
package $basePackage;

import com.google.gson.annotations.Expose;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * Project-local replacement for MMLib recipe base helpers removed in MMLib 1.21.1.
 */
public abstract class AbstractRecipe implements Recipe<RecipeWrapper> {
    public String group = "";
    @Expose
    public float experience;
    @Expose
    public int recipeTime;

    public float getExperience() {
        return experience;
    }

    public int getRecipeTime() {
        return recipeTime;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public abstract ItemStack getResultItem(HolderLookup.Provider registries);

    @Override
    public boolean isSpecial() {
        return true;
    }
}
""".trimIndent()

    private fun localMmlibChanceResultSource(basePackage: String): String = """
package $basePackage;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

public record ChanceResult(ItemStack stack, float chance) {
    public static final ChanceResult EMPTY = new ChanceResult(ItemStack.EMPTY, 0.0F);

    public ItemStack rollOutput(RandomSource random, int fortuneLevel) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        float adjustedChance = Math.min(1.0F, chance + Math.max(0, fortuneLevel) * 0.1F);
        return random.nextFloat() < adjustedChance ? stack.copy() : ItemStack.EMPTY;
    }
}
""".trimIndent()

    private fun localMmlibFluidIngredientSource(basePackage: String): String = """
package $basePackage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

public abstract class FluidIngredient implements Predicate<FluidStack> {
    public static final FluidIngredient EMPTY = new EmptyFluidIngredient();

    protected int amountRequired;
    private List<FluidStack> matchingFluidStacks;

    public static FluidIngredient fromTag(TagKey<Fluid> tag, int amount) {
        return new TagFluidIngredient(tag, amount);
    }

    public static FluidIngredient fromFluid(Fluid fluid, int amount) {
        return new SingleFluidIngredient(fluid, amount);
    }

    public static FluidIngredient fromFluid(Supplier<? extends Fluid> fluid, int amount) {
        return fromFluid(fluid.get(), amount);
    }

    public static FluidIngredient fromFluidStack(FluidStack stack) {
        return stack == null || stack.isEmpty() ? EMPTY : fromFluid(stack.getFluid(), stack.getAmount());
    }

    public int getAmount() {
        return amountRequired;
    }

    public int getRequiredAmount() {
        return amountRequired;
    }

    public List<FluidStack> getMatchingFluidStacks() {
        if (matchingFluidStacks == null) {
            matchingFluidStacks = determineMatchingFluidStacks();
        }
        return matchingFluidStacks;
    }

    public FluidStack[] getStacks() {
        return getMatchingFluidStacks().toArray(FluidStack[]::new);
    }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        writeInternal(json);
        json.addProperty("amount", amountRequired);
        return json;
    }

    public static boolean isFluidIngredient(JsonElement json) {
        if (json == null || !json.isJsonObject()) return false;
        JsonObject obj = json.getAsJsonObject();
        return obj.has("fluid") || obj.has("tag") || obj.has("fluidTag") || obj.has("null_fluid");
    }

    public static FluidIngredient deserialize(JsonElement json) {
        if (json == null || !json.isJsonObject()) return EMPTY;
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("null_fluid")) return EMPTY;
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1000;
        if (obj.has("tag") || obj.has("fluidTag")) {
            ResourceLocation tagId = ResourceLocation.parse(obj.has("tag") ? obj.get("tag").getAsString() : obj.get("fluidTag").getAsString());
            return fromTag(TagKey.create(Registries.FLUID, tagId), amount);
        }
        if (obj.has("fluid")) {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(obj.get("fluid").getAsString()));
            return fromFluid(fluid, amount);
        }
        return EMPTY;
    }

    @Override
    public boolean test(FluidStack stack) {
        return stack != null && stack.getAmount() >= amountRequired && testInternal(stack);
    }

    protected abstract boolean testInternal(FluidStack stack);
    protected abstract List<FluidStack> determineMatchingFluidStacks();
    protected abstract void writeInternal(JsonObject json);

    private static final class EmptyFluidIngredient extends FluidIngredient {
        private EmptyFluidIngredient() {
            this.amountRequired = 0;
        }

        @Override
        public boolean test(FluidStack stack) {
            return false;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return false;
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.emptyList();
        }

        @Override
        protected void writeInternal(JsonObject json) {
        }
    }

    private static final class SingleFluidIngredient extends FluidIngredient {
        private final Fluid fluid;

        private SingleFluidIngredient(Fluid fluid, int amount) {
            this.fluid = fluid;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().isSame(fluid);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.singletonList(new FluidStack(fluid, amountRequired));
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
        }
    }

    private static final class TagFluidIngredient extends FluidIngredient {
        private final TagKey<Fluid> tag;

        private TagFluidIngredient(TagKey<Fluid> tag, int amount) {
            this.tag = tag;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().builtInRegistryHolder().is(tag);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            List<FluidStack> stacks = new ArrayList<>();
            BuiltInRegistries.FLUID.getTag(tag).ifPresent(holders ->
                holders.forEach(holder -> stacks.add(new FluidStack(holder.value(), amountRequired)))
            );
            return stacks;
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("tag", tag.location().toString());
        }
    }
}
""".trimIndent()

    private fun localMmlibRecipeSerializerSource(basePackage: String): String = """
package $basePackage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.lang.reflect.Type;
import java.util.stream.Stream;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.fluids.FluidStack;

public class AbstractRecipeSerializer<T extends AbstractRecipe> implements RecipeSerializer<T> {
    private final Class<T> recipeClass;
    private final Gson gson;
    private final MapCodec<T> mapCodec;
    private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec;

    public AbstractRecipeSerializer(Class<T> recipeClass) {
        this.recipeClass = recipeClass;
        this.gson = createGson();
        this.mapCodec = createMapCodec();
        this.streamCodec = createStreamCodec();
    }

    private Gson createGson() {
        return new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .registerTypeHierarchyAdapter(Ingredient.class, new IngredientAdapter())
                .registerTypeHierarchyAdapter(ItemStack.class, new ItemStackAdapter())
                .registerTypeHierarchyAdapter(FluidStack.class, new FluidStackAdapter())
                .registerTypeHierarchyAdapter(FluidIngredient.class, new FluidIngredientAdapter())
                .registerTypeHierarchyAdapter(ChanceResult.class, new ChanceResultAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<Ingredient>>(){}.getType(), new IngredientListAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<ItemStack>>(){}.getType(), new ItemStackListAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<ChanceResult>>(){}.getType(), new ChanceResultListAdapter())
                .create();
    }

    private MapCodec<T> createMapCodec() {
        return new MapCodec<>() {
            @Override
            public <O> RecordBuilder<O> encode(T input, DynamicOps<O> ops, RecordBuilder<O> prefix) {
                JsonObject json = toJson(input);
                for (var entry : json.entrySet()) {
                    prefix = prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
                }
                return prefix;
            }

            @Override
            public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
                try {
                    JsonObject json = new JsonObject();
                    input.entries().forEach(pair -> {
                        String key = ops.getStringValue(pair.getFirst()).result().orElse("");
                        if (!key.isEmpty()) {
                            json.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
                        }
                    });
                    return DataResult.success(fromJson(json));
                } catch (Exception e) {
                    return DataResult.error(() -> "Failed to decode recipe: " + e.getMessage());
                }
            }

            @Override
            public <O> Stream<O> keys(DynamicOps<O> ops) {
                return Stream.empty();
            }
        };
    }

    private StreamCodec<RegistryFriendlyByteBuf, T> createStreamCodec() {
        return StreamCodec.of(
                (buf, recipe) -> buf.writeUtf(gson.toJson(recipe)),
                buf -> fromJson(com.google.gson.JsonParser.parseString(buf.readUtf(32767)).getAsJsonObject())
        );
    }

    @Override
    public MapCodec<T> codec() {
        return mapCodec;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec;
    }

    public T fromJson(JsonObject json) {
        return gson.fromJson(json, recipeClass);
    }

    public JsonObject toJson(T recipe) {
        return gson.toJsonTree(recipe).getAsJsonObject();
    }

    private static class IngredientAdapter implements JsonSerializer<Ingredient>, JsonDeserializer<Ingredient> {
        @Override
        public JsonElement serialize(Ingredient src, Type typeOfSrc, JsonSerializationContext context) {
            return Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public Ingredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Ingredient.CODEC.decode(JsonOps.INSTANCE, json).result()
                    .map(com.mojang.datafixers.util.Pair::getFirst)
                    .orElse(Ingredient.EMPTY);
        }
    }

    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src.isEmpty()) return JsonNull.INSTANCE;
            return ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return decodeItemStack(json, "ItemStack");
        }
    }

    private static ItemStack decodeItemStack(JsonElement json, String contextName) throws JsonParseException {
        if (json == null || json.isJsonNull()) return ItemStack.EMPTY;
        JsonElement normalized = normalizeLegacyItemStackJson(json);
        var decoded = ItemStack.CODEC.decode(JsonOps.INSTANCE, normalized);
        return decoded.result()
                .map(com.mojang.datafixers.util.Pair::getFirst)
                .orElseThrow(() -> new JsonParseException(
                        "Failed to decode " + contextName + ": " + decoded.error().map(Object::toString).orElse("unknown codec error")));
    }

    private static JsonElement normalizeLegacyItemStackJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return JsonNull.INSTANCE;
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            JsonObject normalized = new JsonObject();
            normalized.addProperty("id", json.getAsString());
            return normalized;
        }
        if (!json.isJsonObject()) return json;
        JsonObject obj = json.getAsJsonObject();
        if (!obj.has("item") || obj.has("id")) return json;

        JsonObject normalized = new JsonObject();
        normalized.add("id", obj.get("item"));
        copyIfPresent(obj, normalized, "count");
        copyIfPresent(obj, normalized, "components");
        if (obj.has("nbt")) {
            JsonObject components = normalized.has("components") && normalized.get("components").isJsonObject()
                    ? normalized.getAsJsonObject("components")
                    : new JsonObject();
            components.add("minecraft:custom_data", obj.get("nbt"));
            normalized.add("components", components);
        }
        return normalized;
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key));
        }
    }

    private static class FluidStackAdapter implements JsonSerializer<FluidStack>, JsonDeserializer<FluidStack> {
        @Override
        public JsonElement serialize(FluidStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src.isEmpty()) return JsonNull.INSTANCE;
            return FluidStack.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public FluidStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return FluidStack.EMPTY;
            JsonElement normalized = normalizeLegacyFluidStackJson(json);
            if (normalized == null || normalized.isJsonNull()) return FluidStack.EMPTY;
            return FluidStack.CODEC.decode(JsonOps.INSTANCE, normalized).result()
                    .map(com.mojang.datafixers.util.Pair::getFirst)
                    .orElse(FluidStack.EMPTY);
        }
    }

    private static JsonElement normalizeLegacyFluidStackJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return JsonNull.INSTANCE;
        if (!json.isJsonObject()) return json;
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("fluid")
                && obj.get("fluid").isJsonPrimitive()
                && "minecraft:empty".equals(obj.get("fluid").getAsString())) {
            return JsonNull.INSTANCE;
        }
        if (!obj.has("fluid") || obj.has("id")) return json;

        JsonObject normalized = new JsonObject();
        normalized.add("id", obj.get("fluid"));
        copyIfPresent(obj, normalized, "amount");
        copyIfPresent(obj, normalized, "components");
        if (obj.has("nbt")) {
            JsonObject components = normalized.has("components") && normalized.get("components").isJsonObject()
                    ? normalized.getAsJsonObject("components")
                    : new JsonObject();
            components.add("minecraft:custom_data", obj.get("nbt"));
            normalized.add("components", components);
        }
        return normalized;
    }

    private static class FluidIngredientAdapter implements JsonSerializer<FluidIngredient>, JsonDeserializer<FluidIngredient> {
        @Override
        public JsonElement serialize(FluidIngredient src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == FluidIngredient.EMPTY) return JsonNull.INSTANCE;
            return src.serialize();
        }

        @Override
        public FluidIngredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return FluidIngredient.EMPTY;
            return FluidIngredient.deserialize(json);
        }
    }

    private static class ChanceResultAdapter implements JsonSerializer<ChanceResult>, JsonDeserializer<ChanceResult> {
        @Override
        public JsonElement serialize(ChanceResult src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject json = new JsonObject();
            json.add("item", ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, src.stack()).result().orElse(JsonNull.INSTANCE));
            json.addProperty("chance", src.chance());
            return json;
        }

        @Override
        public ChanceResult deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || !json.isJsonObject()) return ChanceResult.EMPTY;
            JsonObject obj = json.getAsJsonObject();
            ItemStack stack = decodeItemStack(chanceResultStackJson(obj), "ChanceResult.item");
            float chance = obj.has("chance") ? obj.get("chance").getAsFloat() : 1.0F;
            return new ChanceResult(stack, chance);
        }
    }

    private static JsonElement chanceResultStackJson(JsonObject obj) {
        if (obj.has("stack")) return obj.get("stack");
        if (!obj.has("item")) return JsonNull.INSTANCE;
        JsonElement item = obj.get("item");
        if (item != null && item.isJsonObject()) return item;

        JsonObject stack = new JsonObject();
        stack.add("item", item);
        copyIfPresent(obj, stack, "count");
        copyIfPresent(obj, stack, "components");
        copyIfPresent(obj, stack, "nbt");
        return stack;
    }

    private static class IngredientListAdapter implements JsonSerializer<NonNullList<Ingredient>>, JsonDeserializer<NonNullList<Ingredient>> {
        @Override
        public JsonElement serialize(NonNullList<Ingredient> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (Ingredient ingredient : src) {
                array.add(context.serialize(ingredient));
            }
            return array;
        }

        @Override
        public NonNullList<Ingredient> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<Ingredient> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    Ingredient ingredient = context.deserialize(element, Ingredient.class);
                    list.add(ingredient != null ? ingredient : Ingredient.EMPTY);
                }
            }
            return list;
        }
    }

    private static class ItemStackListAdapter implements JsonSerializer<NonNullList<ItemStack>>, JsonDeserializer<NonNullList<ItemStack>> {
        @Override
        public JsonElement serialize(NonNullList<ItemStack> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (ItemStack stack : src) {
                array.add(context.serialize(stack));
            }
            return array;
        }

        @Override
        public NonNullList<ItemStack> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<ItemStack> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    ItemStack stack = context.deserialize(element, ItemStack.class);
                    list.add(stack != null ? stack : ItemStack.EMPTY);
                }
            }
            return list;
        }
    }

    private static class ChanceResultListAdapter implements JsonSerializer<NonNullList<ChanceResult>>, JsonDeserializer<NonNullList<ChanceResult>> {
        @Override
        public JsonElement serialize(NonNullList<ChanceResult> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (ChanceResult result : src) {
                array.add(context.serialize(result));
            }
            return array;
        }

        @Override
        public NonNullList<ChanceResult> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<ChanceResult> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    ChanceResult result = context.deserialize(element, ChanceResult.class);
                    list.add(result != null ? result : ChanceResult.EMPTY);
                }
            }
            return list;
        }
    }
}
""".trimIndent()

    private fun rewriteLegacyCapabilityHooks(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("LazyOptional") &&
                    !original.contains("getCapability(") &&
                    !original.contains("invalidateCaps(")) {
                    return@forEach
                }

                var modified = original
                modified = Regex(
                    """(?ms)^([ \t]*)(?:@Override\s+)?public\s+<T>\s+LazyOptional<T>\s+getCapability\s*\(([^)]*)\)\s*\{\s*return\s+super\.getCapability\s*\([^;]*\);\s*\}""",
                    RegexOption.MULTILINE
                ).replace(modified, "")
                if (!Regex("""\bCapability\s*<""").containsMatchIn(modified)) {
                    modified = Regex("""(?m)^[ \t]*import\s+[\w.]+\.compat\.Capability;\s*\r?\n""")
                        .replace(modified, "")
                    modified = Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.capabilities\.Capability;\s*\r?\n""")
                        .replace(modified, "")
                }
                modified = Regex(
                    """(?m)^([ \t]*)@Override\s*\r?\n([ \t]*public\s+void\s+invalidateCaps\s*\()"""
                ).replace(modified) { match ->
                    match.groupValues[1] + match.groupValues[2]
                }
                modified = Regex(
                    """(?m)^[ \t]*super\.invalidateCaps\(\);\s*\r?\n"""
                ).replace(modified, "")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite legacy capability fallback hooks for removed Forge capability overrides",
                        before = "super.getCapability(...) / super.invalidateCaps()",
                        after = "delete super-only capability hook / local invalidation only",
                        confidence = Confidence.HIGH,
                        ruleId = "build-rewrite-legacy-capability-hooks"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun addLegacyCapabilityShims(projectDir: Path, dryRun: Boolean, errors: MutableList<String>): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        var resolvedCompatPackage: String? = null
        var failedCompatPackage = false
        fun compatPackage(): String? {
            resolvedCompatPackage?.let { return it }
            if (failedCompatPackage) return null
            return try {
                detectRequiredCompatShimPackage(projectDir, "legacy source compatibility shims")
                    .also { resolvedCompatPackage = it }
            } catch (e: Exception) {
                failedCompatPackage = true
                errors.add(e.message ?: "Cannot derive generated compat shim package for legacy source compatibility shims")
                null
            }
        }

        var needsLazyOptional = false
        var needsCapability = false
        var needsConditionalRecipe = false
        var needsRenderUtils = false
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("net/neoforged/neoforge/") }
            .forEach { javaFile ->
                val text = javaFile.readText()
                val staleLazyOptionalRemoved = removeStaleLazyOptionalImports(text)
                if (staleLazyOptionalRemoved != text) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove stale LazyOptional compatibility import after structural capability migration",
                        before = "unused LazyOptional import",
                        after = "(removed)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-stale-lazyoptional-import"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(staleLazyOptionalRemoved)
                    }
                }
                val textForCapabilities = staleLazyOptionalRemoved
                var capabilitySource = textForCapabilities
                val hasLazyOptionalRelocationSource = listOf(
                    "net.minecraftforge.common.util.LazyOptional",
                    "net.neoforged.neoforge.common.util.LazyOptional",
                    "net.minecraftforge.common.util.*",
                    "net.neoforged.neoforge.common.util.*",
                    "com.modporter.compat.LazyOptional"
                ).any { textForCapabilities.contains(it) }
                if (hasLazyOptionalRelocationSource) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedLazyOptionalSource = relocateLazyOptionalImports(textForCapabilities, packageName)
                    if (relocatedLazyOptionalSource != textForCapabilities) {
                        needsLazyOptional = true
                        capabilitySource = relocatedLazyOptionalSource
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate LazyOptional compatibility import out of NeoForge package",
                            before = "net.neoforged.neoforge.common.util.LazyOptional",
                            after = "$packageName.LazyOptional",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-lazyoptional-import"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedLazyOptionalSource)
                        }
                    }
                } else if (sourceWithoutJavaImports(textForCapabilities).contains("LazyOptional")) {
                    if (compatPackage() != null) {
                        needsLazyOptional = true
                    }
                }

                var conditionalSource = capabilitySource
                val hasCapabilityRelocationSource = listOf(
                    "net.neoforged.neoforge.capabilities.Capability",
                    "com.modporter.compat.Capability"
                ).any { capabilitySource.contains(it) }
                if (hasCapabilityRelocationSource) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedCapability = relocateCapabilityImports(capabilitySource, packageName)
                    if (relocatedCapability != capabilitySource) {
                        needsCapability = true
                        conditionalSource = relocatedCapability
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate Capability compatibility import out of NeoForge package",
                            before = "net.neoforged.neoforge.capabilities.Capability",
                            after = "$packageName.Capability",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-capability-import"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedCapability)
                        }
                    }
                } else if (capabilitySource.contains("net.neoforged.neoforge.capabilities.Capability") ||
                    Regex("""\bCapability\s*<""").containsMatchIn(capabilitySource)) {
                    if (compatPackage() != null) {
                        needsCapability = true
                    }
                }

                var renderSource = conditionalSource
                val hasConditionalRecipeRelocationSource = listOf(
                    "net.minecraftforge.common.crafting.ConditionalRecipe",
                    "net.neoforged.neoforge.common.crafting.ConditionalRecipe"
                ).any { conditionalSource.contains(it) }
                if (hasConditionalRecipeRelocationSource) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedConditionalRecipe = relocateConditionalRecipeImports(conditionalSource, packageName)
                    if (relocatedConditionalRecipe != conditionalSource) {
                        needsConditionalRecipe = true
                        renderSource = relocatedConditionalRecipe
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate removed ConditionalRecipe builder API to generated NeoForge RecipeOutput adapter",
                            before = "net.neoforged.neoforge.common.crafting.ConditionalRecipe",
                            after = "$packageName.ConditionalRecipe",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-conditionalrecipe-import"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedConditionalRecipe)
                        }
                    }
                } else if (conditionalSource.contains("ConditionalRecipe.builder()") ||
                    Regex("""\bConditionalRecipe\.Builder\b""").containsMatchIn(conditionalSource)) {
                    if (compatPackage() != null) {
                        needsConditionalRecipe = true
                    }
                }
                if (renderSource.contains("cn.mcmod_mmf.mmlib.client.RenderUtils") ||
                    renderSource.contains("RenderUtils.renderFluidStack(")) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedRenderUtils = relocateRenderUtilsImportsAndCalls(renderSource, packageName)
                    if (relocatedRenderUtils != renderSource) {
                        needsRenderUtils = true
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate removed MMLib RenderUtils fluid helper to generated GUI fluid renderer",
                            before = "cn.mcmod_mmf.mmlib.client.RenderUtils.renderFluidStack(...)",
                            after = "$packageName.RenderUtils.renderFluidStack(guiGraphics, ...)",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-renderutils-fluid"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedRenderUtils)
                        }
                    }
                }
            }

        val compatPackage = resolvedCompatPackage
        val compatPath = compatPackage?.replace('.', '/')

        if (needsLazyOptional && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/LazyOptional.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add LazyOptional source compatibility shim",
                    before = "(missing)",
                    after = "LazyOptional shim",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-lazyoptional-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyLazyOptionalShim(compatPackage))
                }
            }
        }

        if (needsCapability && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/Capability.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add Capability type source compatibility shim",
                    before = "(missing)",
                    after = "Capability shim",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-capability-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyCapabilityShim(compatPackage))
                }
            }
        }

        if (needsConditionalRecipe && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/ConditionalRecipe.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add ConditionalRecipe builder adapter backed by RecipeOutput.withConditions",
                    before = "(missing)",
                    after = "ConditionalRecipe adapter",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-conditionalrecipe-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyConditionalRecipeShim(compatPackage))
                }
            }
        }

        if (needsRenderUtils && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/RenderUtils.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add GUI fluid render helper compatible with removed MMLib RenderUtils.renderFluidStack",
                    before = "(missing)",
                    after = "RenderUtils fluid renderer",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-renderutils-fluid-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyRenderUtilsShim(compatPackage))
                }
            }
        }

        if (!failedCompatPackage) {
            changes.addAll(removeOldGlobalCapabilityShims(srcDir, dryRun))
        }

        return changes
    }

    private fun detectRequiredCompatShimPackage(projectDir: Path, reason: String): String {
        val modId = detectUniqueProjectModId(projectDir)
            ?: error("Cannot derive generated compat shim package for $reason: missing or ambiguous @Mod annotation and mod metadata mod id")
        val packageSegment = sanitizeRequiredPackageSegment(modId, reason)
        return "com.modporter.generated.$packageSegment.compat"
    }

    private fun sanitizeRequiredPackageSegment(value: String, reason: String): String {
        val sanitized = value.lowercase()
            .replace(Regex("""[^a-z0-9_]"""), "_")
            .trim('_')
        require(sanitized.isNotBlank()) {
            "Cannot derive generated compat shim package for $reason: mod id '$value' has no valid Java package segment"
        }
        return if (sanitized.first().isDigit()) "m$sanitized" else sanitized
    }

    private fun removeOldGlobalCapabilityShims(srcDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val oldShims = listOf(
            srcDir.resolve("com/modporter/compat/LazyOptional.java") to "build-remove-global-lazyoptional-shim",
            srcDir.resolve("com/modporter/compat/Capability.java") to "build-remove-global-capability-shim"
        )
        for ((shim, ruleId) in oldShims) {
            if (!shim.exists()) continue
            val text = shim.readText()
            val generatedByModporter = text.contains("generated by modporter") &&
                text.contains("package com.modporter.compat;")
            if (!generatedByModporter) continue
            changes.add(Change(
                file = shim,
                line = 1,
                description = "Remove old global capability shim package to avoid NeoForge module split-package conflicts",
                before = "com.modporter.compat",
                after = "per-mod generated capability shim package",
                confidence = Confidence.HIGH,
                ruleId = ruleId
            ))
            if (!dryRun) {
                shim.deleteIfExists()
            }
        }
        return changes
    }

    private fun relocateLazyOptionalImports(text: String, compatPackage: String): String =
        text
            .replace("import net.minecraftforge.common.util.LazyOptional;", "import $compatPackage.LazyOptional;")
            .replace("import net.neoforged.neoforge.common.util.LazyOptional;", "import $compatPackage.LazyOptional;")
            .replace("import net.minecraftforge.common.util.*;", "import $compatPackage.LazyOptional;")
            .replace("import net.neoforged.neoforge.common.util.*;", "import $compatPackage.LazyOptional;")
            .replace("import com.modporter.compat.LazyOptional;", "import $compatPackage.LazyOptional;")
            .replace("net.minecraftforge.common.util.LazyOptional", "$compatPackage.LazyOptional")
            .replace("net.neoforged.neoforge.common.util.LazyOptional", "$compatPackage.LazyOptional")
            .replace("com.modporter.compat.LazyOptional", "$compatPackage.LazyOptional")

    private fun removeStaleLazyOptionalImports(text: String): String {
        if (!text.contains("LazyOptional")) return text
        val withoutImports = sourceWithoutJavaImports(text)
        if (withoutImports.contains("LazyOptional")) return text
        return Regex("""(?m)^[ \t]*import\s+(?:[\w.]+\.)?LazyOptional\s*;\s*\r?\n""")
            .replace(text, "")
    }

    private fun sourceWithoutJavaImports(text: String): String =
        Regex("""(?m)^[ \t]*import\s+[^\r\n]+;\s*\r?\n""").replace(text, "")

    private fun relocateCapabilityImports(text: String, compatPackage: String): String =
        text
            .replace("import net.neoforged.neoforge.capabilities.Capability;", "import $compatPackage.Capability;")
            .replace("import com.modporter.compat.Capability;", "import $compatPackage.Capability;")
            .replace("net.neoforged.neoforge.capabilities.Capability", "$compatPackage.Capability")
            .replace("com.modporter.compat.Capability", "$compatPackage.Capability")

    private fun relocateConditionalRecipeImports(text: String, compatPackage: String): String =
        text
            .replace("import net.minecraftforge.common.crafting.ConditionalRecipe;", "import $compatPackage.ConditionalRecipe;")
            .replace("import net.neoforged.neoforge.common.crafting.ConditionalRecipe;", "import $compatPackage.ConditionalRecipe;")
            .replace("net.minecraftforge.common.crafting.ConditionalRecipe", "$compatPackage.ConditionalRecipe")
            .replace("net.neoforged.neoforge.common.crafting.ConditionalRecipe", "$compatPackage.ConditionalRecipe")

    private fun relocateRenderUtilsImportsAndCalls(text: String, compatPackage: String): String {
        var result = text
            .replace("import cn.mcmod_mmf.mmlib.client.RenderUtils;", "import $compatPackage.RenderUtils;")
            .replace("cn.mcmod_mmf.mmlib.client.RenderUtils", "$compatPackage.RenderUtils")
        result = Regex("""RenderUtils\.renderFluidStack\(\s*(?!ms\s*,)""")
            .replace(result, "RenderUtils.renderFluidStack(ms, ")
        return result
    }

    private fun cleanupDuplicateOverrides(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val duplicateOverride = Regex(
            """(?m)^([ \t]*)@Override[ \t]*\r?\n((?:[ \t]*@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\([^)\r\n]*\))?[ \t]*\r?\n)+[ \t]*)@Override"""
        )

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val modified = duplicateOverride.replace(original) { match ->
                    match.groupValues[2] + "@Override"
                }
                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove duplicate @Override generated by chained method-signature rewrites",
                        before = "@Override + annotations + @Override",
                        after = "annotations + @Override",
                        confidence = Confidence.HIGH,
                        ruleId = "build-cleanup-duplicate-override"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(modified)
                    }
                }
            }

        return changes
    }

    companion object {
        val EMPTY_GAMETEST_STRUCTURE_SNBT = """
{
  DataVersion: 3953,
  size: [1, 1, 1],
  entities: [],
  blocks: [],
  palette: []
}
""".trimIndent()

        val NEOFORGE_BLOCK = """
neoForge {
    version = project.neo_forge_version

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    runs {
        client {
            client()
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        clientWorld {
            client()
            programArguments.addAll '--quickPlayPath', 'quickplay/modporter_smoke_world.json', '--quickPlaySingleplayer', 'modporter_smoke_world'
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        gameTestServer {
            type = "gameTestServer"
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        data {
            data()
            programArguments.addAll '--mod', project.mod_id, '--all', '--output', file('src/generated/resources/').getAbsolutePath(), '--existing', file('src/main/resources/').getAbsolutePath()
        }
    }

    mods {
        "MOD_ID_TOKEN" {
            sourceSet(sourceSets.main)
        }
    }
}

sourceSets.main.resources {
    srcDir 'src/generated/resources'
}

tasks.matching { it.name == 'prepareGameTestServerRun' }.configureEach {
    doLast {
        def gameTestStructures = file('src/main/resources/gameteststructures')
        if (gameTestStructures.exists()) {
            copy {
                from gameTestStructures
                into file('run/gameteststructures')
            }
        }
    }
}""".trimIndent().replace("MOD_ID_TOKEN", "\${project.mod_id}")

        fun legacyLazyOptionalShim(compatPackage: String): String = """
package $compatPackage;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Source-compatibility adapter generated by modporter for legacy call chains.
 * NeoForge 1.21 removed LazyOptional; new capabilities should use RegisterCapabilitiesEvent directly.
 */
public final class LazyOptional<T> {
    private static final LazyOptional<?> EMPTY = new LazyOptional<>(null);
    private final Supplier<? extends T> supplier;
    private final java.util.Set<Consumer<LazyOptional<T>>> listeners = new java.util.HashSet<>();
    private boolean valid = true;
    private boolean resolved = false;
    private T value;

    private LazyOptional(Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    public static <T> LazyOptional<T> of(Supplier<? extends T> supplier) {
        return supplier == null ? empty() : new LazyOptional<>(supplier);
    }

    public static <T> LazyOptional<T> ofNullable(T value) {
        return value == null ? empty() : of(() -> value);
    }

    public static <T> LazyOptional<T> empty() {
        return EMPTY.cast();
    }

    @SuppressWarnings("unchecked")
    public <R> LazyOptional<R> cast() {
        return (LazyOptional<R>) (LazyOptional<?>) this;
    }

    public void ifPresent(Consumer<? super T> consumer) {
        java.util.Objects.requireNonNull(consumer);
        T current = orElse(null);
        if (valid && current != null) {
            consumer.accept(current);
        }
    }

    public <R> Optional<R> map(Function<? super T, ? extends R> mapper) {
        java.util.Objects.requireNonNull(mapper);
        return isPresent() ? Optional.of(mapper.apply(getValueUnsafe())) : Optional.empty();
    }

    public <R> LazyOptional<R> lazyMap(Function<? super T, ? extends R> mapper) {
        java.util.Objects.requireNonNull(mapper);
        return isPresent() ? of(() -> mapper.apply(getValueUnsafe())) : empty();
    }

    public Optional<T> filter(Predicate<? super T> predicate) {
        java.util.Objects.requireNonNull(predicate);
        T current = orElse(null);
        return current != null && predicate.test(current) ? Optional.of(current) : Optional.empty();
    }

    public T orElse(T other) {
        if (!valid || supplier == null) {
            return other;
        }
        if (!resolved) {
            value = supplier.get();
            resolved = true;
        }
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        T current = orElse(null);
        return current != null ? current : other.get();
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        T current = orElse(null);
        if (current != null) {
            return current;
        }
        throw exceptionSupplier.get();
    }

    public T get() {
        T current = orElse(null);
        if (current != null) {
            return current;
        }
        throw new java.util.NoSuchElementException("LazyOptional is empty");
    }

    public Optional<T> resolve() {
        return isPresent() ? Optional.of(getValueUnsafe()) : Optional.empty();
    }

    private T getValueUnsafe() {
        T current = orElse(null);
        if (current == null) {
            throw new IllegalStateException("LazyOptional has no resolved value");
        }
        return current;
    }

    public boolean isPresent() {
        return supplier != null && valid;
    }

    public void addListener(Consumer<LazyOptional<T>> listener) {
        if (isPresent()) listeners.add(listener);
        else listener.accept(this);
    }

    public void removeListener(Consumer<LazyOptional<T>> listener) {
        listeners.remove(listener);
    }

    boolean isValid() {
        return valid;
    }

    Runnable invalidateDeferred() {
        if (!valid) return null;
        valid = false;
        value = null;
        resolved = true;
        java.util.List<Consumer<LazyOptional<T>>> pending =
            new java.util.ArrayList<>(listeners);
        listeners.clear();
        return () -> pending.forEach(listener -> listener.accept(this));
    }

    public void invalidate() {
        Runnable notification = invalidateDeferred();
        if (notification != null) notification.run();
    }
}
""".trimIndent()

        fun legacyCapabilityShim(compatPackage: String): String = """
package $compatPackage;

/**
 * Generated compatibility type for legacy Forge capability declarations.
 * NeoForge 1.21 models capabilities with typed BlockCapability/ItemCapability objects.
 */
public final class Capability<T> {
}
""".trimIndent()

        fun legacyConditionalRecipeShim(compatPackage: String): String = """
package $compatPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * Source-compatibility adapter generated by modporter for Forge conditional recipe builders.
 * NeoForge 1.21 carries recipe conditions through RecipeOutput.withConditions(...).
 */
public final class ConditionalRecipe {
    private ConditionalRecipe() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ICondition> conditions = new ArrayList<>();
        private final List<Consumer<RecipeOutput>> recipes = new ArrayList<>();
        private ResourceLocation advancementId;

        public Builder addCondition(ICondition condition) {
            this.conditions.add(condition);
            return this;
        }

        public Builder addRecipe(Consumer<RecipeOutput> recipe) {
            this.recipes.add(recipe);
            return this;
        }

        public Builder generateAdvancement(ResourceLocation id) {
            this.advancementId = id;
            return this;
        }

        public void build(RecipeOutput output, String namespace, String path) {
            build(output, ResourceLocation.fromNamespaceAndPath(namespace, path));
        }

        public void build(RecipeOutput output, ResourceLocation id) {
            RecipeOutput conditionalOutput = output.withConditions(conditions.toArray(ICondition[]::new));
            RecipeOutput namedOutput = new RecipeOutput() {
                @Override
                public void accept(ResourceLocation generatedId, Recipe<?> recipe, AdvancementHolder advancement) {
                    forward(conditionalOutput, recipe, advancement);
                }

                @Override
                public void accept(ResourceLocation generatedId, Recipe<?> recipe, AdvancementHolder advancement, ICondition... extraConditions) {
                    RecipeOutput target = extraConditions.length == 0 ? conditionalOutput : conditionalOutput.withConditions(extraConditions);
                    forward(target, recipe, advancement);
                }

                private void forward(RecipeOutput target, Recipe<?> recipe, AdvancementHolder advancement) {
                    AdvancementHolder forwardedAdvancement = advancement;
                    if (advancementId != null && advancement != null) {
                        forwardedAdvancement = new AdvancementHolder(advancementId, advancement.value());
                    }
                    target.accept(id, recipe, forwardedAdvancement);
                }

                @Override
                public Advancement.Builder advancement() {
                    return conditionalOutput.advancement();
                }
            };
            for (Consumer<RecipeOutput> recipe : recipes) {
                recipe.accept(namedOutput);
            }
        }
    }
}
""".trimIndent()

        fun legacyRenderUtilsShim(compatPackage: String): String = """
package $compatPackage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Source-compatibility renderer generated by modporter for the removed MMLib RenderUtils.renderFluidStack helper.
 * It draws the fluid still texture in 16x16 tiles and applies the fluid tint color.
 */
public final class RenderUtils {
    private RenderUtils() {
    }

    public static void renderFluidStack(GuiGraphics guiGraphics, int x, int y, int width, int height, float z, FluidStack fluidStack) {
        if (guiGraphics == null || fluidStack == null || fluidStack.isEmpty() || width <= 0 || height <= 0) {
            return;
        }

        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluidStack.getFluid());
        ResourceLocation texture = extensions.getStillTexture(fluidStack);
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(texture);
        int tint = extensions.getTintColor(fluidStack);
        float alpha = ((tint >> 24) & 0xFF) / 255.0F;
        if (alpha <= 0.0F) {
            alpha = 1.0F;
        }
        float red = ((tint >> 16) & 0xFF) / 255.0F;
        float green = ((tint >> 8) & 0xFF) / 255.0F;
        float blue = (tint & 0xFF) / 255.0F;

        guiGraphics.setColor(red, green, blue, alpha);
        int zOffset = (int) z;
        for (int drawX = 0; drawX < width; drawX += 16) {
            for (int drawY = 0; drawY < height; drawY += 16) {
                int drawWidth = Math.min(16, width - drawX);
                int drawHeight = Math.min(16, height - drawY);
                guiGraphics.blit(x + drawX, y + drawY, zOffset, drawWidth, drawHeight, sprite);
            }
        }
        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
""".trimIndent()

        val LEGACY_MMLIB_FLUID_INGREDIENT_SHIM = """
package cn.mcmod_mmf.mmlib.fluid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Generated compatibility implementation for old MMLib fluid ingredients on NeoForge 1.21.
 */
public abstract class FluidIngredient implements Predicate<FluidStack> {
    public static final FluidIngredient EMPTY = new EmptyFluidIngredient();

    protected int amountRequired;
    private List<FluidStack> matchingFluidStacks;

    public static FluidIngredient fromTag(TagKey<Fluid> tag, int amount) {
        return new TagFluidIngredient(tag, amount);
    }

    public static FluidIngredient fromFluid(Fluid fluid, int amount) {
        return new SingleFluidIngredient(fluid, amount);
    }

    public static FluidIngredient fromFluid(Supplier<? extends Fluid> fluid, int amount) {
        return fromFluid(fluid.get(), amount);
    }

    public static FluidIngredient fromFluidStack(FluidStack stack) {
        return stack == null || stack.isEmpty() ? EMPTY : fromFluid(stack.getFluid(), stack.getAmount());
    }

    public int getAmount() {
        return amountRequired;
    }

    public int getRequiredAmount() {
        return amountRequired;
    }

    public List<FluidStack> getMatchingFluidStacks() {
        if (matchingFluidStacks == null) {
            matchingFluidStacks = determineMatchingFluidStacks();
        }
        return matchingFluidStacks;
    }

    public FluidStack[] getStacks() {
        return getMatchingFluidStacks().toArray(FluidStack[]::new);
    }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        writeInternal(json);
        json.addProperty("amount", amountRequired);
        return json;
    }

    public static boolean isFluidIngredient(JsonElement json) {
        if (json == null || !json.isJsonObject()) return false;
        JsonObject obj = json.getAsJsonObject();
        return obj.has("fluid") || obj.has("tag") || obj.has("fluidTag") || obj.has("null_fluid");
    }

    public static FluidIngredient deserialize(JsonElement json) {
        if (json == null || !json.isJsonObject()) return EMPTY;
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("null_fluid")) return EMPTY;
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1000;
        if (obj.has("tag") || obj.has("fluidTag")) {
            ResourceLocation tagId = ResourceLocation.parse(obj.has("tag") ? obj.get("tag").getAsString() : obj.get("fluidTag").getAsString());
            return fromTag(TagKey.create(Registries.FLUID, tagId), amount);
        }
        if (obj.has("fluid")) {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(obj.get("fluid").getAsString()));
            return fromFluid(fluid, amount);
        }
        return EMPTY;
    }

    @Override
    public boolean test(FluidStack stack) {
        return stack != null && stack.getAmount() >= amountRequired && testInternal(stack);
    }

    protected abstract boolean testInternal(FluidStack stack);
    protected abstract List<FluidStack> determineMatchingFluidStacks();
    protected abstract void writeInternal(JsonObject json);

    private static final class EmptyFluidIngredient extends FluidIngredient {
        private EmptyFluidIngredient() {
            this.amountRequired = 0;
        }

        @Override
        public boolean test(FluidStack stack) {
            return false;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return false;
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.emptyList();
        }

        @Override
        protected void writeInternal(JsonObject json) {
        }
    }

    private static final class SingleFluidIngredient extends FluidIngredient {
        private final Fluid fluid;

        private SingleFluidIngredient(Fluid fluid, int amount) {
            this.fluid = fluid;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().isSame(fluid);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.singletonList(new FluidStack(fluid, amountRequired));
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
        }
    }

    private static final class TagFluidIngredient extends FluidIngredient {
        private final TagKey<Fluid> tag;

        private TagFluidIngredient(TagKey<Fluid> tag, int amount) {
            this.tag = tag;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().builtInRegistryHolder().is(tag);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            List<FluidStack> stacks = new ArrayList<>();
            BuiltInRegistries.FLUID.getTag(tag).ifPresent(holders ->
                holders.forEach(holder -> stacks.add(new FluidStack(holder.value(), amountRequired)))
            );
            return stacks;
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("tag", tag.location().toString());
        }
    }
}
""".trimIndent()

        val LEGACY_MMLIB_ABSTRACT_RECIPE_SHIM = """
package cn.mcmod_mmf.mmlib.recipe;

import com.google.gson.annotations.Expose;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * Generated compatibility base for MMLib recipes on the NeoForge 1.21 Recipe API.
 */
public abstract class AbstractRecipe implements Recipe<RecipeWrapper> {
    protected ResourceLocation id;
    public String group = "";
    @Expose
    public float experience;
    @Expose
    public int recipeTime;

    public void setId(ResourceLocation id) {
        this.id = id;
    }

    public ResourceLocation getId() {
        return id;
    }

    public float getExperience() {
        return experience;
    }

    public int getRecipeTime() {
        return recipeTime;
    }

    @Override
    public String getGroup() {
        return group;
    }

    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return getResultItem((HolderLookup.Provider) null);
    }

    @Override
    public abstract ItemStack getResultItem(HolderLookup.Provider registries);

    @Override
    public boolean isSpecial() {
        return true;
    }
}
""".trimIndent()

        val LEGACY_MMLIB_CHANCE_RESULT_SHIM = """
package cn.mcmod_mmf.mmlib.recipe;

import com.google.gson.annotations.Expose;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * Generated compatibility value type for MMLib chance results.
 */
public record ChanceResult(@Expose ItemStack stack, @Expose float chance) {
    public static final ChanceResult EMPTY = new ChanceResult(ItemStack.EMPTY, 0.0F);

    public ItemStack rollOutput(RandomSource random, int fortuneLevel) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        float effectiveChance = Math.min(1.0F, chance + Math.max(0, fortuneLevel) * 0.05F);
        return random.nextFloat() <= effectiveChance ? stack.copy() : ItemStack.EMPTY;
    }
}
""".trimIndent()

        val LEGACY_MMLIB_ABSTRACT_RECIPE_SERIALIZER_SHIM = """
package cn.mcmod_mmf.mmlib.recipe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.stream.Stream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Generated compatibility serializer for old Gson-backed MMLib recipes on the 1.21 RecipeSerializer API.
 */
public class AbstractRecipeSerializer<T extends AbstractRecipe> implements RecipeSerializer<T> {
    private final Class<T> recipeClass;
    private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private final MapCodec<T> mapCodec = new MapCodec<>() {
        @Override
        public <O> RecordBuilder<O> encode(T input, DynamicOps<O> ops, RecordBuilder<O> prefix) {
            JsonObject json = toJson(input);
            for (var entry : json.entrySet()) {
                prefix = prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
            }
            return prefix;
        }

        @Override
        public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
            try {
                JsonObject json = new JsonObject();
                input.entries().forEach(pair -> {
                    String key = ops.getStringValue(pair.getFirst()).result().orElse("");
                    if (!key.isEmpty()) {
                        json.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
                    }
                });
                return DataResult.success(fromJson(json));
            } catch (Exception e) {
                return DataResult.error(() -> "Failed to decode legacy MMLib recipe: " + e.getMessage());
            }
        }

        @Override
        public <O> Stream<O> keys(DynamicOps<O> ops) {
            return Stream.empty();
        }
    };
    private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec = StreamCodec.of(
        (buf, recipe) -> buf.writeUtf(gson.toJson(recipe)),
        buf -> fromJson(com.google.gson.JsonParser.parseString(buf.readUtf(32767)).getAsJsonObject())
    );

    public AbstractRecipeSerializer(Class<T> recipeClass) {
        this.recipeClass = recipeClass;
    }

    @Override
    public MapCodec<T> codec() {
        return mapCodec;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec;
    }

    public T fromJson(JsonObject json) {
        try {
            return gson.fromJson(json, recipeClass);
        } catch (JsonParseException e) {
            throw e;
        }
    }

    public JsonObject toJson(T recipe) {
        return gson.toJsonTree(recipe).getAsJsonObject();
    }
}
""".trimIndent()

        val SETTINGS_GRADLE = """pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = "https://maven.neoforged.net/releases" }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}

rootProject.name = '%%PROJECT_NAME%%'
""".trimIndent()

        val GAMETEST_DIMENSION_DATAPACK_TASK = """
tasks.matching { it.name == 'prepareGameTestServerRun' }.configureEach {
    doLast {
        def packRoot = file('run/world/datapacks/modporter_gametest_dimensions')
        if (packRoot.exists()) {
            packRoot.deleteDir()
        }

        def dimensionEntries = [:]
        [file('src/main/resources/data'), file('src/generated/resources/data')].findAll { it.exists() }.each { dataRoot ->
            dataRoot.eachDir { namespaceDir ->
                def dimensionDir = new File(namespaceDir, 'dimension')
                if (!dimensionDir.exists()) {
                    return
                }
                dimensionDir.eachFileRecurse(groovy.io.FileType.FILES) { dimFile ->
                    if (!dimFile.name.endsWith('.json')) {
                        return
                    }
                    def relative = dimensionDir.toPath()
                            .relativize(dimFile.toPath())
                            .toString()
                            .replace(File.separator, '/')
                            .replaceFirst(/\.json${'$'}/, '')
                    dimensionEntries["${'$'}{namespaceDir.name}:${'$'}relative"] = new groovy.json.JsonSlurper().parse(dimFile)
                }
            }
        }
        if (dimensionEntries.isEmpty()) {
            return
        }

        def dimensions = [
                'minecraft:overworld': [
                        type: 'minecraft:overworld',
                        generator: [
                                type: 'minecraft:flat',
                                settings: [
                                        biome: 'minecraft:plains',
                                        features: false,
                                        lakes: false,
                                        layers: [
                                                [block: 'minecraft:bedrock', height: 1],
                                                [block: 'minecraft:dirt', height: 2],
                                                [block: 'minecraft:grass_block', height: 1]
                                        ],
                                        structure_overrides: [
                                                'minecraft:strongholds',
                                                'minecraft:villages'
                                        ]
                                ]
                        ]
                ],
                'minecraft:the_end': [
                        type: 'minecraft:the_end',
                        generator: [
                                type: 'minecraft:noise',
                                biome_source: [type: 'minecraft:the_end'],
                                settings: 'minecraft:end'
                        ]
                ],
                'minecraft:the_nether': [
                        type: 'minecraft:the_nether',
                        generator: [
                                type: 'minecraft:noise',
                                biome_source: [type: 'minecraft:multi_noise', preset: 'minecraft:nether'],
                                settings: 'minecraft:nether'
                        ]
                ]
        ]
        dimensions.putAll(dimensionEntries)

        def presetFile = new File(packRoot, 'data/minecraft/worldgen/world_preset/flat.json')
        presetFile.parentFile.mkdirs()
        presetFile.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([dimensions: dimensions]))
        new File(packRoot, 'pack.mcmeta').text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson([
                pack: [
                        pack_format: 48,
                        description: 'ModPorter GameTest custom dimensions'
                ]
        ]))
    }
}
""".trimIndent()

        /**
         * Find the index of the closing brace that matches the opening brace at [openIndex].
         */
        fun findMatchingBrace(content: String, openIndex: Int): Int =
            findClosing(content, openIndex, '{', '}')

        fun findMatchingParen(content: String, openIndex: Int): Int =
            findClosing(content, openIndex, '(', ')')

        /**
         * Generic bracket matcher: find the closing delimiter matching the opener at [openIndex].
         */
        fun findClosing(content: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
            if (openIndex !in content.indices || content[openIndex] != openChar) return -1

            var depth = 0
            var i = openIndex
            while (i < content.length) {
                val ch = content[i]
                val next = content.getOrNull(i + 1)

                when {
                    ch == '/' && next == '/' -> {
                        val end = content.indexOfAny(charArrayOf('\n', '\r'), i + 2)
                        i = if (end < 0) content.length else end + 1
                        continue
                    }
                    ch == '/' && next == '*' -> {
                        val end = content.indexOf("*/", i + 2)
                        if (end < 0) return -1
                        i = end + 2
                        continue
                    }
                    ch == '"' && next == '"' && content.getOrNull(i + 2) == '"' -> {
                        val end = content.indexOf("\"\"\"", i + 3)
                        if (end < 0) return -1
                        i = end + 3
                        continue
                    }
                    ch == '\'' && next == '\'' && content.getOrNull(i + 2) == '\'' -> {
                        val end = content.indexOf("'''", i + 3)
                        if (end < 0) return -1
                        i = end + 3
                        continue
                    }
                    ch == '"' || ch == '\'' -> {
                        i = skipQuotedLiteral(content, i, ch)
                        if (i < 0) return -1
                        continue
                    }
                }

                when (ch) {
                    openChar -> depth++
                    closeChar -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }

        private fun skipQuotedLiteral(content: String, startIndex: Int, quote: Char): Int {
            var escaped = false
            var i = startIndex + 1
            while (i < content.length) {
                val ch = content[i]
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    return i + 1
                }
                i++
            }
            return -1
        }

        fun findJavaStatementEnd(content: String, startIndex: Int): Int {
            var parenDepth = 0
            var braceDepth = 0
            var bracketDepth = 0
            var inString = false
            var inChar = false
            var inLineComment = false
            var inBlockComment = false
            var inTripleString = false
            var tripleQuote = '\u0000'
            var escaped = false
            var i = startIndex
            while (i < content.length) {
                val ch = content[i]
                val next = content.getOrNull(i + 1)
                val nextTwo = content.getOrNull(i + 2)

                if (inLineComment) {
                    if (ch == '\n' || ch == '\r') inLineComment = false
                    i++
                    continue
                }
                if (inBlockComment) {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (inTripleString) {
                    if (ch == tripleQuote && next == tripleQuote && nextTwo == tripleQuote) {
                        inTripleString = false
                        tripleQuote = '\u0000'
                        i += 3
                    } else {
                        i++
                    }
                    continue
                }
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                    i++
                    continue
                }
                if (inChar) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '\'') {
                        inChar = false
                    }
                    i++
                    continue
                }

                when {
                    ch == '/' && next == '/' -> {
                        inLineComment = true
                        i += 2
                        continue
                    }
                    ch == '/' && next == '*' -> {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                    (ch == '"' || ch == '\'') && next == ch && nextTwo == ch -> {
                        inTripleString = true
                        tripleQuote = ch
                        i += 3
                        continue
                    }
                    ch == '"' -> inString = true
                    ch == '\'' -> inChar = true
                    ch == '(' -> parenDepth++
                    ch == ')' -> if (parenDepth > 0) parenDepth--
                    ch == '{' -> braceDepth++
                    ch == '}' -> if (braceDepth > 0) braceDepth--
                    ch == '[' -> bracketDepth++
                    ch == ']' -> if (bracketDepth > 0) bracketDepth--
                    ch == ';' && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 -> return i
                }
                i++
            }
            return -1
        }

        /**
         * Find line number at a given character offset in a string.
         */
        fun String.lineNumberAt(offset: Int): Int {
            return this.substring(0, offset.coerceAtMost(this.length)).count { it == '\n' } + 1
        }
    }
}

internal object LegacyReflectionSyntax {
    val forbiddenPatterns = listOf(
        "ObfuscationReflectionHelper" to Regex("""\bObfuscationReflectionHelper\b"""),
        "getDeclaredField" to Regex("""\bgetDeclaredField\s*\("""),
        "getDeclaredMethod" to Regex("""\bgetDeclaredMethod\s*\("""),
        "getDeclaredConstructor" to Regex("""\bgetDeclaredConstructor\s*\("""),
        "getMethod" to Regex("""\.getMethod\s*\("""),
        "setAccessible" to Regex("""\bsetAccessible\s*\("""),
        "Class.forName" to Regex("""\bClass\.forName\s*\("""),
        "MethodHandle" to Regex("""\bMethodHandle\b"""),
        "MethodHandles" to Regex("""\bMethodHandles\b"""),
        "java.lang.reflect member access" to Regex("""\bjava\.lang\.reflect\.(?:Field|Method|Constructor)\b"""),
        "unreflect" to Regex("""\bunreflect(?:Getter|Setter)?\s*\(""")
    )

    fun containsForbiddenApi(executableSource: String): Boolean =
        forbiddenPatterns.any { (_, pattern) -> pattern.containsMatchIn(executableSource) }

    fun containsDeclaredFieldLookup(sourceWithStrings: String, fieldName: String): Boolean =
        Regex("""\bgetDeclaredField\s*\(\s*"${Regex.escape(fieldName)}"\s*\)""")
            .containsMatchIn(sourceWithStrings)

    fun containsAccessibleEnable(sourceWithStrings: String): Boolean =
        Regex("""\bsetAccessible\s*\(\s*true\s*\)""").containsMatchIn(sourceWithStrings)
}
