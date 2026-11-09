"""Read-only final receipt audit; a build alone cannot satisfy these checks."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import zipfile


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def file_record(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), f"Missing or linked evidence: {path}")
    return dict(path=str(path.resolve()), bytes=path.stat().st_size, sha256=sha(path))


def verify_original_input(manifest_path):
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    require(manifest["algorithm"] == "sha256-relative-nul-size-nul-file-sha256-lf-v1",
            "Unknown original-input fingerprint algorithm")
    root = Path(manifest["root"])
    require(root.is_dir() and not root.is_symlink(), "Original input root is missing or linked")
    digest = hashlib.sha256()
    count = total = 0
    paths = list(root.rglob("*"))
    require(not any(p.is_symlink() for p in paths), "Original input contains a link")
    for path in sorted((p for p in paths if p.is_file()),
                       key=lambda p: p.relative_to(root).as_posix()):
        name = path.relative_to(root).as_posix()
        payload = path.read_bytes()
        digest.update(f"{name}\0{len(payload)}\0{sha_bytes(payload)}\n".encode("utf-8"))
        count += 1
        total += len(payload)
    require((count, total, digest.hexdigest()) ==
            (manifest["files"], manifest["bytes"], manifest["sha256"]),
            "Original Create input changed after the run's baseline")
    return dict(manifest=file_record(manifest_path), **manifest)


def frozen_code(workspace):
    roots = {}
    for name in ("src", "scripts", "gradle"):
        entries, digest = tree_manifest(workspace / name, ignored_parts={"__pycache__"})
        roots[name] = dict(files=len(entries), sha256=digest)
    private = None
    selected = os.environ.get("MODPORTER_PRIVATE_FRAMEWORK_ROOT", "").strip()
    if selected:
        private_root = Path(selected).expanduser().resolve(strict=True)
        install_path = private_root / ".minecraft-mod-testing/install.json"
        install = json.loads(install_path.read_text(encoding="utf-8"))
        managed = install["managed_files"]
        require(isinstance(managed, dict) and managed, "Private managed manifest is missing")
        for name, expected in managed.items():
            target = (private_root / name).resolve()
            require(target.is_relative_to(private_root), f"Private managed path escapes: {name}")
            require(sha(target) == expected, f"Selected private code drifted: {name}")
        private = dict(root=str(private_root), install=file_record(install_path), managed=managed)
    names = ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "mod-build.ps1",
             "mod-build.cmd", "gradlew", "gradlew.bat"]
    return dict(buildProfile="private" if private else "public", trees=roots,
                privateFramework=private, files={name: sha(workspace / name) for name in names})


def verify_freeze(freeze_path, workspace):
    freeze_path = Path(freeze_path).resolve()
    freeze = json.loads(freeze_path.read_text(encoding="utf-8"))
    require(freeze["schema"] == "modporter.create-strict-freeze.v1", "Unknown freeze schema")
    require(freeze_path == (workspace / "logs" / f"create-freeze-{freeze['runId']}.json").resolve(),
            "Create freeze path differs from its successful run id")
    require(freeze["code"] == frozen_code(workspace), "Code, harness, hooks or verifier changed after Create PASS")
    for evidence in freeze["evidence"]:
        verify_file_evidence(evidence, evidence["path"], "Frozen Create evidence")
    baseline = freeze["originalInput"]["manifest"]
    verify_file_evidence(baseline, workspace / "logs/create-original-input.json", "Original-input baseline")
    require(verify_original_input(baseline["path"]) == freeze["originalInput"],
            "Original-input manifest changed after Create PASS")
    acceptance = json.loads(Path(freeze["acceptancePath"]).read_text(encoding="utf-8"))
    require(acceptance["passed"] is True and acceptance["cases"] == ["create"] and
            acceptance["runId"] == freeze["runId"], "Freeze does not refer to a passing fresh Create acceptance")
    require(acceptance["totals"]["targets"] == acceptance["totals"]["passed"] == 1,
            "Frozen Create acceptance is incomplete")
    return file_record(freeze_path)


def verify_gametest(row, reports_root, run_root, workspace):
    case_id = row["id"]
    fixture_path = reports_root / f"{case_id}-gametest-world-fixture.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    require(fixture["schema"] == "modporter.gametest-world-isolation.v1" and
            fixture["caseId"] == case_id, f"GameTest fixture identity differs: {case_id}")
    universe = (reports_root / f"{case_id}-gametest-universe").resolve()
    world = universe / "world"
    output_dir = Path(row["outputDir"]).resolve()
    require(output_dir.is_relative_to(run_root), "GameTest output is outside its run")
    require(Path(fixture["universe"]).resolve() == universe and
            Path(fixture["world"]).resolve() == world, "GameTest fixture world path differs")
    server_world = Path(fixture["dedicatedServerWorld"]).resolve()
    require(server_world.is_relative_to(output_dir) and server_world.name == "world" and
            server_world.parent.name == "run", "Dedicated server world path differs")
    require(not world.is_relative_to(server_world) and not server_world.is_relative_to(universe),
            "GameTest world overlaps dedicated server world")
    require(fixture["worldExistedBeforeLaunch"] is False, "GameTest world was reused")
    script = Path(fixture["initScript"])
    source_script = workspace / "src/test/resources/benchmark-process/gametest_world_isolation.init.gradle"
    require(script.resolve() == (reports_root / f"gametest-world-{fixture['initSha256']}.init.gradle").resolve(),
            "GameTest init script is outside its content-addressed fixture")
    require(sha(script) == fixture["initSha256"] == sha(source_script), "GameTest init script drifted")
    note = row["checks"]["runGameTestServer"]["note"]
    for marker in (f"isolatedGameTestWorld={fixture['world']}", f"fixture={fixture_path}",
                   f"initSha256={fixture['initSha256']}"):
        require(marker in note, "GameTest telemetry is not bound to its fixture")
    log_path = reports_root / f"{case_id}-runGameTestServer.log"
    text = log_path.read_text(encoding="utf-8-sig")
    complete = [int(n) for n in re.findall(r"GameTestServer[^\r\n]*\b(\d+) GAME TESTS COMPLETE", text)]
    passed = [int(n) for n in re.findall(r"GameTestServer[^\r\n]*All (\d+) required tests passed", text)]
    require(len(complete) == len(passed) == 1 and 0 < passed[0] <= complete[0],
            f"GameTest completion is not proven: {case_id}")
    require(not re.search(r"\b[1-9]\d* (?:required |optional )?tests failed|\bTest .* failed|BUILD FAILED", text),
            f"GameTest failure remains: {case_id}")
    if case_id == "create":
        require(complete == passed == [60], "Create requires exactly 60/60 native GameTests")
    launchers = []
    for launcher in (output_dir / "build/minecraft-mod-testing/nested-launchers").glob("command-*.ps1"):
        source = launcher.read_text(encoding="utf-8")
        if "'runGameTestServer'" not in source:
            continue
        require(launcher.name == f"command-{sha(launcher)}.ps1", "GameTest launcher drifted")
        for value in ("runGameTestServer", "--init-script", str(script),
                      f"-Dmodporter.gametest.universe={fixture['universe']}"):
            literal = "'" + value.replace("'", "''") + "'"
            require(literal in source, f"GameTest launcher lacks {value}")
        launchers.append(file_record(launcher))
    require(len(launchers) == 1, "GameTest requires one content-addressed isolated-world launcher")
    # GameTestServer never saves level.dat; the session lock is what the server writes when it
    # takes the (previously nonexistent) isolated world, and the world must hold generated terrain
    require(any((world / "region").glob("*.mca")), f"GameTest isolated world generated no region: {case_id}")
    return dict(caseId=case_id, total=complete[0], requiredPassed=passed[0],
                fixture=file_record(fixture_path), initScript=file_record(script),
                log=file_record(log_path), world=file_record(world / "session.lock"),
                launcher=launchers[0])


def verify_create_coordinator(review, reports_root):
    coordinator_path = Path(review["receiptPath"])
    coordinator = json.loads(coordinator_path.read_text(encoding="utf-8"))
    require(coordinator["schema"] == "modporter.strict-review.client-world-coordinator.v1",
            "Create requires the native four-scenario coordinator")
    expected = ["non_quantized_motion", "packet_clamped_motion", "tracker_reentry", "station_map_native_roundtrip"]
    require(coordinator["requestedScenarios"] == coordinator["observedScenarios"] == expected,
            "Create native scenario order differs")
    require(coordinator["processStartCount"] == coordinator["worldLoadCount"] == 1 and
            coordinator["scenarioCount"] == 4, "Create native process/world/scenario counts differ")
    for key in ("failedCount", "cleanupFailureCount", "overlapFailureCount"):
        require(coordinator[key] == 0, f"Create native coordinator {key} is nonzero")
    for key in ("allScenariosPassed", "finalExitRequested", "clientBrowseCompleted"):
        require(coordinator[key] is True, f"Create native coordinator {key} is not complete")
    require(coordinator["status"] == "PASS", "Create native coordinator did not pass")
    children = []
    for child in coordinator["children"].values():
        path = Path(child["path"])
        require(path.resolve().is_relative_to(reports_root), "Create native child escaped selected reports")
        require(sha(path) == child["sha256"], "Create native child receipt changed")
        children.append(file_record(path))
    require(len(children) == 2, "Create native coordinator requires both child receipts")
    return children


def verify_file_evidence(evidence, expected_path, label):
    raw_path = Path(evidence["path"])
    require(not raw_path.is_symlink(), f"{label} is linked")
    path = raw_path.resolve()
    require(path == Path(expected_path).resolve(), f"{label} path differs")
    require(path.is_file(), f"{label} is missing")
    payload = path.read_bytes()
    require(len(payload) == evidence["bytes"], f"{label} size changed")
    require(sha_bytes(payload) == evidence["sha256"], f"{label} hash changed")
    return path, payload


def parse_powershell_literal(literal, label):
    require(re.fullmatch(r"'(?:[^']|'')*'", literal) is not None,
            f"{label} is not a PowerShell single-quoted literal")
    value = literal[1:-1].replace("''", "'")
    require("'" + value.replace("'", "''") + "'" == literal,
            f"{label} is not a canonical PowerShell literal")
    return value


def verify_run_data_launcher(lease, lease_evidence, output_dir, runtime_directory):
    command = lease["command"]
    require(command["arguments"] == [],
            "Create runData lease passed unexpected nested-launcher arguments")
    launcher_path = Path(command["executable"]).resolve()
    expected_directory = (
        output_dir / "build" / "minecraft-mod-testing" / "nested-launchers"
    ).resolve()
    require(launcher_path.parent == expected_directory,
            "Create runData nested launcher is outside the converted project")
    match = re.fullmatch(r"command-([0-9a-f]{64})\.ps1", launcher_path.name)
    require(match is not None,
            "Create runData nested launcher is not content addressed")
    launcher_path, launcher_payload = verify_file_evidence(
        lease_evidence["launcher"], launcher_path,
        "Create runData nested launcher"
    )
    require(sha_bytes(launcher_payload) == match.group(1),
            "Create runData nested launcher hash differs from its file name")
    text = launcher_payload.decode("utf-8")
    executable = re.search(
        r"(?m)^[ \t]*\$modporterExecutable = ('(?:[^'\r\n]|'')*')\r?$",
        text,
    )
    require(executable is not None,
            "Create runData nested launcher has no executable")
    require(Path(parse_powershell_literal(
        executable.group(1), "Create runData Gradle executable"
    )).resolve() == (output_dir / "gradlew.bat").resolve(),
            "Create runData nested launcher Gradle executable differs")
    argument_block = re.search(
        r"(?ms)^[ \t]*\$modporterArguments = @\(\r?\n(.*?)^[ \t]*\)\r?$",
        text,
    )
    require(argument_block is not None,
            "Create runData nested launcher has no argument block")
    lines = [
        line.strip()
        for line in argument_block.group(1).splitlines()
        if line.strip()
    ]
    arguments = []
    for index, line in enumerate(lines):
        needs_comma = index < len(lines) - 1
        require(line.endswith(",") == needs_comma,
                "Create runData nested launcher argument separators differ")
        literal = line[:-1] if needs_comma else line
        arguments.append(parse_powershell_literal(
            literal, "Create runData launcher argument"
        ))
    require(arguments == [
        "runData",
        "--no-daemon",
        "--stacktrace",
        "--console=plain",
        f"-PminecraftModTestingRuntimeDirectory={runtime_directory}",
    ], "Create runData nested launcher command differs")
    return launcher_path


def verify_data_generation(row, run_id, reports_root, run_root):
    check = row["checks"]["dataGeneration"]
    if row["id"] != "create":
        require(check["status"] == "NOT_RUN",
                f"Unexpected data-generation runtime for {row['id']}")
        require(check["receiptPath"] is None and check["receiptSha256"] is None,
                f"Unexpected data-generation receipt for {row['id']}")
        return None

    require(check["status"] == "PASS", "Create data generation did not pass")
    output_dir = Path(row["outputDir"]).resolve()
    require(output_dir.is_relative_to(run_root),
            "Create converted output is outside the selected run")
    raw_receipt_path = Path(check["receiptPath"])
    require(not raw_receipt_path.is_symlink(),
            "Create data-generation receipt is linked")
    receipt_path = raw_receipt_path.resolve()
    require(receipt_path == (reports_root / "create-data-generation-receipt.json").resolve(),
            "Create data-generation receipt path differs")
    require(receipt_path.is_file(),
            "Create data-generation receipt is missing")
    require(sha(receipt_path) == check["receiptSha256"],
            "Create data-generation receipt hash changed")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    require(receipt["schema"] == "modporter.create-data-generation.v1",
            "Unexpected Create data-generation receipt schema")
    require(receipt["runId"] == run_id, "Create data-generation receipt run id differs")
    require(receipt["caseId"] == "create", "Create data-generation receipt case differs")
    require(receipt["ruleId"] == "struct-create-copper-native-data-maps",
            "Create data-generation receipt rule differs")
    require(Path(receipt["buildRoot"]).resolve() == output_dir,
            "Create data-generation build root differs")
    require(Path(receipt["modDir"]).resolve() == output_dir,
            "Create data-generation mod directory differs")

    logs = receipt["logs"]
    require(set(logs) == {"runData", "processResources"},
            "Create data-generation receipt log set differs")
    for name, expected_name in (
            ("runData", "create-runData.log"),
            ("processResources", "create-processResources.log")):
        _, payload = verify_file_evidence(
            logs[name], reports_root / expected_name, f"Create {name} log"
        )
        text = payload.decode("utf-8-sig")
        require("BUILD SUCCESSFUL" in text and "BUILD FAILED" not in text,
                f"Create {name} log lacks a clean success marker")

    lease_evidence = receipt["runtimeLease"]
    _, lease_payload = verify_file_evidence(
        lease_evidence,
        reports_root / "create-runData-session.json",
        "Create runData lease receipt"
    )
    lease = json.loads(lease_payload.decode("utf-8-sig"))
    runtime_directory = (output_dir / "run").resolve()
    require(lease["schemaVersion"] == 1, "Create runData lease schema differs")
    require(lease["sessionId"] == lease_evidence["sessionId"] and lease["sessionId"],
            "Create runData lease session id differs")
    require(Path(lease["projectRoot"]).resolve() == output_dir,
            "Create runData lease project root differs")
    require(Path(lease["runtimeDirectory"]).resolve() == runtime_directory,
            "Create runData lease runtime directory differs")
    require(Path(lease_evidence["runtimeDirectory"]).resolve() == runtime_directory,
            "Create data-generation receipt runtime directory differs")
    require(lease["runtimeKind"] == "server",
            "Create runData lease did not use server runtime inventory")
    require(lease["processStartCount"] == 1,
            "Create runData lease process count differs")
    require(lease["processInventoryChecked"] is True,
            "Create runData lease skipped process inventory")
    require(lease["activeRuntimeProcesses"] == [],
            "Create runData lease found an overlapping runtime")
    require(lease["exitCode"] == 0 and lease["passed"] is True and not lease["failure"],
            "Create runData lease did not pass cleanly")
    verify_run_data_launcher(
        lease, lease_evidence, output_dir, runtime_directory
    )

    expected = {"oxidizables.json": 18, "waxables.json": 24}
    files = receipt["files"]
    require({entry["name"] for entry in files} == set(expected),
            "Create data-generation receipt file set differs")
    require(len(files) == len(expected), "Duplicate Create data-generation file evidence")
    for entry in files:
        name = entry["name"]
        require(entry["valueCount"] == expected[name],
                f"Create {name} receipt value count differs")
        loaded = {}
        for role in ("generated", "packaged"):
            evidence = entry[role]
            relative = {
                "generated": f"src/generated/resources/data/neoforge/data_maps/block/{name}",
                "packaged": f"build/resources/main/data/neoforge/data_maps/block/{name}",
            }[role]
            _, payload = verify_file_evidence(
                evidence,
                output_dir / relative,
                f"Create {role} data map {name}"
            )
            values = json.loads(payload.decode("utf-8"))["values"]
            require(isinstance(values, dict) and len(values) == expected[name],
                    f"Create {role} data map values changed: {name}")
            loaded[role] = payload
        require(loaded["generated"] == loaded["packaged"],
                f"Create generated and packaged data maps differ: {name}")

    return dict(path=str(receipt_path), sha256=sha(receipt_path))


def tree_manifest(root, ignored_parts=frozenset()):
    root = Path(root).resolve()
    require(root.is_dir(), f"Bound tree directory is missing: {root}")
    entries = {}
    for path in sorted(root.rglob("*")):
        if ignored_parts.intersection(path.relative_to(root).parts):
            continue
        if not path.is_file():
            continue
        require(not path.is_symlink(), f"Bound tree entry is a symbolic link: {path}")
        name = path.relative_to(root).as_posix()
        entries[name] = sha(path)
    require(entries, f"Bound tree directory is empty: {root}")
    digest = hashlib.sha256()
    for name, file_sha in entries.items():
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(file_sha.encode("ascii"))
        digest.update(b"\n")
    return entries, digest.hexdigest()


def bind_candidate_payload(candidate, compiled_entries):
    candidate = Path(candidate)
    require(candidate.is_file(), f"Candidate inventory is missing: {candidate}")
    with zipfile.ZipFile(candidate) as archive:
        names = [item.filename.replace("\\", "/") for item in archive.infolist()
                 if not item.is_dir()]
        counts = Counter(names)
        for name, expected_sha in compiled_entries.items():
            require(counts[name] == 1,
                    f"Candidate must contain compiled main entry exactly once: {name}")
            require(sha_bytes(archive.read(name)) == expected_sha,
                    f"Candidate compiled main payload differs: {name}")
    return len(compiled_entries)


def main():
    if sys.argv[1:2] == ["--capture-inputs"]:
        parser = argparse.ArgumentParser()
        parser.add_argument("--capture-inputs", required=True, type=Path)
        parser.add_argument("--workspace", required=True, type=Path)
        parser.add_argument("--original-input-manifest", required=True, type=Path)
        parser.add_argument("--run-id", required=True)
        args = parser.parse_args()
        require(not args.capture_inputs.exists(), "Refusing to overwrite run input bindings")
        bindings = dict(runId=args.run_id, code=frozen_code(args.workspace.resolve()),
                        originalInput=verify_original_input(args.original_input_manifest))
        args.capture_inputs.write_text(json.dumps(bindings, indent=2) + "\n", encoding="utf-8")
        print("Create run inputs captured and original input verified")
        return
    if sys.argv[1:2] == ["--check-freeze"]:
        parser = argparse.ArgumentParser()
        parser.add_argument("--check-freeze", required=True, type=Path)
        parser.add_argument("--workspace", required=True, type=Path)
        args = parser.parse_args()
        evidence = verify_freeze(args.check_freeze, args.workspace.resolve())
        print(json.dumps(dict(passed=True, createFreeze=evidence)))
        return
    parser = argparse.ArgumentParser()
    parser.add_argument("telemetry", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--cases", required=True)
    parser.add_argument("--build-log", required=True, type=Path)
    parser.add_argument("--source-snapshot", required=True, type=Path)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--compiled-main", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--mode", choices=("create", "all11"), default="all11")
    parser.add_argument("--original-input-manifest", type=Path)
    parser.add_argument("--create-freeze", type=Path)
    parser.add_argument("--run-bindings", type=Path)
    args = parser.parse_args()
    require(not args.receipt.exists(), "Refusing to overwrite an acceptance receipt")
    expected = args.cases.split(",")
    canonical = [
        "constructionwand", "instantworldmirror", "hotbath", "showercore",
        "sakura", "twilightforest", "aether", "beyondtheveil",
        "starmeowcraft", "create", "worldedit",
    ]
    require(expected == (["create"] if args.mode == "create" else canonical),
            "Audit target set differs from its selected gate")
    require(len(expected) == len(set(expected)), "Duplicate requested target")
    workspace = args.workspace.resolve()
    require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}", args.run_id), "Invalid run id")
    freeze_evidence = None
    original_input = None
    freeze_path = workspace / "logs" / f"create-freeze-{args.run_id}.json"
    if args.mode == "all11":
        require(args.create_freeze is not None, "Final all11 requires an accepted Create freeze")
        freeze_evidence = verify_freeze(args.create_freeze, workspace)
    else:
        require(args.original_input_manifest is not None, "Create gate requires the original-input baseline")
        require(not freeze_path.exists(), "Refusing to overwrite a Create freeze")
        original_input = verify_original_input(args.original_input_manifest)
        require(args.run_bindings is not None and args.run_bindings.resolve() ==
                (workspace / "logs" / f"create-strict-{args.run_id}-inputs.json").resolve(),
                "Create requires its prelaunch input bindings")
        bindings = json.loads(args.run_bindings.read_text(encoding="utf-8"))
        require(bindings == dict(runId=args.run_id, code=frozen_code(workspace), originalInput=original_input),
                "Create code, verifier or original input changed during the run")
    expected_output = f"build/real-mod-benchmark/runs/{args.run_id}"
    run_root = (workspace / expected_output).resolve()
    reports_root = (run_root / "reports").resolve()
    prefix = "create-strict" if args.mode == "create" else "all11-final"
    expected_build_log = (workspace / "logs" / f"{prefix}-{args.run_id}.log").resolve()
    expected_snapshot = Path(str(expected_build_log) + ".source.zip")
    expected_candidate = (run_root / "evidence" / "modporter-tool-all.jar").resolve()
    expected_compiled_main = (workspace / "build/classes/kotlin/main").resolve()
    expected_receipt = (workspace / "logs" / f"{prefix}-{args.run_id}-acceptance.json").resolve()
    require(args.build_log.resolve() == expected_build_log,
            "Build log path differs from selected run id")
    require(args.source_snapshot.resolve() == expected_snapshot,
            "Source snapshot path differs from selected run id")
    require(args.candidate.resolve() == expected_candidate,
            "Candidate inventory path differs from selected run id")
    require(args.compiled_main.resolve() == expected_compiled_main,
            "Compiled main path differs from the actual main source-set output")
    require(args.receipt.resolve() == expected_receipt,
            "Acceptance receipt path differs from selected run id")
    telemetry = json.loads(args.telemetry.read_text(encoding="utf-8-sig"))
    require(telemetry["schema"] == "modporter.real-mod-benchmark.telemetry.v1", "Unexpected schema")
    options = telemetry["options"]
    require(options["runId"] == args.run_id, "Telemetry run id differs")
    require(options["outputRoot"].replace("\\", "/") == expected_output,
            "Telemetry output root differs")
    require(args.telemetry.resolve().parent == reports_root,
            "Telemetry is outside the selected run reports")
    for key in ("strict", "strictRuntime", "handsOff", "compile", "runServer",
                "runGameTestServer", "runClient", "runClientWorld", "logClean", "keepWork"):
        require(options[key] is True, f"Required strict option disabled: {key}")
    require(options["updateReadme"] is False, "Final run must not update README")
    require(options["requestedCases"] == expected, "Requested case order differs")
    require(options["resolvedCases"] == expected, "Resolved case order differs")
    totals = telemetry["totals"]
    require(totals["targets"] == len(expected) == totals["passed"], "Incomplete passing target count")
    for key in ("failed", "skipped", "errors", "skippedTransforms", "low", "unresolvedReviewSignalCount"):
        require(totals[key] == 0, f"Nonzero final counter: {key}")
    require(totals["rawReviewSignalCount"] == totals["validatedReviewSignalCount"], "Unvalidated raw review signals")
    require([row["id"] for row in telemetry["outcomes"]] == expected, "Outcome order differs")
    native_receipts = []
    data_generation_receipts = []
    gametest_evidence = []
    for row in telemetry["outcomes"]:
        require(row["status"] == "PASS", f"Target failed: {row['id']}")
        require(row["required"] is True, f"Target is not required: {row['id']}")
        require(row["manualInterventionRequired"] is False, f"Manual intervention: {row['id']}")
        for counter in ("errors", "skippedTransforms", "low", "unresolvedReviewSignalCount"):
            require(row[counter] == 0, f"Unresolved {counter}: {row['id']}")
        for check in ("compileJava", "runServer", "runGameTestServer", "runClient", "runClientWorld"):
            require(row["checks"][check]["status"] == "PASS", f"Missing actual gate: {row['id']} {check}")
        gametest_evidence.append(verify_gametest(row, reports_root, run_root, workspace))
        data_generation = verify_data_generation(
            row, args.run_id, reports_root, run_root
        )
        if data_generation is not None:
            data_generation_receipts.append(data_generation)
        review = row["strictReviewValidation"]
        if row["rawReviewSignalCount"] or review["state"] == "VALIDATED" or row["id"] == "create":
            require(review["state"] == "VALIDATED", f"Native review not validated: {row['id']}")
            require(review["validated"] == row["rawReviewSignalCount"], "Native review count differs")
            for path_key, hash_key in (("receiptPath", "receiptSha256"),
                                      ("wrapperReceiptPath", "wrapperReceiptSha256")):
                path = Path(review[path_key])
                require(path.resolve().is_relative_to(reports_root),
                        f"Native receipt is outside selected run: {path}")
                require(path.is_file(), f"Missing native receipt: {path}")
                require(sha(path) == review[hash_key], f"Native receipt hash changed: {path}")
                native_receipts.append(dict(path=str(path), sha256=sha(path)))
            client_log = reports_root / f"{row['id']}-runClientWorld.log"
            require(sha(client_log) == review["runtimeLogSha256"], "Validated client-world log changed")
            native_receipts.append(file_record(client_log))
            if row["id"] == "create":
                require(row["source"] == "env:MODPORTER_BENCHMARK_SOURCE_CREATE", "Create did not use the original input override")
                native_receipts.extend(verify_create_coordinator(review, reports_root))
    build_log = args.build_log.read_text(encoding="utf-8-sig")
    require("BUILD SUCCESSFUL" in build_log, "Missing successful Gradle terminal marker")
    require("BUILD FAILED" not in build_log, "Failed Gradle terminal marker remains")
    for task in ("compileKotlin", "shadowJar", "strictRealModBenchmark"):
        require(f"> Task :{task}" in build_log, f"Final build did not execute {task}")
        require(f"> Task :{task} UP-TO-DATE" not in build_log,
                f"Final build reused stale {task} output")
    profile = frozen_code(workspace)["buildProfile"]
    require(build_log.rstrip().endswith(f"modporter_build=passed profile={profile} command=gradle"),
            "Missing final public-build completion marker for the selected profile")
    bound_source = set()
    with zipfile.ZipFile(args.source_snapshot) as archive:
        names = {item.filename.replace("\\", "/") for item in archive.infolist()}
        for required in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
            require(required in names, f"Frozen source archive lacks {required}")
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = item.filename.replace("\\", "/")
            target = (workspace / name).resolve()
            require(target.is_relative_to(workspace), f"Snapshot path escapes: {name}")
            require(target.is_file(), f"Snapshot input disappeared: {name}")
            require(archive.read(item) == target.read_bytes(), f"Source changed after run: {name}")
            bound_source.add(name)
    actual_source = {p.relative_to(workspace).as_posix() for p in (workspace / "src").rglob("*") if p.is_file()}
    require(actual_source <= bound_source, "New source files were added after the frozen run")
    compiled_entries, compiled_tree_sha = tree_manifest(args.compiled_main)
    require("com/modporter/cli/MainKt.class" in compiled_entries,
            "Compiled main output lacks the ModPorter entry point")
    candidate_entry_count = bind_candidate_payload(args.candidate, compiled_entries)
    result = dict(schemaVersion=1, passed=True, cases=expected, totals=totals,
                  runId=options["runId"], telemetry=dict(path=str(args.telemetry), sha256=sha(args.telemetry)),
                  buildLog=dict(path=str(args.build_log), sha256=sha(args.build_log)),
                  sourceSnapshot=dict(path=str(args.source_snapshot), sha256=sha(args.source_snapshot)),
                  compiledMain=dict(path=str(args.compiled_main), sha256=compiled_tree_sha,
                                    entryCount=len(compiled_entries),
                                    role="actual main classes used by strictRealModBenchmark"),
                  candidateInventory=dict(
                      path=str(args.candidate),
                      sha256=sha(args.candidate),
                      role="inventory artifact; strictRealModBenchmark runs source-set classes",
                      projectPayloadBinding=dict(
                          compiledMainSha256=compiled_tree_sha,
                          matchedEntries=candidate_entry_count
                      )
                  ),
                  nativeReceipts=native_receipts,
                  dataGenerationReceipts=data_generation_receipts,
                  gameTestEvidence=gametest_evidence,
                  originalInput=original_input,
                  runBindings=file_record(args.run_bindings) if args.mode == "create" else None,
                  createFreeze=freeze_evidence)
    args.receipt.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if args.mode == "create":
        freeze = dict(schema="modporter.create-strict-freeze.v1", runId=args.run_id,
                      acceptancePath=str(args.receipt.resolve()), code=frozen_code(workspace),
                      originalInput=original_input,
                      evidence=[file_record(p) for p in (
                          args.receipt, args.telemetry, args.build_log, args.source_snapshot, args.candidate,
                          args.run_bindings)])
        freeze_path.write_text(json.dumps(freeze, indent=2) + "\n", encoding="utf-8")
        print(f"Create freeze: {freeze_path}")
    print(json.dumps(dict(passed=True, cases=expected, totals=totals), indent=2))


if __name__ == "__main__":
    main()
