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

LGPL_LICENSE = b"""GNU LESSER GENERAL PUBLIC LICENSE
Version 2.1, February 1999
"""

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DYNAMIC_TREES_LICENSE = (
    REPOSITORY_ROOT / "third_party/dynamictrees/LICENSE"
).read_bytes()
DYNAMIC_TREES_README = (
    REPOSITORY_ROOT / "third_party/dynamictrees/README.md"
).read_bytes()

RUNTIME_SOURCE_NAMES = (
    "PlantType",
    "IPlantable",
    "LegacyPlantSustainer",
    "LegacyPlantPlacement",
    "LegacyPlantableAdapter",
    "LegacySoilAdapter",
    "LegacyPlantPlacementAdapter",
    "LegacyPlantables",
    "LegacyPlantAdapterProfile",
    "ReviewedExternalPlantAdapters",
    "LegacyPlantMixinPlugin",
    "LegacyPlantMushroomRotCompat",
    "mixin/LegacyPlantMushroomRotMixin",
    "mixin/LegacyPlantPluginSentinel",
    "AdditionalOwnedHelper",
)


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def write_candidate_tree(out: Path) -> None:
    metadata_path = out / "src/main/resources/META-INF/neoforge.mods.toml"
    real_java = out / "src/main/java/example/RealMod.java"
    metadata_path.parent.mkdir(parents=True)
    real_java.parent.mkdir(parents=True)
    metadata_path.write_text(construction_metadata(), encoding="utf-8", newline="\n")
    real_java.write_text(
        "package example; class RealMod {}\n", encoding="utf-8", newline="\n"
    )
    (out / "build.gradle").write_text(
        construction_build(), encoding="utf-8", newline="\n"
    )
    (out / "gradle.properties").write_text(
        "minecraft_version=1.21.1\nmod_id=constructionwand\n",
        encoding="utf-8",
        newline="\n",
    )
    (out / "LICENSE").write_bytes(MIT_LICENSE)


def write_runtime_manifest(out: Path, manifest: dict[str, object]) -> None:
    path = out / export.RUNTIME_COMPONENT_MANIFEST
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def runtime_source_content(name: str) -> bytes:
    is_forge = name in export.RUNTIME_COMPONENT_FORGE_SOURCE_NAMES
    is_dynamic_trees = name in export.RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCE_NAMES
    license_name = (
        "LGPL-2.1-only" if is_forge else "MIT" if is_dynamic_trees else "Apache-2.0"
    )
    simple_name = name.rsplit("/", 1)[-1]
    package_name = "com.modporter.compat.plant.v1"
    if name in export.RUNTIME_COMPONENT_MIXIN_SOURCE_NAMES:
        package_name += ".mixin"
    declaration = "interface" if name == "IPlantable" else "class"
    lines = [f"/* SPDX-License-Identifier: {license_name} */"]
    if is_dynamic_trees:
        lines.extend(
            [
                f"/* {export.RUNTIME_COMPONENT_DYNAMIC_TREES_COPYRIGHT} */",
                (
                    "/* Derived from Dynamic Trees "
                    f"{export.RUNTIME_COMPONENT_DYNAMIC_TREES_TAG} "
                    "MushroomRotGenFeature#postRot */"
                ),
            ]
        )
    if name == "LegacyPlantMixinPlugin":
        lines.extend(
            [
                "/* com.modporter.compat.plant.v1.mixin.LegacyPlantPluginSentinel */",
                '/* CONSUMER = "LegacyPlantMushroomRotMixin" */',
            ]
        )
    lines.extend(
        [
            f"package {package_name};",
            f"public {declaration} {simple_name} {{}}",
        ]
    )
    return ("\n".join(lines) + "\n").encode("utf-8")


def mixin_configuration() -> bytes:
    config = {
        "required": True,
        "minVersion": "0.8.7",
        "package": "com.modporter.compat.plant.v1.mixin",
        "compatibilityLevel": "JAVA_21",
        "plugin": "com.modporter.compat.plant.v1.LegacyPlantMixinPlugin",
        "mixins": ["LegacyPlantPluginSentinel"],
        "injectors": {"defaultRequire": 1},
    }
    return (json.dumps(config, indent=2) + "\n").encode("utf-8")


def write_runtime_component(out: Path) -> dict[str, object]:
    runtime_root = out / export.RUNTIME_COMPONENT_ROOT
    resources_root = runtime_root / "src/main/resources"
    sources: list[dict[str, str]] = []
    for name in RUNTIME_SOURCE_NAMES:
        is_forge = name in export.RUNTIME_COMPONENT_FORGE_SOURCE_NAMES
        is_dynamic_trees = name in export.RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCE_NAMES
        license_name = (
            "LGPL-2.1-only"
            if is_forge
            else "MIT"
            if is_dynamic_trees
            else "Apache-2.0"
        )
        origin = (
            "MinecraftForge"
            if is_forge
            else "DynamicTrees"
            if is_dynamic_trees
            else "ModPorter"
        )
        content = runtime_source_content(name)
        generated_path = (
            f"src/main/java/com/modporter/compat/plant/v1/{name}.java"
        )
        embedded_path = (
            "META-INF/modporter/legacy-plant-compat/sources/"
            f"com/modporter/compat/plant/v1/{name}.java"
        )
        generated = runtime_root / generated_path
        embedded = resources_root / embedded_path
        generated.parent.mkdir(parents=True, exist_ok=True)
        embedded.parent.mkdir(parents=True, exist_ok=True)
        generated.write_bytes(content)
        embedded.write_bytes(content)
        sources.append(
            {
                "name": name,
                "generatedPath": generated_path,
                "embeddedPath": embedded_path,
                "sha256": sha256_bytes(content),
                "license": license_name,
                "origin": origin,
            }
        )

    runtime_resource_content = {
        export.RUNTIME_COMPONENT_MIXIN_CONFIG: mixin_configuration(),
    }
    runtime_resources: list[dict[str, str]] = []
    for relative, role in export.RUNTIME_COMPONENT_RUNTIME_RESOURCE_ROLES.items():
        content = runtime_resource_content[relative]
        path = resources_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        runtime_resources.append(
            {"path": relative, "sha256": sha256_bytes(content), "role": role}
        )

    material_content = {
        "META-INF/modporter/LICENSE": APACHE_LICENSE,
        "META-INF/modporter/NOTICE": b"ModPorter distribution notice\n",
        "META-INF/modporter/third-party/minecraftforge/LICENSE.txt": LGPL_LICENSE,
        "META-INF/modporter/third-party/minecraftforge/README.md": (
            "Minecraft Forge "
            + export.RUNTIME_COMPONENT_FORGE_RELEASE
            + "\nCommit: "
            + export.RUNTIME_COMPONENT_FORGE_COMMIT
            + "\n"
        ).encode("utf-8"),
        "META-INF/modporter/third-party/dynamictrees/LICENSE": DYNAMIC_TREES_LICENSE,
        "META-INF/modporter/third-party/dynamictrees/README.md": DYNAMIC_TREES_README,
        "META-INF/modporter/legacy-plant-compat/build.gradle.recipe": (
            "plugins { id 'java-library' }\n"
            "jar { manifest { attributes('MixinConfigs': "
            f"'{export.RUNTIME_COMPONENT_MIXIN_CONFIG}') }} }}\n"
        ).encode("utf-8"),
        "META-INF/modporter/legacy-plant-compat/BUILD.md": (
            b"# Reconstruction\nCopy sources and resources, then run Gradle.\n"
        ),
    }
    materials: list[dict[str, str]] = []
    for relative, role in export.RUNTIME_COMPONENT_MATERIAL_ROLES.items():
        content = material_content[relative]
        path = resources_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        materials.append(
            {"path": relative, "sha256": sha256_bytes(content), "role": role}
        )

    template_sha = sha256_bytes(b"canonical legacy plant template fixture\n")
    manifest: dict[str, object] = {
        "schemaVersion": 1,
        "component": export.RUNTIME_COMPONENT_ID,
        "packagingVersion": export.RUNTIME_COMPONENT_PACKAGING_VERSION,
        "runtimeVersion": (
            f"{export.RUNTIME_COMPONENT_PACKAGING_VERSION}-{template_sha[:16]}"
        ),
        "templateSourceSha": template_sha,
        "forgeRelease": export.RUNTIME_COMPONENT_FORGE_RELEASE,
        "forgeCommit": export.RUNTIME_COMPONENT_FORGE_COMMIT,
        "licenseClassification": "per-file",
        "sources": sources,
        "runtimeResources": runtime_resources,
        "materials": materials,
    }
    write_runtime_manifest(out, manifest)
    (runtime_root / "build.gradle").write_text(
        "plugins { id 'java-library' }\n"
        "jar { manifest { attributes('MixinConfigs': "
        f"'{export.RUNTIME_COMPONENT_MIXIN_CONFIG}') }} }}\n",
        encoding="utf-8",
        newline="\n",
    )
    return manifest


def publication_inputs(root: Path, out: Path) -> tuple[
    export.ExportConfig, export.PortSpec, export.ToolBundle, export.ModMetadata
]:
    jar = root / "modporter-0.4.0-all.jar"
    write_tool_jar(jar)
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
    return (
        config,
        spec,
        export.read_tool_bundle(jar),
        export.read_mod_metadata_text(source_construction_metadata()),
    )


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


    def test_publication_policy_records_mixed_runtime_licenses_and_preserves_tree(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            out = root / "out"
            write_candidate_tree(out)
            manifest = write_runtime_component(out)
            runtime_root = out / export.RUNTIME_COMPONENT_ROOT
            runtime_before = export.file_hashes(runtime_root)
            config, spec, tool, original = publication_inputs(root, out)

            export.apply_publication_policy(
                config,
                spec,
                tool,
                export.GitState(spec.upstream_commit, "a" * 40, ""),
                MIT_LICENSE,
                original,
            )

            self.assertEqual(MIT_LICENSE, (out / "LICENSE").read_bytes())
            self.assertEqual(runtime_before, export.file_hashes(runtime_root))
            self.assertFalse((out / export.SUPPORT_LICENSE_FILE).exists())
            self.assertFalse((out / export.SUPPORT_NOTICE_FILE).exists())

            metadata = export.read_mod_metadata_text(
                (
                    out / "src/main/resources/META-INF/neoforge.mods.toml"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual("MIT License", metadata.license_name)

            provenance_text = (out / export.PROVENANCE_FILE).read_text(
                encoding="utf-8"
            )
            provenance = json.loads(provenance_text)
            self.assertNotIn(str(root), provenance_text)
            self.assertEqual("Apache-2.0", provenance["generator"]["license"])
            self.assertEqual([], provenance["generatedSupportSources"])
            self.assertIsNone(provenance["supportLicense"])
            component = provenance["runtimeComponents"][0]
            self.assertEqual(export.RUNTIME_COMPONENT_ID, component["component"])
            self.assertEqual(manifest["runtimeVersion"], component["runtimeVersion"])
            self.assertEqual(manifest["templateSourceSha"], component["templateSourceSha"])
            self.assertEqual(len(RUNTIME_SOURCE_NAMES), len(component["sources"]))
            source_by_name = {
                source["name"]: source for source in component["sources"]
            }
            self.assertEqual(
                export.RUNTIME_COMPONENT_REQUIRED_SOURCE_NAMES,
                export.RUNTIME_COMPONENT_REQUIRED_SOURCE_NAMES & set(source_by_name),
            )
            self.assertEqual(
                {
                    f"{export.RUNTIME_COMPONENT_ROOT}/src/main/java/"
                    "com/modporter/compat/plant/v1/LegacyPlantMushroomRotCompat.java"
                },
                set(component["licenseScopes"]["MIT"]),
            )
            self.assertEqual(
                ("MIT", "DynamicTrees"),
                (
                    source_by_name["LegacyPlantMushroomRotCompat"]["license"],
                    source_by_name["LegacyPlantMushroomRotCompat"]["origin"],
                ),
            )
            self.assertIn(
                f"{export.RUNTIME_COMPONENT_ROOT}/src/main/java/"
                "com/modporter/compat/plant/v1/PlantType.java",
                component["licenseScopes"]["LGPL-2.1-only"],
            )
            self.assertIn(
                f"{export.RUNTIME_COMPONENT_ROOT}/src/main/java/"
                "com/modporter/compat/plant/v1/AdditionalOwnedHelper.java",
                component["licenseScopes"]["Apache-2.0"],
            )
            for name in export.RUNTIME_COMPONENT_MIXIN_SOURCE_NAMES:
                self.assertEqual(
                    f"src/main/java/com/modporter/compat/plant/v1/{name}.java",
                    source_by_name[name]["generatedPath"],
                )

            self.assertEqual(manifest["runtimeResources"], component["runtimeResources"])
            self.assertEqual(1, len(component["runtimeResources"]))
            runtime_resource = component["runtimeResources"][0]
            self.assertEqual(export.RUNTIME_COMPONENT_MIXIN_CONFIG, runtime_resource["path"])
            self.assertEqual(
                export.RUNTIME_COMPONENT_RUNTIME_RESOURCE_ROLES[
                    export.RUNTIME_COMPONENT_MIXIN_CONFIG
                ],
                runtime_resource["role"],
            )
            mixin_bytes = (
                out
                / export.RUNTIME_COMPONENT_RESOURCES
                / export.RUNTIME_COMPONENT_MIXIN_CONFIG
            ).read_bytes()
            self.assertEqual(sha256_bytes(mixin_bytes), runtime_resource["sha256"])
            self.assertEqual(json.loads(mixin_configuration()), json.loads(mixin_bytes))

            dt_source = (
                out
                / export.RUNTIME_COMPONENT_ROOT
                / source_by_name["LegacyPlantMushroomRotCompat"]["generatedPath"]
            ).read_bytes()
            self.assertIn(
                export.RUNTIME_COMPONENT_DYNAMIC_TREES_COPYRIGHT.encode("utf-8"),
                dt_source,
            )
            self.assertEqual(
                DYNAMIC_TREES_LICENSE,
                (
                    out
                    / export.RUNTIME_COMPONENT_RESOURCES
                    / "META-INF/modporter/third-party/dynamictrees/LICENSE"
                ).read_bytes(),
            )

            notice = (out / export.NOTICE_FILE).read_text(encoding="utf-8")
            self.assertIn("LGPL-2.1-only", notice)
            self.assertIn("Apache-2.0", notice)
            self.assertIn("MIT", notice)
            self.assertIn("PlantType.java", notice)
            self.assertIn("LegacyPlantMushroomRotCompat.java", notice)
            self.assertIn("AdditionalOwnedHelper.java", notice)

            build = (out / "build.gradle").read_text(encoding="utf-8")
            self.assertIn(
                f"from('{export.RUNTIME_COMPONENT_RESOURCES}')", build
            )
            component_data = export.verify_runtime_component(out)
            self.assertIsNotNone(component_data)
            assert component_data is not None
            self.assertNotIn(
                export.RUNTIME_COMPONENT_MIXIN_CONFIG,
                component_data.outer_resource_paths,
            )
            self.assertNotIn(
                f"include('{export.RUNTIME_COMPONENT_MIXIN_CONFIG}')", build
            )
            for relative in component_data.outer_resource_paths:
                self.assertIn(f"include('{relative}')", build)
            for relative in (
                "META-INF/modporter/third-party/dynamictrees/LICENSE",
                "META-INF/modporter/third-party/dynamictrees/README.md",
            ):
                self.assertIn(relative, component_data.outer_resource_paths)

    def test_runtime_component_validation_fails_closed_before_policy_writes(self) -> None:
        def mutate_missing_manifest(out: Path, manifest: dict[str, object]) -> None:
            (out / export.RUNTIME_COMPONENT_MANIFEST).unlink()

        def mutate_missing_source(out: Path, manifest: dict[str, object]) -> None:
            source = manifest["sources"][0]
            assert isinstance(source, dict)
            (out / export.RUNTIME_COMPONENT_ROOT / source["generatedPath"]).unlink()

        def mutate_extra_source(out: Path, manifest: dict[str, object]) -> None:
            path = (
                out
                / export.RUNTIME_COMPONENT_ROOT
                / "src/main/java/com/modporter/compat/plant/v1/Undeclared.java"
            )
            path.write_text(
                "package com.modporter.compat.plant.v1; class Undeclared {}\n",
                encoding="utf-8",
                newline="\n",
            )

        def mutate_source_hash(out: Path, manifest: dict[str, object]) -> None:
            source = manifest["sources"][0]
            assert isinstance(source, dict)
            source["sha256"] = "0" * 64
            write_runtime_manifest(out, manifest)

        def mutate_embedded_source(out: Path, manifest: dict[str, object]) -> None:
            source = manifest["sources"][0]
            assert isinstance(source, dict)
            path = (
                out
                / export.RUNTIME_COMPONENT_RESOURCES
                / source["embeddedPath"]
            )
            path.write_bytes(path.read_bytes() + b"// drift\n")

        def mutate_missing_material(out: Path, manifest: dict[str, object]) -> None:
            material = manifest["materials"][0]
            assert isinstance(material, dict)
            (
                out
                / export.RUNTIME_COMPONENT_RESOURCES
                / material["path"]
            ).unlink()

        def mutate_material_hash(out: Path, manifest: dict[str, object]) -> None:
            material = manifest["materials"][0]
            assert isinstance(material, dict)
            material["sha256"] = "0" * 64
            write_runtime_manifest(out, manifest)

        def mutate_missing_build(out: Path, manifest: dict[str, object]) -> None:
            (out / export.RUNTIME_COMPONENT_BUILD).unlink()

        def mutate_unknown_license(out: Path, manifest: dict[str, object]) -> None:
            source = manifest["sources"][-1]
            assert isinstance(source, dict)
            source["license"] = "Proprietary"
            write_runtime_manifest(out, manifest)

        def mutate_forge_relabel(out: Path, manifest: dict[str, object]) -> None:
            source = manifest["sources"][0]
            assert isinstance(source, dict)
            source["license"] = "Apache-2.0"
            source["origin"] = "ModPorter"
            write_runtime_manifest(out, manifest)

        def remove_source(
            out: Path, manifest: dict[str, object], source_index: int
        ) -> None:
            sources = manifest["sources"]
            assert isinstance(sources, list)
            source = sources.pop(source_index)
            assert isinstance(source, dict)
            (out / export.RUNTIME_COMPONENT_ROOT / source["generatedPath"]).unlink()
            (
                out
                / export.RUNTIME_COMPONENT_RESOURCES
                / source["embeddedPath"]
            ).unlink()
            write_runtime_manifest(out, manifest)

        def mutate_omit_forge_source(out: Path, manifest: dict[str, object]) -> None:
            remove_source(out, manifest, 0)

        def mutate_omit_owned_source(out: Path, manifest: dict[str, object]) -> None:
            remove_source(out, manifest, 3)

        def source_entry(
            manifest: dict[str, object], name: str
        ) -> dict[str, object]:
            sources = manifest["sources"]
            assert isinstance(sources, list)
            return next(
                source
                for source in sources
                if isinstance(source, dict) and source.get("name") == name
            )

        def material_entry(
            manifest: dict[str, object], path: str
        ) -> dict[str, object]:
            materials = manifest["materials"]
            assert isinstance(materials, list)
            return next(
                material
                for material in materials
                if isinstance(material, dict) and material.get("path") == path
            )

        def runtime_resource_entry(manifest: dict[str, object]) -> dict[str, object]:
            resources = manifest["runtimeResources"]
            assert isinstance(resources, list) and len(resources) == 1
            resource = resources[0]
            assert isinstance(resource, dict)
            return resource

        def rewrite_source(
            out: Path,
            manifest: dict[str, object],
            name: str,
            content: bytes,
        ) -> None:
            source = source_entry(manifest, name)
            (out / export.RUNTIME_COMPONENT_ROOT / source["generatedPath"]).write_bytes(
                content
            )
            (out / export.RUNTIME_COMPONENT_RESOURCES / source["embeddedPath"]).write_bytes(
                content
            )
            source["sha256"] = sha256_bytes(content)
            write_runtime_manifest(out, manifest)

        def rewrite_runtime_resource(
            out: Path,
            manifest: dict[str, object],
            config: dict[str, object],
        ) -> None:
            resource = runtime_resource_entry(manifest)
            content = (json.dumps(config, indent=2) + "\n").encode("utf-8")
            (out / export.RUNTIME_COMPONENT_RESOURCES / resource["path"]).write_bytes(
                content
            )
            resource["sha256"] = sha256_bytes(content)
            write_runtime_manifest(out, manifest)

        def remove_named_source(
            out: Path, manifest: dict[str, object], name: str
        ) -> None:
            sources = manifest["sources"]
            assert isinstance(sources, list)
            index = next(
                index
                for index, source in enumerate(sources)
                if isinstance(source, dict) and source.get("name") == name
            )
            remove_source(out, manifest, index)

        def mutate_dt_relabel(out: Path, manifest: dict[str, object]) -> None:
            source = source_entry(manifest, "LegacyPlantMushroomRotCompat")
            source["license"] = "Apache-2.0"
            source["origin"] = "ModPorter"
            write_runtime_manifest(out, manifest)

        def mutate_dt_origin(out: Path, manifest: dict[str, object]) -> None:
            source = source_entry(manifest, "LegacyPlantMushroomRotCompat")
            source["origin"] = "ModPorter"
            write_runtime_manifest(out, manifest)

        def mutate_dt_copyright(out: Path, manifest: dict[str, object]) -> None:
            content = runtime_source_content("LegacyPlantMushroomRotCompat").replace(
                export.RUNTIME_COMPONENT_DYNAMIC_TREES_COPYRIGHT.encode("utf-8"),
                b"Copyright removed",
            )
            rewrite_source(out, manifest, "LegacyPlantMushroomRotCompat", content)

        def mutate_missing_dt_license(out: Path, manifest: dict[str, object]) -> None:
            material = material_entry(
                manifest, "META-INF/modporter/third-party/dynamictrees/LICENSE"
            )
            (out / export.RUNTIME_COMPONENT_RESOURCES / material["path"]).unlink()

        def mutate_fake_dt_license(out: Path, manifest: dict[str, object]) -> None:
            material = material_entry(
                manifest, "META-INF/modporter/third-party/dynamictrees/LICENSE"
            )
            (out / export.RUNTIME_COMPONENT_RESOURCES / material["path"]).write_bytes(
                MIT_LICENSE
            )
            material["sha256"] = sha256_bytes(MIT_LICENSE)
            write_runtime_manifest(out, manifest)

        def mutate_missing_runtime_resource(
            out: Path, manifest: dict[str, object]
        ) -> None:
            resource = runtime_resource_entry(manifest)
            (out / export.RUNTIME_COMPONENT_RESOURCES / resource["path"]).unlink()

        def mutate_runtime_resource_hash(
            out: Path, manifest: dict[str, object]
        ) -> None:
            runtime_resource_entry(manifest)["sha256"] = "0" * 64
            write_runtime_manifest(out, manifest)

        def runtime_semantic_mutation(key: str, value: object):
            def mutate(out: Path, manifest: dict[str, object]) -> None:
                config = json.loads(mixin_configuration())
                if key == "defaultRequire":
                    config["injectors"]["defaultRequire"] = value
                else:
                    config[key] = value
                rewrite_runtime_resource(out, manifest, config)

            return mutate

        def mutate_missing_runtime_resources_field(
            out: Path, manifest: dict[str, object]
        ) -> None:
            manifest.pop("runtimeResources")
            write_runtime_manifest(out, manifest)

        def mutate_empty_runtime_resources(
            out: Path, manifest: dict[str, object]
        ) -> None:
            manifest["runtimeResources"] = []
            write_runtime_manifest(out, manifest)

        def mutate_unknown_runtime_resource(
            out: Path, manifest: dict[str, object]
        ) -> None:
            runtime_resource_entry(manifest)["path"] = "unknown.mixins.json"
            write_runtime_manifest(out, manifest)

        def mutate_runtime_resource_role(
            out: Path, manifest: dict[str, object]
        ) -> None:
            runtime_resource_entry(manifest)["role"] = "Wrong role"
            write_runtime_manifest(out, manifest)

        def mutate_extra_runtime_resource(
            out: Path, manifest: dict[str, object]
        ) -> None:
            (out / export.RUNTIME_COMPONENT_RESOURCES / "undeclared.json").write_text(
                "{}\n", encoding="utf-8", newline="\n"
            )

        def mutate_mixin_name_traversal(
            out: Path, manifest: dict[str, object]
        ) -> None:
            source_entry(manifest, "mixin/LegacyPlantMushroomRotMixin")["name"] = (
                "mixin/../LegacyPlantMushroomRotMixin"
            )
            write_runtime_manifest(out, manifest)

        def mutate_unknown_nested_name(
            out: Path, manifest: dict[str, object]
        ) -> None:
            source_entry(manifest, "mixin/LegacyPlantMushroomRotMixin")["name"] = (
                "mixin/UnexpectedNested"
            )
            write_runtime_manifest(out, manifest)

        def mutate_generated_path_traversal(
            out: Path, manifest: dict[str, object]
        ) -> None:
            source_entry(manifest, "mixin/LegacyPlantMushroomRotMixin")[
                "generatedPath"
            ] = "src/main/java/com/modporter/compat/plant/v1/mixin/../Escape.java"
            write_runtime_manifest(out, manifest)

        def mutate_embedded_path_traversal(
            out: Path, manifest: dict[str, object]
        ) -> None:
            source_entry(manifest, "mixin/LegacyPlantMushroomRotMixin")[
                "embeddedPath"
            ] = (
                "META-INF/modporter/legacy-plant-compat/sources/"
                "com/modporter/compat/plant/v1/mixin/../Escape.java"
            )
            write_runtime_manifest(out, manifest)

        def mutate_omit_dt_consumer(out: Path, manifest: dict[str, object]) -> None:
            remove_named_source(out, manifest, "LegacyPlantMushroomRotCompat")

        def mutate_omit_mixin_sentinel(out: Path, manifest: dict[str, object]) -> None:
            remove_named_source(out, manifest, "mixin/LegacyPlantPluginSentinel")

        cases = {
            "missing manifest": (
                mutate_missing_manifest,
                "missing runtime component manifest",
            ),
            "missing expanded source": (mutate_missing_source, "missing runtime source"),
            "undeclared expanded source": (
                mutate_extra_source,
                "source manifest is incomplete",
            ),
            "source hash drift": (mutate_source_hash, "runtime source hash mismatch"),
            "embedded source drift": (
                mutate_embedded_source,
                "embedded runtime source differs",
            ),
            "missing legal material": (
                mutate_missing_material,
                "missing runtime component material",
            ),
            "legal material hash drift": (
                mutate_material_hash,
                "runtime component material hash mismatch",
            ),
            "missing component build": (
                mutate_missing_build,
                "missing runtime component build.gradle",
            ),
            "unknown source license": (
                mutate_unknown_license,
                "unknown runtime source license",
            ),
            "Forge source relabeled Apache": (
                mutate_forge_relabel,
                "must be classified as LGPL-2.1-only",
            ),
            "required Forge source omitted": (
                mutate_omit_forge_source,
                "missing required canonical sources",
            ),
            "required owned source omitted": (
                mutate_omit_owned_source,
                "missing required canonical sources",
            ),
            "Dynamic Trees consumer relabeled Apache": (
                mutate_dt_relabel,
                "must be classified as MIT",
            ),
            "MIT source origin mismatch": (
                mutate_dt_origin,
                "origin 'ModPorter' does not match MIT",
            ),
            "Dynamic Trees copyright removed with hashes updated": (
                mutate_dt_copyright,
                "lost copyright or provenance markers",
            ),
            "missing Dynamic Trees license": (
                mutate_missing_dt_license,
                "missing runtime component material",
            ),
            "self-hashed replacement Dynamic Trees license": (
                mutate_fake_dt_license,
                "MIT license bytes are not canonical",
            ),
            "missing runtime mixin JSON": (
                mutate_missing_runtime_resource,
                "missing runtime component resource",
            ),
            "runtime mixin JSON hash drift": (
                mutate_runtime_resource_hash,
                "runtime component resource hash mismatch",
            ),
            "runtime mixin required false with hash updated": (
                runtime_semantic_mutation("required", False),
                "runtime mixin configuration must be required",
            ),
            "runtime mixin package changed with hash updated": (
                runtime_semantic_mutation("package", "example.invalid"),
                "unexpected package",
            ),
            "runtime mixin plugin changed with hash updated": (
                runtime_semantic_mutation("plugin", "example.InvalidPlugin"),
                "unexpected plugin",
            ),
            "runtime mixin sentinel replaced with consumer": (
                runtime_semantic_mutation(
                    "mixins", ["LegacyPlantMushroomRotMixin"]
                ),
                "only the fail-closed sentinel",
            ),
            "runtime mixin defaultRequire changed with hash updated": (
                runtime_semantic_mutation("defaultRequire", 0),
                "defaultRequire must be integer 1",
            ),
            "runtimeResources field omitted": (
                mutate_missing_runtime_resources_field,
                "runtime component manifest fields are invalid: missing runtimeResources",
            ),
            "runtimeResources empty": (
                mutate_empty_runtime_resources,
                "runtime component resource set is incomplete",
            ),
            "unknown runtime resource": (
                mutate_unknown_runtime_resource,
                "unknown runtime component resource",
            ),
            "runtime resource role mismatch": (
                mutate_runtime_resource_role,
                "runtime component resource role mismatch",
            ),
            "undeclared runtime resource": (
                mutate_extra_runtime_resource,
                "runtime component resource manifest is incomplete",
            ),
            "mixin name traversal": (
                mutate_mixin_name_traversal,
                "not a canonical Java source name",
            ),
            "unknown nested mixin source": (
                mutate_unknown_nested_name,
                "not a canonical Java source name",
            ),
            "generated source path traversal": (
                mutate_generated_path_traversal,
                "generatedPath is not a safe relative path",
            ),
            "embedded source path traversal": (
                mutate_embedded_path_traversal,
                "embeddedPath is not a safe relative path",
            ),
            "required Dynamic Trees consumer omitted": (
                mutate_omit_dt_consumer,
                "missing required canonical sources",
            ),
            "required mixin sentinel omitted": (
                mutate_omit_mixin_sentinel,
                "missing required canonical sources",
            ),
        }
        for label, (mutate, error_pattern) in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                out = root / "out"
                write_candidate_tree(out)
                manifest = write_runtime_component(out)
                config, spec, tool, original = publication_inputs(root, out)
                mutate(out, manifest)
                before = export.file_hashes(out)

                with self.assertRaisesRegex(export.ExportError, error_pattern):
                    export.apply_publication_policy(
                        config,
                        spec,
                        tool,
                        export.GitState(spec.upstream_commit, "a" * 40, ""),
                        MIT_LICENSE,
                        original,
                    )

                self.assertEqual(before, export.file_hashes(out))
                self.assertFalse((out / export.NOTICE_FILE).exists())
                self.assertFalse((out / export.PROVENANCE_FILE).exists())

    def test_runtime_request_without_component_fails_before_policy_writes(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            out = root / "out"
            write_candidate_tree(out)
            request = out / export.RUNTIME_COMPONENT_REQUEST
            request.parent.mkdir(parents=True, exist_ok=True)
            request.write_text(
                "package=com.modporter.compat.plant.v1\n",
                encoding="utf-8",
                newline="\n",
            )
            config, spec, tool, original = publication_inputs(root, out)
            before = export.file_hashes(out)

            with self.assertRaisesRegex(
                export.ExportError, "runtime was requested.*component is missing"
            ):
                export.apply_publication_policy(
                    config,
                    spec,
                    tool,
                    export.GitState(spec.upstream_commit, "a" * 40, ""),
                    MIT_LICENSE,
                    original,
                )

            self.assertEqual(before, export.file_hashes(out))
            self.assertFalse((out / export.NOTICE_FILE).exists())
            self.assertFalse((out / export.PROVENANCE_FILE).exists())

    def test_policy_scope_explicitly_rejects_component_hash_drift(self) -> None:
        component_path = f"{export.RUNTIME_COMPONENT_ROOT}/build.gradle"
        before = {
            "build.gradle": "old-build",
            "metadata.toml": "old-metadata",
            component_path: "component-before",
        }
        after = {
            "build.gradle": "new-build",
            "metadata.toml": "new-metadata",
            component_path: "component-after",
            export.NOTICE_FILE: "notice",
            export.PROVENANCE_FILE: "provenance",
        }
        with self.assertRaisesRegex(
            export.ExportError, "changed runtime component files"
        ):
            export.verify_policy_edit_scope(
                before,
                after,
                metadata_relative="metadata.toml",
                support_sources=(),
                component_files=(component_path,),
            )


if __name__ == "__main__":
    unittest.main()
