#!/usr/bin/env python3
"""Derive Forge 1.20.1 -> NeoForge 1.21.1 `Tags.*` field renames from both loaders' sources.

Forge's `net/minecraftforge/common/Tags.java` names each field's `forge:` tag path
(`SAND_COLORLESS = tag("sand/colorless")`); NeoForge's
`net/neoforged/neoforge/common/TagConventionLogWarning.java` maps each legacy `forge:` path to the
`Tags` field that replaced it (`createForgeMapEntry(Registries.ITEM, "sand/colorless",
Tags.Items.SANDS_COLORLESS)`). Joining them on (registry, path) gives the field renames. A legacy
path NeoForge maps to more than one tag (`stained_glass` -> GLASS_BLOCKS + DYED) has no single
replacement field; it is listed under `intersections` with every tag NeoForge names for it, in
NeoForge's order (the legacy set is the members common to all of them).

    python scripts/forge_tag_renames.py --forge-sources forge-1.20.1-...-sources.jar \\
        --neoforge-sources neoforge-21.1....-sources.jar --output src/main/resources/mappings/forge2neo/tag-field-renames.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

CLASS_REGISTRY = {"Blocks": "BLOCK", "Items": "ITEM", "Fluids": "FLUID", "EntityTypes": "ENTITY_TYPE", "Biomes": "BIOME"}


def forge_fields(source: str) -> dict[tuple[str, str], str]:
    """(nested class, field) -> forge tag path for fields declared with the class's tag("path") helper."""
    fields = {}
    current = None
    for line in source.splitlines():
        cls = re.match(r'\s*public static class (\w+)', line)
        if cls:
            current = cls.group(1)
            continue
        field = re.match(r'\s*public static final TagKey<[^>]+(?:<[^>]*>)?>\s+(\w+)\s*=\s*tag\("([^"]+)"\)\s*;', line)
        if field and current in CLASS_REGISTRY:
            fields[(current, field.group(1))] = field.group(2)
    return fields


def neoforge_tag_ids(source: str) -> dict[tuple[str, str], str]:
    """(nested class, field) -> "namespace:path" for NeoForge Tags fields whose class's tag(name) helper names its namespace."""
    namespaces = {}
    current = None
    in_tag_helper = False
    for line in source.splitlines():
        cls = re.match(r'\s*public static class (\w+)', line)
        if cls:
            current = cls.group(1)
            continue
        # only the class's own tag(String name) helper: the one its tag("path") fields call
        if re.match(r'\s*private static TagKey<[^>]+>\s+tag\(String name\)', line):
            in_tag_helper = True
            continue
        helper = re.search(r'fromNamespaceAndPath\("([a-z0-9_.-]+)",\s*name\)', line)
        if in_tag_helper and helper and current in CLASS_REGISTRY:
            namespaces[current] = helper.group(1)
        if in_tag_helper and line.strip() == '}':
            in_tag_helper = False
    return {key: f"{namespaces[key[0]]}:{path}" for key, path in forge_fields(source).items() if key[0] in namespaces}


def neoforge_replacements(source: str) -> dict[tuple[str, str], list[str]]:
    """(registry, legacy forge path) -> replacement Tags fields ("Items.SANDS_COLORLESS")."""
    result: dict[tuple[str, str], list[str]] = {}
    for match in re.finditer(r'createForgeMapEntry\(Registries\.(\w+),\s*"([^"]+)",\s*([^)]*)\)', source):
        result.setdefault((match.group(1), match.group(2)), []).extend(re.findall(r'Tags\.(\w+\.\w+)', match.group(3)))
    for match in re.finditer(r'createMapEntry\(Registries\.(\w+),\s*"forge",\s*"([^"]+)",\s*([^)]*)\)', source):
        result.setdefault((match.group(1), match.group(2)), []).extend(re.findall(r'Tags\.(\w+\.\w+)', match.group(3)))
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--forge-sources", type=Path, required=True)
    parser.add_argument("--neoforge-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    with zipfile.ZipFile(args.forge_sources) as jar:
        forge = forge_fields(jar.read("net/minecraftforge/common/Tags.java").decode("utf-8"))
    with zipfile.ZipFile(args.neoforge_sources) as jar:
        replacements = neoforge_replacements(jar.read("net/neoforged/neoforge/common/TagConventionLogWarning.java").decode("utf-8"))
        neoforge_ids = neoforge_tag_ids(jar.read("net/neoforged/neoforge/common/Tags.java").decode("utf-8"))
    renames = []
    intersections = []
    for (cls, field), path in sorted(forge.items()):
        named = replacements.get((CLASS_REGISTRY[cls], path), [])
        targets = sorted(set(named))
        if len(targets) > 1:
            ordered = list(dict.fromkeys(named))
            if all(target.split(".")[0] == cls for target in ordered):
                fields = [target.split(".")[1] for target in ordered]
                tag_ids = [neoforge_ids.get((cls, name)) for name in fields]
                intersections.append({
                    "class": cls,
                    "forgeField": field,
                    "neoforgeFields": fields,
                    "forgePath": path,
                    **({"neoforgeTags": tag_ids} if all(tag_ids) else {}),
                })
            continue
        if len(targets) != 1:
            continue
        target_cls, target_field = targets[0].split(".")
        if target_cls != cls or target_field == field:
            continue
        renames.append({"class": cls, "forgeField": field, "neoforgeField": target_field, "forgePath": path})
    args.output.write_text(json.dumps({
        "description": "Forge 1.20.1 Tags fields renamed in NeoForge 1.21.1, joined on the legacy forge tag path "
                       "(Forge Tags.java + NeoForge TagConventionLogWarning.java); see scripts/forge_tag_renames.py",
        "forgeSources": args.forge_sources.name,
        "neoforgeSources": args.neoforge_sources.name,
        "renames": renames,
        "intersections": intersections,
    }, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(renames)} renames and {len(intersections)} intersections to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
