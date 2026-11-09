#!/usr/bin/env python3
"""Dump the declared fields and methods of every class in a compiled Minecraft/NeoForge jar.

The input is the real NeoForge 1.21.1 compiled game jar that ModDevGradle / NeoForm
produces (classes carry Mojang names). The output is a gzip TSV that migration rules use
to verify that a member a mod references by name (for example through reflection) exists
on the target before the converted project is compiled::

    F<TAB>net.minecraft.server.MinecraftServer<TAB>storageSource<TAB>Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;<TAB>access
    M<TAB>net.minecraft.server.MinecraftServer<TAB>getPlayerList<TAB>()Lnet/minecraft/server/players/PlayerList;<TAB>access
    S<TAB>net.minecraft.client.gui.screens.Screen<TAB>net.minecraft.client.gui.components.events.AbstractContainerEventHandler<TAB>extends

Members with generic types carry their `Signature` attribute as a sixth column (for example
`()Lcom/mojang/serialization/MapCodec<+Lnet/minecraft/world/level/block/DiodeBlock;>;`), so rules
can tell a wildcard return type from an exact one.

`S` rows name each class's direct superclass (`extends`) and interfaces (`implements`), so rules
can tell whether a mod method overrides a platform one somewhere up the hierarchy.

Only the class file constant pool is parsed (no bytecode), so this needs nothing but the
standard library.
"""
from __future__ import annotations

import argparse
import gzip
import struct
import sys
import zipfile
from pathlib import Path


def parse_class(data: bytes):
    """Return (binary_name, [(kind, name, descriptor, access)], [(supertype, relation)]) for one class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    pos = 8
    count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    pool: list = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]
            pool[i] = data[pos + 2:pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 7:
            pool[i] = ("class", struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag == 8:
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        elif tag in (16, 19, 20):
            pos += 2
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 1
    access, this_class, super_class = struct.unpack_from(">HHH", data, pos)
    pos += 2 + 2 + 2  # access, this, super
    name = pool[pool[this_class][1]]
    supertypes = []
    if super_class:
        supertypes.append((pool[pool[super_class][1]], "extends"))
    interfaces = struct.unpack_from(">H", data, pos)[0]
    for index in range(interfaces):
        interface = struct.unpack_from(">H", data, pos + 2 + 2 * index)[0]
        supertypes.append((pool[pool[interface][1]], "implements"))
    pos += 2 + 2 * interfaces
    members = []
    for kind in ("F", "M"):
        count = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(count):
            flags, name_index, desc_index, attr_count = struct.unpack_from(">HHHH", data, pos)
            pos += 8
            signature = ""
            for _ in range(attr_count):
                attr_name, attr_len = struct.unpack_from(">HI", data, pos)
                if pool[attr_name] == "Signature":
                    signature = pool[struct.unpack_from(">H", data, pos + 6)[0]]
                pos += 6 + attr_len
            members.append((kind, pool[name_index], pool[desc_index], flags, signature))
    return name, members, supertypes


def access_word(flags: int) -> str:
    if flags & 0x0001:
        return "public"
    if flags & 0x0004:
        return "protected"
    if flags & 0x0002:
        return "private"
    return "package"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prefix", action="append", default=None,
                        help="class name prefixes to keep (default: net/minecraft/, com/mojang/, net/neoforged/)")
    args = parser.parse_args(argv)
    prefixes = tuple(args.prefix or ["net/minecraft/", "com/mojang/", "net/neoforged/"])
    rows = []
    classes = 0
    with zipfile.ZipFile(args.jar) as jar:
        for entry in jar.namelist():
            if not entry.endswith(".class") or not entry.startswith(prefixes):
                continue
            name, members, supertypes = parse_class(jar.read(entry))
            classes += 1
            dotted = name.replace("/", ".")
            for supertype, relation in supertypes:
                if supertype != "java/lang/Object":
                    rows.append(("S", dotted, supertype.replace("/", "."), relation, ""))
            for kind, member, descriptor, flags, signature in members:
                if flags & 0x1000:  # synthetic
                    continue
                rows.append((kind, dotted, member, descriptor, access_word(flags), signature))
    rows.sort()
    rows = [row[:4] if row[0] == "S" else row if row[5] else row[:5] for row in rows]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(args.output, "wt", encoding="utf-8", newline="\n") as out:
        out.write(f"# kind\towner\tname\tdescriptor\taccess\tsignature (declared members of {args.jar.name}; see scripts/target_members.py)\n")
        for row in rows:
            out.write("\t".join(row) + "\n")
    print(f"wrote {len(rows)} members from {classes} classes to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
