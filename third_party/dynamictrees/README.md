# Dynamic Trees post-rot compatibility source

This directory records the license and primary provenance for the Dynamic
Trees source adapted by ModPorter's shared legacy-plant compatibility runtime.

## Pinned upstream

- Project: Dynamic Trees
- Maven coordinate: `com.ferreusveritas.dynamictrees:DynamicTrees-1.20.1:1.3.0-BETA13`
- Git tag: `1.3.0-BETA13`
- Tag commit: `9b43a9261e11dbd7d84b7e37272cbbd9dcf23277`
- Repository: `https://github.com/DynamicTreesTeam/DynamicTrees`
- Binary artifact: `https://harleyoconnor.com/maven/com/ferreusveritas/dynamictrees/DynamicTrees-1.20.1/1.3.0-BETA13/DynamicTrees-1.20.1-1.3.0-BETA13.jar`
- Binary artifact SHA-256: `76f00472f12983d76ec6c88e319e6eb837fb2f49f64d92da1b296c3f01f3ca46`
- Source artifact: `https://harleyoconnor.com/maven/com/ferreusveritas/dynamictrees/DynamicTrees-1.20.1/1.3.0-BETA13/DynamicTrees-1.20.1-1.3.0-BETA13-sources.jar`
- Source artifact SHA-256: `490a4023d765fd6bcc9b919025543380ceb862b131fb54227ac84c969f94f3af`
- Artifact POM SHA-256: `cad503ea7270788211c70f5417965f760c3ab1d34aac4cffdc808e2ef7d6e999`
- Preserved tag license SHA-256: `7f29ddd022421f848d57e58f4e71486131eee9acd61a4cb70944141846b80ba0`

The source artifact entry
`com/ferreusveritas/dynamictrees/systems/genfeature/MushroomRotGenFeature.java`
is byte-identical to the file at the pinned tag commit. Its SHA-256 is
`d0ad96cfb0f89c3d935234b3bef45b2fcc9ca4f5e93e898423e23ef825507d5c`.
The artifact POM declares MIT. Neither the binary artifact nor the source
artifact contains a license entry, so `LICENSE` here preserves the exact
repository license at the matching source commit.

## Adapted source scope

The following compatibility template adapts `MushroomRotGenFeature#postRot`
and its mushroom-sustain check from this pinned MIT source:

- `src/main/resources/compat/LegacyPlantMushroomRotCompat.java.template`

The upstream source identifies Harley O'Connor as its author. The adapted
template must retain the repository copyright notice, `Copyright (c)
2016,2017 Ferreus Veritas`, the MIT SPDX identifier, and this component
provenance reference.

The target Dynamic Trees 1.7.2 JAR carries a newer `LICENSE_Dynamic Trees`
notice. That target notice is not substituted for the pinned 1.3.0-BETA13
source license because its copyright text differs.
