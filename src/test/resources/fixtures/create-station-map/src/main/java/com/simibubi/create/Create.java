package com.simibubi.create;

import java.util.Random;
import org.slf4j.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.simibubi.create.api.registrate.CreateRegistrateRegistrationCallback;
import com.simibubi.create.compat.Mods;
import com.simibubi.create.compat.computercraft.ComputerCraftProxy;
import com.simibubi.create.compat.curios.Curios;
import com.simibubi.create.compat.inventorySorter.InventorySorterCompat;
import com.simibubi.create.content.decoration.palettes.AllPaletteBlocks;
import com.simibubi.create.content.equipment.potatoCannon.AllPotatoProjectileBlockHitActions;
import com.simibubi.create.content.equipment.potatoCannon.AllPotatoProjectileEntityHitActions;
import com.simibubi.create.content.equipment.potatoCannon.AllPotatoProjectileRenderModes;
import com.simibubi.create.content.fluids.tank.BoilerHeaters;
import com.simibubi.create.content.kinetics.TorquePropagator;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.logistics.item.filter.attribute.AllItemAttributeTypes;
import com.simibubi.create.content.logistics.packager.AllInventoryIdentifiers;
import com.simibubi.create.content.logistics.packager.AllUnpackingHandlers;
import com.simibubi.create.content.logistics.packagerLink.GlobalLogisticsManager;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.schematics.ServerSchematicLoader;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.track.AllPortalTracks;
import com.simibubi.create.foundation.CreateNBTProcessors;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.advancement.AllTriggers;
import com.simibubi.create.foundation.block.CopperRegistries;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.simibubi.create.infrastructure.command.ServerLagger;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.data.CreateDatagen;
import com.simibubi.create.infrastructure.worldgen.AllFeatures;
import com.simibubi.create.infrastructure.worldgen.AllPlacementModifiers;
import net.createmod.catnip.lang.FontHelper;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLLoader;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import com.simibubi.create.content.equipment.armor.AllArmorMaterials;



@Mod(Create.ID)
public class Create {

    public static final String ID = "create";

    public static final String NAME = "Create";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Use the {@link Random} of a local {@link Level} or {@link Entity} or create one
     */
    @Deprecated
    public static final Random RANDOM = new Random();

    /**
     * <b>Other mods should not use this field!</b> If you are an addon developer, create your own instance of
     * {@link CreateRegistrate}.
     * </br
     * If you were using this instance to register a callback listener use {@link CreateRegistrateRegistrationCallback#register} instead.
     */
    private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(ID).defaultCreativeTab((ResourceKey<CreativeModeTab>) null).setTooltipModifierFactory(item -> new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE).andThen(TooltipModifier.mapNull(KineticStats.create(item))));

    public static final ServerSchematicLoader SCHEMATIC_RECEIVER = new ServerSchematicLoader();

    public static final RedstoneLinkNetworkHandler REDSTONE_LINK_NETWORK_HANDLER = new RedstoneLinkNetworkHandler();

    public static final TorquePropagator TORQUE_PROPAGATOR = new TorquePropagator();

    public static final GlobalRailwayManager RAILWAYS = new GlobalRailwayManager();

    public static final GlobalLogisticsManager LOGISTICS = new GlobalLogisticsManager();

    public static final ServerLagger LAGGER = new ServerLagger();

    public Create(IEventBus modEventBus, ModContainer modContainer) {
        AllArmorMaterials.ARMOR_MATERIALS.register(modEventBus);
        onCtor();
    
        CapabilityMinecartController.modporterEntityAttachmentRegister(modEventBus);

        modEventBus.addListener(com.simibubi.create.foundation.recipe.AllIngredients::register);

        modEventBus.addListener(com.simibubi.create.infrastructure.RemapHelper::remapBlocks);

        modEventBus.addListener(com.simibubi.create.infrastructure.RemapHelper::remapItems);

        modEventBus.addListener(com.simibubi.create.infrastructure.RemapHelper::remapFluids);

        modEventBus.addListener(com.simibubi.create.infrastructure.RemapHelper::remapBlockEntities);
}

    public static void onCtor() {
        LOGGER.info("{} {} initializing! Commit hash: {}", NAME, CreateBuildInfo.VERSION, CreateBuildInfo.GIT_COMMIT);
        ModLoadingContext modLoadingContext = ModLoadingContext.get();
        IEventBus modEventBus = modEventBus;
        IEventBus forgeEventBus = NeoForge.EVENT_BUS;
        REGISTRATE.registerEventListeners(modEventBus);
        AllSoundEvents.prepare();
        AllCreativeModeTabs.register(modEventBus);
        AllDisplaySources.register();
        AllDisplayTargets.register();
        AllBlocks.register();
        AllItems.register();
        AllFluids.register();
        AllPaletteBlocks.register();
        AllMenuTypes.register();
        AllEntityTypes.register();
        AllBlockEntityTypes.register();
        AllEnchantments.register();
        AllRecipeTypes.register(modEventBus);
        AllParticleTypes.register(modEventBus);
        AllStructureProcessorTypes.register(modEventBus);
        AllEntityDataSerializers.register(modEventBus);
        forgeEventBus.addListener(AllPackets::registerPackets);
        AllFeatures.register(modEventBus);
        AllPlacementModifiers.register(modEventBus);
        AllMountedStorageTypes.register();
        AllConfigs.register(modLoadingContext);
        AllSchematicStateFilters.registerDefaults();
        // FIXME: some of these registrations are not thread-safe
        BogeySizes.init();
        AllBogeyStyles.init();
        // ----
        ComputerCraftProxy.register();
        NeoForgeMod.enableMilkFluid();
        CopperRegistries.inject();
        modEventBus.addListener(Create::init);
        modEventBus.addListener(Create::onRegister);
        modEventBus.addListener(AllEntityTypes::registerEntityAttributes);
        modEventBus.addListener(EventPriority.LOWEST, CreateDatagen::gatherData);
        modEventBus.addListener(AllSoundEvents::register);
        if (FMLLoader.getDist() == Dist.CLIENT) {
            CreateClient.onCtorClient(modEventBus, forgeEventBus);
        }
        // FIXME: this is not thread-safe
        Mods.CURIOS.executeIfInstalled(() -> () -> Curios.init(modEventBus, forgeEventBus));
        Mods.INVENTORYSORTER.executeIfInstalled(() -> () -> InventorySorterCompat.init(modEventBus));
    }

    public static void init(final FMLCommonSetupEvent event) {
        AllFluids.registerFluidInteractions();
        CreateNBTProcessors.register();
        event.enqueueWork(() -> {
            // TODO: custom registration should all happen in one place
            // Most registration happens in the constructor.
            // These registrations use Create's registered objects directly so they must run after registration has finished.
            BoilerHeaters.registerDefaults();
            AllPortalTracks.registerDefaults();
            AllBlockSpoutingBehaviours.registerDefaults();
            AllMovementBehaviours.registerDefaults();
            AllInteractionBehaviours.registerDefaults();
            AllContraptionMovementSettings.registerDefaults();
            AllOpenPipeEffectHandlers.registerDefaults();
            AllMountedDispenseItemBehaviors.registerDefaults();
            AllUnpackingHandlers.registerDefaults();
            AllInventoryIdentifiers.registerDefaults();
            // --
            AllAdvancements.register();
            AllTriggers.register();
        });
    }

    public static void onRegister(final RegisterEvent event) {
        AllArmInteractionPointTypes.init();
        AllFanProcessingTypes.init();
        AllItemAttributeTypes.init();
        AllContraptionTypes.init();
        AllPotatoProjectileRenderModes.init();
        AllPotatoProjectileEntityHitActions.init();
        AllPotatoProjectileBlockHitActions.init();
    }

    public static LangBuilder lang() {
        return new LangBuilder(ID);
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    public static CreateRegistrate registrate() {
        if (!STACK_WALKER.getCallerClass().getPackageName().startsWith("com.simibubi.create"))
            throw new UnsupportedOperationException("Other mods are not permitted to use create's registrate instance.");
        return REGISTRATE;
    }
}
