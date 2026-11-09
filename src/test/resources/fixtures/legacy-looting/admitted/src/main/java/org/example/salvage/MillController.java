package org.example.salvage;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class MillController {
	public static void damage(Entity target, Level level, float amount) {
		target.hurt(HazardDamageSources.pulverize(level), amount);
	}
}
