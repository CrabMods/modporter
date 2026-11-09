# Upstream Minecraft 1.21.1 Release Status

Status checked on **2026-11-10** against official GitHub releases and source
repositories, source-linked Modrinth projects, and official CurseForge file
inventories where available. A confirmed entry has an original-author binary
published for Minecraft 1.21.1. An unconfirmed entry means this review did not
verify such a binary; it is not a general proof that none exists.

**Summary: 7 confirmed, 4 unconfirmed.**

| Strict benchmark target | Status | Primary official evidence | Scope note |
| --- | --- | --- | --- |
| Construction Wand | **Unconfirmed** | [Latest CurseForge file: 1.20.2-2.12](https://www.curseforge.com/minecraft/mc-mods/construction-wand/files/4810932); [source branch configuration](https://github.com/Theta-Dev/ConstructionWand/blob/1.21/gradle.properties#L7-L8) | The complete 63-file official CurseForge inventory, the source-linked Modrinth version list, and GitHub releases were checked; none contained a 1.21.1 release. The branch named `1.21` still declares Minecraft 1.20.2, so it is not 1.21.1 release evidence. |
| InstantWorldMirror | **Confirmed** | [1.4.0-Beta for NeoForge 1.21.1](https://modrinth.com/mod/instant-world-mirror/version/hb9bM2qc) | Official CrabMods project linked to `CrabMods/InstantWorldMirror`. |
| Hot Bath | **Confirmed** | [4.1.2 for NeoForge 1.21.1](https://modrinth.com/mod/hot-bath/version/r40uJRV7) | Official CrabMods release. |
| ShowerCore | **Confirmed** | [1.2.0 for NeoForge 1.21.1](https://modrinth.com/mod/showercore/version/AS6icsnj) | Official CrabMods release. |
| Sakura Mod | **Unconfirmed** | [Official source-linked Modrinth project](https://modrinth.com/mod/sakura_mod); [1.20.1 source configuration](https://github.com/0999312/Sakura_mod/blob/1.20.1/gradle.properties#L7-L8) | Modrinth listed releases only through 1.20.1 and GitHub had no releases. The official CurseForge file inventory was not fully enumerated, so this is deliberately not an absolute absence claim. |
| Twilight Forest | **Confirmed** | [4.8.3345 for NeoForge 1.21.1](https://www.curseforge.com/minecraft/mc-mods/the-twilight-forest/files/7797302); [4.8.796 beta for Fabric 1.21.1](https://www.curseforge.com/minecraft/mc-mods/the-twilight-forest/files/9348759) | Official CurseForge files linked by `TeamTwilight/twilightforest`. |
| The Aether | **Confirmed** | [1.5.11 for Fabric 1.21.1](https://modrinth.com/mod/aether/version/NSqdh1i9); [1.5.10 for NeoForge 1.21.1](https://modrinth.com/mod/aether/version/K5X5qMwG) | Official project linked to `The-Aether-Team/The-Aether`. |
| Beyond the Veil | **Unconfirmed** | [Official repository and upstream statement](https://github.com/valeriotor/Beyond-The-Veil#readme) | The repository states that the mod is 1.12.2-only and GitHub had no releases. Its linked CurseForge file inventory was not fully enumerated, so this is not an absolute absence claim. |
| StarMeowCraft | **Unconfirmed** | [Current 1.20.1 source configuration](https://github.com/rawchickenNEG/StarMeowCraft/blob/master/gradle.properties#L10-L18) | GitHub had no releases, and no original-author Modrinth or CurseForge project was verified during this review. |
| Create | **Confirmed** | [6.0.10 for NeoForge 1.21.1](https://modrinth.com/mod/create/version/UjX6dr61) | Official project linked to `Creators-of-Create/Create`. |
| WorldEdit | **Confirmed** | [7.3.8 mod release for Fabric/NeoForge 1.21.1](https://modrinth.com/mod/worldedit/version/WTAFvuRx) | Official project linked to `EngineHub/WorldEdit`. |

Third-party ports and revived forks are excluded. In particular, unrelated
Construction Wand Fabric, Polymer, and revived projects do not establish an
original-author 1.21.1 release.
