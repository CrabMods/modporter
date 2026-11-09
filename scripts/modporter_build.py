"""Project-owned public Gradle launcher with an optional private preflight."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Sequence

PUBLIC_BUILD_VERSION = "1.0.0"
PRIVATE_FRAMEWORK_ENV = "MODPORTER_PRIVATE_FRAMEWORK_ROOT"
PUBLIC_HOOK = Path("gradle/modporter-testing.gradle")


class BuildConfigurationError(RuntimeError):
    """Raised when the selected build profile cannot be executed safely."""


@dataclass(frozen=True)
class BuildRequest:
    command: str
    gradle_arguments: tuple[str, ...] = ()


@dataclass(frozen=True)
class BuildPlan:
    project_root: Path
    request: BuildRequest
    profile: str
    private_ensure_command: tuple[str, ...] | None
    gradle_command: tuple[str, ...] | None


def _without_separator(arguments: Sequence[str]) -> tuple[str, ...]:
    values = tuple(arguments)
    return values[1:] if values[:1] == ("--",) else values


def parse_cli(arguments: Sequence[str]) -> tuple[Path | None, BuildRequest]:
    parser = argparse.ArgumentParser(
        description="Run the project-owned public Gradle wrapper"
    )
    parser.add_argument("--project-root", type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("ensure")
    commands.add_parser("version")
    build = commands.add_parser("build")
    build.add_argument("arguments", nargs=argparse.REMAINDER)
    gradle = commands.add_parser("gradle")
    gradle.add_argument("arguments", nargs=argparse.REMAINDER)
    parsed = parser.parse_args(list(arguments))
    extra = _without_separator(getattr(parsed, "arguments", ()))
    return parsed.project_root, BuildRequest(parsed.command, extra)


def resolve_project_root(explicit: Path | None, script_path: Path) -> Path:
    candidate = explicit if explicit is not None else script_path.parent.parent
    try:
        root = candidate.expanduser().resolve(strict=True)
    except OSError as exc:
        raise BuildConfigurationError(
            f"project root does not exist: {candidate}"
        ) from exc
    if not root.is_dir():
        raise BuildConfigurationError(f"project root is not a directory: {root}")
    return root


def resolve_private_framework_root(
    environment: Mapping[str, str],
) -> Path | None:
    selected = environment.get(PRIVATE_FRAMEWORK_ENV, "").strip()
    if not selected:
        return None
    candidate = Path(selected).expanduser()
    try:
        root = candidate.resolve(strict=True)
    except OSError as exc:
        raise BuildConfigurationError(
            f"{PRIVATE_FRAMEWORK_ENV} does not exist: {candidate}"
        ) from exc
    if not root.is_dir():
        raise BuildConfigurationError(
            f"{PRIVATE_FRAMEWORK_ENV} is not a directory: {root}"
        )
    required = (
        root / ".minecraft-mod-testing" / "minecraft_mod_testing.py",
        root / ".minecraft-mod-testing" / "install.json",
        root / ".minecraft-mod-testing" / "project.json",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise BuildConfigurationError(
            "selected private framework is incomplete: " + ", ".join(missing)
        )
    return root


def private_ensure_command(
    private_root: Path, python_executable: str
) -> tuple[str, ...]:
    launcher = private_root / ".minecraft-mod-testing" / "minecraft_mod_testing.py"
    return (
        python_executable,
        str(launcher),
        "--project",
        str(private_root),
        "ensure",
    )


def gradle_wrapper_command(
    project_root: Path,
    platform_name: str,
    environment: Mapping[str, str],
) -> tuple[str, ...]:
    if platform_name == "nt":
        wrapper = project_root / "gradlew.bat"
        if not wrapper.is_file():
            raise BuildConfigurationError(f"Gradle wrapper is missing: {wrapper}")
        return (
            environment.get("COMSPEC", "cmd.exe"),
            "/d",
            "/c",
            str(wrapper),
        )
    wrapper = project_root / "gradlew"
    if not wrapper.is_file():
        raise BuildConfigurationError(f"Gradle wrapper is missing: {wrapper}")
    return ("sh", str(wrapper))


def _check_public_files(project_root: Path) -> None:
    required = (
        project_root / PUBLIC_HOOK,
        project_root / "scripts" / "Invoke-ModporterRuntimeSession.ps1",
        project_root / "scripts" / "runtime_process_inventory.ps1",
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise BuildConfigurationError(
            "public build adapter is incomplete: " + ", ".join(missing)
        )


def create_plan(
    project_root: Path,
    request: BuildRequest,
    environment: Mapping[str, str],
    *,
    platform_name: str = os.name,
    python_executable: str = sys.executable,
) -> BuildPlan:
    if request.command == "version":
        return BuildPlan(project_root, request, "public", None, None)
    _check_public_files(project_root)
    private_root = resolve_private_framework_root(environment)
    private_command = (
        private_ensure_command(private_root, python_executable)
        if private_root is not None
        else None
    )
    profile = "private" if private_command is not None else "public"
    if request.command == "ensure":
        # `ensure` is a local public preflight even when no private sidecar is
        # selected. Resolve the platform wrapper now so a later build cannot
        # claim a valid profile with a missing project-owned launcher.
        gradle_wrapper_command(project_root, platform_name, environment)
        gradle_command = None
    elif request.command in ("build", "gradle"):
        prefix = gradle_wrapper_command(project_root, platform_name, environment)
        tasks = ("build",) if request.command == "build" else ()
        gradle_command = (*prefix, *tasks, *request.gradle_arguments)
    else:
        raise BuildConfigurationError(f"unsupported command: {request.command}")
    return BuildPlan(
        project_root,
        request,
        profile,
        private_command,
        gradle_command,
    )


def execute_plan(
    plan: BuildPlan,
    environment: Mapping[str, str],
    *,
    executor: Callable[..., object] = subprocess.run,
    output: Callable[[str], None] = print,
) -> int:
    if plan.request.command == "version":
        output(f"modporter-public-build {PUBLIC_BUILD_VERSION}")
        output("modporter_build=passed profile=public command=version")
        return 0
    if plan.private_ensure_command is not None:
        private_result = executor(
            list(plan.private_ensure_command),
            cwd=plan.project_root,
            env=dict(environment),
            check=False,
        )
        private_code = int(getattr(private_result, "returncode"))
        if private_code != 0:
            return private_code
    if plan.gradle_command is not None:
        result = executor(
            list(plan.gradle_command),
            cwd=plan.project_root,
            env=dict(environment),
            check=False,
        )
        gradle_code = int(getattr(result, "returncode"))
        if gradle_code != 0:
            return gradle_code
    output(
        "modporter_build=passed "
        f"profile={plan.profile} command={plan.request.command}"
    )
    return 0


def main(
    arguments: Sequence[str] | None = None,
    *,
    script_path: Path | None = None,
    environment: Mapping[str, str] | None = None,
) -> int:
    values = sys.argv[1:] if arguments is None else list(arguments)
    env = os.environ.copy() if environment is None else dict(environment)
    anchor = Path(__file__) if script_path is None else script_path
    try:
        explicit_root, request = parse_cli(values)
        project_root = resolve_project_root(explicit_root, anchor.resolve())
        plan = create_plan(project_root, request, env)
        return execute_plan(plan, env)
    except BuildConfigurationError as exc:
        print(f"modporter_build error={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
