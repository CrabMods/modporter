package org.example.salvage;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LootingLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class DropEvents {
	private DropEvents() {}

	@SubscribeEvent
	public static void applyPulverizeBonus(LootingLevelEvent event) {
		DamageSource source = event.getDamageSource();
		if (source == null || !source.is(HazardKeys.PULVERIZE))
			return;
		event.setLootingLevel(3);
	}

	@SubscribeEvent
	public static void keepDropHandling(LivingDropsEvent event) {
		DamageSource source = event.getSource();
		if (source == null || !source.is(HazardKeys.PULVERIZE))
			return;
		event.getDrops().forEach(drop -> drop.setNoPickUpDelay());
	}
}
