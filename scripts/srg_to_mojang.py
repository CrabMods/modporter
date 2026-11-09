#!/usr/bin/env python3
"""Derive a 1.20.1 SRG -> Mojang member name table from the real mapping data.

Inputs are the two authoritative mapping artifacts that ForgeGradle itself uses:

* MCPConfig ``joined.tsrg`` / ``obf_to_srg.tsrg`` (obfuscated -> SRG member ids, with
  obfuscated descriptors), published by MinecraftForge for the exact Minecraft version.
* Mojang's official ``client_mappings.txt`` and ``server_mappings.txt`` (ProGuard format,
  named -> obfuscated); the server file adds the dedicated-server-only classes.

Both are keyed by the obfuscated names, so joining them yields, for every field and
method, the SRG id Forge 1.20.1 mods see (``f_20937_`` / ``m_20291_``) and the Mojang
name NeoForge 1.21.1 uses (``fallFlyTicks`` / ``getSharedFlag``).  Nothing is guessed:
every row comes from those two files.

Output (gzip TSV, one member per line)::

    F<TAB>net.minecraft.world.entity.LivingEntity<TAB>f_20937_<TAB>fallFlyTicks<TAB>
    M<TAB>net.minecraft.world.entity.Entity<TAB>m_20291_<TAB>getSharedFlag<TAB>(I)Z

Method descriptors use the Mojang class names (the form Forge 1.20.1 access
transformers and ObfuscationReflectionHelper calls already use for class references).
"""
from __future__ import annotations

import argparse
import gzip
import re
import sys
from pathlib import Path

PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}


def type_descriptor(named_type: str, named_to_obf: dict[str, str] | None) -> str:
    """ProGuard type (``int[]``, ``net.minecraft.Foo``) -> JVM descriptor."""
    dims = named_type.count("[]")
    base = named_type.replace("[]", "")
    if base in PRIMITIVES:
        core = PRIMITIVES[base]
    else:
        internal = base.replace(".", "/")
        if named_to_obf is not None and base in named_to_obf:
            internal = named_to_obf[base].replace(".", "/")
        core = "L" + internal + ";"
    return "[" * dims + core


def parse_proguard(path: Path):
    """Return (obf_class -> named_class, named_class -> obf_class, members).

    ``members`` maps obf_class -> {"fields": {obf: named}, "methods": {(obf, obf_desc): (named, named_desc)}}.
    """
    class_re = re.compile(r"^(\S+) -> (\S+):$")
    method_re = re.compile(r"^\s+(?:\d+:\d+:)?(\S+) (\S+)\((.*?)\) -> (\S+)$")
    field_re = re.compile(r"^\s+(\S+) (\S+) -> (\S+)$")
    lines = path.read_text(encoding="utf-8").splitlines()
    obf_to_named: dict[str, str] = {}
    named_to_obf: dict[str, str] = {}
    raw: list[tuple[str, list[str]]] = []
    current: list[str] | None = None
    for line in lines:
        if line.startswith("#") or not line.strip():
            continue
        match = class_re.match(line)
        if match:
            named, obf = match.groups()
            obf_to_named[obf] = named
            named_to_obf[named] = obf
            current = []
            raw.append((obf, current))
            continue
        if current is not None:
            current.append(line)
    members: dict[str, dict] = {}
    for obf_class, body in raw:
        fields: dict[str, str] = {}
        methods: dict[tuple[str, str], tuple[str, str]] = {}
        for line in body:
            m = method_re.match(line)
            if m:
                ret, name, args, obf = m.groups()
                arg_types = [a.strip() for a in args.split(",") if a.strip()]
                obf_desc = "(" + "".join(type_descriptor(a, named_to_obf) for a in arg_types) + ")" + type_descriptor(ret, named_to_obf)
                named_desc = "(" + "".join(type_descriptor(a, None) for a in arg_types) + ")" + type_descriptor(ret, None)
                methods[(obf, obf_desc)] = (name, named_desc)
                continue
            f = field_re.match(line)
            if f:
                _type, name, obf = f.groups()
                fields[obf] = name
        members[obf_class] = {"fields": fields, "methods": methods}
    return obf_to_named, named_to_obf, members


def parse_tsrg(path: Path):
    """Return obf_class -> {"fields": {obf: srg}, "methods": {(obf, obf_desc): srg}} from a TSRG (v1) file."""
    result: dict[str, dict] = {}
    current: dict | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("tsrg2"):
            continue
        if not line.startswith("\t"):
            parts = line.split()
            if len(parts) < 2:
                continue
            current = {"fields": {}, "methods": {}}
            result[parts[0]] = current
            continue
        if current is None:
            continue
        parts = line.strip().split()
        if len(parts) == 2:
            current["fields"][parts[0]] = parts[1]
        elif len(parts) == 3:
            current["methods"][(parts[0], parts[1])] = parts[2]
    return result


SRG_MEMBER = re.compile(r"^(?:f|m)_\d+_$")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tsrg", type=Path, required=True, help="MCPConfig obf_to_srg.tsrg / joined.tsrg")
    parser.add_argument("--proguard", type=Path, required=True, action="append",
                        help="Mojang client_mappings.txt / server_mappings.txt (repeatable; later files add missing classes)")
    parser.add_argument("--output", type=Path, required=True, help="gzip TSV output")
    args = parser.parse_args(argv)

    obf_to_named: dict[str, str] = {}
    mojang: dict[str, dict] = {}
    for proguard in args.proguard:
        file_obf_to_named, _named_to_obf, file_members = parse_proguard(proguard)
        for obf_class, named_class in file_obf_to_named.items():
            obf_to_named.setdefault(obf_class, named_class)
        for obf_class, members in file_members.items():
            target = mojang.setdefault(obf_class, {"fields": {}, "methods": {}})
            for obf_field, named in members["fields"].items():
                target["fields"].setdefault(obf_field, named)
            for key, named in members["methods"].items():
                target["methods"].setdefault(key, named)
    srg = parse_tsrg(args.tsrg)
    rows: list[tuple[str, str, str, str, str]] = []
    missing = 0
    for obf_class, entry in srg.items():
        # Classes Mojang never obfuscated (net.minecraft.server.MinecraftServer) are keyed with
        # slashes in the TSRG and dots in the ProGuard file.
        proguard_key = obf_class if obf_class in obf_to_named else obf_class.replace("/", ".")
        named_class = obf_to_named.get(proguard_key)
        if named_class is None:
            continue
        moj = mojang.get(proguard_key, {"fields": {}, "methods": {}})
        for obf_field, srg_field in entry["fields"].items():
            if not SRG_MEMBER.match(srg_field):
                continue
            named = moj["fields"].get(obf_field)
            if named is None:
                missing += 1
                continue
            rows.append(("F", named_class, srg_field, named, ""))
        for (obf_method, obf_desc), srg_method in entry["methods"].items():
            if not SRG_MEMBER.match(srg_method):
                continue
            named = moj["methods"].get((obf_method, obf_desc))
            if named is None:
                missing += 1
                continue
            rows.append(("M", named_class, srg_method, named[0], named[1]))
    rows.sort()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.output, "wt", encoding="utf-8", newline="\n") as out:
        out.write("# kind\towner\tsrg\tmojang\tdescriptor (derived from MCPConfig tsrg + Mojang client and server mappings; see scripts/srg_to_mojang.py)\n")
        for row in rows:
            out.write("\t".join(row) + "\n")
    print(f"wrote {len(rows)} members ({missing} SRG members without a Mojang name skipped) to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
