from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts import export_research_port as export


APACHE_LICENSE = b"""Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/
"""

MIT_LICENSE = b"""MIT License

Copyright (c) Example

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
"""


def write_tool_jar(path: Path, version: str = "0.4.0") -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("modporter-version.properties", f"version={version}\n")
        archive.writestr("META-INF/modporter/LICENSE", APACHE_LICENSE)
        archive.writestr("META-INF/modporter/NOTICE", "ModPorter notice\n")


def construction_metadata(*, credit: bool = True) -> str:
    credit_line = (
        'credits="Ported with ModPorter: https://github.com/CrabMods/modporter"\n'
        if credit
        else ""
    )
    return f'''modLoader="javafml"
loaderVersion="[1,)"
license="MIT License"
[[mods]]
modId="constructionwand"
version="${{file.jarVersion}}"
displayName="Construction Wand"
displayURL="https://github.com/Theta-Dev/ConstructionWand"
{credit_line}authors="ThetaDev"
description=\'\'\'
Construction Wands make building easier!

Original second paragraph.
\'\'\'
[[dependencies.constructionwand]]
modId="neoforge"
type="required"
'''


def source_construction_metadata() -> str:
    return '''modLoader="javafml"
loaderVersion="[46,)"
license="MIT License"
[[mods]]
modId="constructionwand"
version="${file.jarVersion}"
displayName="Construction Wand"
displayURL="https://github.com/Theta-Dev/ConstructionWand"
authors="ThetaDev"
description=\'\'\'
Construction Wands make building easier!

Original second paragraph.
\'\'\'
'''


def construction_build() -> str:
    return '''
plugins {
    id("net.neoforged.moddev") version "2.0.140"
}
version = "${minecraft_version}-${version_major}.${version_minor}"
group = "${author}.${modid}"
archivesBaseName = "${modid}"

jar {
    manifest {
        attributes([
            "Implementation-Title": archivesBaseName,
            "Implementation-Version": "${version}",
            "Implementation-Vendor": "${author}",
            "Implementation-Timestamp": new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")
        ])
    }
}
'''


class ExportResearchPortTest(unittest.TestCase):
    def test_reads_released_04_tool_and_records_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "modporter-0.4.0-all.jar"
            write_tool_jar(jar)
            bundle = export.read_tool_bundle(jar)
            self.assertEqual("0.4.0", bundle.version)
            self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(), bundle.jar_sha256)
            self.assertEqual(APACHE_LICENSE, bundle.apache_license)

    def test_rejects_tool_outside_04_line(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "modporter.jar"
            write_tool_jar(jar, "0.5.0")
            with self.assertRaisesRegex(export.ExportError, "released 0.4.x"):
                export.read_tool_bundle(jar)

    def test_supported_request_is_exactly_pinned(self) -> None:
        spec = export.SUPPORTED_PORTS["constructionwand"]
        config = export.ExportConfig(
            jar=Path("tool.jar"),
            src=Path("source"),
            out=Path("output"),
            repo_url="https://github.com/CrabMods/construction-wand-modporter-port",
            mod_id=spec.mod_id,
            display_name=spec.display_name,
            candidate_version="1.21.1-2.12-modporter.1",
            upstream_url=spec.upstream_url,
            upstream_commit=spec.upstream_commit,
        )
        self.assertEqual(spec, export.validate_config(config))
        changed = export.ExportConfig(
            **{**config.__dict__, "upstream_commit": "0" * 40}
        )
        with self.assertRaisesRegex(export.ExportError, "outside the reviewed"):
            export.validate_config(changed)

    def test_tool_command_uses_port_and_credit(self) -> None:
        command = export.tool_command(
            Path("modporter-0.4.0-all.jar"),
            Path("snapshot"),
            Path("candidate"),
        )
        self.assertEqual(
            [
                "java",
                "-jar",
                "modporter-0.4.0-all.jar",
                "port",
                "--src",
                "snapshot",
                "--out",
                "candidate",
                "--add-tool-credit",
            ],
            command,
        )

    def test_metadata_policy_preserves_authors_and_original_description(self) -> None:
        original = export.read_mod_metadata_text(source_construction_metadata())
        updated = export.update_metadata(
            construction_metadata(),
            candidate_version="1.21.1-2.12-modporter.1",
            display_name="Construction Wand",
            repo_url="https://github.com/CrabMods/construction-wand-modporter-port",
            original=original,
        )
        self.assertIn('version="1.21.1-2.12-modporter.1"', updated)
        self.assertIn('displayName="Construction Wand (Unofficial Candidate)"', updated)
        self.assertIn(
            'displayURL="https://github.com/CrabMods/construction-wand-modporter-port"',
            updated,
        )
        self.assertIn(f'issueTrackerURL="{export.ISSUES_URL}"', updated)
        self.assertIn(export.VALIDATION_SCOPE, updated)
        self.assertIn('authors="ThetaDev"', updated)
        self.assertIn("Construction Wands make building easier!", updated)
        self.assertIn("Original second paragraph.", updated)

    def test_metadata_policy_requires_tool_credit(self) -> None:
        original = export.read_mod_metadata_text(source_construction_metadata())
        with self.assertRaisesRegex(export.ExportError, "add-tool-credit"):
            export.update_metadata(
                construction_metadata(credit=False),
                candidate_version="candidate.1",
                display_name="Construction Wand",
                repo_url="https://github.com/CrabMods/construction-wand-modporter-port",
                original=original,
            )

    def test_build_policy_sets_version_reproducibility_and_packaging(self) -> None:
        updated = export.update_build_gradle(
            construction_build(),
            spec=export.SUPPORTED_PORTS["constructionwand"],
            candidate_version="1.21.1-2.12-modporter.1",
            include_support_license=True,
        )
        self.assertIn("version = '1.21.1-2.12-modporter.1'", updated)
        self.assertNotIn("Implementation-Timestamp", updated)
        self.assertIn("preserveFileTimestamps = false", updated)
        self.assertIn("reproducibleFileOrder = true", updated)
        self.assertIn("from('LICENSE')", updated)
        self.assertIn(f"from('{export.NOTICE_FILE}')", updated)
        self.assertIn(f"from('{export.SUPPORT_LICENSE_FILE}')", updated)

    def test_benchmark_contamination_is_rejected(self) -> None:
        paths = [
            "src/main/java/example/RealMod.java",
            (
                "src/main/java/com/modporter/generated/constructionwand/"
                "benchmark/ModPorterBenchmarkGameTests.java"
            ),
        ]
        self.assertEqual([paths[1]], export.benchmark_contamination(paths))

    def test_publication_policy_changes_only_metadata_and_adds_support_terms(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            out = root / "out"
            metadata_path = out / "src/main/resources/META-INF/neoforge.mods.toml"
            real_java = out / "src/main/java/example/RealMod.java"
            compat_java = (
                out
                / "src/main/java/com/modporter/generated/constructionwand/compat/Helper.java"
            )
            metadata_path.parent.mkdir(parents=True)
            real_java.parent.mkdir(parents=True)
            compat_java.parent.mkdir(parents=True)
            metadata_path.write_text(construction_metadata(), encoding="utf-8")
            real_java.write_text("package example; class RealMod {}\n", encoding="utf-8")
            compat_java.write_text(
                "package com.modporter.generated.constructionwand.compat;"
                " class Helper {}\n",
                encoding="utf-8",
            )
            (out / "build.gradle").write_text(construction_build(), encoding="utf-8")
            (out / "gradle.properties").write_text(
                "minecraft_version=1.21.1\nmod_id=constructionwand\n",
                encoding="utf-8",
            )
            (out / "LICENSE").write_bytes(MIT_LICENSE)

            jar = root / "modporter-0.4.0-all.jar"
            write_tool_jar(jar)
            tool = export.read_tool_bundle(jar)
            spec = export.SUPPORTED_PORTS["constructionwand"]
            config = export.ExportConfig(
                jar=jar,
                src=root / "source",
                out=out,
                repo_url="https://github.com/CrabMods/construction-wand-modporter-port",
                mod_id=spec.mod_id,
                display_name=spec.display_name,
                candidate_version="1.21.1-2.12-modporter.1",
                upstream_url=spec.upstream_url,
                upstream_commit=spec.upstream_commit,
            )
            original = export.read_mod_metadata_text(source_construction_metadata())
            before_java = real_java.read_bytes()
            before_compat = compat_java.read_bytes()
            export.apply_publication_policy(
                config,
                spec,
                tool,
                export.GitState(spec.upstream_commit, "a" * 40, ""),
                MIT_LICENSE,
                original,
            )

            self.assertEqual(before_java, real_java.read_bytes())
            self.assertEqual(before_compat, compat_java.read_bytes())
            self.assertEqual(
                APACHE_LICENSE, (out / export.SUPPORT_LICENSE_FILE).read_bytes()
            )
            self.assertTrue((out / export.NOTICE_FILE).is_file())
            provenance_text = (out / export.PROVENANCE_FILE).read_text(encoding="utf-8")
            provenance = json.loads(provenance_text)
            self.assertEqual(export.VALIDATION_SCOPE, provenance["candidate"]["validationScope"])
            self.assertNotIn(str(root), provenance_text)
            self.assertFalse(provenance["policy"]["modLogicHandPatches"])


if __name__ == "__main__":
    unittest.main()
