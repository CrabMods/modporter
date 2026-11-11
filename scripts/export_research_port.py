#!/usr/bin/env python3
"""Export a bounded ModPorter-generated research candidate.

The wrapper accepts only the two MIT-licensed, pinned upstream projects whose
current Groovy build shapes were reviewed for the 0.4.0 release. It verifies
the public versioned GitHub release and SHA256SUMS before running the JAR
against a clean ``git archive`` snapshot, then applies a narrow
metadata and packaging policy.  It never edits Java/Kotlin mod logic.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from dataclasses import dataclass, replace
from pathlib import Path, PurePosixPath
from typing import Callable, Iterable, Mapping, Sequence
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


ISSUES_URL = "https://github.com/CrabMods/modporter/issues"
TOOL_URL = "https://github.com/CrabMods/modporter"
REQUIRED_TOOL_VERSION = "0.4.0"
VALIDATION_SCOPE = "bootstrap / inventory / logs; not item behavior verified"
DESCRIPTION_PREFIX = (
    "Unofficial developer research candidate generated with ModPorter. "
    f"Validation scope: {VALIDATION_SCOPE}."
)
POLICY_MARKER = "// modporter-publication-policy-v1"

PROVENANCE_FILE = "MODPORTER-PROVENANCE.json"
NOTICE_FILE = "MODPORTER-NOTICE"
SUPPORT_LICENSE_FILE = "MODPORTER-SUPPORT-LICENSE"
SUPPORT_NOTICE_FILE = "MODPORTER-SUPPORT-NOTICE"

RUNTIME_COMPONENT_ROOT = ".modporter/legacy-plant-compat"
RUNTIME_COMPONENT_REQUEST = ".modporter/legacy-plant-compat.required"
RUNTIME_COMPONENT_RESOURCES = f"{RUNTIME_COMPONENT_ROOT}/src/main/resources"
RUNTIME_COMPONENT_MANIFEST_RESOURCE = (
    "META-INF/modporter/legacy-plant-compat/provenance.json"
)
RUNTIME_COMPONENT_MANIFEST = (
    f"{RUNTIME_COMPONENT_RESOURCES}/{RUNTIME_COMPONENT_MANIFEST_RESOURCE}"
)
RUNTIME_COMPONENT_BUILD = f"{RUNTIME_COMPONENT_ROOT}/build.gradle"
RUNTIME_COMPONENT_ID = "com.modporter.compat:legacy-plant-compat"
RUNTIME_COMPONENT_PACKAGING_VERSION = "1.0.2"
RUNTIME_COMPONENT_FORGE_RELEASE = "1.20.1-47.1.33"
RUNTIME_COMPONENT_FORGE_COMMIT = "30f255d76c4c2e915414a24e6d8aed42f429817e"
RUNTIME_COMPONENT_MATERIAL_ROLES: Mapping[str, str] = {
    "META-INF/modporter/LICENSE": (
        "Apache-2.0 license for ModPorter helper sources"
    ),
    "META-INF/modporter/NOTICE": "ModPorter distribution notice",
    "META-INF/modporter/third-party/minecraftforge/LICENSE.txt": (
        "LGPL-2.1-only license for Forge-derived sources"
    ),
    "META-INF/modporter/third-party/minecraftforge/README.md": (
        "Minecraft Forge component notice and pinned provenance"
    ),
    "META-INF/modporter/third-party/dynamictrees/LICENSE": (
        "MIT license for Dynamic Trees-derived sources"
    ),
    "META-INF/modporter/third-party/dynamictrees/README.md": (
        "Dynamic Trees component notice and pinned provenance"
    ),
    "META-INF/modporter/legacy-plant-compat/build.gradle.recipe": (
        "Reproducible Gradle build recipe without an expected output hash"
    ),
    "META-INF/modporter/legacy-plant-compat/BUILD.md": (
        "Minimal source and resource reconstruction instructions"
    ),
}
RUNTIME_COMPONENT_MIXIN_CONFIG = "modporter-legacy-plant-dt.mixins.json"
RUNTIME_COMPONENT_RUNTIME_RESOURCE_ROLES: Mapping[str, str] = {
    RUNTIME_COMPONENT_MIXIN_CONFIG: (
        "Mixin configuration for the reviewed Dynamic Trees compatibility consumer"
    )
}
RUNTIME_COMPONENT_LICENSES = {"Apache-2.0", "LGPL-2.1-only", "MIT"}
RUNTIME_COMPONENT_FORGE_SOURCE_NAMES = {
    "PlantType",
    "IPlantable",
    "LegacyPlantables",
}
RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCE_NAMES = {"LegacyPlantMushroomRotCompat"}
RUNTIME_COMPONENT_MIXIN_SOURCE_NAMES = {
    "mixin/LegacyPlantMushroomRotMixin",
    "mixin/LegacyPlantPluginSentinel",
}
RUNTIME_COMPONENT_REQUIRED_SOURCE_NAMES = RUNTIME_COMPONENT_FORGE_SOURCE_NAMES | {
    "LegacyPlantSustainer",
    "LegacyPlantPlacement",
    "LegacyPlantableAdapter",
    "LegacySoilAdapter",
    "LegacyPlantPlacementAdapter",
    "LegacyPlantAdapterProfile",
    "ReviewedExternalPlantAdapters",
    "LegacyPlantMixinPlugin",
    "LegacyPlantMushroomRotCompat",
    *RUNTIME_COMPONENT_MIXIN_SOURCE_NAMES,
}
RUNTIME_COMPONENT_DYNAMIC_TREES_COORDINATE = (
    "com.ferreusveritas.dynamictrees:DynamicTrees-1.20.1:1.3.0-BETA13"
)
RUNTIME_COMPONENT_DYNAMIC_TREES_TAG = "1.3.0-BETA13"
RUNTIME_COMPONENT_DYNAMIC_TREES_COMMIT = "9b43a9261e11dbd7d84b7e37272cbbd9dcf23277"
RUNTIME_COMPONENT_DYNAMIC_TREES_BINARY_SHA256 = (
    "76f00472f12983d76ec6c88e319e6eb837fb2f49f64d92da1b296c3f01f3ca46"
)
RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCES_SHA256 = (
    "490a4023d765fd6bcc9b919025543380ceb862b131fb54227ac84c969f94f3af"
)
RUNTIME_COMPONENT_DYNAMIC_TREES_POM_SHA256 = (
    "cad503ea7270788211c70f5417965f760c3ab1d34aac4cffdc808e2ef7d6e999"
)
RUNTIME_COMPONENT_DYNAMIC_TREES_ADAPTED_SOURCE_SHA256 = (
    "d0ad96cfb0f89c3d935234b3bef45b2fcc9ca4f5e93e898423e23ef825507d5c"
)
RUNTIME_COMPONENT_DYNAMIC_TREES_LICENSE_SHA256 = (
    "7f29ddd022421f848d57e58f4e71486131eee9acd61a4cb70944141846b80ba0"
)
RUNTIME_COMPONENT_DYNAMIC_TREES_COPYRIGHT = (
    "Copyright (c) 2016,2017 Ferreus Veritas"
)

BENCHMARK_PATH_PARTS = (
    "/com/modporter/generated/",
    "/benchmark/",
)
BENCHMARK_CLASS_NAMES = (
    "ModPorterBenchmarkGameTests",
    "ModPorterClientWorldHarness",
)


class ExportError(RuntimeError):
    """A fail-closed candidate export error."""


@dataclass(frozen=True)
class PortSpec:
    mod_id: str
    display_name: str
    upstream_url: str
    upstream_commit: str
    source_build_markers: tuple[str, ...]
    generated_build_markers: tuple[str, ...]


SUPPORTED_PORTS: Mapping[str, PortSpec] = {
    "constructionwand": PortSpec(
        mod_id="constructionwand",
        display_name="Construction Wand",
        upstream_url="https://github.com/Theta-Dev/ConstructionWand.git",
        upstream_commit="bc64f11a7d799e921995821878a031bcfde4e22a",
        source_build_markers=(
            "id 'net.minecraftforge.gradle'",
            'version = "${mcversion}-${version_major}.${version_minor}"',
            'archivesBaseName = "${modid}"',
        ),
        generated_build_markers=(
            'id("net.neoforged.moddev")',
            'version = "${minecraft_version}-${version_major}.${version_minor}"',
            'archivesBaseName = "${modid}"',
        ),
    ),
    "sakura": PortSpec(
        mod_id="sakura",
        display_name="Sakura Mod",
        upstream_url="https://github.com/0999312/Sakura_mod.git",
        upstream_commit="0201301e5aa371d7f0816b1d786ca89e99936912",
        source_build_markers=(
            "id 'net.neoforged.moddev.legacyforge'",
            "version = '1.0.10-1.20.1'",
            "archivesBaseName = 'Sakura'",
        ),
        generated_build_markers=(
            'id("net.neoforged.moddev")',
            "group = 'cn.mcmod.sakura'",
            "archivesBaseName = 'Sakura'",
        ),
    ),
}


@dataclass(frozen=True)
class ExportConfig:
    jar: Path
    src: Path
    out: Path
    repo_url: str
    mod_id: str
    display_name: str
    candidate_version: str
    upstream_url: str
    upstream_commit: str


@dataclass(frozen=True)
class ToolBundle:
    version: str
    jar_sha256: str
    jar_name: str
    apache_license: bytes
    notice: bytes | None
    release: Mapping[str, object] | None = None


@dataclass(frozen=True)
class GitState:
    head: str
    tree: str
    status: str


@dataclass(frozen=True)
class ModMetadata:
    authors: str
    description: str
    display_name: str
    license_name: str


@dataclass(frozen=True)
class RuntimeComponent:
    component: str
    packaging_version: str
    runtime_version: str
    template_source_sha: str
    forge_release: str
    forge_commit: str
    sources: tuple[Mapping[str, str], ...]
    runtime_resources: tuple[Mapping[str, str], ...]
    materials: tuple[Mapping[str, str], ...]
    preserved_files: tuple[str, ...]

    @property
    def outer_resource_paths(self) -> tuple[str, ...]:
        return (
            RUNTIME_COMPONENT_MANIFEST_RESOURCE,
            *(material["path"] for material in self.materials),
        )

    def publication_record(self) -> dict[str, object]:
        scopes = {
            license_name: sorted(
                f"{RUNTIME_COMPONENT_ROOT}/{source['generatedPath']}"
                for source in self.sources
                if source["license"] == license_name
            )
            for license_name in sorted(RUNTIME_COMPONENT_LICENSES)
        }
        return {
            "component": self.component,
            "runtimeRoot": RUNTIME_COMPONENT_ROOT,
            "manifestPath": RUNTIME_COMPONENT_MANIFEST,
            "packagingVersion": self.packaging_version,
            "runtimeVersion": self.runtime_version,
            "templateSourceSha": self.template_source_sha,
            "forgeRelease": self.forge_release,
            "forgeCommit": self.forge_commit,
            "licenseClassification": "per-file",
            "licenseScopes": scopes,
            "sources": [dict(source) for source in self.sources],
            "runtimeResources": [
                dict(resource) for resource in self.runtime_resources
            ],
            "materials": [dict(material) for material in self.materials],
        }


RunCommand = Callable[..., subprocess.CompletedProcess[str]]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_repo_url(raw: str) -> str:
    value = raw.strip()
    ssh_match = re.fullmatch(r"git@github\.com:([^/]+)/(.+)", value, re.IGNORECASE)
    if ssh_match:
        value = f"https://github.com/{ssh_match.group(1)}/{ssh_match.group(2)}"
    return value.rstrip("/").removesuffix(".git").lower()


def validate_github_repo_url(raw: str, label: str) -> str:
    value = raw.strip()
    parsed = urlsplit(value)
    if (
        parsed.scheme != "https"
        or parsed.netloc.lower() != "github.com"
        or parsed.query
        or parsed.fragment
    ):
        raise ExportError(f"{label} must be a plain https://github.com/owner/repo URL")
    segments = [segment for segment in parsed.path.split("/") if segment]
    if len(segments) != 2 or any(segment in {".", ".."} for segment in segments):
        raise ExportError(f"{label} must identify exactly one GitHub repository")
    return value.rstrip("/")


def validate_candidate_version(value: str) -> str:
    candidate = value.strip()
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+~-]{0,127}", candidate):
        raise ExportError(
            "candidate version must contain only letters, digits, '.', '_', '+', '~', or '-'"
        )
    return candidate


def validate_display_name(value: str) -> str:
    candidate = value.strip()
    if not candidate or len(candidate) > 120 or "\n" in candidate or "\r" in candidate:
        raise ExportError("display name must be a non-empty single line")
    return candidate


def validate_config(config: ExportConfig) -> PortSpec:
    if config.mod_id not in SUPPORTED_PORTS:
        raise ExportError(
            "unsupported mod id; this exporter is bounded to constructionwand and sakura"
        )
    spec = SUPPORTED_PORTS[config.mod_id]
    if validate_display_name(config.display_name) != spec.display_name:
        raise ExportError(
            f"{config.mod_id}: display name must remain {spec.display_name!r}"
        )
    if not re.fullmatch(r"[0-9a-f]{40}", config.upstream_commit):
        raise ExportError("upstream commit must be a full lowercase 40-character Git hash")
    upstream_url = validate_github_repo_url(config.upstream_url, "upstream URL")
    repo_url = validate_github_repo_url(config.repo_url, "candidate repository URL")
    if normalize_repo_url(upstream_url) != normalize_repo_url(spec.upstream_url):
        raise ExportError(
            f"{config.mod_id}: upstream URL is outside the reviewed publication scope"
        )
    if config.upstream_commit != spec.upstream_commit:
        raise ExportError(
            f"{config.mod_id}: upstream commit is outside the reviewed publication scope"
        )
    if normalize_repo_url(repo_url) == normalize_repo_url(upstream_url):
        raise ExportError("candidate repository URL must not impersonate the upstream repository")
    validate_candidate_version(config.candidate_version)
    return spec


def read_tool_bundle(jar: Path) -> ToolBundle:
    if not jar.is_file():
        raise ExportError(f"released ModPorter JAR does not exist: {jar}")
    try:
        with zipfile.ZipFile(jar) as archive:
            version_text = archive.read("modporter-version.properties").decode("utf-8")
            properties = dict(
                line.split("=", 1)
                for line in version_text.splitlines()
                if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line
            )
            version = properties.get("version", "").strip()
            if version != REQUIRED_TOOL_VERSION:
                raise ExportError(
                    f"unsupported ModPorter version {version!r}; "
                    f"expected the released {REQUIRED_TOOL_VERSION} JAR reviewed for these candidates"
                )
            apache_license = archive.read("META-INF/modporter/LICENSE")
            if (
                b"Apache License" not in apache_license
                or b"Version 2.0" not in apache_license
            ):
                raise ExportError(
                    "released JAR does not contain the expected ModPorter Apache-2.0 license"
                )
            try:
                notice = archive.read("META-INF/modporter/NOTICE")
            except KeyError:
                notice = None
    except ExportError:
        raise
    except (KeyError, OSError, UnicodeDecodeError, zipfile.BadZipFile) as exc:
        raise ExportError(f"cannot verify released ModPorter JAR: {exc}") from exc
    return ToolBundle(
        version=version,
        jar_sha256=sha256_file(jar),
        jar_name=jar.name,
        apache_license=apache_license,
        notice=notice,
    )


def fetch_release_resource(url: str, maximum_bytes: int) -> bytes:
    request = Request(url, headers={"User-Agent": "ModPorter-research-export"})
    try:
        with urlopen(request, timeout=30) as response:
            if urlsplit(response.url).scheme != "https":
                raise ExportError("release download redirected outside HTTPS")
            payload = response.read(maximum_bytes + 1)
    except OSError as exc:
        raise ExportError(f"cannot verify public ModPorter release: {exc}") from exc
    if len(payload) > maximum_bytes:
        raise ExportError("public release response exceeded its expected size")
    return payload


def verify_released_tool(
    tool: ToolBundle,
    *,
    fetch: Callable[[str, int], bytes] = fetch_release_resource,
) -> ToolBundle:
    tag = f"v{tool.version}"
    release_url = f"{TOOL_URL}/releases/tag/{tag}"
    asset_root = f"{TOOL_URL}/releases/download/{tag}"
    jar_name = f"modporter-{tool.version}-all.jar"
    try:
        release = json.loads(fetch(
            f"https://api.github.com/repos/CrabMods/modporter/releases/tags/{tag}",
            1024 * 1024,
        ), object_pairs_hook=_strict_json_object)
        if (
            not isinstance(release, dict)
            or release.get("tag_name") != tag
            or release.get("draft") is not False
            or release.get("html_url") != release_url
            or type(release.get("id")) is not int
            or release["id"] <= 0
            or not isinstance(release.get("published_at"), str)
            or not release["published_at"]
            or not isinstance(release.get("assets"), list)
        ):
            raise ExportError("ModPorter release identity or publication state is invalid")
        assets = {}
        for name in (jar_name, "SHA256SUMS"):
            matches = [a for a in release["assets"] if isinstance(a, dict) and a.get("name") == name]
            if (
                len(matches) != 1
                or matches[0].get("browser_download_url") != f"{asset_root}/{name}"
                or type(matches[0].get("id")) is not int
                or matches[0]["id"] <= 0
            ):
                raise ExportError(f"public release must contain exactly one official {name} asset")
            assets[name] = matches[0]
        checksums = fetch(assets["SHA256SUMS"]["browser_download_url"], 16 * 1024)
        match = re.fullmatch(
            rf"([0-9a-f]{{64}})  {re.escape(jar_name)}",
            checksums.decode("utf-8-sig").strip(),
        )
        if match is None or match[1] != tool.jar_sha256:
            raise ExportError("ModPorter JAR does not match the published release SHA256SUMS")
        for name, payload_sha in ((jar_name, tool.jar_sha256), ("SHA256SUMS", hashlib.sha256(checksums).hexdigest())):
            digest = assets[name].get("digest")
            if digest is not None and digest != f"sha256:{payload_sha}":
                raise ExportError(f"published {name} asset digest differs from its content")
    except (ValueError, TypeError) as exc:
        raise ExportError(f"invalid public ModPorter release evidence: {exc}") from exc
    return replace(tool, release={
        "tag": tag,
        "url": release_url,
        "id": release["id"],
        "publishedAt": release["published_at"],
        "jarAssetId": assets[jar_name]["id"],
        "jarAssetUrl": assets[jar_name]["browser_download_url"],
        "checksumsAssetId": assets["SHA256SUMS"]["id"],
        "checksumsUrl": assets["SHA256SUMS"]["browser_download_url"],
        "checksumsSha256": hashlib.sha256(checksums).hexdigest(),
    })


def default_runner(
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    capture_output: bool = False,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        cwd=cwd,
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE if capture_output else None,
        stderr=subprocess.PIPE if capture_output else None,
    )


def run_checked(
    runner: RunCommand,
    command: Sequence[str],
    *,
    cwd: Path | None = None,
    capture_output: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = runner(command, cwd=cwd, capture_output=capture_output)
    if result.returncode != 0:
        output = "\n".join(
            part.rstrip()
            for part in (getattr(result, "stdout", None), getattr(result, "stderr", None))
            if part
        )
        suffix = f":\n{output}" if output else ""
        raise ExportError(
            f"command failed with exit code {result.returncode}: "
            + " ".join(command)
            + suffix
        )
    return result


def git_text(runner: RunCommand, repo: Path, *args: str) -> str:
    return (run_checked(
        runner,
        ["git", "-C", str(repo), *args],
        capture_output=True,
    ).stdout or "").strip()


def source_git_state(runner: RunCommand, src: Path) -> GitState:
    root = Path(git_text(runner, src, "rev-parse", "--show-toplevel")).resolve()
    if root != src.resolve():
        raise ExportError(f"source must be the Git repository root: {src}")
    return GitState(
        head=git_text(runner, src, "rev-parse", "HEAD^{commit}").lower(),
        tree=git_text(runner, src, "rev-parse", "HEAD^{tree}").lower(),
        status=git_text(
            runner,
            src,
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--ignore-submodules=none",
        ),
    )


def verify_source_git(
    runner: RunCommand,
    config: ExportConfig,
    spec: PortSpec,
) -> GitState:
    if not config.src.is_dir():
        raise ExportError(f"source directory does not exist: {config.src}")
    state = source_git_state(runner, config.src)
    if state.head != config.upstream_commit:
        raise ExportError(
            f"source HEAD is {state.head}, expected pinned commit {config.upstream_commit}"
        )
    if state.status:
        raise ExportError("source Git worktree is not clean:\n" + state.status)
    remote_names = git_text(runner, config.src, "remote").splitlines()
    remote_urls: list[str] = []
    for remote in remote_names:
        if not remote.strip():
            continue
        result = run_checked(
            runner,
            ["git", "-C", str(config.src), "remote", "get-url", "--all", remote.strip()],
            capture_output=True,
        )
        remote_urls.extend((result.stdout or "").splitlines())
    if normalize_repo_url(spec.upstream_url) not in {
        normalize_repo_url(url) for url in remote_urls
    }:
        raise ExportError(
            "source Git repository has no remote matching the supplied upstream URL"
        )
    submodule_entries = git_text(
        runner, config.src, "ls-tree", "-r", config.upstream_commit
    ).splitlines()
    if any(line.startswith("160000 ") for line in submodule_entries):
        raise ExportError("reviewed exporter does not support Git submodules")
    return state


def ensure_source_unchanged(
    runner: RunCommand,
    src: Path,
    before: GitState,
) -> None:
    after = source_git_state(runner, src)
    if after != before:
        raise ExportError(
            "source Git state changed during generation; refusing the candidate export"
        )


def relative_file_paths(root: Path) -> list[str]:
    result: list[str] = []
    for current, directories, files in os.walk(root):
        directories[:] = sorted(
            directory for directory in directories if directory != ".git"
        )
        current_path = Path(current)
        for filename in sorted(files):
            result.append((current_path / filename).relative_to(root).as_posix())
    return result


def benchmark_contamination(paths: Iterable[str]) -> list[str]:
    contaminated: list[str] = []
    for raw in paths:
        normalized = "/" + raw.replace("\\", "/").strip("/")
        lowered = normalized.lower()
        benchmark_package = all(part in lowered for part in BENCHMARK_PATH_PARTS)
        benchmark_class = any(name.lower() in lowered for name in BENCHMARK_CLASS_NAMES)
        if benchmark_package or benchmark_class:
            contaminated.append(raw.replace("\\", "/"))
    return sorted(set(contaminated))


def reject_benchmark_contamination(root: Path, label: str) -> None:
    contaminated = benchmark_contamination(relative_file_paths(root))
    if contaminated:
        preview = ", ".join(contaminated[:8])
        if len(contaminated) > 8:
            preview += f", ... ({len(contaminated)} files)"
        raise ExportError(f"{label} contains generated benchmark classes: {preview}")


def verify_mit_license(src: Path) -> bytes:
    license_path = src / "LICENSE"
    if not license_path.is_file():
        raise ExportError("reviewed input must contain the upstream LICENSE file")
    content = license_path.read_bytes()
    lowered = content.lower()
    required = (
        b"mit license",
        b"permission is hereby granted, free of charge",
        b"the software is provided",
    )
    if any(fragment not in lowered for fragment in required):
        raise ExportError("input LICENSE is not the reviewed MIT license shape")
    if b"all rights reserved" in lowered:
        raise ExportError("input license contains an all-rights-reserved restriction")
    return content


def reject_tracked_arr(runner: RunCommand, src: Path, commit: str) -> None:
    tracked = git_text(runner, src, "ls-tree", "-r", "--name-only", commit).splitlines()
    for relative in tracked:
        path = src.joinpath(*relative.split("/"))
        if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
            continue
        try:
            content = path.read_bytes()
        except OSError as exc:
            raise ExportError(f"cannot inspect tracked rights text {relative}: {exc}") from exc
        if b"all rights reserved" in content.lower():
            raise ExportError(
                f"tracked input contains an all-rights-reserved declaration: {relative}"
            )


def find_source_metadata(src: Path) -> Path:
    candidates = (
        src / "src/main/resources/META-INF/mods.toml",
        src / "src/main/templates/META-INF/mods.toml",
    )
    found = [path for path in candidates if path.is_file()]
    if len(found) != 1:
        raise ExportError(
            "reviewed source must contain exactly one Forge mods.toml metadata file"
        )
    return found[0]


def find_generated_metadata(out: Path) -> Path:
    candidates = (
        out / "src/main/resources/META-INF/neoforge.mods.toml",
        out / "src/main/templates/META-INF/neoforge.mods.toml",
    )
    found = [path for path in candidates if path.is_file()]
    if len(found) != 1:
        raise ExportError(
            "generated tree must contain exactly one NeoForge metadata file"
        )
    return found[0]


def _mod_block(text: str) -> tuple[int, int, str]:
    header = re.search(r"(?m)^[ \t]*\[\[\s*mods\s*]][ \t]*(?:#.*)?$", text)
    if not header:
        raise ExportError("metadata is missing [[mods]]")
    next_table = re.search(r"(?m)^[ \t]*\[\[", text[header.end():])
    end = header.end() + next_table.start() if next_table else len(text)
    return header.start(), end, text[header.start():end]


def _basic_string(block: str, key: str, *, required: bool = True) -> str | None:
    matches = list(re.finditer(
        rf'(?m)^[ \t]*{re.escape(key)}[ \t]*=[ \t]*"([^"\r\n]*)"[ \t]*(?:#.*)?$',
        block,
    ))
    if not matches:
        if required:
            raise ExportError(f"metadata mod block is missing {key}")
        return None
    if len(matches) != 1:
        raise ExportError(f"metadata mod block contains multiple {key} fields")
    return matches[0].group(1)


def _description(block: str) -> str:
    match = re.search(
        r"(?ms)^[ \t]*description[ \t]*=[ \t]*(?P<quote>'''|\"\"\")"
        r"(?P<body>.*?)(?P=quote)[ \t]*(?:#.*)?$",
        block,
    )
    if not match:
        raise ExportError("metadata mod block is missing a triple-quoted description")
    return match.group("body").strip("\r\n")


def read_mod_metadata_text(text: str) -> ModMetadata:
    _, _, block = _mod_block(text)
    return ModMetadata(
        authors=_basic_string(block, "authors") or "",
        description=_description(block),
        display_name=_basic_string(block, "displayName") or "",
        license_name=_basic_string(text[:_mod_block(text)[0]], "license") or "",
    )


def read_mod_metadata(path: Path) -> ModMetadata:
    return read_mod_metadata_text(path.read_text(encoding="utf-8"))


def _replace_mod_basic_string(block: str, key: str, value: str) -> str:
    pattern = re.compile(
        rf'(?m)^([ \t]*{re.escape(key)}[ \t]*=[ \t]*)"([^"\r\n]*)"'
        r"([ \t]*(?:#.*)?)$"
    )
    matches = list(pattern.finditer(block))
    if len(matches) != 1:
        raise ExportError(f"expected exactly one {key} field in metadata mod block")
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return pattern.sub(rf'\1"{escaped}"\3', block, count=1)


def _prefix_description(block: str, prefix: str) -> str:
    pattern = re.compile(
        r"(?ms)^([ \t]*description[ \t]*=[ \t]*)(?P<quote>'''|\"\"\")"
        r"(?P<body>.*?)(?P=quote)([ \t]*(?:#.*)?)$"
    )
    match = pattern.search(block)
    if not match:
        raise ExportError("cannot locate metadata description for publication prefix")
    original = match.group("body").strip("\r\n")
    newline = "\r\n" if "\r\n" in block else "\n"
    replacement = (
        match.group(1)
        + match.group("quote")
        + newline
        + prefix
        + newline
        + newline
        + original
        + newline
        + match.group("quote")
        + match.group(4)
    )
    return block[:match.start()] + replacement + block[match.end():]


def update_metadata(
    text: str,
    *,
    candidate_version: str,
    display_name: str,
    repo_url: str,
    original: ModMetadata,
) -> str:
    start, end, block = _mod_block(text)
    generated = ModMetadata(
        authors=_basic_string(block, "authors") or "",
        description=_description(block),
        display_name=_basic_string(block, "displayName") or "",
        license_name=_basic_string(text[:start], "license") or "",
    )
    if generated.authors != original.authors:
        raise ExportError("generated metadata changed the upstream authors")
    if generated.description != original.description:
        raise ExportError("generated metadata changed the upstream description")
    if generated.license_name.lower() not in {"mit", "mit license"}:
        raise ExportError("generated metadata no longer declares the MIT license")
    if "ModPorter" not in block or TOOL_URL not in block:
        raise ExportError(
            "generated metadata is missing --add-tool-credit output"
        )

    block = _replace_mod_basic_string(block, "version", candidate_version)
    block = _replace_mod_basic_string(
        block, "displayName", f"{display_name} (Unofficial Candidate)"
    )
    block = _replace_mod_basic_string(block, "displayURL", repo_url)
    block = _prefix_description(block, DESCRIPTION_PREFIX)
    text = text[:start] + block + text[end:]

    issue_pattern = re.compile(
        r'(?m)^([ \t]*issueTrackerURL[ \t]*=[ \t]*)"([^"\r\n]*)"'
        r"([ \t]*(?:#.*)?)$"
    )
    escaped_issues = ISSUES_URL.replace("\\", "\\\\").replace('"', '\\"')
    matches = list(issue_pattern.finditer(text))
    if len(matches) > 1:
        raise ExportError("metadata contains multiple issueTrackerURL fields")
    if matches:
        text = issue_pattern.sub(rf'\1"{escaped_issues}"\3', text, count=1)
    else:
        header = re.search(r"(?m)^[ \t]*\[\[\s*mods\s*]]", text)
        if not header:
            raise ExportError("metadata is missing [[mods]]")
        newline = "\r\n" if "\r\n" in text else "\n"
        prefix = text[:header.start()]
        if prefix and not prefix.endswith(("\n", "\r")):
            prefix += newline
        text = (
            prefix
            + f'issueTrackerURL="{ISSUES_URL}"'
            + newline
            + text[header.start():]
        )
    return text


def update_build_gradle(
    text: str,
    *,
    spec: PortSpec,
    candidate_version: str,
    include_support_license: bool,
    component_resource_paths: Sequence[str] = (),
) -> str:
    for marker in spec.generated_build_markers:
        if marker not in text:
            raise ExportError(
                f"{spec.mod_id}: unsupported generated Groovy build shape; "
                f"missing {marker!r}"
            )
    if POLICY_MARKER in text:
        raise ExportError("generated build already contains publication policy edits")

    version_pattern = re.compile(r"(?m)^version[ \t]*=[ \t]*[^\r\n]+$")
    version_matches = list(version_pattern.finditer(text))
    if len(version_matches) != 1:
        raise ExportError("generated Groovy build must contain exactly one project version")
    text = version_pattern.sub(f"version = '{candidate_version}'", text, count=1)

    timestamp_pattern = re.compile(
        r'(?m)^[ \t]*["\']Implementation-Timestamp["\'][ \t]*:[^\r\n]+(?:\r?\n)?'
    )
    if not timestamp_pattern.search(text):
        raise ExportError(
            "unsupported generated Groovy build shape: Implementation-Timestamp is absent"
        )
    text = timestamp_pattern.sub("", text)
    if "Implementation-Timestamp" in text:
        raise ExportError("could not remove all Implementation-Timestamp manifest entries")

    support_lines = ""
    if include_support_license:
        support_lines = (
            f"    from('{SUPPORT_LICENSE_FILE}') {{ into 'META-INF' }}\n"
            f"    from('{SUPPORT_NOTICE_FILE}') {{ into 'META-INF' }}\n"
        )
    component_lines = ""
    if component_resource_paths:
        includes = "\n".join(
            f"        include('{relative}')"
            for relative in component_resource_paths
        )
        component_lines = (
            f"    from('{RUNTIME_COMPONENT_RESOURCES}') {{\n"
            f"{includes}\n"
            "    }\n"
        )
    policy = f"""

{POLICY_MARKER}
tasks.withType(org.gradle.api.tasks.bundling.AbstractArchiveTask).configureEach {{
    preserveFileTimestamps = false
    reproducibleFileOrder = true
}}

tasks.withType(org.gradle.api.tasks.bundling.Jar).configureEach {{
    from('LICENSE') {{ into 'META-INF' }}
    from('{NOTICE_FILE}') {{ into 'META-INF' }}
    from('{PROVENANCE_FILE}') {{ into 'META-INF' }}
{support_lines.rstrip()}
{component_lines.rstrip()}
}}
"""
    return text.rstrip() + policy


def verify_source_shape(src: Path, spec: PortSpec) -> ModMetadata:
    build = src / "build.gradle"
    if not build.is_file() or (src / "build.gradle.kts").exists():
        raise ExportError(f"{spec.mod_id}: only the reviewed Groovy source build is supported")
    build_text = build.read_text(encoding="utf-8")
    for marker in spec.source_build_markers:
        if marker not in build_text:
            raise ExportError(
                f"{spec.mod_id}: unsupported source build shape; missing {marker!r}"
            )
    properties = (src / "gradle.properties")
    if not properties.is_file():
        raise ExportError("source is missing gradle.properties")
    if spec.mod_id == "constructionwand":
        if not re.search(r"(?m)^modid=constructionwand$", properties.read_text(encoding="utf-8")):
            raise ExportError("Construction Wand source mod id shape changed")
    metadata_path = find_source_metadata(src)
    metadata = read_mod_metadata(metadata_path)
    if metadata.display_name != spec.display_name:
        raise ExportError(
            f"source metadata display name changed: {metadata.display_name!r}"
        )
    if metadata.license_name.lower() not in {"mit", "mit license"}:
        raise ExportError("source metadata does not declare the MIT license")
    return metadata


def ensure_output_preflight(src: Path, out: Path) -> bool:
    source = src.resolve()
    output = out.resolve()
    if output == source or source in output.parents:
        raise ExportError("output must not be the source directory or a child of it")
    existed = out.exists()
    if existed:
        if not out.is_dir():
            raise ExportError("output path exists and is not a directory")
        if any(out.iterdir()):
            raise ExportError("output directory must be new or empty")
    if (out / ".git").exists():
        raise ExportError("output must never be an existing Git repository")
    return existed


def create_git_archive_snapshot(
    runner: RunCommand,
    src: Path,
    commit: str,
    destination: Path,
) -> None:
    destination.mkdir(parents=True, exist_ok=False)
    archive_path = destination.parent / "source.tar"
    run_checked(
        runner,
        [
            "git",
            "-C",
            str(src),
            "archive",
            "--format=tar",
            f"--output={archive_path}",
            commit,
        ],
        capture_output=True,
    )
    try:
        with tarfile.open(archive_path, mode="r:") as archive:
            for member in archive.getmembers():
                target = (destination / member.name).resolve()
                if destination.resolve() not in target.parents and target != destination.resolve():
                    raise ExportError("Git archive contains a path outside the snapshot")
            archive.extractall(destination, filter="data")
    finally:
        archive_path.unlink(missing_ok=True)


def tool_command(jar: Path, snapshot: Path, out: Path) -> list[str]:
    return [
        "java",
        "-jar",
        str(jar),
        "port",
        "--src",
        str(snapshot),
        "--out",
        str(out),
        "--add-tool-credit",
    ]


def file_hashes(root: Path) -> dict[str, str]:
    return {
        relative: sha256_file(root.joinpath(*relative.split("/")))
        for relative in relative_file_paths(root)
    }


def generated_support_sources(out: Path) -> list[str]:
    support: list[str] = []
    pattern = re.compile(
        r"^src/[^/]+/(?:java|kotlin)/com/modporter/generated/", re.IGNORECASE
    )
    for relative in relative_file_paths(out):
        normalized = relative.replace("\\", "/")
        if pattern.match(normalized) and "/benchmark/" not in normalized.lower():
            support.append(normalized)
    return sorted(support)


def _strict_json_object(pairs: Sequence[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ExportError(f"component manifest contains duplicate key {key!r}")
        result[key] = value
    return result


def _component_relative_path(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ExportError(f"{label} must be a nonblank relative path")
    if "\\" in value:
        raise ExportError(f"{label} must use forward slashes")
    if "\x00" in value or re.match(r"^[A-Za-z]:", value):
        raise ExportError(f"{label} is not a safe relative path: {value!r}")
    parsed = PurePosixPath(value)
    normalized = parsed.as_posix()
    if (
        parsed.is_absolute()
        or normalized != value
        or any(part in {"", ".", ".."} for part in parsed.parts)
    ):
        raise ExportError(f"{label} is not a safe relative path: {value!r}")
    return normalized


def _reject_component_links(root: Path) -> None:
    for current, directories, files in os.walk(root, topdown=True, followlinks=False):
        current_path = Path(current)
        for name in [*directories, *files]:
            path = current_path / name
            if path.is_symlink():
                relative = path.relative_to(root).as_posix()
                raise ExportError(
                    f"runtime component must not contain symbolic links: {relative}"
                )


def _component_file(root: Path, relative: str, label: str) -> Path:
    current = root
    for part in PurePosixPath(relative).parts:
        current = current / part
        if current.is_symlink():
            raise ExportError(f"{label} must not traverse a symbolic link: {relative}")
    if not current.is_file():
        raise ExportError(f"missing {label}: {relative}")
    return current


def _exact_object(
    value: object,
    expected_keys: set[str],
    label: str,
) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ExportError(f"{label} must be a JSON object")
    keys = set(value)
    if keys != expected_keys:
        missing = sorted(expected_keys - keys)
        unknown = sorted(keys - expected_keys)
        details: list[str] = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if unknown:
            details.append("unknown " + ", ".join(unknown))
        raise ExportError(f"{label} fields are invalid: {'; '.join(details)}")
    return value


def _manifest_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ExportError(f"{label} must be a nonblank string")
    return value


def _manifest_sha256(value: object, label: str) -> str:
    text = _manifest_string(value, label)
    if not re.fullmatch(r"[0-9a-f]{64}", text):
        raise ExportError(f"{label} must be a lowercase SHA-256")
    return text


def _validate_component_source_content(
    name: str,
    license_name: str,
    content: bytes,
) -> None:
    if content.startswith(b"\xef\xbb\xbf"):
        raise ExportError(f"runtime source must be UTF-8 without BOM: {name}")
    try:
        decoded = content.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ExportError(f"runtime source is not UTF-8: {name}") from exc
    if "\r" in decoded or not decoded.endswith("\n"):
        raise ExportError(f"runtime source must use LF and end with a newline: {name}")
    package_name = "com.modporter.compat.plant.v1"
    if name in RUNTIME_COMPONENT_MIXIN_SOURCE_NAMES:
        package_name += ".mixin"
    if f"package {package_name};" not in decoded:
        raise ExportError(f"runtime source package does not match its canonical path: {name}")
    if license_name in {"LGPL-2.1-only", "MIT"}:
        if f"SPDX-License-Identifier: {license_name}" not in decoded:
            raise ExportError(f"runtime source is missing its {license_name} SPDX marker: {name}")
    if name in RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCE_NAMES:
        required = (
            RUNTIME_COMPONENT_DYNAMIC_TREES_COPYRIGHT,
            f"Derived from Dynamic Trees {RUNTIME_COMPONENT_DYNAMIC_TREES_TAG}",
            "MushroomRotGenFeature#postRot",
        )
        if any(marker not in decoded for marker in required):
            raise ExportError(
                "Dynamic Trees-derived runtime source lost copyright or provenance markers"
            )
    if name == "LegacyPlantMixinPlugin":
        required = (
            "com.modporter.compat.plant.v1.mixin.LegacyPlantPluginSentinel",
            'CONSUMER = "LegacyPlantMushroomRotMixin"',
        )
        if any(marker not in decoded for marker in required):
            raise ExportError("runtime mixin plugin lost its consumer or sentinel binding")


def _validate_component_material_content(path: str, content: bytes) -> None:
    if path == "META-INF/modporter/LICENSE":
        if b"Apache License" not in content or b"Version 2.0" not in content:
            raise ExportError("component Apache-2.0 license material is invalid")
    elif path == "META-INF/modporter/third-party/minecraftforge/LICENSE.txt":
        if (
            b"GNU LESSER GENERAL PUBLIC LICENSE" not in content.upper()
            or b"Version 2.1" not in content
        ):
            raise ExportError("component LGPL-2.1-only license material is invalid")
    elif path == "META-INF/modporter/third-party/minecraftforge/README.md":
        decoded = content.decode("utf-8", errors="strict")
        if (
            RUNTIME_COMPONENT_FORGE_RELEASE not in decoded
            or RUNTIME_COMPONENT_FORGE_COMMIT not in decoded
        ):
            raise ExportError("component Forge notice does not bind the pinned release and commit")
    elif path == "META-INF/modporter/third-party/dynamictrees/LICENSE":
        if hashlib.sha256(content).hexdigest() != RUNTIME_COMPONENT_DYNAMIC_TREES_LICENSE_SHA256:
            raise ExportError("component Dynamic Trees MIT license bytes are not canonical")
        if b"MIT License" not in content or b"Permission is hereby granted" not in content:
            raise ExportError("component Dynamic Trees MIT license material is invalid")
    elif path == "META-INF/modporter/third-party/dynamictrees/README.md":
        decoded = content.decode("utf-8", errors="strict")
        required = (
            RUNTIME_COMPONENT_DYNAMIC_TREES_COORDINATE,
            RUNTIME_COMPONENT_DYNAMIC_TREES_TAG,
            RUNTIME_COMPONENT_DYNAMIC_TREES_COMMIT,
            RUNTIME_COMPONENT_DYNAMIC_TREES_BINARY_SHA256,
            RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCES_SHA256,
            RUNTIME_COMPONENT_DYNAMIC_TREES_POM_SHA256,
            RUNTIME_COMPONENT_DYNAMIC_TREES_ADAPTED_SOURCE_SHA256,
            RUNTIME_COMPONENT_DYNAMIC_TREES_LICENSE_SHA256,
        )
        if any(marker not in decoded for marker in required):
            raise ExportError("component Dynamic Trees notice has incomplete pinned provenance")
    elif path == "META-INF/modporter/legacy-plant-compat/build.gradle.recipe":
        decoded = content.decode("utf-8", errors="strict")
        if (
            "MixinConfigs" not in decoded
            or RUNTIME_COMPONENT_MIXIN_CONFIG not in decoded
        ):
            raise ExportError("component build recipe does not register the mixin config")
    elif not content.strip():
        raise ExportError(f"component material is empty: {path}")


def _validate_component_runtime_resource(path: str, content: bytes) -> None:
    if content.startswith(b"\xef\xbb\xbf"):
        raise ExportError(f"runtime component resource must be UTF-8 without BOM: {path}")
    try:
        decoded = content.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ExportError(f"runtime component resource is not UTF-8: {path}") from exc
    if "\r" in decoded or not decoded.endswith("\n"):
        raise ExportError(
            f"runtime component resource must use LF and end with a newline: {path}"
        )
    try:
        raw = json.loads(decoded, object_pairs_hook=_strict_json_object)
    except ExportError:
        raise
    except json.JSONDecodeError as exc:
        raise ExportError(f"runtime mixin configuration is invalid JSON: {exc}") from exc
    config = _exact_object(
        raw,
        {
            "required",
            "minVersion",
            "package",
            "compatibilityLevel",
            "plugin",
            "mixins",
            "injectors",
        },
        "runtime mixin configuration",
    )
    if config["required"] is not True:
        raise ExportError("runtime mixin configuration must be required")
    expected_strings = {
        "minVersion": "0.8.7",
        "package": "com.modporter.compat.plant.v1.mixin",
        "compatibilityLevel": "JAVA_21",
        "plugin": "com.modporter.compat.plant.v1.LegacyPlantMixinPlugin",
    }
    for key, expected in expected_strings.items():
        if config[key] != expected:
            raise ExportError(f"runtime mixin configuration has unexpected {key}")
    if config["mixins"] != ["LegacyPlantPluginSentinel"]:
        raise ExportError("runtime mixin configuration must contain only the fail-closed sentinel")
    injectors = _exact_object(
        config["injectors"], {"defaultRequire"}, "runtime mixin injectors"
    )
    if type(injectors["defaultRequire"]) is not int or injectors["defaultRequire"] != 1:
        raise ExportError("runtime mixin configuration defaultRequire must be integer 1")


def verify_runtime_component(out: Path) -> RuntimeComponent | None:
    runtime_root = out.joinpath(*PurePosixPath(RUNTIME_COMPONENT_ROOT).parts)
    request_paths = [
        relative
        for relative in relative_file_paths(out)
        if relative == RUNTIME_COMPONENT_REQUEST
        or relative.endswith(f"/{RUNTIME_COMPONENT_REQUEST}")
    ]
    if not runtime_root.exists():
        if request_paths:
            raise ExportError(
                "legacy plant runtime was requested but the canonical component is missing: "
                + ", ".join(request_paths)
            )
        return None
    if runtime_root.is_symlink() or not runtime_root.is_dir():
        raise ExportError("canonical runtime component root is not a regular directory")
    _reject_component_links(runtime_root)

    manifest_path = _component_file(
        out, RUNTIME_COMPONENT_MANIFEST, "runtime component manifest"
    )
    manifest_bytes = manifest_path.read_bytes()
    if manifest_bytes.startswith(b"\xef\xbb\xbf"):
        raise ExportError("runtime component manifest must be UTF-8 without BOM")
    try:
        manifest_text = manifest_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ExportError("runtime component manifest is not UTF-8") from exc
    if "\r" in manifest_text or not manifest_text.endswith("\n"):
        raise ExportError("runtime component manifest must use LF and end with a newline")
    try:
        raw = json.loads(manifest_text, object_pairs_hook=_strict_json_object)
    except ExportError:
        raise
    except json.JSONDecodeError as exc:
        raise ExportError(f"runtime component manifest is invalid JSON: {exc}") from exc
    manifest = _exact_object(
        raw,
        {
            "schemaVersion",
            "component",
            "packagingVersion",
            "runtimeVersion",
            "templateSourceSha",
            "forgeRelease",
            "forgeCommit",
            "licenseClassification",
            "sources",
            "runtimeResources",
            "materials",
        },
        "runtime component manifest",
    )
    schema_version = manifest["schemaVersion"]
    if type(schema_version) is not int or schema_version != 1:
        raise ExportError("runtime component manifest schemaVersion must be integer 1")
    component = _manifest_string(manifest["component"], "component")
    packaging_version = _manifest_string(
        manifest["packagingVersion"], "packagingVersion"
    )
    template_source_sha = _manifest_sha256(
        manifest["templateSourceSha"], "templateSourceSha"
    )
    runtime_version = _manifest_string(manifest["runtimeVersion"], "runtimeVersion")
    forge_release = _manifest_string(manifest["forgeRelease"], "forgeRelease")
    forge_commit = _manifest_string(manifest["forgeCommit"], "forgeCommit")
    classification = _manifest_string(
        manifest["licenseClassification"], "licenseClassification"
    )
    if component != RUNTIME_COMPONENT_ID:
        raise ExportError(f"unknown runtime component {component!r}")
    if packaging_version != RUNTIME_COMPONENT_PACKAGING_VERSION:
        raise ExportError(f"unsupported runtime packagingVersion {packaging_version!r}")
    expected_runtime_version = f"{packaging_version}-{template_source_sha[:16]}"
    if runtime_version != expected_runtime_version:
        raise ExportError("runtimeVersion does not match packagingVersion/templateSourceSha")
    if forge_release != RUNTIME_COMPONENT_FORGE_RELEASE:
        raise ExportError(f"unexpected Forge release {forge_release!r}")
    if forge_commit != RUNTIME_COMPONENT_FORGE_COMMIT:
        raise ExportError(f"unexpected Forge commit {forge_commit!r}")
    if classification != "per-file":
        raise ExportError(f"unknown component license classification {classification!r}")

    raw_sources = manifest["sources"]
    if not isinstance(raw_sources, list) or not raw_sources:
        raise ExportError("runtime component sources must be a nonempty array")
    sources: list[Mapping[str, str]] = []
    source_paths: set[str] = set()
    embedded_paths: set[str] = set()
    source_names: set[str] = set()
    for index, raw_source in enumerate(raw_sources):
        source = _exact_object(
            raw_source,
            {"name", "generatedPath", "embeddedPath", "sha256", "license", "origin"},
            f"sources[{index}]",
        )
        name = _manifest_string(source["name"], f"sources[{index}].name")
        simple_name: str
        if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", name):
            simple_name = name
        elif (
            re.fullmatch(r"mixin/[A-Za-z_$][A-Za-z0-9_$]*", name)
            and name in RUNTIME_COMPONENT_MIXIN_SOURCE_NAMES
        ):
            simple_name = name.removeprefix("mixin/")
        else:
            raise ExportError(f"sources[{index}].name is not a canonical Java source name")
        generated_path = _component_relative_path(
            source["generatedPath"], f"sources[{index}].generatedPath"
        )
        embedded_path = _component_relative_path(
            source["embeddedPath"], f"sources[{index}].embeddedPath"
        )
        expected_generated = (
            f"src/main/java/com/modporter/compat/plant/v1/{name}.java"
        )
        expected_embedded = (
            "META-INF/modporter/legacy-plant-compat/sources/"
            f"com/modporter/compat/plant/v1/{name}.java"
        )
        if generated_path != expected_generated or embedded_path != expected_embedded:
            raise ExportError(f"sources[{index}] uses a non-canonical source path")
        if not generated_path.endswith(f"/{simple_name}.java"):
            raise ExportError(f"sources[{index}] source name/path mismatch")
        sha256 = _manifest_sha256(source["sha256"], f"sources[{index}].sha256")
        license_name = _manifest_string(source["license"], f"sources[{index}].license")
        origin = _manifest_string(source["origin"], f"sources[{index}].origin")
        if license_name not in RUNTIME_COMPONENT_LICENSES:
            raise ExportError(f"unknown runtime source license {license_name!r}")
        if name in RUNTIME_COMPONENT_FORGE_SOURCE_NAMES:
            expected_license, expected_origin = "LGPL-2.1-only", "MinecraftForge"
        elif name in RUNTIME_COMPONENT_DYNAMIC_TREES_SOURCE_NAMES:
            expected_license, expected_origin = "MIT", "DynamicTrees"
        else:
            expected_license, expected_origin = "Apache-2.0", "ModPorter"
        if license_name != expected_license:
            raise ExportError(
                f"runtime source {name} must be classified as {expected_license}"
            )
        if origin != expected_origin:
            raise ExportError(
                f"sources[{index}] origin {origin!r} does not match {license_name}"
            )
        if name in source_names or generated_path in source_paths or embedded_path in embedded_paths:
            raise ExportError("runtime component manifest contains duplicate source identity")
        source_names.add(name)
        source_paths.add(generated_path)
        embedded_paths.add(embedded_path)
        generated_file = _component_file(
            runtime_root, generated_path, f"runtime source {generated_path}"
        )
        embedded_file = _component_file(
            runtime_root / "src/main/resources",
            embedded_path,
            f"embedded runtime source {embedded_path}",
        )
        generated_content = generated_file.read_bytes()
        if hashlib.sha256(generated_content).hexdigest() != sha256:
            raise ExportError(f"runtime source hash mismatch: {generated_path}")
        if embedded_file.read_bytes() != generated_content:
            raise ExportError(f"embedded runtime source differs from generated source: {name}")
        _validate_component_source_content(name, license_name, generated_content)
        sources.append(
            {
                "name": name,
                "generatedPath": generated_path,
                "embeddedPath": embedded_path,
                "sha256": sha256,
                "license": license_name,
                "origin": origin,
            }
        )

    missing_required_sources = sorted(
        RUNTIME_COMPONENT_REQUIRED_SOURCE_NAMES - source_names
    )
    if missing_required_sources:
        raise ExportError(
            "runtime component is missing required canonical sources: "
            + ", ".join(missing_required_sources)
        )

    source_root = runtime_root / "src/main/java"
    if not source_root.is_dir() or source_root.is_symlink():
        raise ExportError("runtime component source root is missing or invalid")
    actual_source_paths = {
        f"src/main/java/{relative}" for relative in relative_file_paths(source_root)
    }
    if actual_source_paths != source_paths:
        raise ExportError(
            "runtime component source manifest is incomplete: "
            f"expected {sorted(actual_source_paths)}, declared {sorted(source_paths)}"
        )

    resources_root = runtime_root / "src/main/resources"
    raw_runtime_resources = manifest["runtimeResources"]
    if not isinstance(raw_runtime_resources, list):
        raise ExportError("runtime component runtimeResources must be an array")
    runtime_resources: list[Mapping[str, str]] = []
    runtime_resource_paths: set[str] = set()
    for index, raw_resource in enumerate(raw_runtime_resources):
        resource = _exact_object(
            raw_resource,
            {"path", "sha256", "role"},
            f"runtimeResources[{index}]",
        )
        resource_path = _component_relative_path(
            resource["path"], f"runtimeResources[{index}].path"
        )
        if resource_path in runtime_resource_paths:
            raise ExportError("runtime component manifest contains duplicate runtime resource path")
        runtime_resource_paths.add(resource_path)
        expected_role = RUNTIME_COMPONENT_RUNTIME_RESOURCE_ROLES.get(resource_path)
        if expected_role is None:
            raise ExportError(f"unknown runtime component resource {resource_path!r}")
        role = _manifest_string(resource["role"], f"runtimeResources[{index}].role")
        if role != expected_role:
            raise ExportError(f"runtime component resource role mismatch: {resource_path}")
        sha256 = _manifest_sha256(
            resource["sha256"], f"runtimeResources[{index}].sha256"
        )
        resource_file = _component_file(
            resources_root, resource_path, f"runtime component resource {resource_path}"
        )
        content = resource_file.read_bytes()
        if hashlib.sha256(content).hexdigest() != sha256:
            raise ExportError(f"runtime component resource hash mismatch: {resource_path}")
        _validate_component_runtime_resource(resource_path, content)
        runtime_resources.append(
            {"path": resource_path, "sha256": sha256, "role": role}
        )
    if runtime_resource_paths != set(RUNTIME_COMPONENT_RUNTIME_RESOURCE_ROLES):
        raise ExportError(
            "runtime component resource set is incomplete: "
            f"expected {sorted(RUNTIME_COMPONENT_RUNTIME_RESOURCE_ROLES)}, "
            f"declared {sorted(runtime_resource_paths)}"
        )

    raw_materials = manifest["materials"]
    if not isinstance(raw_materials, list):
        raise ExportError("runtime component materials must be an array")
    materials: list[Mapping[str, str]] = []
    material_paths: set[str] = set()
    for index, raw_material in enumerate(raw_materials):
        material = _exact_object(
            raw_material,
            {"path", "sha256", "role"},
            f"materials[{index}]",
        )
        material_path = _component_relative_path(
            material["path"], f"materials[{index}].path"
        )
        if material_path in material_paths:
            raise ExportError("runtime component manifest contains duplicate material path")
        material_paths.add(material_path)
        expected_role = RUNTIME_COMPONENT_MATERIAL_ROLES.get(material_path)
        if expected_role is None:
            raise ExportError(f"unknown runtime component material {material_path!r}")
        role = _manifest_string(material["role"], f"materials[{index}].role")
        if role != expected_role:
            raise ExportError(f"runtime component material role mismatch: {material_path}")
        sha256 = _manifest_sha256(material["sha256"], f"materials[{index}].sha256")
        material_file = _component_file(
            resources_root, material_path, f"runtime component material {material_path}"
        )
        content = material_file.read_bytes()
        if hashlib.sha256(content).hexdigest() != sha256:
            raise ExportError(f"runtime component material hash mismatch: {material_path}")
        try:
            _validate_component_material_content(material_path, content)
        except UnicodeDecodeError as exc:
            raise ExportError(f"runtime component material is not UTF-8: {material_path}") from exc
        materials.append({"path": material_path, "sha256": sha256, "role": role})
    if material_paths != set(RUNTIME_COMPONENT_MATERIAL_ROLES):
        raise ExportError(
            "runtime component material set is incomplete: "
            f"expected {sorted(RUNTIME_COMPONENT_MATERIAL_ROLES)}, declared {sorted(material_paths)}"
        )

    actual_resources = set(relative_file_paths(resources_root))
    expected_resources = {
        RUNTIME_COMPONENT_MANIFEST_RESOURCE,
        *embedded_paths,
        *runtime_resource_paths,
        *material_paths,
    }
    if actual_resources != expected_resources:
        raise ExportError(
            "runtime component resource manifest is incomplete: "
            f"expected {sorted(actual_resources)}, declared {sorted(expected_resources)}"
        )
    build_file = _component_file(
        out, RUNTIME_COMPONENT_BUILD, "runtime component build.gradle"
    )
    try:
        build_text = build_file.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        raise ExportError("runtime component build.gradle is not UTF-8") from exc
    if "MixinConfigs" not in build_text or RUNTIME_COMPONENT_MIXIN_CONFIG not in build_text:
        raise ExportError("runtime component build.gradle does not register the mixin config")

    preserved = {
        f"{RUNTIME_COMPONENT_ROOT}/{relative}"
        for relative in relative_file_paths(runtime_root)
    }
    return RuntimeComponent(
        component=component,
        packaging_version=packaging_version,
        runtime_version=runtime_version,
        template_source_sha=template_source_sha,
        forge_release=forge_release,
        forge_commit=forge_commit,
        sources=tuple(sources),
        runtime_resources=tuple(runtime_resources),
        materials=tuple(materials),
        preserved_files=tuple(sorted(preserved)),
    )

def notice_text(
    config: ExportConfig,
    tool: ToolBundle,
    support_sources: Sequence[str],
    runtime_component: RuntimeComponent | None = None,
) -> str:
    support_statement = (
        f"Generated compatibility/support sources are listed in {PROVENANCE_FILE}. "
        f"The copied ModPorter Apache-2.0 terms are in {SUPPORT_LICENSE_FILE}."
        if support_sources
        else "This generated tree contains no com.modporter.generated compatibility/support sources."
    )
    if runtime_component is None:
        component_statement = "This generated tree contains no canonical shared runtime component."
    else:
        scope_lines: list[str] = []
        for license_name in sorted(RUNTIME_COMPONENT_LICENSES):
            scope_lines.append(f"  {license_name}:")
            scope_lines.extend(
                "    " + f"{RUNTIME_COMPONENT_ROOT}/{source['generatedPath']}"
                for source in runtime_component.sources
                if source["license"] == license_name
            )
        component_statement = "\n".join(
            [
                "Canonical shared runtime component:",
                f"  {runtime_component.component}",
                f"  Runtime version: {runtime_component.runtime_version}",
                f"  Component manifest: {RUNTIME_COMPONENT_MANIFEST}",
                "  Per-file source license scopes:",
                *scope_lines,
                "  Component legal materials are preserved under "
                f"{RUNTIME_COMPONENT_RESOURCES}/META-INF/modporter/.",
            ]
        )
    return f"""ModPorter Candidate Notice

{config.display_name} (Unofficial Candidate)

This is a developer research baseline generated from:
  {config.upstream_url}@{config.upstream_commit}

Generator:
  ModPorter {tool.version}
  JAR: {tool.jar_name}
  JAR SHA-256: {tool.jar_sha256}

Candidate repository:
  {config.repo_url}

Migration issues:
  {ISSUES_URL}

Validation scope:
  {VALIDATION_SCOPE}

The original authors, copyright notices, MIT license, source, and assets keep
their original ownership and licensing. ModPorter does not relicense upstream
code or assets. This candidate is unofficial and is offered for community
review; use and redistribution remain subject to the preserved upstream terms.

{support_statement}

{component_statement}
"""


def provenance_data(
    config: ExportConfig,
    tool: ToolBundle,
    source_state: GitState,
    support_sources: Sequence[str],
    runtime_component: RuntimeComponent | None = None,
) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "candidate": {
            "modId": config.mod_id,
            "displayName": f"{config.display_name} (Unofficial Candidate)",
            "version": config.candidate_version,
            "repository": config.repo_url,
            "issues": ISSUES_URL,
            "kind": "developer research baseline",
            "validationScope": VALIDATION_SCOPE,
        },
        "upstream": {
            "repository": config.upstream_url,
            "commit": config.upstream_commit,
            "tree": source_state.tree,
            "license": "MIT",
        },
        "generator": {
            "name": "ModPorter",
            "version": tool.version,
            "repository": TOOL_URL,
            "license": "Apache-2.0",
            "jarFile": tool.jar_name,
            "jarSha256": tool.jar_sha256,
            "release": dict(tool.release) if tool.release is not None else None,
            "invocation": [
                "java",
                "-jar",
                tool.jar_name,
                "port",
                "--src",
                "<clean-git-archive>",
                "--out",
                "<new-empty-output>",
                "--add-tool-credit",
            ],
        },
        "policy": {
            "modLogicHandPatches": False,
            "metadataAndPackagingEditsOnly": True,
            "upstreamRelicensed": False,
            "reproducibleArchiveFlags": True,
            "implementationTimestampRemoved": True,
        },
        "generatedSupportSources": list(support_sources),
        "supportLicense": SUPPORT_LICENSE_FILE if support_sources else None,
        "runtimeComponents": (
            [runtime_component.publication_record()]
            if runtime_component is not None
            else []
        ),
    }


def write_portable_json(path: Path, data: Mapping[str, object]) -> None:
    rendered = json.dumps(data, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    # The schema deliberately contains only repository URLs, hashes, names, and
    # relative paths.  Reject common private absolute-path forms if that changes.
    if re.search(r'(?im)"[^"\r\n]*":\s*"[A-Za-z]:[\\/]', rendered):
        raise ExportError("provenance unexpectedly contains a Windows absolute path")
    if re.search(r'(?im)"[^"\r\n]*":\s*"/(?!/)', rendered):
        raise ExportError("provenance unexpectedly contains an absolute filesystem path")
    path.write_text(rendered, encoding="utf-8", newline="\n")


def verify_policy_edit_scope(
    before: Mapping[str, str],
    after: Mapping[str, str],
    *,
    metadata_relative: str,
    support_sources: Sequence[str],
    component_files: Sequence[str] = (),
) -> None:
    allowed_modified = {"build.gradle", metadata_relative}
    allowed_added = {NOTICE_FILE, PROVENANCE_FILE}
    if support_sources:
        allowed_added.update({SUPPORT_LICENSE_FILE, SUPPORT_NOTICE_FILE})

    missing_component_files = sorted(
        path for path in component_files if path not in before or path not in after
    )
    if missing_component_files:
        raise ExportError(
            "publication policy lost required runtime component files: "
            + ", ".join(missing_component_files[:8])
        )
    changed_component_files = sorted(
        path for path in component_files if before[path] != after[path]
    )
    if changed_component_files:
        raise ExportError(
            "publication policy changed runtime component files: "
            + ", ".join(changed_component_files[:8])
        )

    removed = sorted(set(before) - set(after))
    if removed:
        raise ExportError(
            "publication policy removed generated files: " + ", ".join(removed[:8])
        )
    modified = sorted(
        path for path in set(before) & set(after) if before[path] != after[path]
    )
    unexpected_modified = [path for path in modified if path not in allowed_modified]
    if unexpected_modified:
        raise ExportError(
            "publication policy changed non-metadata files: "
            + ", ".join(unexpected_modified[:8])
        )
    added = sorted(set(after) - set(before))
    unexpected_added = [path for path in added if path not in allowed_added]
    if unexpected_added:
        raise ExportError(
            "publication policy added undeclared files: "
            + ", ".join(unexpected_added[:8])
        )
    missing_added = sorted(allowed_added - set(added))
    if missing_added:
        raise ExportError(
            "publication policy did not add required files: " + ", ".join(missing_added)
        )


def apply_publication_policy(
    config: ExportConfig,
    spec: PortSpec,
    tool: ToolBundle,
    source_state: GitState,
    source_license: bytes,
    source_metadata: ModMetadata,
) -> None:
    out = config.out
    if not out.is_dir():
        raise ExportError("ModPorter did not create the output directory")
    if (out / ".git").exists():
        raise ExportError("generated output unexpectedly contains Git repository metadata")
    reject_benchmark_contamination(out, "generated output")
    output_license = out / "LICENSE"
    if not output_license.is_file():
        raise ExportError("generated output is missing the upstream LICENSE")
    if output_license.read_bytes() != source_license:
        raise ExportError("generated output did not preserve the upstream LICENSE bytes")

    build_path = out / "build.gradle"
    if not build_path.is_file() or (out / "build.gradle.kts").exists():
        raise ExportError("only the reviewed generated Groovy build shape is supported")
    properties_path = out / "gradle.properties"
    if not properties_path.is_file():
        raise ExportError("generated output is missing gradle.properties")
    properties = properties_path.read_text(encoding="utf-8")
    if not re.search(
        rf"(?m)^mod_id={re.escape(config.mod_id)}[ \t]*$", properties
    ):
        raise ExportError("generated gradle.properties does not declare the expected mod_id")

    metadata_path = find_generated_metadata(out)
    metadata_relative = metadata_path.relative_to(out).as_posix()
    policy_names = {
        NOTICE_FILE,
        PROVENANCE_FILE,
        SUPPORT_LICENSE_FILE,
        SUPPORT_NOTICE_FILE,
    }
    collisions = sorted(name for name in policy_names if (out / name).exists())
    if collisions:
        raise ExportError(
            "generated output already contains reserved publication files: "
            + ", ".join(collisions)
        )

    before = file_hashes(out)
    support_sources = generated_support_sources(out)
    runtime_component = verify_runtime_component(out)

    metadata_text = metadata_path.read_text(encoding="utf-8")
    metadata_path.write_text(
        update_metadata(
            metadata_text,
            candidate_version=config.candidate_version,
            display_name=config.display_name,
            repo_url=config.repo_url,
            original=source_metadata,
        ),
        encoding="utf-8",
        newline="\n",
    )
    build_path.write_text(
        update_build_gradle(
            build_path.read_text(encoding="utf-8"),
            spec=spec,
            candidate_version=config.candidate_version,
            include_support_license=bool(support_sources),
            component_resource_paths=(
                runtime_component.outer_resource_paths
                if runtime_component is not None
                else ()
            ),
        ),
        encoding="utf-8",
        newline="\n",
    )
    (out / NOTICE_FILE).write_text(
        notice_text(config, tool, support_sources, runtime_component),
        encoding="utf-8",
        newline="\n",
    )
    if support_sources:
        (out / SUPPORT_LICENSE_FILE).write_bytes(tool.apache_license)
        support_notice = tool.notice or (
            b"ModPorter generated compatibility/support sources.\n"
            b"See MODPORTER-SUPPORT-LICENSE for Apache-2.0 terms.\n"
        )
        (out / SUPPORT_NOTICE_FILE).write_bytes(support_notice)
    write_portable_json(
        out / PROVENANCE_FILE,
        provenance_data(
            config, tool, source_state, support_sources, runtime_component
        ),
    )

    after = file_hashes(out)
    verify_policy_edit_scope(
        before,
        after,
        metadata_relative=metadata_relative,
        support_sources=support_sources,
        component_files=(
            runtime_component.preserved_files
            if runtime_component is not None
            else ()
        ),
    )
    if (out / "LICENSE").read_bytes() != source_license:
        raise ExportError("publication policy changed the upstream LICENSE")
    reject_benchmark_contamination(out, "publication output")


def export_research_port(
    config: ExportConfig,
    *,
    runner: RunCommand = default_runner,
    release_fetch: Callable[[str, int], bytes] = fetch_release_resource,
) -> None:
    spec = validate_config(config)
    tool = verify_released_tool(read_tool_bundle(config.jar), fetch=release_fetch)
    output_existed = ensure_output_preflight(config.src, config.out)
    source_state = verify_source_git(runner, config, spec)
    reject_benchmark_contamination(config.src, "input source")
    source_license = verify_mit_license(config.src)
    reject_tracked_arr(runner, config.src, config.upstream_commit)
    source_metadata = verify_source_shape(config.src, spec)

    try:
        with tempfile.TemporaryDirectory(prefix="modporter-publication-") as temp:
            snapshot = Path(temp) / "source"
            create_git_archive_snapshot(
                runner,
                config.src,
                config.upstream_commit,
                snapshot,
            )
            reject_benchmark_contamination(snapshot, "Git archive snapshot")
            if sha256_file(config.jar) != tool.jar_sha256:
                raise ExportError("verified ModPorter JAR changed before execution")
            result = runner(
                tool_command(config.jar.resolve(), snapshot, config.out.resolve()),
                cwd=None,
                capture_output=False,
            )
            if result.returncode != 0:
                raise ExportError(
                    f"ModPorter exited with code {result.returncode}; partial output was removed"
                )
            if sha256_file(config.jar) != tool.jar_sha256:
                raise ExportError("verified ModPorter JAR changed during execution")
        ensure_source_unchanged(runner, config.src, source_state)
        apply_publication_policy(
            config,
            spec,
            tool,
            source_state,
            source_license,
            source_metadata,
        )
        ensure_source_unchanged(runner, config.src, source_state)
    except Exception:
        if config.out.exists():
            if config.out.is_dir():
                shutil.rmtree(config.out)
            else:
                config.out.unlink()
        if output_existed:
            config.out.mkdir(parents=True, exist_ok=True)
        raise


def parse_args(argv: Sequence[str] | None = None) -> ExportConfig:
    parser = argparse.ArgumentParser(
        description="Generate a bounded, reviewable ModPorter research candidate"
    )
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--src", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--repo-url", required=True)
    parser.add_argument("--mod-id", required=True)
    parser.add_argument("--display-name", required=True)
    parser.add_argument("--candidate-version", required=True)
    parser.add_argument("--upstream-url", required=True)
    parser.add_argument("--upstream-commit", required=True)
    args = parser.parse_args(argv)
    return ExportConfig(
        jar=args.jar,
        src=args.src,
        out=args.out,
        repo_url=args.repo_url,
        mod_id=args.mod_id,
        display_name=args.display_name,
        candidate_version=args.candidate_version,
        upstream_url=args.upstream_url,
        upstream_commit=args.upstream_commit,
    )


def main(argv: Sequence[str] | None = None) -> int:
    try:
        config = parse_args(argv)
        export_research_port(config)
    except ExportError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(f"Exported reviewable candidate to {config.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
