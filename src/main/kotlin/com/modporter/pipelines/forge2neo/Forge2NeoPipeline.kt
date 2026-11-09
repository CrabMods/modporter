package com.modporter.pipelines.forge2neo

import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.LegacyMigrationInventoryPrepass
import com.modporter.core.transforms.structural.LegacySourceContractsPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.registry.PipelineDefinition
import com.modporter.resources.ResourceMigrationPass

/**
 * Forge 1.20.1 → NeoForge 1.21.1 migration pipeline.
 * The original and most complete pipeline.
 */
object Forge2NeoPipeline {
    val definition = PipelineDefinition(
        id = "forge2neo",
        displayName = "Forge 1.20.1 → NeoForge 1.21.1",
        sourceFramework = "forge-1.20.1",
        targetFramework = "neoforge-1.21.1",
        mappingsPrefix = "/mappings/forge2neo",
        passFactory = { mappingDb, options ->
            val buildSystemPass = BuildSystemPass(
                offlineMode = options.offline || !options.resolveDeps,
                mappingsPrefix = "/mappings/forge2neo"
            )
            listOf(
                LegacyMigrationInventoryPrepass(buildSystemPass),
                LegacySourceContractsPass(),
                TextReplacementPass(mappingDb),
                AstTransformPass(mappingDb),
                StructuralRefactorPass(),
                buildSystemPass,
                ResourceMigrationPass(
                    mappingDb,
                    addToolCredit = options.addToolCredit,
                    toolCreditName = options.toolCreditName,
                    toolCreditUrl = options.toolCreditUrl
                )
            )
        },
        validationPatterns = listOf(
            "net.minecraftforge",
            "MinecraftForge.EVENT_BUS",
            "ForgeRegistries.",
            "FMLJavaModLoadingContext",
            "LazyOptional",
            "SimpleChannel",
            "DistExecutor",
            "ForgeConfigSpec",
            "ICapabilityProvider",
            "mods.toml"
        ),
        detectionPatterns = listOf(
            "net.minecraftforge",
            "ForgeRegistries",
            "FMLJavaModLoadingContext",
            "MinecraftForge.EVENT_BUS"
        )
    )
}
