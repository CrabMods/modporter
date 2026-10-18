# ModPorter

> **Work in progress / developer preview:** ModPorter helps move Forge 1.20.1 projects toward NeoForge 1.21.1. The v0.3.0 milestone proves the rules covered by the current benchmark set; it is not a guarantee that every untested mod will port fully hands-off.

## What It Is

ModPorter is a ruleset-driven migration tool. It applies deterministic source, build, and resource transformations collected from real mods, then protects those rules with regression tests and runtime gates.

When a new mod fails, it usually means the mod uses an API surface, project structure, data format, or runtime behavior that the current rules do not cover yet. The intended path is to add a reusable rule with tests while keeping the existing benchmark set green. Contributions that expand general migration coverage without benchmark-specific bypasses are welcome.

## Current Milestone

As of `v0.3.0`, the strict real-mod gate has been locally verified for the public Git-backed benchmark snapshot below:

<!-- MODPORTER:BENCHMARK-SNAPSHOT:START -->
| Target | Provider | Source | Resolved Git commit | Strict gate status |
|--------|----------|--------|---------------------|--------------------|
| ConstructionWand | Git | `Theta-Dev/ConstructionWand`, `1.20` | `bc64f11a7d799e921995821878a031bcfde4e22a` | PASS |
| InstantWorldMirror | Git | `CrabMods/InstantWorldMirror`, `1.20.1` | `5283fd1e20769aafc0a28225288f4be12d417680` | PASS |
| HotBath | Git | `CrabMods/hotBath`, `1.20.1` | `6b2d0925cf04d58fc6dc21e687ef98324ac6d8cc` | PASS |
| ShowerCore | Git | `CrabMods/ShowerCore`, `1.20.1` with `hotbath` dependency | `0b25a6d04ca7daa6dbdfb25d94434a8e7f8edaee` | PASS |
| Sakura Mod | Git | `0999312/Sakura_mod`, `1.20.1` | `0201301e5aa371d7f0816b1d786ca89e99936912` | PASS |
| Twilight Forest | Git | `TeamTwilight/twilightforest`, `1.20.1` | `1bc3a4c21213bc443967e92125613cb9ef47891e` | PASS |
| The Aether | Git | `The-Aether-Team/The-Aether`, `1.20.1-develop` | `2f0be3a51bae2f434fbf5b5c0aecc56b50f921b7` | PASS |
| Beyond the Veil | Git | `valeriotor/Beyond-The-Veil`, `1.20` | `ce8dfdbca00d956516c23de989d45bd138ee6867` | PASS |
| StarMeowCraft | Git | `rawchickenNEG/StarMeowCraft`, `master` | `ec41c1a7331cd5b8103a81a482d99a9fb3df5933` | PASS |
<!-- MODPORTER:BENCHMARK-SNAPSHOT:END -->

Each PASS means that target cleared hands-off conversion, compile, dedicated server lifecycle, GameTest server, client boot, saved-world quick-load, and log-clean runtime audit at the resolved Git commit shown above. Accepted runtime findings must be machine-evidenced as source-inherited or external dependency behavior.

## Quick Start

```bash
# Build the fat JAR
./gradlew shadowJar

# Port a mod
java -jar build/libs/modporter-*-all.jar port \
  --src /path/to/forge-mod \
  --out /path/to/output

# Analyze a mod
java -jar build/libs/modporter-*-all.jar analyze --src /path/to/forge-mod

# Add an optional tool credit to supported mod metadata
java -jar build/libs/modporter-*-all.jar port \
  --src /path/to/forge-mod \
  --out /path/to/output \
  --add-tool-credit
```

`--add-tool-credit` appends a concise `Ported with ModPorter: https://github.com/CrabMods/modporter` entry to supported `credits` metadata. It preserves existing credits and does not modify author metadata.

## Pipeline

The current `forge2neo` pipeline migrates Forge 1.20.1 mods to NeoForge 1.21.1 through five passes:

1. **TextReplacement** - package renames, API moves, import migrations, and targeted source rewrites
2. **AST** - Java transformations that benefit from parsed structure
3. **StructuralRefactor** - event bus cleanup, mod-bus event extraction, obsolete method removal, AT/mixin-aware rewrites
4. **BuildSystem** - ForgeGradle to NeoForge ModDev, dependency migration, source-set hygiene, Access Transformer and mixin metadata preservation
5. **ResourceMigration** - `mods.toml`, recipes, advancements, tags, models, and generated data/resource fixes

Production rules are expected to be source-structure or API-surface rules with regression coverage. Strict validation rejects incomplete behavior such as TODO migrations, source excludes, skipped structural parsing, commented source logic, and unevidenced runtime warnings.

## Gates

```bash
# Default test and package gate
./gradlew test shadowJar

# Real-mod engineering loop
./gradlew realModBenchmark

# Strict release gate
./gradlew strictRealModBenchmark
```

`realModBenchmark` reads `src/test/resources/benchmarks/real-mods.tsv`, fetches configured Git sources, ports each target in a temporary workspace, deletes fetched/converted sources by default, and writes summaries under `build/real-mod-benchmark/reports/`.

`strictRealModBenchmark` adds hands-off compile, dedicated server lifecycle, GameTest server, client boot, client saved-world quick-load, and log-clean runtime audit. A mod should only be treated as covered by ModPorter after this strict gate passes.

See `docs/BENCHMARKING.md` for provider configuration, runtime gate details, and one-target reproduction commands.

## Development

```bash
./gradlew test
./gradlew test jacocoTestReport
```

Requires Java 17+ for the tool itself. NeoForge 1.21.1 compile/runtime verification requires Java 21.
