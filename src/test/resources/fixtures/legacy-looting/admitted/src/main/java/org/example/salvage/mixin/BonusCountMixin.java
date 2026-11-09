package org.example.salvage.mixin;

import org.example.salvage.HazardKeys;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;

@Mixin(LootingEnchantFunction.class)
public abstract class BonusCountMixin {
	@Shadow
	@Final
	NumberProvider value;

	@Shadow
	@Final
	int limit;

	@Shadow
	abstract boolean hasLimit();

	@Inject(method = "run", at = @At("TAIL"))
	private void salvage$addPulverizeBonus(
		ItemStack stack,
		LootContext context,
		CallbackInfoReturnable<ItemStack> cir
	) {
		DamageSource source = context.getParamOrNull(LootContextParams.DAMAGE_SOURCE);
		if (source != null && source.is(HazardKeys.PULVERIZE)) {
			int policyLevel = 3;
			float increase = policyLevel * this.value.getFloat(context);
			stack.grow(Math.round(increase));
			if (this.hasLimit() && stack.getCount() > this.limit)
				stack.setCount(this.limit);
		}
	}
}
