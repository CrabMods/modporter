import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_strict_acceptance import frozen_code, sha, verify_native_capability


class StrictPublicBindingsTest(unittest.TestCase):
    def make_workspace(self, directory):
        root = Path(directory) / "public-project"
        for relative in (
            "src/main/Tool.kt", "scripts/public_tool.py", "gradle/public.gradle",
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "mod-build.ps1", "mod-build.cmd", "gradlew", "gradlew.bat",
        ):
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(relative + "\n", encoding="utf-8")
        return root

    def test_public_binding_ignores_an_unusable_local_private_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_workspace(directory)
            install = root / ".minecraft-mod-testing/install.json"
            install.parent.mkdir()
            install.write_text("not a usable private manifest", encoding="utf-8")
            with patch.dict(os.environ, {}, clear=True):
                captured = frozen_code(root)
            self.assertEqual(captured["buildProfile"], "public")
            self.assertIsNone(captured["privateFramework"])
            self.assertEqual(set(captured["trees"]), {"src", "scripts", "gradle"})

    def test_public_runtime_source_drift_changes_binding_but_python_cache_does_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_workspace(directory)
            with patch.dict(os.environ, {}, clear=True):
                baseline = frozen_code(root)
                cache = root / "scripts/__pycache__/public_tool.pyc"
                cache.parent.mkdir()
                cache.write_bytes(b"generated cache")
                self.assertEqual(baseline, frozen_code(root))
                (root / "scripts/public_tool.py").write_text("changed source\n", encoding="utf-8")
                self.assertNotEqual(baseline, frozen_code(root))

    def test_selected_private_sidecar_drift_fails_instead_of_becoming_public(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_workspace(directory)
            sidecar = Path(directory) / "private-local"
            install = sidecar / ".minecraft-mod-testing/install.json"
            install.parent.mkdir(parents=True)
            payload = sidecar / "private_tool.py"
            payload.write_bytes(b"original private tool")
            install.write_text(json.dumps({"managed_files": {
                "private_tool.py": hashlib.sha256(payload.read_bytes()).hexdigest()
            }}), encoding="utf-8")
            with patch.dict(os.environ, {"MODPORTER_PRIVATE_FRAMEWORK_ROOT": str(sidecar)}, clear=True):
                self.assertEqual(frozen_code(root)["buildProfile"], "private")
                payload.write_bytes(b"changed private tool")
                with self.assertRaisesRegex(AssertionError, "Selected private code drifted"):
                    frozen_code(root)

    def native_capability_row(self, reports, state="VALIDATED"):
        receipt = reports / "native-receipt.json"
        trace = reports / "native-trace.json"
        wrapper = reports / "wrapper.json"
        runtime_log = reports / "create-runClientWorld.log"
        trace.write_text(
            '{"artifactSchema":"legacy-block-entity-capability-native-v1.trace",'
            '"profileId":"legacy-block-entity-capability-native-v1","status":"PASS"}\n',
            encoding="utf-8",
        )
        receipt.write_text(
            '{"artifactSchema":"legacy-block-entity-capability-native-v1.receipt",'
            '"profileId":"legacy-block-entity-capability-native-v1","status":"PASS",'
            f'"tracePath":{__import__("json").dumps(str(trace))},'
            f'"traceSha256":"{sha(trace)}"}}\n',
            encoding="utf-8",
        )
        wrapper.write_text("{}\n", encoding="utf-8")
        runtime_log.write_text("native PASS\n", encoding="utf-8")
        return {
            "id": "create",
            "nativeCapabilityValidation": {
                "state": state,
                "issues": [],
                "profileId": "legacy-block-entity-capability-native-v1" if state == "VALIDATED" else None,
                "receiptPath": str(receipt) if state == "VALIDATED" else None,
                "receiptSha256": sha(receipt) if state == "VALIDATED" else None,
                "tracePath": str(trace) if state == "VALIDATED" else None,
                "traceSha256": sha(trace) if state == "VALIDATED" else None,
                "wrapperReceiptPath": str(wrapper) if state == "VALIDATED" else None,
                "wrapperReceiptSha256": sha(wrapper) if state == "VALIDATED" else None,
                "runtimeLogSha256": sha(runtime_log) if state == "VALIDATED" else None,
            },
        }

    def test_native_capability_evidence_is_bound_by_full_verifier(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory) / "reports"
            reports.mkdir()
            row = self.native_capability_row(reports)
            evidence = verify_native_capability(row, reports)
            self.assertEqual(len(evidence), 4)
            Path(row["nativeCapabilityValidation"]["tracePath"]).write_text(
                "changed\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(AssertionError, "evidence hash changed"):
                verify_native_capability(row, reports)

    def test_create_cannot_skip_native_capability_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory) / "reports"
            reports.mkdir()
            row = self.native_capability_row(reports, state="NOT_EVALUATED")
            with self.assertRaisesRegex(AssertionError, "was not evaluated"):
                verify_native_capability(row, reports)


if __name__ == "__main__":
    unittest.main()
