#!/usr/bin/env python3
"""Compare identical compilation, metadata or exec work under PRoot, chroot and guest.

Preparation and result validation are outside the guest-side GNU time interval.
PRoot uses the caller's Termux identity; only chroot explicitly invokes su.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import shutil
import statistics
import subprocess
import tarfile
import time
import tomllib
import uuid
from benchmark_cpu import observe_cpu

FILE_WORKLOADS = ("file-stat", "file-open", "file-cycle")
LOOP_WORKLOADS = (*FILE_WORKLOADS, "poll", "epoll", "memory")


def loop_measurement(output, workload, iterations, blocks):
    expected = blocks if workload in FILE_WORKLOADS else 1
    records = [json.loads(line.removeprefix("READINESS_BENCH "))
               for line in output.splitlines() if line.startswith("READINESS_BENCH ")]
    if (len(records) != expected or [row["block"] for row in records] != list(range(expected))
            or any(row["workload"] != workload or row["iterations"] != iterations
                   or not math.isfinite(row["seconds"]) or row["seconds"] <= 0 for row in records)):
        raise ValueError("Missing or inconsistent loop measurements")
    return {"blocks": records, "workSeconds": sum(row["seconds"] for row in records),
            "firstBlockSeconds": records[0]["seconds"],
            "tailBlockSeconds": statistics.median(row["seconds"] for row in records[len(records) // 2:])}


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def affinity_measurement(output, expected_mask=None):
    records = re.findall(r"^MD_AFFINITY (before|after) ([0-9a-fA-F,]+)$", output, re.M)
    if [phase for phase, _ in records] != ["before", "after"]:
        raise ValueError("Missing workload affinity evidence")
    masks = [int(value.replace(",", ""), 16) for _, value in records]
    if not masks[0] or masks[0] != masks[1] or (expected_mask is not None and masks[0] != expected_mask):
        raise ValueError("Workload CPU affinity changed or differs from requested mask")
    return {phase: value for phase, value in records}


def power_sources(snapshot):
    values = dict(re.findall(r"^\s*(AC|USB|Wireless|Dock) powered:\s*(true|false)\s*$", snapshot, re.M))
    if not values:
        raise RuntimeError("Battery service did not report power sources")
    return values


def priority_measurement(output):
    records = re.findall(r"^MD_NICE (before|after) (-?\d+)$", output, re.M)
    if [phase for phase, _ in records] != ["before", "after"] or any(int(value) for _, value in records):
        raise ValueError("Workloads must use the same normal priority (nice=0)")
    return {phase: int(value) for phase, value in records}


def performance_target(summary):
    if not all(mode in summary for mode in ("chroot", "proot", "guest")):
        return None
    native, proot, guest = (summary[mode]["medianSeconds"] for mode in ("chroot", "proot", "guest"))
    if any(not math.isfinite(value) or value <= 0 for value in (native, proot, guest)):
        raise ValueError("Performance targets require positive finite measurements")
    target = math.sqrt(native) * math.sqrt(proot)
    return {"formula": "sqrt(chroot * proot)", "seconds": target,
            "guestOverTarget": guest / target, "met": guest <= target}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path, help="Prepared sysroot and manifest.json (Debian or Alpine)")
    parser.add_argument("--sqlite", type=Path, required=True, help="SQLite amalgamation directory")
    parser.add_argument("--runtime", required=True, help="Installed shell-visible guest bundle")
    parser.add_argument("--baseline-runtime", help="Explicit guest reference bundle for interleaved A/B runs")
    parser.add_argument("--workload", choices=("compile", "metadata", "spawn", *LOOP_WORKLOADS), default="compile")
    parser.add_argument("--jobs", type=int, default=1, help="Identical make parallelism in every mode")
    parser.add_argument("--cpu-mask", type=lambda value: int(value, 16),
                        help="Common hexadecimal host CPU mask, inherited by each runtime and its children")
    parser.add_argument("--iterations", type=int, default=1024, help="Operations per readiness/filesystem block")
    parser.add_argument("--file-blocks", type=int, default=8, help="Consecutive blocks, retaining first and tail")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--guest-store", help="Reuse an explicitly prepared identical benchmark store")
    parser.add_argument("--baseline-store", help="Identical reference fixture when the store format differs")
    parser.add_argument("--guest-statistics", action="store_true", help="Opt-in aggregate interception counters")
    parser.add_argument("--preopen-proc", action="store_true",
                        help="Read proc status in the parent before timing, retaining inherited FD selectors")
    parser.add_argument("--modes", nargs="+", choices=("chroot", "proot", "guest", "guest-baseline"),
                        default=["chroot", "proot", "guest"])
    args = parser.parse_args()
    if args.cpu_mask is not None and args.cpu_mask <= 0:
        parser.error("--cpu-mask must be positive")
    if args.rounds < 1 or args.warmups < 0 or len(set(args.modes)) != len(args.modes):
        parser.error("positive rounds, nonnegative warmups and distinct modes required")
    if not 1 <= args.jobs <= 8 or not 1 <= args.iterations <= 10000000 or not 1 <= args.file_blocks <= 64:
        parser.error("jobs must be 1-8, iterations 1-10000000 and file-blocks 1-64")
    if "guest-baseline" in args.modes and not args.baseline_runtime:
        parser.error("guest-baseline requires --baseline-runtime")
    if args.baseline_store and "guest-baseline" not in args.modes:
        parser.error("--baseline-store requires guest-baseline")
    repo = Path(__file__).resolve().parents[2]
    work = args.work.resolve()
    root = work / "sysroot"
    fixtures = Path(__file__).resolve().parent / "fixtures"
    bench = root / "bench"
    bench.mkdir(exist_ok=True)
    bench_context = subprocess.check_output(["ls", "-Zd", str(bench)], text=True).split()[0]
    for name in ("sqlite3.c", "shell.c", "sqlite3.h", "sqlite3ext.h"):
        shutil.copyfile(args.sqlite / name, bench / name)
    shutil.copyfile(fixtures / "compile-benchmark.mk", bench / "Makefile")
    shutil.copyfile(fixtures / "compile-benchmark.sh", bench / "run.sh")
    shutil.copyfile(fixtures / "compile-library-check.c", bench / "verify-library.c")
    shutil.copyfile(fixtures / "runtime-workloads.c", bench / "runtime-workloads.c")
    shutil.copyfile(fixtures / "readiness-benchmark.c", bench / "readiness-benchmark.c")
    for directory in ("dev", "proc", "sys", "tmp"):
        (root / directory).mkdir(exist_ok=True)
    # Temporary files are shared only by sequential runs, never simultaneous builds.
    (root / "tmp").chmod(0o1777)
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "),
                              timeout=400, request_timeout=360)
    state = client.call("get_state")
    if state["shell"]["uid"] != 2000:
        raise RuntimeError("Guest comparison requires the selected shell UID 2000")
    tag = uuid.uuid4().hex
    remote = "/data/local/tmp/md-compile-benchmark-" + tag
    output = work / ("compile-results-" + tag + ".json")
    report = {"id": tag, "startedUtc": datetime.now(timezone.utc).isoformat(),
              "device": state["device"], "app": state["app"],
              "runtime": args.runtime, "baselineRuntime": args.baseline_runtime,
              "workload": args.workload, "remote": remote, "samples": [], "probes": {},
              "manifest": json.loads((work / "manifest.json").read_text()),
              "sourceHashes": {name: digest(bench / name) for name in
                               ("sqlite3.c", "shell.c", "sqlite3.h", "sqlite3ext.h", "Makefile", "run.sh", "verify-library.c", "runtime-workloads.c", "readiness-benchmark.c")},
              "git": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
              "policy": {"jobs": args.jobs, "iterations": args.iterations, "fileBlocks": args.file_blocks,
                         "compilerCache": False, "dropPageCache": False,
                         "guestStatistics": args.guest_statistics,
                         "preopenProc": args.preopen_proc,
                         "affinityObservation": "sched_getaffinity/getpriority in an untimed child",
                         "warmupsPerMode": args.warmups, "rounds": args.rounds,
                         "affinityPinned": args.cpu_mask is not None,
                         "cpuMask": hex(args.cpu_mask) if args.cpu_mask else None,
                         "cpuGovernorChanged": False,
                         "nice": 0, "hostNice": os.getpriority(os.PRIO_PROCESS, 0),
                         "timing": "monotonic loop time for microbenchmarks; GNU time around make for compilation",
                         "identities": {"proot": os.getuid(), "chroot": 0, "guest": 2000, "guest-baseline": 2000}},
              "completed": False}
    guest_store = args.guest_store or remote + "/store"
    report["guestStore"] = guest_store
    report["baselineStore"] = args.baseline_store or guest_store
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def save():
        output.write_text(json.dumps(report, indent=2) + "\n")

    def shell(command):
        result = client.call("console.execute", {"sessionId": console, "command": command})
        if result["exitCode"]:
            raise RuntimeError(result)
        return result["output"]

    def local(command, env=None, normal_priority=False):
        if normal_priority:
            current = os.getpriority(os.PRIO_PROCESS, 0)
            if current > 0:
                raise RuntimeError("Cannot normalize priority without raising it; run the benchmark at nice <= 0")
            command = ["nice", "-n", str(-current), *command]
        # EVENT_WAIT: child completion; timeout fails the sample, never means readiness.
        result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=300)
        if result.returncode:
            raise RuntimeError({"command": command, "exitCode": result.returncode, "output": result.stdout})
        return result.stdout

    def launch(mode, action):
        guest = ["/bin/sh", "/bench/run.sh", action, str(args.jobs), str(args.iterations),
                 str(args.file_blocks), str(int(args.preopen_proc))]
        def pinned(command, taskset):
            return ([taskset, format(args.cpu_mask, "x")] if args.cpu_mask else []) + command
        if mode in ("guest", "guest-baseline"):
            runtime = args.runtime if mode == "guest" else args.baseline_runtime
            store = guest_store if mode == "guest" else report["baselineStore"]
            return shell(shlex.join(pinned([runtime + "/libmagicdesk_guest_run.so",
                                    *(["--statistics"] if args.guest_statistics else []),
                                    "--deadline-seconds", "300", "--store", store, "--user", "0:0", "--"] + guest,
                                          "/system/bin/taskset")))
        if mode == "proot":
            env = os.environ.copy()
            for name in ("LD_PRELOAD", "LD_LIBRARY_PATH", "PROOT_NO_SECCOMP"):
                env.pop(name, None)
            return local(pinned(["proot", "--kill-on-exit", "-r", str(root), "-b", "/dev", "-b", "/proc",
                          "-b", "/sys", "-w", "/bench", "/usr/bin/env", "-i", *guest], "taskset"), env, normal_priority=True)
        # All bind mounts live in a private namespace and disappear with this command.
        body = "\n".join(["set -eu", "mount -o rprivate none /", *[
            shlex.join(["mount", "--bind", "/" + name, str(root / name)]) for name in ("dev", "proc", "sys")],
            "exec " + shlex.join(pinned(["chroot", str(root), "/usr/bin/env", "-i", *guest], "/system/bin/taskset"))])
        try:
            return local(["su", "-c", shlex.join(["/system/bin/unshare", "-m", "/system/bin/sh", "-c", body])], normal_priority=True)
        finally:
            # Native chroot creates root-owned files with its SELinux label. Restore
            # only the shared benchmark tree, outside timing, for the next PRoot run.
            local(["su", "-c", shlex.join(["chown", "-hR", f"{os.getuid()}:{os.getgid()}", str(bench)])
                   + "\n" + shlex.join(["chcon", "-hR", bench_context, str(bench)])])

    try:
        report["hostIdentity"] = local(["id"])
        report["prootVersion"] = local(["proot", "--version"])
        report["prootPackage"] = local(["dpkg-query", "-W", "proot"])
        report["kernel"] = shell("uname -a")
        report["shellIdentity"] = shell("id")
        if any(mode.startswith("guest") for mode in args.modes) and not args.guest_store:
            archive = work / ("compile-root-" + tag + ".tar")
            def guest_owner(info):
                info.uid = info.gid = 0
                info.uname = info.gname = "root"
                return info
            with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as stream:
                stream.add(root, arcname=".", filter=guest_owner)
            shell(shlex.join(["mkdir", "-p", remote]))
            transport.upload(client, str(archive), remote + "/root.tar")
            image = args.runtime + "/libmagicdesk_guest_image.so"
            report["import"] = shell(shlex.join([image, "rootfs", remote + "/root.tar", remote + "/image"]))
            report["create"] = shell(shlex.join([image, "create", remote + "/image", guest_store]))
            report["importArchiveSha256"] = digest(archive)
        if "guest" in args.modes:
            report["runtimeHashes"] = shell("sha256sum " + shlex.quote(args.runtime) + "/libmagicdesk_guest_*.so")
        if "guest-baseline" in args.modes:
            report["baselineRuntimeHashes"] = shell("sha256sum " + shlex.quote(args.baseline_runtime) + "/libmagicdesk_guest_*.so")
        for mode in args.modes:
            # The evidence helper uses native scheduler calls, never a proc FD
            # whose persistent selectors could affect the measured descendants.
            launch("proot" if mode == "chroot" else mode, "prepare")
            report["probes"][mode] = launch(mode, "probe")
            affinity_measurement(report["probes"][mode], args.cpu_mask)
            priority_measurement(report["probes"][mode])
            compiler = re.findall(r"^MD_(?:COMPILER|CC1) ([0-9a-f]{64})  ", report["probes"][mode], re.M)
            if len(compiler) != 2 or compiler != report.setdefault("compilerHashes", compiler):
                raise RuntimeError("Compiler mismatch: " + mode)
            for name in report["sourceHashes"]:
                if report["sourceHashes"][name] + "  " + name not in report["probes"][mode]:
                    raise RuntimeError("Benchmark source mismatch: " + mode + "/" + name)
            print("Probe passed:", mode, flush=True)
            save()
        for round_index in range(-args.warmups, args.rounds):
            shift = max(0, round_index) % len(args.modes)
            order = args.modes[shift:] + args.modes[:shift]
            for mode in order:
                phase = "warmup" if round_index < 0 else "measured"
                print("Starting", phase, round_index, mode, flush=True)
                started = time.monotonic()
                sample = {"mode": mode, "round": round_index, "phase": phase,
                          "startedUtc": datetime.now(timezone.utc).isoformat()}
                report["samples"].append(sample)
                sample["thermalBefore"] = shell("cat /sys/class/thermal/thermal_zone0/temp")
                sample["batteryBefore"] = shell("dumpsys battery")
                sample["loadBefore"] = shell("cat /proc/loadavg")
                report.setdefault("powerSources", power_sources(sample["batteryBefore"]))
                save()
                with observe_cpu(shell) as cpu:
                    sample['cpu'] = cpu
                    text = launch(mode, "build" if args.workload == "compile" else args.workload)
                sample["outerSeconds"] = time.monotonic() - started
                sample["thermalAfter"] = shell("cat /sys/class/thermal/thermal_zone0/temp")
                sample["batteryAfter"] = shell("dumpsys battery")
                sample["loadAfter"] = shell("cat /proc/loadavg")
                sample["output"] = text
                sample["affinity"] = affinity_measurement(text, args.cpu_mask)
                sample["priority"] = priority_measurement(text)
                sample["finishedUtc"] = datetime.now(timezone.utc).isoformat()
                if any(power_sources(sample[key]) != report["powerSources"]
                       for key in ("batteryBefore", "batteryAfter")):
                    raise RuntimeError("Power source changed; keep this report separate from a stable series")
                if args.workload in LOOP_WORKLOADS:
                    sample.update(loop_measurement(text, args.workload, args.iterations, args.file_blocks))
                else:
                    timing = re.search(r"^MD_TIMING ([0-9.]+) ([0-9.]+) ([0-9.]+) (\d+) (\d+) (\d+)$", text, re.M)
                    expected = ("MD_SQL_OK 5000050000", "MD_LIBRARY_OK 42") if args.workload == "compile" else (
                        "MD_WORKLOAD_OK " + args.workload + (" 4096" if args.workload == "metadata" else " 128"),)
                    if not timing or not all(marker in text for marker in expected):
                        raise RuntimeError("Missing timing or workload validation: " + text)
                    sample.update(zip(("wallSeconds", "userSeconds", "systemSeconds", "maxRssKiB",
                                       "involuntarySwitches", "voluntarySwitches"), map(float, timing.groups())))
                    sample["workSeconds"] = sample["wallSeconds"]
                    if args.workload != "compile":
                        elapsed = re.search(r"^MD_ELAPSED workload=" + args.workload + r" watch=none ns=(\d+)$", text, re.M)
                        if not elapsed:
                            raise RuntimeError("Missing monotonic workload timing: " + text)
                        sample["workSeconds"] = int(elapsed.group(1)) / 1e9
                sample["artifacts"] = dict((name, value) for value, name in
                                           re.findall(r"^([0-9a-f]{64})  (sqlite3|libsqlite3.so|shell.o|sqlite3.o|sqlite3.pic.o)$", text, re.M))
                if args.workload == "compile" and len(sample["artifacts"]) != 5:
                    raise RuntimeError("Missing compiled artifact hashes")
                sample["passed"] = True
                save()
                print(mode, "seconds=", sample["workSeconds"], "workload=pass", flush=True)
        measured = [s for s in report["samples"] if s["phase"] == "measured" and s.get("passed")]
        report["identicalArtifacts"] = (len({json.dumps(s["artifacts"], sort_keys=True) for s in measured}) == 1
                                        if args.workload == "compile" else None)
        if report["identicalArtifacts"] is False:
            raise RuntimeError("Compiled artifacts differ between measured builds")
        report["summary"] = {mode: {
            "medianSeconds": statistics.median(s["workSeconds"] for s in measured if s["mode"] == mode),
            "seconds": [s["workSeconds"] for s in measured if s["mode"] == mode]
        } for mode in args.modes}
        report["performanceTarget"] = performance_target(report["summary"])
        report["completed"] = True
        print(json.dumps(report["summary"], indent=2), flush=True)
        print("Per-workload target:", report["performanceTarget"], flush=True)
        print("Identical artifacts:", report["identicalArtifacts"], flush=True)
    except BaseException as error:
        report["error"] = {"type": type(error).__name__, "detail": str(error)}
        if report["samples"] and not report["samples"][-1].get("passed"):
            report["samples"][-1]["passed"] = False
            report["samples"][-1]["error"] = report["error"]
        raise
    finally:
        try:
            client.call("console.close", {"sessionId": console})
        finally:
            save()
            print("Report:", output, flush=True)


if __name__ == "__main__":
    main()
