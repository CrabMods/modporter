import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_strict_acceptance import frozen_code


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


if __name__ == "__main__":
    unittest.main()
