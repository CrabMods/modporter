package org.example.salvage.mixin;

public final class SalvageMixinPlugin {
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.startsWith("org.example.salvage.mixin.compat.optional")
			&& !Mods.OPTIONAL.isLoaded())
			return false;
		return true;
	}

	private enum Mods {
		OPTIONAL;

		boolean isLoaded() {
			return false;
		}
	}
}
