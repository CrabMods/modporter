# Minecraft Forge legacy-plant compatibility component

This directory records the license and provenance of the Forge-derived source
used by ModPorter's shared legacy-plant compatibility runtime.

## Pinned upstream

- Project: Minecraft Forge
- Minecraft / Forge release: `1.20.1-47.1.33`
- Upstream commit: `30f255d76c4c2e915414a24e6d8aed42f429817e`
- Repository: `https://github.com/MinecraftForge/MinecraftForge`
- Source artifact: `https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.1.33/forge-1.20.1-47.1.33-sources.jar`
- Source artifact SHA-256: `610925fc49c74a2740d8883fee82adea80dd7481cc079218ef409297cf048a73`
- Upstream license source: `LICENSE.txt` at the pinned commit
- Preserved license SHA-256: `481c96d94d182382c4225d5b210f8c658c85350cf548f25c9f56c058804f1e57`

`LICENSE.txt` in this directory is byte-for-byte identical to the upstream
license at the pinned commit. The upstream `PlantType.java` and
`IPlantable.java` source files carry the same Forge Development LLC copyright
and `SPDX-License-Identifier: LGPL-2.1-only` notices retained below.

## LGPL-2.1-only file scope

The following modified compatibility templates retain Forge's copyright and
LGPL-2.1-only SPDX notices:

- `src/main/resources/compat/PlantType.java.template`
- `src/main/resources/compat/IPlantable.java.template`
- `src/main/resources/compat/LegacyPlantables.java.template`

ModPorter adapts the Forge 1.20.1 plant contract and behavior for one shared
NeoForge 1.21.1 compatibility runtime. The templates record the ModPorter
modification and its notice date without removing or replacing the upstream
copyright or SPDX lines.

## Apache-2.0 ModPorter helper scope

The following independently authored ModPorter helper templates remain under
ModPorter's Apache-2.0 license and are outside the Forge-derived file list:

- `src/main/resources/compat/LegacyPlantSustainer.java.template`
- `src/main/resources/compat/LegacyPlantPlacement.java.template`
- `src/main/resources/compat/LegacyPlantableAdapter.java.template`
- `src/main/resources/compat/LegacySoilAdapter.java.template`
- `src/main/resources/compat/LegacyPlantPlacementAdapter.java.template`

The expanded runtime adds these five independently authored Apache-2.0
ModPorter helper and mixin templates:

- `src/main/resources/compat/LegacyPlantAdapterProfile.java.template`
- `src/main/resources/compat/ReviewedExternalPlantAdapters.java.template`
- `src/main/resources/compat/LegacyPlantMixinPlugin.java.template`
- `src/main/resources/compat/mixin/LegacyPlantMushroomRotMixin.java.template`
- `src/main/resources/compat/mixin/LegacyPlantPluginSentinel.java.template`

## MIT Dynamic Trees consumer scope

The sixth new runtime source is separately classified under MIT and is not a
Forge-derived or Apache-2.0 helper:

- `src/main/resources/compat/LegacyPlantMushroomRotCompat.java.template`

Its exact source provenance and preserved MIT license are recorded in
`../dynamictrees/README.md` and `../dynamictrees/LICENSE`.

ModPorter's overall Apache-2.0 license and an input mod's own license are not
replaced by this component record. Distributions containing the Forge-derived
files must also preserve this component notice and `LICENSE.txt`.
