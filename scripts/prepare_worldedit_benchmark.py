#!/usr/bin/env python3
"""Prepare the exact Forge-only WorldEdit source tree used by the benchmark.

The accepted benchmark input was a mechanical reduction of an official
WorldEdit commit. It retained the core and Forge projects, removed unrelated
platform projects, and made three build-configuration edits. This script
reproduces that tree from the official upstream commit and verifies the full
Git tree object before it can be used.

This recipe does not claim that ModPorter accepts WorldEdit's full upstream
monorepo. The strict benchmark must use the printed
MODPORTER_BENCHMARK_SOURCE_WORLDEDIT override.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import stat
import subprocess
import sys
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OWNED_ROOT = ROOT / ".modporter" / "benchmark-inputs"
DEFAULT_TARGET = OWNED_ROOT / "worldedit-forge-only"

UPSTREAM_URL = "https://github.com/EngineHub/WorldEdit.git"
UPSTREAM_COMMIT = "b04ab0e267285e51bcc064a25cf429f10f9b4aba"
EXPECTED_TREE = "781261ac5a63f3f15d79d83bb487bb3e2972d46d"
SOURCE_OVERRIDE = "MODPORTER_BENCHMARK_SOURCE_WORLDEDIT"
EXPECTED_DELETION_COUNT = 189

REMOVED_PATHS = (
    "buildSrc/src/main/kotlin/AdapterConfig.kt",
    "worldedit-bukkit",
    "worldedit-cli",
    "worldedit-fabric",
    "worldedit-libs/bukkit",
    "worldedit-libs/cli",
    "worldedit-libs/fabric",
    "worldedit-libs/sponge",
    "worldedit-mod",
    "worldedit-sponge",
)

EXPECTED_INPUT_BLOBS = {
    "build.gradle.kts": "fc530aee4f8bc9e6ad71e42d413c503b1e3fa03b",
    "buildSrc/build.gradle.kts": "0e91f8c6a0543a5ffdc2cdcb87f149c583fcf7e5",
    "settings.gradle.kts": "7a4b5f2238fb4f6c7b894ca1c5228b2f2196160c",
}

EXPECTED_OUTPUT_BLOBS = {
    "build.gradle.kts": "c459577b0d11e8665c3ab4cea383f38cd3830b52",
    "buildSrc/build.gradle.kts": "6ea1910463dcabefdc627f7a9cdc448f8a762d46",
    "settings.gradle.kts": "930fa8b26a73c7584cabb9a65c936b240f2528d5",
}

RETAINED_OBJECTS = {
    "LICENSE.txt": "0e2a5685345eaf9c54de97350e761d389c1872c3",
    "worldedit-core": "8a41f755596dd06a0445e2270eb517e517252df0",
    "worldedit-forge": "db5eef88f8dde5211743f6037a2641d093f247fc",
    "worldedit-libs/README.md": "6387fde12be60caba3340fc4bbb6685099eacc3a",
    "worldedit-libs/build.gradle.kts": "152530cb0879c5fafaa554d4fe4345ffe3c50dea",
    "worldedit-libs/core": "60221cdcf830e73cd6e1d8298e41b8de42d9e771",
    "worldedit-libs/forge": "69e9b3f34ac07201a1a564e415d3dd3ac4a41660",
}

BYTE_RANGE_EDITS: dict[str, tuple[tuple[int, int, bytes], ...]] = {
    "build.gradle.kts": (
        (35, 348, b""),
    ),
    "buildSrc/build.gradle.kts": (
        (1019, 1151, b""),
        (1469, 1626, b""),
        (1693, 1815, b""),
        (1886, 1990, b""),
    ),
    "settings.gradle.kts": (
        (
            59,
            359,
            b"""listOf("core", "forge").forEach {
    include("worldedit-libs:$it")
    include("worldedit-$it")
}
""",
        ),
    ),
}


class PreparationError(RuntimeError):
    """A fail-closed source preparation error."""


def run_git(
    repo: Path,
    *args: str,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if check and result.returncode != 0:
        command = "git " + " ".join(shlex.quote(arg) for arg in args)
        raise PreparationError(
            f"{command} failed in {repo} with exit code {result.returncode}:\n"
            f"{result.stdout.rstrip()}"
        )
    return result


def git_text(repo: Path, *args: str) -> str:
    return run_git(repo, *args).stdout.strip()


def normalized_remote(raw: str) -> str:
    return raw.strip().rstrip("/").removesuffix(".git").lower()


def object_at(repo: Path, treeish: str, path: str) -> str:
    return git_text(repo, "rev-parse", f"{treeish}:{path}")


def apply_byte_range_edits(
    content: bytes,
    edits: tuple[tuple[int, int, bytes], ...],
    label: str,
) -> bytes:
    ordered = sorted(edits, key=lambda edit: edit[0], reverse=True)
    next_start = len(content)
    for start, end, _ in ordered:
        if not 0 <= start <= end <= len(content):
            raise PreparationError(
                f"{label}: byte edit [{start}, {end}) is outside "
                f"the {len(content)}-byte input"
            )
        if end > next_start:
            raise PreparationError(
                f"{label}: overlapping byte edit [{start}, {end})"
            )
        next_start = start

    updated = bytearray(content)
    for start, end, replacement in ordered:
        updated[start:end] = replacement
    return bytes(updated)


def rewrite_build_files(repo: Path) -> None:
    for relative, edits in BYTE_RANGE_EDITS.items():
        expected_blob = EXPECTED_INPUT_BLOBS[relative]
        actual_blob = object_at(repo, "HEAD", relative)
        if actual_blob != expected_blob:
            raise PreparationError(
                f"{relative}: upstream input blob changed: "
                f"expected {expected_blob}, got {actual_blob}"
            )
        path = repo / relative
        path.write_bytes(
            apply_byte_range_edits(path.read_bytes(), edits, relative)
        )


def remove_platform_paths(repo: Path) -> None:
    for relative in REMOVED_PATHS:
        path = repo.joinpath(*relative.split("/"))
        if not path.exists() and not path.is_symlink():
            raise PreparationError(f"Expected upstream path is missing: {relative}")
        if path.is_symlink() or path.is_file():
            path.unlink()
        elif path.is_dir():
            shutil.rmtree(path)
        else:
            raise PreparationError(f"Refusing to remove unsupported path type: {relative}")


def verify_change_scope(repo: Path) -> dict[str, object]:
    name_status = git_text(
        repo,
        "diff",
        "--cached",
        "--name-status",
        "--no-renames",
        UPSTREAM_COMMIT,
    ).splitlines()
    modified: list[str] = []
    deleted: list[str] = []
    other: list[str] = []
    for line in name_status:
        status, _, path = line.partition("\t")
        if status == "M":
            modified.append(path)
        elif status == "D":
            deleted.append(path)
        else:
            other.append(line)

    expected_modified = sorted(EXPECTED_OUTPUT_BLOBS)
    if sorted(modified) != expected_modified:
        raise PreparationError(
            f"Unexpected modified files: expected {expected_modified}, got {sorted(modified)}"
        )
    if len(deleted) != EXPECTED_DELETION_COUNT:
        raise PreparationError(
            f"Unexpected deletion count: expected {EXPECTED_DELETION_COUNT}, "
            f"got {len(deleted)}"
        )
    if other:
        raise PreparationError(f"Unexpected staged change types: {other}")

    allowed_prefixes = tuple(path + "/" for path in REMOVED_PATHS if "." not in Path(path).name)
    allowed_file = REMOVED_PATHS[0]
    unexpected_deletions = [
        path
        for path in deleted
        if path != allowed_file and not path.startswith(allowed_prefixes)
    ]
    if unexpected_deletions:
        raise PreparationError(
            "Deletion escaped the verified platform-module scope: "
            + ", ".join(unexpected_deletions)
        )
    return {
        "modifiedFiles": expected_modified,
        "deletedFileCount": len(deleted),
        "removedPaths": list(REMOVED_PATHS),
    }


def verify_prepared(repo: Path) -> dict[str, object]:
    if not repo.is_dir() or not (repo / ".git").is_dir():
        raise PreparationError(f"Not an owned Git checkout: {repo}")

    origin = git_text(repo, "remote", "get-url", "origin")
    if normalized_remote(origin) != normalized_remote(UPSTREAM_URL):
        raise PreparationError(
            f"Existing checkout has unexpected origin {origin!r}; "
            f"expected {UPSTREAM_URL!r}. No files were changed."
        )

    head = git_text(repo, "rev-parse", "HEAD")
    if head != UPSTREAM_COMMIT:
        raise PreparationError(
            f"Existing checkout HEAD is {head}, expected official parent "
            f"{UPSTREAM_COMMIT}. No reset was attempted."
        )

    unstaged = run_git(repo, "diff", "--quiet", "--exit-code", check=False)
    if unstaged.returncode != 0:
        raise PreparationError(
            "Existing checkout has tracked worktree changes outside its staged "
            "prepared tree. No reset was attempted."
        )
    untracked = git_text(repo, "ls-files", "--others", "--exclude-standard").splitlines()
    ignored = git_text(
        repo,
        "ls-files",
        "--others",
        "--ignored",
        "--exclude-standard",
    ).splitlines()
    if untracked or ignored:
        extras = sorted(set(untracked + ignored))
        raise PreparationError(
            "Existing checkout contains untracked or ignored files; refusing to "
            "reuse or clean it: " + ", ".join(extras[:20])
        )

    tree = git_text(repo, "write-tree")
    if tree != EXPECTED_TREE:
        raise PreparationError(
            f"Existing checkout index tree is {tree}, expected {EXPECTED_TREE}. "
            "No reset was attempted."
        )

    scope = verify_change_scope(repo)
    for relative, expected in EXPECTED_OUTPUT_BLOBS.items():
        actual = object_at(repo, tree, relative)
        if actual != expected:
            raise PreparationError(
                f"{relative}: prepared blob is {actual}, expected {expected}"
            )
    for relative, expected in RETAINED_OBJECTS.items():
        upstream = object_at(repo, UPSTREAM_COMMIT, relative)
        prepared = object_at(repo, tree, relative)
        if upstream != expected or prepared != expected:
            raise PreparationError(
                f"{relative}: retained object mismatch; upstream={upstream}, "
                f"prepared={prepared}, expected={expected}"
            )
    for relative in REMOVED_PATHS:
        exists = run_git(
            repo,
            "cat-file",
            "-e",
            f"{tree}:{relative}",
            check=False,
        )
        if exists.returncode == 0:
            raise PreparationError(f"Removed path remains in prepared tree: {relative}")

    license_text = (repo / "LICENSE.txt").read_text(encoding="utf-8")
    if (
        "GNU GENERAL PUBLIC LICENSE" not in license_text
        or "Version 3, 29 June 2007" not in license_text
    ):
        raise PreparationError("Upstream LICENSE.txt is not the expected GPLv3 text")

    return {
        **scope,
        "retainedObjects": RETAINED_OBJECTS,
        "license": {
            "path": "LICENSE.txt",
            "blob": RETAINED_OBJECTS["LICENSE.txt"],
            "description": "GNU General Public License version 3",
        },
    }


def initialize_checkout(repo: Path) -> None:
    repo.mkdir()
    run_git(repo, "init", "-q")
    run_git(repo, "config", "core.autocrlf", "false")
    run_git(repo, "config", "core.filemode", "false")
    run_git(repo, "remote", "add", "origin", UPSTREAM_URL)
    run_git(repo, "fetch", "--depth", "1", "origin", UPSTREAM_COMMIT)
    run_git(repo, "checkout", "--detach", "-q", "FETCH_HEAD")
    head = git_text(repo, "rev-parse", "HEAD")
    if head != UPSTREAM_COMMIT:
        raise PreparationError(
            f"Fetched {head}, expected official commit {UPSTREAM_COMMIT}"
        )


def prepare_checkout(repo: Path) -> dict[str, object]:
    initialize_checkout(repo)
    rewrite_build_files(repo)
    remove_platform_paths(repo)
    run_git(repo, "add", "-A")
    tree = git_text(repo, "write-tree")
    if tree != EXPECTED_TREE:
        raise PreparationError(
            f"Prepared tree is {tree}, expected {EXPECTED_TREE}; "
            "the source recipe or upstream bytes changed"
        )
    return verify_prepared(repo)


def remove_owned_temp(path: Path, expected_parent: Path) -> None:
    if not path.exists() and not path.is_symlink():
        return
    if path.parent.resolve() != expected_parent.resolve():
        raise PreparationError(f"Refusing to clean temporary path outside owned root: {path}")
    if not path.name.startswith(".worldedit-forge-only.tmp-"):
        raise PreparationError(f"Refusing to clean unexpected temporary path: {path}")

    def make_writable(function: object, raw_path: str, _: object) -> None:
        os.chmod(raw_path, stat.S_IWRITE)
        function(raw_path)

    shutil.rmtree(path, onerror=make_writable)


def prepare_or_reuse(target: Path, check_only: bool) -> tuple[str, dict[str, object]]:
    if target.exists() or target.is_symlink():
        if target.is_symlink():
            raise PreparationError(f"Refusing to use symlinked target: {target}")
        return "reused", verify_prepared(target)
    if check_only:
        raise PreparationError(f"Prepared source does not exist: {target}")

    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.parent / (
        f".worldedit-forge-only.tmp-{os.getpid()}-{uuid.uuid4().hex}"
    )
    try:
        evidence = prepare_checkout(temp)
        if target.exists() or target.is_symlink():
            raise PreparationError(
                f"Target appeared while preparing source; refusing to replace it: {target}"
            )
        temp.rename(target)
        return "prepared", evidence
    except Exception:
        remove_owned_temp(temp, target.parent)
        raise


def owned_target(raw: str | None) -> Path:
    owned_root = OWNED_ROOT.resolve()
    target = DEFAULT_TARGET if raw is None else Path(raw)
    if not target.is_absolute():
        target = ROOT / target
    target = target.resolve()
    try:
        target.relative_to(owned_root)
    except ValueError as exc:
        raise PreparationError(
            f"Target must remain under the owned benchmark root {owned_root}: {target}"
        ) from exc
    if target == owned_root:
        raise PreparationError(f"Target must be below the owned benchmark root: {target}")
    return target


def shell_instructions(target: Path) -> dict[str, str]:
    value = str(target)
    return {
        "environmentVariable": SOURCE_OVERRIDE,
        "value": value,
        "powershell": f"$env:{SOURCE_OVERRIDE} = '{value.replace(chr(39), chr(39) * 2)}'",
        "posix": f"export {SOURCE_OVERRIDE}={shlex.quote(value)}",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Prepare and verify the official-parent-derived Forge-only WorldEdit "
            "benchmark input."
        )
    )
    parser.add_argument(
        "--output",
        help=(
            "Target below .modporter/benchmark-inputs "
            f"(default: {DEFAULT_TARGET.relative_to(ROOT).as_posix()})"
        ),
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify an existing prepared source without creating it",
    )
    parser.add_argument(
        "--json-only",
        action="store_true",
        help="suppress the human-readable path line",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        target = owned_target(args.output)
        status, evidence = prepare_or_reuse(target, args.check)
        result = {
            "status": status,
            "path": str(target),
            "upstream": {
                "url": UPSTREAM_URL,
                "commit": UPSTREAM_COMMIT,
            },
            "preparedTree": EXPECTED_TREE,
            "scope": (
                "Forge-only benchmark input retaining WorldEdit core, Forge, and "
                "their retained libs; not a validation of the full upstream monorepo"
            ),
            "sourceOverride": shell_instructions(target),
            "proof": evidence,
        }
        if not args.json_only:
            print(f"WorldEdit benchmark source {status}: {target}")
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, PreparationError) as exc:
        print(f"prepare_worldedit_benchmark.py: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
