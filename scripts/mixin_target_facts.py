#!/usr/bin/env python3
"""Derive the bytecode facts the generic legacy Mixin target migrations consume.

The input is the real NeoForge 1.21.1 compiled game jar (classes carry Mojang names, the
same jar ``scripts/target_members.py`` reads), disassembled with ``javap -c -p -s -l``.
Candidate classes are found from the class files' constant pools first, so only those are
disassembled. Two files are written::

    vanilla-block-use-split.json
        1.20.5 split ``Block#use(BlockState, Level, BlockPos, Player, InteractionHand,
        BlockHitResult)`` into ``useItemOn`` and ``useWithoutItem``. For every class whose
        1.20.1 version declared that ``use`` (the 1.20.1 SRG table says so), the member
        references each of the two 1.21.1 methods' own code invokes. A Mixin injector that
        targeted ``use`` moves to the one method whose code contains its @At target.

    vanilla-prefix-forwarders.json
        1.21.1 methods that keep a 1.20.1 parameter list (they are in the 1.20.1 SRG table)
        but only forward to a same-class, same-name overload that takes those parameters
        followed by more (pushing constants for the extra ones), where that extended overload
        did not exist in 1.20.1. Each entry carries the extended overload's parameter names
        and the member references its code invokes, so an injector into the legacy
        descriptor can follow the body to the extended overload.

``verify`` checks every claim of the curated ``legacy-mixin-member-moves.json`` against the
1.21.1 jar and, with ``--legacy-jar``, against a Forge 1.20.1 Mojang-named jar; it exits
non-zero when a claim does not hold. ``invokes`` prints what one method invokes (debugging).

Usage::

    python scripts/mixin_target_facts.py generate --jar <neoforge-1.21.1.jar> \
        --javap <jdk21>/bin/javap --output-dir src/main/resources/mappings/forge2neo
    python scripts/mixin_target_facts.py verify --jar <neoforge-1.21.1.jar> \
        --legacy-jar <forge-1.20.1 mapped_official.jar> --javap <jdk21>/bin/javap
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import struct
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from target_members import parse_class  # noqa: E402  (sibling script)

ROOT = Path(__file__).resolve().parent.parent
MAPPINGS = ROOT / "src/main/resources/mappings/forge2neo"
SRG_TABLE = MAPPINGS / "srg-mojang-1.20.1.tsv.gz"
MOVES = MAPPINGS / "legacy-mixin-member-moves.json"

LEGACY_USE_DESCRIPTOR = (
    "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
    "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;"
    "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)"
    "Lnet/minecraft/world/InteractionResult;"
)
SPLIT_METHODS = ("useItemOn", "useWithoutItem")
BATCH = 150

LOAD = re.compile(r"^(aload|iload|lload|fload|dload)(?:_(\d)|\s+(\d+))$")
STORE = re.compile(r"^(astore|istore|lstore|fstore|dstore)(?:_(\d)|\s+(\d+))$")
CONSTANT = re.compile(
    r"^(aconst_null|iconst_m1|iconst_[0-5]|lconst_[01]|fconst_[0-2]|dconst_[01]|bipush|sipush|ldc|ldc_w|ldc2_w)\b"
)
RETURN = re.compile(r"^(return|areturn|ireturn|lreturn|freturn|dreturn)$")
INVOKE = re.compile(r"^(invokevirtual|invokestatic|invokeinterface|invokespecial)\b")
CLASS_HEADER = re.compile(
    r"^(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp|synthetic)\s+)*"
    r"(?:class|interface|enum|record|@interface)\s+([\w.$]+)"
)


@dataclass
class Method:
    owner: str
    name: str
    descriptor: str
    static: bool
    code: list[str] = field(default_factory=list)
    locals: dict[int, str] = field(default_factory=dict)

    def invokes(self) -> list[str]:
        """`Lowner;name(desc)ret` of every method its own code invokes, in first-seen order."""
        seen: list[str] = []
        for instruction in self.code:
            reference = invoked_reference(instruction, self.owner)
            if reference is not None and reference not in seen:
                seen.append(reference)
        return seen

    def parameter_names(self) -> list[str]:
        slot = 0 if self.static else 1
        names = []
        for parameter in parameter_types(self.descriptor):
            names.append(self.locals.get(slot, ""))
            slot += 2 if parameter in ("J", "D") else 1
        return names

    def field_accesses(self, code: list[str] | None = None) -> list[tuple[str, str]]:
        """(opcode, `Lowner;name`) of every field access in [code] (default: the whole method)."""
        accesses = []
        for instruction in self.code if code is None else code:
            access = field_reference(instruction, self.owner)
            if access is not None:
                accesses.append(access)
        return accesses

    def static_initializer_segment(self, field_name: str) -> list[str] | None:
        """The instructions computing the value stored by `putstatic [field_name]` in <clinit>."""
        start = 0
        for position, instruction in enumerate(self.code):
            access = field_reference(instruction, self.owner)
            if access is None or access[0] != "putstatic":
                continue
            if access[1].endswith(";" + field_name) and access[1].startswith(f"L{self.owner.replace('.', '/')};"):
                return self.code[start:position]
            start = position + 1
        return None


def field_reference(instruction: str, current_owner: str) -> tuple[str, str] | None:
    opcode = instruction.split()[0] if instruction else ""
    if opcode not in ("getfield", "getstatic", "putfield", "putstatic"):
        return None
    comment = instruction.split("//", 1)[1].strip() if "//" in instruction else ""
    match = re.match(r"Field\s+(.+)$", comment)
    if not match:
        return None
    member = match.group(1).split(":", 1)[0]
    if "." in member:
        owner, name = member.rsplit(".", 1)
    else:
        owner, name = current_owner.replace(".", "/"), member
    return opcode, f"L{owner};{name}"


def invoked_reference(instruction: str, current_owner: str) -> str | None:
    opcode = instruction.split()[0] if instruction else ""
    if not INVOKE.match(opcode):
        return None
    comment = instruction.split("//", 1)[1].strip() if "//" in instruction else ""
    match = re.match(r"(?:Method|InterfaceMethod)\s+(.+)$", comment)
    if not match:
        return None
    target = match.group(1)
    member, descriptor = target.split(":", 1)
    if "." in member:
        owner, name = member.rsplit(".", 1)
    else:
        owner, name = current_owner.replace(".", "/"), member
    owner = owner.strip('"')
    if owner.startswith("["):
        return None
    return f"L{owner};{name.strip(chr(34))}{descriptor}"


def parameter_types(descriptor: str) -> list[str]:
    types = []
    index = descriptor.index("(") + 1
    while descriptor[index] != ")":
        start = index
        while descriptor[index] == "[":
            index += 1
        index = descriptor.index(";", index) + 1 if descriptor[index] == "L" else index + 1
        types.append(descriptor[start:index])
    return types


def return_type(descriptor: str) -> str:
    return descriptor[descriptor.index(")") + 1:]


def run_javap(javap: str, jar: Path, classes: list[str]) -> dict[tuple[str, str, str], Method]:
    methods: dict[tuple[str, str, str], Method] = {}
    for start in range(0, len(classes), BATCH):
        batch = classes[start:start + BATCH]
        output = subprocess.run(
            [javap, "-c", "-p", "-s", "-l", "-cp", str(jar), *batch],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        ).stdout
        parse_javap(output, methods)
    return methods


def parse_javap(output: str, methods: dict[tuple[str, str, str], Method]) -> None:
    owner = None
    pending: tuple[str, bool] | None = None
    current: Method | None = None
    section = None
    for raw in output.splitlines():
        if not raw.startswith(" ") and raw.strip():
            header = CLASS_HEADER.match(raw)
            if header:
                owner = header.group(1)
            current, pending, section = None, None, None
            continue
        line = raw.strip()
        if raw.startswith("  ") and not raw.startswith("   ") and line == "static {};":
            pending, current, section = ("<clinit>", True), None, None
            continue
        if raw.startswith("  ") and not raw.startswith("   ") and line.endswith(";"):
            signature = line[:-1]
            name_match = re.search(r"([\w$<>]+)\(", signature)
            pending = (name_match.group(1), " static " in f" {signature} ") if name_match else None
            current, section = None, None
            continue
        if line.startswith("descriptor:") and pending is not None and owner is not None:
            descriptor = line.split(":", 1)[1].strip()
            name, static = pending
            current = Method(owner, name, descriptor, static)
            methods[(owner, name, descriptor)] = current
            pending, section = None, None
            continue
        if current is None:
            continue
        if line == "Code:":
            section = "code"
            continue
        if line.startswith("LocalVariableTable:"):
            section = "locals"
            continue
        if line.startswith(("LineNumberTable:", "StackMapTable:", "Exceptions:", "Signature:",
                            "RuntimeVisible", "RuntimeInvisible", "MethodParameters:",
                            "LocalVariableTypeTable:", "AnnotationDefault:", "Exception table:")):
            section = None
            continue
        if section == "code":
            instruction = re.match(r"^\d+:\s+(.*)$", line)
            if instruction:
                current.code.append(instruction.group(1))
        elif section == "locals":
            columns = line.split()
            if len(columns) >= 5 and columns[0].isdigit() and columns[0] == "0":
                current.locals.setdefault(int(columns[2]), columns[3])


def read_srg_table(path: Path) -> set[tuple[str, str, str]]:
    members = set()
    with gzip.open(path, "rt", encoding="utf-8") as table:
        for line in table:
            if line.startswith("#"):
                continue
            columns = line.rstrip("\n").split("\t")
            if len(columns) >= 5 and columns[0] == "M":
                members.add((columns[1], columns[3], columns[4]))
    return members


def declared_methods(jar: Path, prefixes: tuple[str, ...]) -> dict[str, list[tuple[str, str, int]]]:
    """binary name -> [(name, descriptor, access flags)] of every non-synthetic declared method."""
    result: dict[str, list[tuple[str, str, int]]] = {}
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if not entry.endswith(".class") or not entry.startswith(prefixes):
                continue
            name, members, _ = parse_class(archive.read(entry))
            result[name.replace("/", ".")] = [
                (member, descriptor, flags)
                for kind, member, descriptor, flags, _ in members
                if kind == "M" and not flags & 0x1000
            ]
    return result


def forwarded_overload(method: Method, extended_descriptor: str) -> bool:
    """True when [method]'s code is exactly: load its parameters in order, push constants,
    invoke the same-class same-name overload with [extended_descriptor], return its value."""
    code = [instruction for instruction in method.code if instruction != "nop"]
    slot = 0
    index = 0
    if not method.static:
        if not code or not LOAD.match(code[0]) or load_slot(code[0]) != 0:
            return False
        slot, index = 1, 1
    for parameter in parameter_types(method.descriptor):
        if index >= len(code) or not LOAD.match(code[index]) or load_slot(code[index]) != slot:
            return False
        slot += 2 if parameter in ("J", "D") else 1
        index += 1
    extra = len(parameter_types(extended_descriptor)) - len(parameter_types(method.descriptor))
    for _ in range(extra):
        if index >= len(code) or not CONSTANT.match(code[index]):
            return False
        index += 1
    if index + 2 != len(code):
        return False
    expected = f"L{method.owner.replace('.', '/')};{method.name}{extended_descriptor}"
    return invoked_reference(code[index], method.owner) == expected and bool(RETURN.match(code[index + 1]))


def load_slot(instruction: str) -> int:
    match = LOAD.match(instruction)
    return int(match.group(2) or match.group(3))


def generate(args: argparse.Namespace) -> int:
    legacy = read_srg_table(args.srg_table)
    declared = declared_methods(args.jar, ("net/minecraft/",))

    split_classes = sorted(
        owner for owner, methods in declared.items()
        if (owner, "use", LEGACY_USE_DESCRIPTOR) in legacy
        and any(name in SPLIT_METHODS for name, _, _ in methods)
    )
    forwarder_pairs: list[tuple[str, str, str, str]] = []
    for owner, methods in declared.items():
        by_name: dict[str, list[tuple[str, int]]] = {}
        for name, descriptor, flags in methods:
            by_name.setdefault(name, []).append((descriptor, flags))
        for name, overloads in by_name.items():
            if len(overloads) < 2 or name.startswith("<"):
                continue
            for short, short_flags in overloads:
                if (owner, name, short) not in legacy:
                    continue
                for long, long_flags in overloads:
                    if long == short or (owner, name, long) in legacy:
                        continue
                    if (short_flags & 0x0008) != (long_flags & 0x0008):
                        continue
                    if return_type(short) != return_type(long):
                        continue
                    short_parameters = parameter_types(short)
                    long_parameters = parameter_types(long)
                    if len(long_parameters) > len(short_parameters) and \
                            long_parameters[:len(short_parameters)] == short_parameters:
                        forwarder_pairs.append((owner, name, short, long))

    disassemble = sorted(set(split_classes) | {pair[0] for pair in forwarder_pairs})
    methods = run_javap(args.javap, args.jar, disassemble)

    split: dict[str, dict[str, dict[str, object]]] = {}
    for owner in split_classes:
        entry: dict[str, dict[str, object]] = {}
        for name, descriptor, _ in sorted(declared[owner]):
            if name not in SPLIT_METHODS:
                continue
            method = methods[(owner, name, descriptor)]
            entry[name] = {"descriptor": descriptor, "invokes": sorted(method.invokes())}
        split[owner] = entry

    forwarders = []
    for owner, name, short, long in sorted(forwarder_pairs):
        legacy_method = methods.get((owner, name, short))
        extended = methods.get((owner, name, long))
        if legacy_method is None or extended is None or not forwarded_overload(legacy_method, long):
            continue
        forwarders.append({
            "owner": owner,
            "name": name,
            "legacyDescriptor": short,
            "extendedDescriptor": long,
            "extendedParameterNames": extended.parameter_names(),
            "extendedInvokes": sorted(extended.invokes()),
        })

    source = args.jar.name
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_json(args.output_dir / "vanilla-block-use-split.json", {
        "source": source,
        "generator": "scripts/mixin_target_facts.py generate",
        "legacyMethod": {"name": "use", "descriptor": LEGACY_USE_DESCRIPTOR},
        "classes": split,
    })
    write_json(args.output_dir / "vanilla-prefix-forwarders.json", {
        "source": source,
        "generator": "scripts/mixin_target_facts.py generate",
        "forwarders": forwarders,
    })
    print(f"wrote {len(split)} split classes and {len(forwarders)} prefix forwarders from {source}")
    return 0


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=False) + "\n", encoding="utf-8", newline="\n")


def reference_key(reference: str) -> tuple[str, str, str]:
    """`Lowner;name(desc)ret` -> (dotted owner, name, descriptor)."""
    owner, rest = reference[1:].split(";", 1)
    name = rest[:rest.index("(")]
    return owner.replace("/", "."), name, rest[len(name):]


def split_selector(owner: str, selector: str) -> tuple[str, str, str]:
    name = selector[:selector.index("(")]
    return owner, name, selector[len(name):]


def verify(args: argparse.Namespace) -> int:
    moves = json.loads(args.moves.read_text(encoding="utf-8"))
    native_classes: set[str] = set()
    legacy_classes: set[str] = set()
    for move in moves.get("enclosingMoves", []):
        native_classes.add(move["owner"])
        legacy_classes.add(move["owner"])
        native_classes.add(reference_key(move["nativeInvoke"])[0])
        legacy_classes.add(reference_key(move["legacyInvoke"])[0])
    for insertion in moves.get("parameterInsertions", []):
        native_classes.add(insertion["owner"])
        legacy_classes.add(insertion["owner"])
        for implementation in insertion.get("serverImplementations", []):
            native_classes.add(implementation["owner"])
    native = run_javap(args.javap, args.jar, sorted(native_classes))
    legacy = run_javap(args.javap, args.legacy_jar, sorted(legacy_classes)) if args.legacy_jar else None
    failures: list[str] = []

    def require(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    for move in moves.get("enclosingMoves", []):
        owner = move["owner"]
        legacy_key = split_selector(owner, move["legacyMethod"])
        native_key = split_selector(owner, move["nativeMethod"])
        require(legacy_key not in native, f"{owner}.{move['legacyMethod']} still exists on 1.21.1")
        native_method = native.get(native_key)
        require(native_method is not None, f"{owner}.{move['nativeMethod']} is missing on 1.21.1")
        if native_method is not None:
            require(move["nativeInvoke"] in native_method.invokes(),
                    f"{owner}.{move['nativeMethod']} does not invoke {move['nativeInvoke']} on 1.21.1")
            require(move["legacyInvoke"] not in native_method.invokes(),
                    f"{owner}.{move['nativeMethod']} still invokes {move['legacyInvoke']} on 1.21.1")
        native_invoke = native.get(reference_key(move["nativeInvoke"]))
        require(native_invoke is not None and native_invoke.static == move["invokeStatic"],
                f"{move['nativeInvoke']} is missing on 1.21.1 or its static flag is not {move['invokeStatic']}")
        if legacy is not None:
            legacy_invoke = legacy.get(reference_key(move["legacyInvoke"]))
            require(legacy_invoke is not None and legacy_invoke.static == move["invokeStatic"],
                    f"{move['legacyInvoke']} is missing on 1.20.1 or its static flag is not {move['invokeStatic']}")
            legacy_method = legacy.get(legacy_key)
            require(legacy_method is not None, f"{owner}.{move['legacyMethod']} is missing on 1.20.1")
            if legacy_method is not None:
                require(move["legacyInvoke"] in legacy_method.invokes(),
                        f"{owner}.{move['legacyMethod']} does not invoke {move['legacyInvoke']} on 1.20.1")

    for insertion in moves.get("parameterInsertions", []):
        owner, name = insertion["owner"], insertion["name"]
        native_method = native.get((owner, name, insertion["nativeDescriptor"]))
        require(native_method is not None, f"{owner}.{name}{insertion['nativeDescriptor']} is missing on 1.21.1")
        require((owner, name, insertion["legacyDescriptor"]) not in native,
                f"{owner}.{name}{insertion['legacyDescriptor']} still exists on 1.21.1")
        if native_method is not None:
            reads = parameter_reads(native_method, insertion["insertedIndex"])
            require(reads is not None and set(reads) <= set(insertion["parameterReads"]),
                    f"{owner}.{name} uses its inserted parameter beyond {insertion['parameterReads']}: {reads}")
        for implementation in insertion.get("serverImplementations", []):
            method = native.get(split_selector(implementation["owner"], implementation["method"]))
            require(method is not None and method.invokes() == implementation["invokes"],
                    f"{implementation['owner']}.{implementation['method']} invokes "
                    f"{method.invokes() if method else None}, not {implementation['invokes']}")
        if legacy is not None:
            require((owner, name, insertion["legacyDescriptor"]) in legacy,
                    f"{owner}.{name}{insertion['legacyDescriptor']} is missing on 1.20.1")

    if legacy is not None:
        verify_member_moves(args, moves, require)
    else:
        for key in ("injectorMoves", "staticFieldMethods", "invokerFieldMoves", "removedStaticHelpers"):
            require(not moves.get(key), f"{key} needs --legacy-jar to be verified")

    for failure in failures:
        print(f"FAILED: {failure}")
    counts = ", ".join(f"{len(value)} {key}" for key, value in moves.items() if isinstance(value, list))
    print(f"verified {counts}: {'ok' if not failures else f'{len(failures)} failures'}")
    return 1 if failures else 0


def class_file(jar: Path, binary: str):
    """(members, supertypes) of [binary] in [jar], or None when the jar has no such class."""
    entry = binary.replace(".", "/") + ".class"
    with zipfile.ZipFile(jar) as archive:
        if entry not in archive.namelist():
            return None
        _, members, supertypes = parse_class(archive.read(entry))
    return members, [supertype.replace("/", ".") for supertype, _ in supertypes]


def inherits(jar: Path, binary: str, ancestor: str) -> bool:
    frontier, seen = [binary], set()
    while frontier:
        current = frontier.pop()
        if current == ancestor:
            return True
        if current in seen:
            continue
        seen.add(current)
        parsed = class_file(jar, current)
        if parsed is not None:
            frontier.extend(parsed[1])
    return False


def declares(jar: Path, owner: str, kind: str, name: str, descriptor: str, static: bool | None = None) -> bool:
    parsed = class_file(jar, owner)
    if parsed is None:
        return False
    return any(
        member_kind == kind and member == name and member_descriptor == descriptor and
        (static is None or bool(flags & 0x0008) == static)
        for member_kind, member, member_descriptor, flags, _ in parsed[0]
    )


def verify_member_moves(args: argparse.Namespace, moves: dict, require) -> None:
    """Checks the injector, static-field, invoker-field and removed-helper claims on both jars."""
    def method(jar: Path, owner: str, selector: str) -> Method | None:
        return run_javap(args.javap, jar, [owner]).get(split_selector(owner, selector))

    for move in moves.get("injectorMoves", []):
        legacy_method = method(args.legacy_jar, move["legacyOwner"], move["legacyMethod"])
        native_method = method(args.jar, move["nativeOwner"], move["nativeMethod"])
        label = f"{move['legacyOwner']}.{move['legacyMethod']} -> {move['nativeOwner']}.{move['nativeMethod']}"
        require(legacy_method is not None, f"{label}: legacy method missing on 1.20.1")
        require(native_method is not None, f"{label}: native method missing on 1.21.1")
        require(method(args.jar, move["legacyOwner"], move["legacyMethod"]) is None,
                f"{label}: legacy method still exists on 1.21.1")
        for shared in move["sharedInvokes"]:
            require(legacy_method is not None and shared in legacy_method.invokes(), f"{label}: 1.20.1 lacks {shared}")
            require(native_method is not None and shared in native_method.invokes(), f"{label}: 1.21.1 lacks {shared}")
        name, descriptor = move["legacyMethod"].split("(", 1)
        for caller in callers(args.legacy_jar, args.javap, f"L*;{name}({descriptor}"):
            caller_owner = caller.split("(", 1)[0].rsplit(".", 1)[0]
            require(inherits(args.legacy_jar, caller_owner, move["nativeOwner"]),
                    f"{label}: 1.20.1 caller {caller} is not a {move['nativeOwner']}")

    for entry in moves.get("staticFieldMethods", []):
        owner = entry["owner"]
        label = f"{owner}.{entry['legacyField']} -> {entry['nativeMethod']}"
        require(declares(args.legacy_jar, owner, "F", entry["legacyField"], entry["fieldDescriptor"], static=True),
                f"{label}: 1.20.1 static field missing")
        require(not declares(args.jar, owner, "F", entry["legacyField"], entry["fieldDescriptor"]),
                f"{label}: field still exists on 1.21.1")
        native_name, native_descriptor = entry["nativeMethod"].split("(", 1)
        require(declares(args.jar, owner, "M", native_name, "(" + native_descriptor, static=False),
                f"{label}: 1.21.1 instance method missing")
        initializer = method(args.legacy_jar, owner, "<clinit>()V")
        segment = initializer.static_initializer_segment(entry["legacyField"]) if initializer else None
        native_method = method(args.jar, owner, entry["nativeMethod"])
        require(segment is not None and native_method is not None, f"{label}: initializer or method not found")
        if segment is not None and native_method is not None:
            legacy_invokes = {invoked_reference(it, owner) for it in segment} - {None}
            legacy_reads = {name for _, name in initializer.field_accesses(segment)}
            native_reads = {name for _, name in native_method.field_accesses()}
            for shared in entry["sharedInvokes"]:
                require(shared in legacy_invokes and shared in native_method.invokes(), f"{label}: not both invoke {shared}")
            for shared in entry["sharedFieldReads"]:
                require(shared in legacy_reads and shared in native_reads, f"{label}: not both read {shared}")

    for entry in moves.get("invokerFieldMoves", []):
        label = f"{entry['legacyOwner']}.{entry['legacyMethod']} -> {entry['nativeField']}.{entry['nativeCall']}"
        legacy_name, legacy_descriptor = entry["legacyMethod"].split("(", 1)
        require(declares(args.legacy_jar, entry["legacyOwner"], "M", legacy_name, "(" + legacy_descriptor),
                f"{label}: 1.20.1 method missing")
        require(not declares(args.jar, entry["nativeOwner"], "M", legacy_name, "(" + legacy_descriptor),
                f"{label}: method still exists on 1.21.1")
        require(declares(args.jar, entry["nativeOwner"], "F", entry["nativeField"], entry["nativeFieldDescriptor"]),
                f"{label}: 1.21.1 field missing")
        field_type = entry["nativeFieldDescriptor"][1:-1].replace("/", ".")
        call_name, call_descriptor = entry["nativeCall"].split("(", 1)
        require(declares(args.jar, field_type, "M", call_name, "(" + call_descriptor),
                f"{label}: {field_type} lacks {entry['nativeCall']}")
        legacy_witness = method(args.legacy_jar, entry["legacyOwner"], entry["legacyWitness"])
        native_witness = method(args.jar, entry["nativeOwner"], entry["nativeWitness"])
        legacy_reference = f"L{entry['legacyOwner'].replace('.', '/')};{entry['legacyMethod']}"
        native_reference = f"L{field_type.replace('.', '/')};{entry['nativeCall']}"
        field_read = ("getfield", f"L{entry['nativeOwner'].replace('.', '/')};{entry['nativeField']}")
        require(legacy_witness is not None and legacy_reference in legacy_witness.invokes(),
                f"{label}: 1.20.1 {entry['legacyWitness']} does not invoke the legacy method")
        require(native_witness is not None and native_reference in native_witness.invokes() and
                field_read in native_witness.field_accesses(),
                f"{label}: 1.21.1 {entry['nativeWitness']} does not read the field and invoke {entry['nativeCall']}")

    for entry in moves.get("removedStaticHelpers", []):
        owner = entry["owner"]
        label = f"{owner}.{entry['legacyMethod']}"
        name, descriptor = entry["legacyMethod"].split("(", 1)
        require(declares(args.legacy_jar, owner, "M", name, "(" + descriptor, static=True), f"{label}: 1.20.1 static method missing")
        require(not declares(args.jar, owner, "M", name, "(" + descriptor), f"{label}: still exists on 1.21.1")
        legacy_witness = method(args.legacy_jar, owner, entry["witness"])
        native_witness = method(args.jar, owner, entry["witness"])
        require(legacy_witness is not None and f"L{owner.replace('.', '/')};{entry['legacyMethod']}" in legacy_witness.invokes(),
                f"{label}: 1.20.1 {entry['witness']} does not invoke it")
        for invoked in entry["nativeWitnessInvokes"]:
            require(native_witness is not None and invoked in native_witness.invokes(),
                    f"{label}: 1.21.1 {entry['witness']} does not invoke {invoked}")


def parameter_reads(method: Method, parameter_index: int) -> list[str] | None:
    """The member references a parameter is only ever the receiver of (None when it escapes)."""
    slot = 0 if method.static else 1
    for parameter in parameter_types(method.descriptor)[:parameter_index]:
        slot += 2 if parameter in ("J", "D") else 1
    reads: list[str] = []
    for position, instruction in enumerate(method.code):
        store = STORE.match(instruction)
        if store and int(store.group(2) or store.group(3)) == slot:
            return None
        if LOAD.match(instruction) and load_slot(instruction) == slot:
            following = method.code[position + 1] if position + 1 < len(method.code) else ""
            reference = invoked_reference(following, method.owner)
            if reference is None or parameter_types(reference[reference.index("("):]):
                return None
            reads.append(reference)
    return reads


def member_references(data: bytes) -> set[str]:
    """`Lowner;name(desc)ret` of every method reference in one class file's constant pool."""
    count = struct.unpack_from(">H", data, 8)[0]
    pool: list = [None] * count
    pos, index = 10, 1
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, pos)[0]
            pool[index] = data[pos + 2:pos + 2 + length].decode("utf-8", "replace")
            pos += 2 + length
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag,) + struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1
    references = set()
    for entry in pool:
        if isinstance(entry, tuple) and entry[0] in (10, 11):
            owner = pool[pool[entry[1]][1]]
            name_and_type = pool[entry[2]]
            references.add(f"L{owner};{pool[name_and_type[1]]}{pool[name_and_type[2]]}")
    return references


def callers(jar: Path, javap: str, reference: str, prefixes: tuple[str, ...] = ("net/minecraft/", "net/neoforged/", "net/minecraftforge/")) -> list[str]:
    """`owner.name(desc)` of every method in [jar] whose own code invokes [reference]; an owner
    of `*` (`L*;name(desc)ret`) matches the member through any receiver type."""
    member = reference.split(";", 1)[1] if reference.startswith("L*;") else None

    def matches(candidate: str) -> bool:
        return candidate == reference if member is None else candidate.split(";", 1)[1] == member

    users = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if entry.endswith(".class") and entry.startswith(prefixes):
                if any(matches(it) for it in member_references(archive.read(entry))):
                    users.append(entry[:-6].replace("/", "."))
    methods = run_javap(javap, jar, sorted(users))
    return sorted(
        f"{owner}.{name}{descriptor}"
        for (owner, name, descriptor), method in methods.items()
        if any(matches(it) for it in method.invokes())
    )


def refs(args: argparse.Namespace) -> int:
    for caller in callers(args.jar, args.javap, args.reference):
        print(caller)
    return 0


def verify_library(args: argparse.Namespace) -> int:
    """Checks library-callback-parameters.json against every given library jar."""
    data = json.loads(args.facts.read_text(encoding="utf-8"))
    failures: list[str] = []

    def declarations(jar: Path, binary: str) -> list[str]:
        output = subprocess.run([args.javap, "-cp", str(jar), binary], capture_output=True, text=True,
                                encoding="utf-8", errors="replace").stdout
        return [line.strip() for line in output.splitlines()]

    for jar in args.library_jar:
        for builder in data["builders"]:
            builder_type = builder["type"]
            members = declarations(jar, builder_type)
            for name in builder["selfReturning"]:
                lines = [line for line in members if re.search(rf"\s{re.escape(name)}\(", line)]
                if not lines or any(not re.search(rf"\s{re.escape(builder_type)}<[^()]*>\s{re.escape(name)}\(", line)
                                    for line in lines):
                    failures.append(f"{jar.name}: {builder_type}#{name} does not always return the builder")
            for owner, methods in ((root["owner"], root["methods"]) for root in builder["roots"]):
                owner_members = declarations(jar, owner)
                for name in methods:
                    lines = [line for line in owner_members if re.search(rf"\s{re.escape(name)}\(", line)]
                    if not lines or any(f"{builder_type}<" not in line for line in lines):
                        failures.append(f"{jar.name}: {owner}#{name} does not always return {builder_type}")
            transform = [line for line in declarations(jar, "com.tterrag.registrate.builders.Builder")
                         if re.search(rf"\s{re.escape(builder['transform'])}\(", line)]
            if not transform or not all("S2 " in line and "NonNullFunction<S, S2>" in line for line in transform):
                failures.append(f"{jar.name}: Builder#{builder['transform']} is not NonNullFunction<S, S2> -> S2")
            for callback in builder["callbacks"]:
                lines = [line for line in members if re.search(rf"\s{re.escape(callback['method'])}\(", line)]
                if len(lines) != 1 or f"<{callback['parameterType']}," not in lines[0]:
                    failures.append(f"{jar.name}: {builder_type}#{callback['method']} callback is not {callback['parameterType']}")
                header = " ".join(declarations(jar, callback["parameterType"])[:3])
                if f"extends {callback['platformSupertype']}" not in header:
                    failures.append(f"{jar.name}: {callback['parameterType']} does not extend {callback['platformSupertype']}")
    for failure in failures:
        print(f"FAILED: {failure}")
    print(f"verified {len(data['builders'])} library builders on {len(args.library_jar)} jars: "
          f"{'ok' if not failures else f'{len(failures)} failures'}")
    return 1 if failures else 0


def invokes(args: argparse.Namespace) -> int:
    methods = run_javap(args.javap, args.jar, [args.owner])
    for (owner, name, descriptor), method in sorted(methods.items()):
        if name == args.method:
            print(f"{owner}.{name}{descriptor}")
            for reference in method.invokes():
                print(f"    {reference}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    generate_command = commands.add_parser("generate")
    generate_command.add_argument("--jar", type=Path, required=True)
    generate_command.add_argument("--javap", default="javap")
    generate_command.add_argument("--srg-table", type=Path, default=SRG_TABLE)
    generate_command.add_argument("--output-dir", type=Path, default=MAPPINGS)
    generate_command.set_defaults(handler=generate)

    verify_command = commands.add_parser("verify")
    verify_command.add_argument("--jar", type=Path, required=True)
    verify_command.add_argument("--legacy-jar", type=Path)
    verify_command.add_argument("--javap", default="javap")
    verify_command.add_argument("--moves", type=Path, default=MOVES)
    verify_command.set_defaults(handler=verify)

    invokes_command = commands.add_parser("invokes")
    invokes_command.add_argument("--jar", type=Path, required=True)
    invokes_command.add_argument("--javap", default="javap")
    invokes_command.add_argument("owner")
    invokes_command.add_argument("method")
    invokes_command.set_defaults(handler=invokes)

    library_command = commands.add_parser("verify-library")
    library_command.add_argument("--library-jar", type=Path, action="append", required=True)
    library_command.add_argument("--javap", default="javap")
    library_command.add_argument("--facts", type=Path, default=MAPPINGS / "library-callback-parameters.json")
    library_command.set_defaults(handler=verify_library)

    refs_command = commands.add_parser("refs")
    refs_command.add_argument("--jar", type=Path, required=True)
    refs_command.add_argument("--javap", default="javap")
    refs_command.add_argument("reference")
    refs_command.set_defaults(handler=refs)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
