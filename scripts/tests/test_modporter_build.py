from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from modporter_build import (
    BuildConfigurationError,
    BuildRequest,
    create_plan,
    execute_plan,
    parse_cli,
)


class PublicBuildCliTest(unittest.TestCase):
    def make_project(self, parent: Path, *, windows: bool = False) -> Path:
        root = parent / "project"
        (root / "gradle").mkdir(parents=True)
        (root / "gradle" / "modporter-testing.gradle").write_text(
            "// public hook\n", encoding="utf-8"
        )
        scripts = root / "scripts"
        scripts.mkdir()
        (scripts / "Invoke-ModporterRuntimeSession.ps1").write_text(
            "# public runner\n", encoding="utf-8"
        )
        (scripts / "runtime_process_inventory.ps1").write_text(
            "# public inventory\n", encoding="utf-8"
        )
        wrapper = root / ("gradlew.bat" if windows else "gradlew")
        wrapper.write_text("wrapper\n", encoding="utf-8")
        return root

    def make_private_sidecar(self, parent: Path) -> Path:
        root = parent / "private"
        managed = root / ".minecraft-mod-testing"
        managed.mkdir(parents=True)
        for name in ("minecraft_mod_testing.py", "install.json", "project.json"):
            (managed / name).write_text("{}\n", encoding="utf-8")
        return root

    def test_build_parser_strips_one_separator(self) -> None:
        root, request = parse_cli(
            ["--project-root", "repo", "build", "--", "--no-daemon"]
        )
        self.assertEqual(Path("repo"), root)
        self.assertEqual(BuildRequest("build", ("--no-daemon",)), request)

    def test_public_build_uses_only_the_project_wrapper(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = self.make_project(Path(directory))
            plan = create_plan(
                project,
                BuildRequest("build", ("--console=plain",)),
                {},
                platform_name="posix",
                python_executable="python-test",
            )
            self.assertEqual("public", plan.profile)
            self.assertIsNone(plan.private_ensure_command)
            self.assertEqual(
                ("sh", str(project / "gradlew"), "build", "--console=plain"),
                plan.gradle_command,
            )

    def test_private_selection_is_strict_and_runs_before_gradle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            project = self.make_project(parent)
            private = self.make_private_sidecar(parent)
            env = {"MODPORTER_PRIVATE_FRAMEWORK_ROOT": str(private)}
            plan = create_plan(
                project,
                BuildRequest("gradle", ("test",)),
                env,
                platform_name="posix",
                python_executable="python-test",
            )
            calls: list[list[str]] = []

            def fake_executor(command, **_kwargs):
                calls.append(command)
                return SimpleNamespace(returncode=0)

            lines: list[str] = []
            code = execute_plan(plan, env, executor=fake_executor, output=lines.append)
            self.assertEqual(0, code)
            self.assertEqual("python-test", calls[0][0])
            self.assertEqual("--project", calls[0][-3])
            self.assertEqual(str(private.resolve()), calls[0][-2])
            self.assertEqual("ensure", calls[0][-1])
            self.assertEqual(["sh", str(project / "gradlew"), "test"], calls[1])
            self.assertEqual(
                ["modporter_build=passed profile=private command=gradle"],
                lines,
            )

    def test_private_failure_has_no_public_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            project = self.make_project(parent)
            private = self.make_private_sidecar(parent)
            env = {"MODPORTER_PRIVATE_FRAMEWORK_ROOT": str(private)}
            plan = create_plan(
                project,
                BuildRequest("build"),
                env,
                platform_name="posix",
                python_executable="python-test",
            )
            calls: list[list[str]] = []

            def fake_executor(command, **_kwargs):
                calls.append(command)
                return SimpleNamespace(returncode=17)

            lines: list[str] = []
            code = execute_plan(plan, env, executor=fake_executor, output=lines.append)
            self.assertEqual(17, code)
            self.assertEqual(1, len(calls))
            self.assertEqual([], lines)

    def test_gradle_failure_has_no_terminal_success_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = self.make_project(Path(directory))
            plan = create_plan(
                project,
                BuildRequest("build"),
                {},
                platform_name="posix",
            )
            lines: list[str] = []
            code = execute_plan(
                plan,
                {},
                executor=lambda *_args, **_kwargs: SimpleNamespace(returncode=9),
                output=lines.append,
            )
            self.assertEqual(9, code)
            self.assertEqual([], lines)

    def test_selected_missing_private_sidecar_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = self.make_project(Path(directory))
            with self.assertRaisesRegex(
                BuildConfigurationError, "MODPORTER_PRIVATE_FRAMEWORK_ROOT"
            ):
                create_plan(
                    project,
                    BuildRequest("ensure"),
                    {"MODPORTER_PRIVATE_FRAMEWORK_ROOT": str(project / "missing")},
                    platform_name=os.name,
                )

    def test_public_hook_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = self.make_project(Path(directory))
            (project / "gradle" / "modporter-testing.gradle").unlink()
            with self.assertRaisesRegex(BuildConfigurationError, "public build adapter"):
                create_plan(project, BuildRequest("ensure"), {})

    def test_public_ensure_requires_the_platform_wrapper(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = self.make_project(Path(directory))
            (project / "gradlew").unlink()
            with self.assertRaisesRegex(BuildConfigurationError, "Gradle wrapper"):
                create_plan(
                    project,
                    BuildRequest("ensure"),
                    {},
                    platform_name="posix",
                )


if __name__ == "__main__":
    unittest.main()
