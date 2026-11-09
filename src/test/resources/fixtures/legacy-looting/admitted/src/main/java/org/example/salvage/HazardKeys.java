package org.example.salvage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;

public final class HazardKeys {
	public static final ResourceKey<DamageType> PULVERIZE = key("pulverize");

	private HazardKeys() {}

	private static ResourceKey<DamageType> key(String name) {
		return null;
	}

	public static void bootstrap(BootstrapContext<DamageType> context) {
		new DamageTypeBuilder(PULVERIZE).scaling(DamageScaling.ALWAYS).register(context);
	}
}
