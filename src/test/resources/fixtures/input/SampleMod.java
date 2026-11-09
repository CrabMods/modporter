package com.example.samplemod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

@Mod("samplemod")
public class SampleMod {
    public static final String MOD_ID = "samplemod";

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Item> SAMPLE_ITEM =
        ITEMS.register("sample_item", () -> new Item(new Item.Properties()));

    public SampleMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        ResourceLocation id = new ResourceLocation(MOD_ID, "sample");
        ResourceLocation parsed = new ResourceLocation("samplemod:sample");
        // do something
    }

    @Mod.EventBusSubscriber(modid = MOD_ID)
    public static class Events {
        @SubscribeEvent
        public static void onEntityHurt(LivingHurtEvent event) {
            float damage = event.getAmount();
        }
    }
}
