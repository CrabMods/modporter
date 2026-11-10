# Repository Instructions

<!-- modporter-public-testing:start -->
## Public build and runtime gates

- Use `mod-build.ps1 build` as the canonical build. It runs the repository's
  Gradle wrapper and public `gradle/modporter-testing.gradle` hook without
  contacting a private release or account.
- `MODPORTER_PRIVATE_FRAMEWORK_ROOT` may point to a separate ignored local
  sidecar. When selected, its authenticated `ensure` gate must pass before the
  public build starts; missing, stale, changed, or inaccessible sidecars fail
  the command without falling back to the public profile.
- Never let uncoordinated Minecraft runtimes reuse the same game directory,
  save, or port. Batch compatible scenarios and reset scenario-owned state
  between cases.
<!-- modporter-public-testing:end -->

## Task artifact cleanup

- On Windows, send authorized obsolete task artifacts to the Recycle Bin by
  default. Permanent deletion and emptying the Recycle Bin require explicit
  user instructions.
- Verify each literal target stays inside its named task directory, has no
  reparse points, and is not used by an active process. Preserve original
  inputs, real saves, current acceptance evidence, and unrelated work.
- A `blocked by policy` rejection does not complete cleanup. Record the exact
  rejected action, then use the authorized recoverable Recycle Bin operation
  for the same scoped target. Do not retry permanent deletion through another
  tool or disable an approval guard.
- Verify the original path is absent and the Recycle Bin metadata and payload
  identify that exact target. Report it as recycled; do not claim disk space
  was freed. If recycling itself fails, preserve the target and report the
  concrete remaining blocker.
