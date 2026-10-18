# Real Mod Benchmarking

ModPorter can be driven as an engineering benchmark instead of a one-off conversion script.
The benchmark manifest is `src/test/resources/benchmarks/real-mods.tsv`.

## Source Providers

The manifest is tab-separated:

```text
id  displayName  provider  location  ref  subdir  required  dependencies
```

Provider types:

| Provider | Meaning |
|----------|---------|
| `git` | Clone `location` at `ref` into a temporary source directory. |
| `local` | Copy a local source directory from `location`; reserved for private experiments and environment overrides, not the committed release manifest. |
| `missing` | Known benchmark target without a configured source provider yet; skipped unless strict mode is enabled. |

Committed benchmark entries that participate in the release gate must use reproducible Git
providers. `local` sources are useful for development overrides, but they make the default strict
gate depend on one maintainer machine and are rejected by the config tests.

The benchmark deletes fetched sources and converted outputs by default. Only reports and logs stay
under `build/real-mod-benchmark/reports/`.

`strictRealModBenchmark` also updates the README benchmark snapshot after a complete strict-runtime
success over every currently available Git-backed manifest entry. The generated README block records
the resolved Git commit hash for each source, so release documentation points at the exact upstream
revision that passed the gate.

`dependencies` is a comma-separated list of other benchmark ids, or `-`. When a selected case has
dependencies, the harness expands the run list transitively, compiles dependency cases first, builds
their jars into `build/real-mod-benchmark/tmp/artifacts/`, then copies those jars into the dependent
case's temporary `libs/` directory before compile. Those jars are test artifacts and are deleted with
the rest of `tmp/` unless `MODPORTER_BENCHMARK_KEEP_WORK=true`.

## Test Modes

| Mode | Entry point | Verification | Signal |
|------|-------------|--------------|--------|
| Unit/rule tests | `src/test/kotlin/com/modporter/transforms` | Expected text, AST, and build/resource rewrites | Fast regression coverage for deterministic rules |
| Synthetic pipeline tests | `BuildVerificationTest` | Converted project shape and major Forge-to-NeoForge structure | Good coverage for common surfaces, not a compiler proof |
| Synthetic compile gate | `CompilationVerificationTest` with `FORGE2NEO_COMPILE_TEST=true` | Minimal converted projects compile against NeoForge 1.21.1 | Strong signal for covered minimal APIs |
| Real mod benchmark | `./gradlew realModBenchmark` | Provider-backed real mods convert in temp workspaces, optionally compile/runtime-test with log audit, then clean up | Main engineering loop for hands-off porting |
| Strict real mod benchmark | `./gradlew strictRealModBenchmark` | Hands-off compile, dedicated server lifecycle, GameTest server, client boot, client saved-world quick-load, and warning-clean logs | Definition of a genuinely successful port |

## Run The Benchmark

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat realModBenchmark --no-daemon
```

This automatically fetches any configured `git` providers, ports them in a temporary work area,
validates conversion structure, writes a report, then cleans the temporary work area.

## Override Sources

For private repos or local experiments, override a manifest entry without editing the manifest:

```powershell
# Use a local checkout.
$env:MODPORTER_BENCHMARK_SOURCE_RADIOS = "E:\path\to\Radios"

# Or point a target at a Git repo/ref.
$env:MODPORTER_BENCHMARK_GIT_RADIOS = "https://example.com/Radios.git"
$env:MODPORTER_BENCHMARK_REF_RADIOS = "1.20.1"
```

Override names are based on the manifest id. For example `ping_system` uses:

```powershell
$env:MODPORTER_BENCHMARK_SOURCE_PING_SYSTEM = "E:\path\to\ping_system"
```

## Optional Gates

```powershell
# Fail if optional manifest sources/providers are missing.
$env:MODPORTER_BENCHMARK_STRICT = "true"

# Fail if any converted mod still has medium/low-confidence changes.
$env:MODPORTER_BENCHMARK_HANDS_OFF = "true"

# Run compileJava in each converted output.
$env:MODPORTER_BENCHMARK_COMPILE = "true"

# Run runServer as a bounded smoke test after compile.
# This requires the server to reach the ready/world-load marker, terminates the process tree,
# and fails on crash/error/fatal log lines.
$env:MODPORTER_BENCHMARK_RUNSERVER = "true"
$env:MODPORTER_BENCHMARK_TIMEOUT_SECONDS = "240"

# Run the generated gameTestServer run config.
$env:MODPORTER_BENCHMARK_RUNGAMETESTSERVER = "true"

# Run the client until title-screen readiness markers are reached.
$env:MODPORTER_BENCHMARK_RUNCLIENT = "true"

# Quick-load a vanilla smoke world in the client.
$env:MODPORTER_BENCHMARK_RUNCLIENTWORLD = "true"

# Optional: use a prepared vanilla singleplayer save instead of generating one.
$env:MODPORTER_BENCHMARK_CLIENT_WORLD = "E:\path\to\vanilla-smoke-world"

# Optional: override the vanilla server jar used to generate the client smoke world.
$env:MODPORTER_BENCHMARK_MINECRAFT_SERVER_JAR = "E:\path\to\minecraft_1.21.1_server.jar"

# Optional: override the Java 21 executable used by vanilla smoke-world generation.
$env:MODPORTER_BENCHMARK_JAVA21 = "C:\Program Files\Java\jdk-21\bin\java.exe"

# After a progress marker such as client startup, terminate and fail if final markers never arrive.
$env:MODPORTER_BENCHMARK_PROGRESS_GRACE_SECONDS = "75"

# Fail runtime gates on non-allowlisted WARN lines. The allowlist is limited to known platform noise.
$env:MODPORTER_BENCHMARK_LOG_CLEAN = "true"

# Turn on the strict success definition in one switch. This implies hands-off, compile,
# runServer, runGameTestServer, runClient, runClientWorld, and logClean.
$env:MODPORTER_BENCHMARK_STRICT_RUNTIME = "true"

# Keep fetched sources and converted outputs for debugging.
$env:MODPORTER_BENCHMARK_KEEP_WORK = "true"

# Refresh the README benchmark snapshot after a complete strict success.
$env:MODPORTER_BENCHMARK_UPDATE_README = "true"
```

For the full success gate, prefer:

```powershell
.\gradlew.bat strictRealModBenchmark --no-daemon
```

## Current Benchmark Targets

| Target | Provider | Notes |
|--------|----------|-------|
| ConstructionWand | `git` | Required baseline target |
| InstantWorldMirror | `git` | Public benchmark target from `CrabMods/InstantWorldMirror` branch `1.20.1` |
| HotBath | `git` | Public benchmark target from `CrabMods/hotBath` branch `1.20.1`; publishes a temporary benchmark jar for dependents |
| ShowerCore | `git` | Public benchmark target from `CrabMods/ShowerCore` branch `1.20.1`; depends on `hotbath`, whose dependency jar is staged automatically |
| Sakura Mod | `git` | Public benchmark target from upstream `0999312/Sakura_mod` branch `1.20.1` with broad API coverage |
| Twilight Forest | `git` | Public large-mod target from official `TeamTwilight/twilightforest` branch `1.20.1`; compare failures against their official `1.21.1` branch |
| The Aether | `git` | Public large-mod target from official `The-Aether-Team/The-Aether` branch `1.20.1-develop` |
| Beyond the Veil | `git` | Public large-mod target from `valeriotor/Beyond-The-Veil` branch `1.20` |
| Radios, LeaningTower, VillagerTourism, ping_system | `missing` | Known future targets; skipped unless strict mode is enabled |

The intended iteration loop is:

1. Add a `git` provider for a benchmark mod or provide an env override.
2. Run `realModBenchmark` without extra gates to confirm conversion structure.
3. Enable `MODPORTER_BENCHMARK_COMPILE=true` and fix compile failures with deterministic rules.
4. Enable `MODPORTER_BENCHMARK_HANDS_OFF=true` and drive medium/low buckets down to zero.
5. Enable `MODPORTER_BENCHMARK_RUNSERVER=true` for dedicated server startup and world generation.
6. Enable `MODPORTER_BENCHMARK_RUNGAMETESTSERVER=true` for the generated GameTest server run through completion/shutdown.
7. Enable `MODPORTER_BENCHMARK_RUNCLIENT=true` for client boot coverage.
8. Enable `MODPORTER_BENCHMARK_RUNCLIENTWORLD=true` for client saved-world load coverage.
9. Enable `MODPORTER_BENCHMARK_LOG_CLEAN=true` to enforce warning-clean runtime logs.
10. Enable `MODPORTER_BENCHMARK_UPDATE_README=true` only for a complete strict run that should refresh release documentation.
11. Use `MODPORTER_BENCHMARK_STRICT_RUNTIME=true` or `strictRealModBenchmark` as the success bar.

## Runtime Coverage Boundary

`MODPORTER_BENCHMARK_COMPILE=true` only proves the converted sources compile.
`MODPORTER_BENCHMARK_RUNSERVER=true` starts a dedicated server, waits for the ready/world-load marker,
terminates the process tree, and audits the complete runtime log for crash/error/fatal signals.
`MODPORTER_BENCHMARK_RUNGAMETESTSERVER=true` runs the generated GameTest server to completion/shutdown
and audits that runtime log as a server start-to-down gate.
`MODPORTER_BENCHMARK_RUNCLIENT=true` starts the client and waits for resource/audio readiness markers.
`MODPORTER_BENCHMARK_RUNCLIENTWORLD=true` stages a vanilla smoke save into
`run/saves/modporter_smoke_world`, then runs `runClientWorld`, which quick-loads that saved world with
`--quickPlaySingleplayer`. By default the harness generates that save with the local
`minecraft_1.21.1_server.jar`; `MODPORTER_BENCHMARK_CLIENT_WORLD` can point at a prepared vanilla save.
If the client reaches startup readiness but the integrated-server/world-load markers never appear,
`MODPORTER_BENCHMARK_PROGRESS_GRACE_SECONDS` bounds the wait, terminates the process tree, and reports
the missing world-load markers as a failure.

With `MODPORTER_BENCHMARK_LOG_CLEAN=true`, runtime gates also fail on non-allowlisted `WARN` lines.
`strictRealModBenchmark` enables all of those gates. A mod should only be called successfully ported
when that strict runtime benchmark passes.
