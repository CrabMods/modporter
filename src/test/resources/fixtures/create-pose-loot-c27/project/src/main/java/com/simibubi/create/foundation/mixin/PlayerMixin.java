package com.simibubi.create.foundation.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity {
	protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
		super(entityType, level);
	}

	@ModifyExpressionValue(
		method = "aiStep",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;isPassenger()Z"
		)
	)
	private boolean pretendNotPassenger(boolean isPassenger) {
		// avoid touching all items in the contraption's massive hitbox
		boolean shouldSync = AllConfigs.server().kinetics.syncPlayerPickupHitboxWithContraptionHitbox.get();
		if (isPassenger && !shouldSync && this.getVehicle() instanceof AbstractContraptionEntity) {
			return false;
		}

		return isPassenger;
	}
}
