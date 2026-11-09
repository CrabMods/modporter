# ModPorter

ModPorter is a rule-based migration tool for Minecraft mod developers. It
produces a reviewable starting point for moving Forge 1.20.1 projects to
NeoForge 1.21.1, reducing repetitive source, build-system, and resource work
so developers can focus on mod-specific behavior and review.

The project is intended for development, research, and learning. Generated
projects are candidates for human review and focused gameplay testing before
wider distribution. Migration fixes belong in reusable ModPorter rules; the
generated examples are not separately hand-maintained feature forks.

## Developer Workflow

1. Keep an unchanged copy of the original mod source and record its revision.
2. Run ModPorter into a separate output directory.
3. Review the transformation report and generated code.
4. Build and validate the generated project, then review its gameplay details.
5. Report repeatable migration problems to
   [ModPorter Issues](https://github.com/CrabMods/modporter/issues) so a general
   rule can be fixed and the project regenerated.

```sh
java -jar modporter-0.4.0-all.jar analyze --src /path/to/forge-mod
java -jar modporter-0.4.0-all.jar port \
  --src /path/to/forge-mod \
  --out /path/to/neoforge-candidate \
  --add-tool-credit
```

`--add-tool-credit` adds a concise ModPorter entry to supported mod metadata
while preserving the original authors and existing credits.

## Recorded Strict Benchmark

The November 9, 2026 engineering baseline passed all **11/11** required
source-project strict targets, with no failed or skipped targets, no low
confidence transformations, and no unresolved review signals.

| Target | Verified input |
| --- | --- |
| ConstructionWand | `Theta-Dev/ConstructionWand@bc64f11a7d79` |
| InstantWorldMirror | `CrabMods/InstantWorldMirror@eeeafdb6d998` |
| HotBath | `CrabMods/hotBath@7d284519936b` |
| ShowerCore | `CrabMods/ShowerCore@2f17206e9cb2` |
| Sakura Mod | `0999312/Sakura_mod@0201301e5aa3` |
| Twilight Forest | `TeamTwilight/twilightforest@1bc3a4c21213` |
| The Aether | `The-Aether-Team/The-Aether@2f0be3a51bae` |
| Beyond the Veil | `valeriotor/Beyond-The-Veil@ba4940c10e52` |
| StarMeowCraft | `rawchickenNEG/StarMeowCraft@77c9f63b73a7` |
| Create | Original source snapshot, SHA-256 prefix `d6a7482014bf` |
| WorldEdit | Prepared Forge-only input, derived from `b04ab0e26728` |

### Verification Scope

| Automated check | Coverage |
| --- | --- |
| Conversion and compilation | Source, build-system, and resource migration; generated project compiles |
| Dedicated server | Server startup and the declared shutdown gate |
| GameTest | The test suite present in each verified source project |
| Client | Client boot and saved-world loading |
| Item interface | Creative inventory opens; all displayed stacks in the relevant tabs are browsed |
| Runtime logs | Strict warning/error audit; accepted upstream or dependency findings require source evidence |

The checks cover project loading and item-interface presentation. Detailed
behavior of individual items, blocks, machines, and integrations remains an
area for developer review and focused playtesting.

The recorded baseline is a source-project validation result. A separately
published candidate JAR is identified and tested as its own artifact. See
[Benchmarking](docs/BENCHMARKING.md) for exact input scope and reproduction.

## How the Rules Work

The Forge-to-NeoForge pipeline applies source-contract checks followed by
text, AST, structural, build-system, and resource transformations. Rules use
source structure and API evidence, with regression tests for refusal cases
and unchanged-input behavior as well as successful migration.

New mods can expose additional API families or project structures. A useful
issue report includes the input revision, ModPorter version, transformation
report, and relevant build/runtime logs. Fixing the reusable rule improves
later generated ports as well.

## Build and Test

The tool uses Java 17 or newer. NeoForge 1.21.1 verification uses Java 21.
Python and PowerShell 7 are required for the project-owned public build/test
entry point.

```powershell
# Canonical build, tests, coverage report, and fat JAR
./mod-build.ps1 build shadowJar --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx4g' `
  '-Pkotlin.compiler.execution.strategy=in-process'

# Rule and integration tests
./mod-build.ps1 gradle test --no-daemon

# Strict source-project benchmark (prepares the declared WorldEdit input)
./scripts/run_strict_benchmarks.ps1 -KeepWork
```

The full build uses a 4 GB Gradle heap and compiles Kotlin in that process.
The public entry point uses the checked-in Gradle wrapper and
`gradle/modporter-testing.gradle`; it does not require `gh`, a GitHub token, or
private framework credentials. Maintainers may explicitly select an ignored
local sidecar with `MODPORTER_PRIVATE_FRAMEWORK_ROOT`; a selected sidecar must
pass its own authenticated check before the public build starts. CI builds and
packages in the public profile on Windows and Ubuntu. Version tags publish a
developer-preview JAR and its SHA-256 checksum after the CI gates pass.

## Generated Research Ports

Example repositories use `<mod-name>-modporter-port` and branches named for
the target Minecraft version. They identify the upstream revision, generator,
validation scope, and license, and route migration reports back to ModPorter.
Human review remains part of preparing a generated project for wider release.
The [upstream 1.21.1 release status](docs/UPSTREAM_RELEASE_STATUS.md) records
which benchmark owners publish official binaries and which checks remain unresolved.

Original mod copyrights and licenses remain with their owners. ModPorter does
not grant redistribution rights to an input project's code or assets.

## License

ModPorter is licensed under [Apache-2.0](LICENSE). Third-party dependencies
and regression fixtures retain their own licenses; see [NOTICE](NOTICE) and
[third-party attribution](third_party/README.md).
