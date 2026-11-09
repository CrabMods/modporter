#!/usr/bin/env python3
"""Export a bounded ModPorter-generated research candidate.

The wrapper accepts only the two MIT-licensed, pinned upstream projects whose
current Groovy build shapes were reviewed for the 0.4.0 release.  It runs the
released JAR against a clean ``git archive`` snapshot, then applies a narrow
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
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence
from urllib.parse import urlsplit


ISSUES_URL = "https://github.com/CrabMods/modporter/issues"
TOOL_URL = "https://github.com/CrabMods/modporter"
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
            if not re.fullmatch(r"0\.4\.\d+", version):
                raise ExportError(
                    f"unsupported ModPorter version {version!r}; expected a released 0.4.x JAR"
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


def notice_text(
    config: ExportConfig,
    tool: ToolBundle,
    support_sources: Sequence[str],
) -> str:
    support_statement = (
        f"Generated compatibility/support sources are listed in {PROVENANCE_FILE}. "
        f"The copied ModPorter Apache-2.0 terms are in {SUPPORT_LICENSE_FILE}."
        if support_sources
        else "This generated tree contains no com.modporter.generated compatibility/support sources."
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
"""


def provenance_data(
    config: ExportConfig,
    tool: ToolBundle,
    source_state: GitState,
    support_sources: Sequence[str],
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
            "jarFile": tool.jar_name,
            "jarSha256": tool.jar_sha256,
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
) -> None:
    allowed_modified = {"build.gradle", metadata_relative}
    allowed_added = {NOTICE_FILE, PROVENANCE_FILE}
    if support_sources:
        allowed_added.update({SUPPORT_LICENSE_FILE, SUPPORT_NOTICE_FILE})

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
        ),
        encoding="utf-8",
        newline="\n",
    )
    (out / NOTICE_FILE).write_text(
        notice_text(config, tool, support_sources),
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
        provenance_data(config, tool, source_state, support_sources),
    )

    after = file_hashes(out)
    verify_policy_edit_scope(
        before,
        after,
        metadata_relative=metadata_relative,
        support_sources=support_sources,
    )
    if (out / "LICENSE").read_bytes() != source_license:
        raise ExportError("publication policy changed the upstream LICENSE")
    reject_benchmark_contamination(out, "publication output")


def export_research_port(
    config: ExportConfig,
    *,
    runner: RunCommand = default_runner,
) -> None:
    spec = validate_config(config)
    tool = read_tool_bundle(config.jar)
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
            result = runner(
                tool_command(config.jar.resolve(), snapshot, config.out.resolve()),
                cwd=None,
                capture_output=False,
            )
            if result.returncode != 0:
                raise ExportError(
                    f"ModPorter exited with code {result.returncode}; partial output was removed"
                )
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
