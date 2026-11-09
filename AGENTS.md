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
