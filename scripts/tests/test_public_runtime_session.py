"""Process-level tests for the public ModPorter runtime session adapter."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPOSITORY_ROOT / "scripts" / "Invoke-ModporterRuntimeSession.ps1"
PWSH = shutil.which("pwsh")
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@unittest.skipUnless(os.name == "nt" and PWSH, "Windows PowerShell 7 is required")
class PublicRuntimeSessionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(
            prefix="modporter-public-runtime-"
        )
        self.root = Path(self.temporary.name)
        self.system_temp = self.root / "system-temp"
        self.system_temp.mkdir()
        self.environment = os.environ.copy()
        self.environment["TEMP"] = str(self.system_temp)
        self.environment["TMP"] = str(self.system_temp)
        self.harness_writer = self._write_script(
            "write-harness.ps1",
            r'''
$artifactPath = Join-Path (
    Split-Path -Parent $env:PUBLIC_RUNTIME_HARNESS_PATH
) 'artifact.bin'
[IO.File]::WriteAllBytes($artifactPath, [byte[]](1, 2, 3, 4))
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifactPath).
    Hash.ToLowerInvariant()
if ($env:PUBLIC_RUNTIME_FIXTURE_MODE -eq 'bad-hash') {
    $sha256 = '0' * 64
}
$allPassed = if ($env:PUBLIC_RUNTIME_FIXTURE_MODE -eq 'string-boolean') {
    'false'
} else {
    $true
}
$artifact = [ordered]@{
    path = $artifactPath
    sha256 = $sha256
    bytes = [long](Get-Item -LiteralPath $artifactPath).Length
}
$receipt = [ordered]@{
    schemaVersion = 1
    sessionId = $env:MINECRAFT_MOD_TEST_SESSION_ID
    processStartCount = 1
    worldLoadCount = 1
    scenarioCount = 1
    failedCount = 0
    cleanupFailureCount = 0
    allScenariosPassed = $allPassed
    requestedScenarios = @('alpha')
    observedScenarios = @('alpha')
    scenarios = @([ordered]@{
        id = 'alpha'
        passed = $true
        cleanupPassed = $true
        unexecuted = $false
        startedAtTick = 1
        finishedAtTick = 2
        artifacts = @($artifact)
    })
    runtimeDirectory = $env:MODPORTER_RUNTIME_SESSION_DIRECTORY
    sealedAtUtc = [DateTime]::UtcNow.ToString('o')
}
$receipt | ConvertTo-Json -Depth 20 |
    Set-Content -LiteralPath $env:PUBLIC_RUNTIME_HARNESS_PATH -Encoding utf8
''',
        )
        self.marker_child = self._write_script(
            "marker-child.ps1",
            r'''
if (-not [string]::IsNullOrWhiteSpace(
        $env:PUBLIC_RUNTIME_READY_PATH)) {
    [IO.File]::WriteAllText(
        $env:PUBLIC_RUNTIME_READY_PATH,
        "ready`n",
        [Text.UTF8Encoding]::new($false)
    )
}
$milliseconds = 0
if (-not [string]::IsNullOrWhiteSpace(
        $env:PUBLIC_RUNTIME_SLEEP_MILLISECONDS)) {
    $milliseconds = [int]$env:PUBLIC_RUNTIME_SLEEP_MILLISECONDS
}
if ($milliseconds -gt 0) {
    Start-Sleep -Milliseconds $milliseconds
}
''',
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_script(self, name: str, source: str) -> Path:
        path = self.root / name
        path.write_text(source.strip() + "\n", encoding="utf-8")
        return path

    def _command(
        self,
        *,
        runtime_directory: Path,
        executable: Path,
        receipt_path: Path,
        harness_path: Path | None = None,
        runtime_kind: str = "other",
        extra_arguments: tuple[str, ...] = (),
        timeout_seconds: int = 30,
    ) -> list[str]:
        command = [
            str(PWSH),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(RUNNER),
            "-ProjectRoot",
            str(self.root),
            "-RuntimeDirectory",
            str(runtime_directory),
            "-Executable",
            str(executable),
            "-RuntimeKind",
            runtime_kind,
            "-ReceiptPath",
            str(receipt_path),
            "-TimeoutSeconds",
            str(timeout_seconds),
        ]
        if harness_path is not None:
            command.extend(
                [
                    "-HarnessReceiptPath",
                    str(harness_path),
                    "-ExpectedScenarioCount",
                    "1",
                    "-ExpectedScenarioIdsCsv",
                    "alpha",
                    "-ExpectedProcessStartCount",
                    "1",
                    "-ExpectedWorldLoadCount",
                    "1",
                ]
            )
        command.extend(extra_arguments)
        return command

    def _run(
        self,
        *,
        runtime_directory: Path,
        executable: Path,
        receipt_path: Path,
        harness_path: Path | None = None,
        runtime_kind: str = "other",
        extra_arguments: tuple[str, ...] = (),
        environment: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self._command(
                runtime_directory=runtime_directory,
                executable=executable,
                receipt_path=receipt_path,
                harness_path=harness_path,
                runtime_kind=runtime_kind,
                extra_arguments=extra_arguments,
            ),
            cwd=self.root,
            env=self.environment if environment is None else environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=45,
            check=False,
            creationflags=CREATE_NO_WINDOW,
        )

    @staticmethod
    def _read_json(path: Path) -> dict[str, object]:
        return json.loads(path.read_text(encoding="utf-8-sig"))

    def _harness_environment(
        self, harness_path: Path, mode: str
    ) -> dict[str, str]:
        environment = self.environment.copy()
        environment["PUBLIC_RUNTIME_HARNESS_PATH"] = str(harness_path)
        environment["PUBLIC_RUNTIME_FIXTURE_MODE"] = mode
        return environment

    def test_valid_harness_binds_artifact_and_harness_hash(self) -> None:
        case = self.root / "valid"
        case.mkdir()
        harness = case / "harness.json"
        receipt = case / "session.json"
        result = self._run(
            runtime_directory=case / "runtime",
            executable=self.harness_writer,
            receipt_path=receipt,
            harness_path=harness,
            environment=self._harness_environment(harness, "ok"),
        )
        self.assertEqual(result.returncode, 0, result.stderr)

        outer = self._read_json(receipt)
        inner = self._read_json(harness)
        artifact = inner["scenarios"][0]["artifacts"][0]
        artifact_path = Path(artifact["path"])
        self.assertTrue(outer["passed"])
        self.assertEqual(outer["evidenceMode"], "harness")
        self.assertTrue(outer["harnessContractRequested"])
        self.assertTrue(outer["harnessValidated"])
        self.assertEqual(
            outer["harnessReceiptSha256"],
            hashlib.sha256(harness.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            artifact["sha256"],
            hashlib.sha256(artifact_path.read_bytes()).hexdigest(),
        )
        self.assertEqual(artifact["bytes"], artifact_path.stat().st_size)

    def test_corrupt_artifact_and_string_boolean_are_rejected(self) -> None:
        for mode, expected_failure in (
            ("bad-hash", "sha256 does not match"),
            ("string-boolean", "JSON boolean true"),
        ):
            with self.subTest(mode=mode):
                case = self.root / mode
                case.mkdir()
                harness = case / "harness.json"
                receipt = case / "session.json"
                result = self._run(
                    runtime_directory=case / "runtime",
                    executable=self.harness_writer,
                    receipt_path=receipt,
                    harness_path=harness,
                    environment=self._harness_environment(harness, mode),
                )
                self.assertNotEqual(result.returncode, 0)
                outer = self._read_json(receipt)
                self.assertEqual(outer["exitCode"], 74)
                self.assertFalse(outer["passed"])
                self.assertFalse(outer["harnessValidated"])
                self.assertIn(expected_failure, outer["failure"])

    def test_lease_only_server_records_no_world_or_scenario_claim(self) -> None:
        case = self.root / "lease-only-server"
        case.mkdir()
        receipt = case / "session.json"
        marker = case / "child-started.txt"
        environment = self.environment.copy()
        environment["PUBLIC_RUNTIME_READY_PATH"] = str(marker)

        result = self._run(
            runtime_directory=case / "runtime",
            executable=self.marker_child,
            receipt_path=receipt,
            runtime_kind="server",
            environment=environment,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(marker.exists())

        outer = self._read_json(receipt)
        self.assertTrue(outer["passed"])
        self.assertEqual(outer["runtimeKind"], "server")
        self.assertEqual(outer["evidenceMode"], "lease-only")
        self.assertFalse(outer["harnessContractRequested"])
        self.assertFalse(outer["harnessValidated"])
        self.assertEqual(outer["harnessReceiptPath"], "")
        self.assertEqual(outer["harnessReceiptSha256"], "")
        self.assertTrue(outer["processInventoryChecked"])
        self.assertTrue(outer["runtimeExitValidated"])
        self.assertEqual(outer["processStartCount"], 1)
        self.assertEqual(
            outer["expected"],
            {
                "scenarioCount": None,
                "scenarioIds": [],
                "processStartCount": None,
                "worldLoadCount": None,
            },
        )

    def test_explicit_harness_contract_without_path_is_refused(self) -> None:
        contracts = (
            ("blank-path", ("-HarnessReceiptPath", "")),
            ("scenario-count", ("-ExpectedScenarioCount", "0")),
            ("scenario-ids", ("-ExpectedScenarioIdsCsv", "alpha")),
            ("process-count", ("-ExpectedProcessStartCount", "1")),
            ("world-count", ("-ExpectedWorldLoadCount", "1")),
        )
        for name, arguments in contracts:
            with self.subTest(contract=name):
                case = self.root / f"missing-harness-{name}"
                case.mkdir()
                receipt = case / "session.json"
                marker = case / "child-started.txt"
                environment = self.environment.copy()
                environment["PUBLIC_RUNTIME_READY_PATH"] = str(marker)

                result = self._run(
                    runtime_directory=case / "runtime",
                    executable=self.marker_child,
                    receipt_path=receipt,
                    runtime_kind="server",
                    extra_arguments=arguments,
                    environment=environment,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(marker.exists())
                self.assertFalse(receipt.exists())
                self.assertIn(
                    "HarnessReceiptPath is required", result.stderr
                )

    def test_existing_receipt_is_refused_before_child_launch(self) -> None:
        case = self.root / "immutable"
        case.mkdir()
        receipt = case / "session.json"
        receipt.write_text("immutable-sentinel\n", encoding="utf-8")
        marker = case / "child-started.txt"
        environment = self.environment.copy()
        environment["PUBLIC_RUNTIME_READY_PATH"] = str(marker)

        result = self._run(
            runtime_directory=case / "runtime",
            executable=self.marker_child,
            receipt_path=receipt,
            environment=environment,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(
            receipt.read_text(encoding="utf-8"), "immutable-sentinel\n"
        )
        self.assertFalse(marker.exists())
        self.assertIn("already exists", result.stderr)

    def test_same_resource_is_denied_while_distinct_resource_runs(self) -> None:
        case = self.root / "leases"
        case.mkdir()
        first_ready = case / "first-ready.txt"
        first_receipt = case / "first-session.json"
        first_environment = self.environment.copy()
        first_environment["PUBLIC_RUNTIME_READY_PATH"] = str(first_ready)
        first_environment["PUBLIC_RUNTIME_SLEEP_MILLISECONDS"] = "3000"
        first = subprocess.Popen(
            self._command(
                runtime_directory=case / "runtime-a",
                executable=self.marker_child,
                receipt_path=first_receipt,
            ),
            cwd=self.root,
            env=first_environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            creationflags=CREATE_NO_WINDOW,
        )
        try:
            deadline = time.monotonic() + 15
            while not first_ready.exists() and time.monotonic() < deadline:
                if first.poll() is not None:
                    stdout, stderr = first.communicate()
                    self.fail(
                        "first lease exited before readiness: "
                        f"stdout={stdout!r}, stderr={stderr!r}"
                    )
                time.sleep(0.05)
            self.assertTrue(first_ready.exists(), "first child never became ready")

            same_marker = case / "same-child-started.txt"
            same_receipt = case / "same-session.json"
            same_environment = self.environment.copy()
            same_environment["PUBLIC_RUNTIME_READY_PATH"] = str(same_marker)
            same = self._run(
                runtime_directory=case / "runtime-a",
                executable=self.marker_child,
                receipt_path=same_receipt,
                environment=same_environment,
            )
            self.assertNotEqual(same.returncode, 0)
            self.assertFalse(same_marker.exists())
            same_outer = self._read_json(same_receipt)
            self.assertIn("already held", same_outer["failure"])

            distinct_marker = case / "distinct-child-started.txt"
            distinct_receipt = case / "distinct-session.json"
            distinct_environment = self.environment.copy()
            distinct_environment["PUBLIC_RUNTIME_READY_PATH"] = str(
                distinct_marker
            )
            distinct = self._run(
                runtime_directory=case / "runtime-b",
                executable=self.marker_child,
                receipt_path=distinct_receipt,
                environment=distinct_environment,
            )
            self.assertEqual(distinct.returncode, 0, distinct.stderr)
            self.assertTrue(distinct_marker.exists())
            self.assertTrue(self._read_json(distinct_receipt)["passed"])

            stdout, stderr = first.communicate(timeout=15)
            self.assertEqual(first.returncode, 0, f"{stdout}\n{stderr}")
            self.assertTrue(self._read_json(first_receipt)["passed"])
        finally:
            if first.poll() is None:
                try:
                    first.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    first.kill()
                    first.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()