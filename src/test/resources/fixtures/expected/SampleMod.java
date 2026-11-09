package com.example.samplemod;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

@Mod("samplemod")
public class SampleMod {
    public static final String MOD_ID = "samplemod";

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(BuiltInRegistries.ITEM, MOD_ID);

    public static final DeferredHolder<Item> SAMPLE_ITEM =
        ITEMS.register("sample_item", () -> new Item(new Item.Properties()));

    public SampleMod(IEventBus modBus, ModContainer modContainer) {
        ITEMS.register(modBus);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onLivingHurt(LivingDamageEvent.Post event) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, "sample");
        ResourceLocation parsed = ResourceLocation.parse("samplemod:sample");
        // do something
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static class Events {
        @SubscribeEvent
        public static void onEntityHurt(LivingDamageEvent.Post event) {
            float damage = event.getNewDamage();
        }
    }
}
