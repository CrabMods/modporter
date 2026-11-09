# Public Minecraft runtime sessions

ModPorter owns a small public runtime adapter. Its default public profile
has no private-framework dependency:

- `scripts/Invoke-ModporterRuntimeSession.ps1` owns one runtime-directory
  lease, launches one declared process topology, optionally validates an
  explicitly requested harness contract, and seals an immutable outer receipt.
- `scripts/runtime_process_inventory.ps1` finds Java Minecraft runtimes scoped
  to a canonical game directory. It also inspects bounded absolute local Java
  `@argument` files because ModDevGradle may put the launch target and game
  directory there instead of on the visible command line.
- `gradle/modporter-testing.gradle` guards direct runtime tasks with the same
  file lease and scoped inventory. Its `modporterTestingEnsure` task runs
  `python scripts/run_gradle.py ensure`, and it exposes the compatibility alias
  `minecraftModTestingEnsure`.

The adapter has no release updater, remote lookup, GitHub authentication, or
private-framework fallback. A local private sidecar may be selected separately
through the public build launcher, but it is optional and does not change this
session contract.

## Invoke one session

Use PowerShell hashtable splatting when an argument is an array. The runner
preserves `MINECRAFT_MOD_TEST_SESSION_ID` for the in-game harness, exports
`MODPORTER_RUNTIME_SESSION_OWNER_PID` for nested lease verification, and
restores the previous environment afterward.

```powershell
$session = @{
    ProjectRoot = (Get-Location).Path
    RuntimeDirectory = 'build/runtime/client'
    Executable = 'scripts/Run-HiddenValidationGradle.ps1'
    RuntimeKind = 'client'
    ReceiptPath = 'build/evidence/client/runtime-session.json'
    HarnessReceiptPath = 'build/evidence/client/harness.json'
    ExpectedScenarioCount = 2
    ExpectedScenarioIds = @('world-ready', 'render-ready')
    ExpectedProcessStartCount = 1
    ExpectedWorldLoadCount = 1
    TimeoutSeconds = 7200
}

& ./scripts/Invoke-ModporterRuntimeSession.ps1 @session
```

When calling the runner through a native process argument vector, pass ordered
scenario IDs with `-ExpectedScenarioIdsCsv 'world-ready|render-ready'`.

Omit `HarnessReceiptPath` and every expected harness field for an ordinary
lease-only client, server, or GameTest launch whose world or scenario evidence
is validated by a separate project gate. The outer receipt records
`evidenceMode: "lease-only"`, `harnessContractRequested: false`,
`harnessValidated: false`, and null scenario/world expectations. A lease-only
receipt proves the scoped resource lease, process inventory, owned child exit,
and absence of remaining scoped Minecraft processes. It does not claim that a
world loaded or that any scenario passed.

Binding any harness path, expected scenario count or IDs, expected process
count, or expected world count requests the strict harness contract. Such a
request requires a nonblank `HarnessReceiptPath`; a missing, invalid, or
tampered required harness fails without falling back to lease-only evidence.

The receipt path and harness path must be unique and absent before launch. The
runner accepts a successful Minecraft session only when:

- the resource-keyed mutex and file lease were acquired;
- the pre-launch scoped Java inventory was inspectable and empty;
- the harness session ID matches the assigned environment value;
- process, world, and scenario counts match the declared values;
- requested and observed scenario IDs match exactly in order and multiplicity;
- every scenario passed, completed cleanup, and has valid tick bounds;
- every embedded artifact descriptor still matches its file size and SHA-256;
- the harness was sealed during the runtime session;
- the command exited once and no scoped Minecraft process remained; and
- the harness receipt was hashed into the immutable outer receipt.

On timeout, the runner stops only the process tree created for that invocation.
It does not stop unrelated Java, Gradle, Minecraft, or user processes.

## Direct Gradle runtime tasks

Apply the public hook from the project build:

```groovy
apply from: 'gradle/modporter-testing.gradle'
```

A direct runtime task must declare its actual game directory:

```powershell
python scripts/run_gradle.py gradle -- runClient --no-daemon `
  -PmodporterRuntimeDirectory=build/runtime/client
```

The legacy `-PminecraftModTestingRuntimeDirectory` property remains a
compatibility alias. The hook skips its inner lease only when an outer session
has a nonblank session ID, the declared owner PID is a live ancestor of the
Gradle process, and `MODPORTER_RUNTIME_SESSION_DIRECTORY` resolves to the same
canonical directory. An arbitrary or stale environment flag cannot disable
the lease.

Give every simultaneous client or server its own runtime directory, world copy,
and listening port. One multiplayer topology should be launched by one outer
orchestrator and report the complete process and world counts in one harness
receipt. Never point validation at a real save; copy a verified pristine
fixture and prove the source fixture is unchanged after the run.
