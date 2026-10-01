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


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def power_sources(snapshot):
    values = dict(re.findall(r"^\s*(AC|USB|Wireless|Dock) powered:\s*(true|false)\s*$", snapshot, re.M))
    if not values:
        raise RuntimeError("Battery service did not report power sources")
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path, help="Fresh prepare.mjs output containing sysroot")
    parser.add_argument("--sqlite", type=Path, required=True, help="SQLite amalgamation directory")
    parser.add_argument("--runtime", required=True, help="Installed shell-visible guest bundle")
    parser.add_argument("--baseline-runtime", help="Explicit guest reference bundle for interleaved A/B runs")
    parser.add_argument("--workload", choices=("compile", "metadata", "spawn"), default="compile")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--guest-store", help="Reuse an explicitly prepared identical benchmark store")
    parser.add_argument("--baseline-store", help="Identical reference fixture when the store format differs")
    parser.add_argument("--guest-statistics", action="store_true", help="Opt-in aggregate interception counters")
    parser.add_argument("--modes", nargs="+", choices=("chroot", "proot", "guest", "guest-baseline"),
                        default=["chroot", "proot", "guest"])
    args = parser.parse_args()
    if args.rounds < 1 or args.warmups < 0 or len(set(args.modes)) != len(args.modes):
        parser.error("positive rounds, nonnegative warmups and distinct modes required")
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
    for name in ("sqlite3.c", "shell.c", "sqlite3.h", "sqlite3ext.h"):
        shutil.copyfile(args.sqlite / name, bench / name)
    shutil.copyfile(fixtures / "compile-benchmark.mk", bench / "Makefile")
    shutil.copyfile(fixtures / "compile-benchmark.sh", bench / "run.sh")
    shutil.copyfile(fixtures / "compile-library-check.c", bench / "verify-library.c")
    shutil.copyfile(fixtures / "runtime-workloads.c", bench / "runtime-workloads.c")
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
                               ("sqlite3.c", "shell.c", "sqlite3.h", "sqlite3ext.h", "Makefile", "run.sh", "verify-library.c", "runtime-workloads.c")},
              "git": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
              "policy": {"jobs": 1, "compilerCache": False, "dropPageCache": False,
                         "guestStatistics": args.guest_statistics,
                         "warmupsPerMode": args.warmups, "rounds": args.rounds,
                         "affinityPinned": False, "cpuGovernorChanged": False,
                         "timing": "GNU time around workload; excludes launch and preparation",
                         "identities": {"proot": os.getuid(), "chroot": 0, "guest": 2000, "guest-baseline": 2000}},
              "completed": False}
    guest_store = args.guest_store or remote + "/store"
    report["guestStore"] = guest_store
    report["baselineStore"] = args.baseline_store or guest_store
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]

    def save():
        output.write_text(json.dumps(report, indent=2) + "\n")

    def frequencies():
        values = {}
        for path in sorted(Path("/sys/devices/system/cpu/cpufreq").glob("policy*/scaling_*_freq")):
            try:
                values[str(path)] = int(path.read_text().strip())
            except (OSError, ValueError):
                pass
        return values

    def shell(command):
        result = client.call("console.execute", {"sessionId": console, "command": command})
        if result["exitCode"]:
            raise RuntimeError(result)
        return result["output"]

    def local(command, env=None):
        # EVENT_WAIT: child completion; timeout fails the sample, never means readiness.
        result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=300)
        if result.returncode:
            raise RuntimeError({"command": command, "exitCode": result.returncode, "output": result.stdout})
        return result.stdout

    def launch(mode, action):
        guest = ["/bin/sh", "/bench/run.sh", action]
        if mode in ("guest", "guest-baseline"):
            runtime = args.runtime if mode == "guest" else args.baseline_runtime
            store = guest_store if mode == "guest" else report["baselineStore"]
            return shell(shlex.join([runtime + "/libmagicdesk_guest_run.so",
                                    *(["--statistics"] if args.guest_statistics else []),
                                    "--deadline-seconds", "300", "--store", store, "--"] + guest))
        if mode == "proot":
            env = os.environ.copy()
            for name in ("LD_PRELOAD", "LD_LIBRARY_PATH", "PROOT_NO_SECCOMP"):
                env.pop(name, None)
            return local(["proot", "--kill-on-exit", "-r", str(root), "-b", "/dev", "-b", "/proc",
                          "-b", "/sys", "-w", "/bench", *guest], env)
        # All bind mounts live in a private namespace and disappear with this command.
        body = "\n".join(["set -eu", "mount -o rprivate none /", *[
            shlex.join(["mount", "--bind", "/" + name, str(root / name)]) for name in ("dev", "proc", "sys")],
            "exec " + shlex.join(["chroot", str(root), "/usr/bin/env", "-i", *guest])])
        return local(["su", "-c", shlex.join(["/system/bin/unshare", "-m", "/system/bin/sh", "-c", body])])

    try:
        report["hostIdentity"] = local(["id"])
        report["prootVersion"] = local(["proot", "--version"])
        report["prootPackage"] = local(["dpkg-query", "-W", "proot"])
        report["kernel"] = shell("uname -a")
        report["shellIdentity"] = shell("id")
        if any(mode.startswith("guest") for mode in args.modes) and not args.guest_store:
            archive = work / ("compile-root-" + tag + ".tar")
            with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as stream:
                stream.add(root, arcname=".")
            shell(shlex.join(["mkdir", "-p", remote + "/root"]))
            transport.upload(client, str(archive), remote + "/root.tar")
            shell(shlex.join(["tar", "xf", remote + "/root.tar", "-C", remote + "/root"]))
            report["import"] = shell(shlex.join([args.runtime + "/libmagicdesk_guest_service.so",
                                                 "--import", remote + "/root", guest_store]))
        if "guest" in args.modes:
            report["runtimeHashes"] = shell("sha256sum " + shlex.quote(args.runtime) + "/libmagicdesk_guest_*.so")
        if "guest-baseline" in args.modes:
            report["baselineRuntimeHashes"] = shell("sha256sum " + shlex.quote(args.baseline_runtime) + "/libmagicdesk_guest_*.so")
        for mode in args.modes:
            report["probes"][mode] = launch(mode, "probe")
            for name in ("sqlite3.c", "shell.c", "sqlite3.h", "run.sh", "runtime-workloads.c"):
                if report["sourceHashes"][name] + "  " + name not in report["probes"][mode]:
                    raise RuntimeError("Benchmark source mismatch: " + mode + "/" + name)
            if args.workload != "compile":
                # The shared native tree stays writable by its real Termux owner.
                # Preparation is untimed; chroot subsequently reads the same fixture.
                launch("proot" if mode == "chroot" else mode, "prepare")
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
                sample["frequenciesBefore"] = frequencies()
                sample["batteryBefore"] = shell("dumpsys battery")
                report.setdefault("powerSources", power_sources(sample["batteryBefore"]))
                save()
                text = launch(mode, "build" if args.workload == "compile" else args.workload)
                sample["outerSeconds"] = time.monotonic() - started
                sample["thermalAfter"] = shell("cat /sys/class/thermal/thermal_zone0/temp")
                sample["frequenciesAfter"] = frequencies()
                sample["batteryAfter"] = shell("dumpsys battery")
                sample["output"] = text
                sample["finishedUtc"] = datetime.now(timezone.utc).isoformat()
                if any(power_sources(sample[key]) != report["powerSources"]
                       for key in ("batteryBefore", "batteryAfter")):
                    raise RuntimeError("Power source changed; keep this report separate from a stable series")
                timing = re.search(r"^MD_TIMING ([0-9.]+) ([0-9.]+) ([0-9.]+) (\d+) (\d+) (\d+)$", text, re.M)
                expected = ("MD_SQL_OK 5000050000", "MD_LIBRARY_OK 42") if args.workload == "compile" else (
                    "MD_WORKLOAD_OK " + args.workload + (" 4096" if args.workload == "metadata" else " 128"),)
                if not timing or not all(marker in text for marker in expected):
                    raise RuntimeError("Missing timing or workload validation: " + text)
                sample.update(zip(("wallSeconds", "userSeconds", "systemSeconds", "maxRssKiB",
                                   "involuntarySwitches", "voluntarySwitches"), map(float, timing.groups())))
                sample["artifacts"] = dict((name, value) for value, name in
                                           re.findall(r"^([0-9a-f]{64})  (sqlite3|libsqlite3.so|shell.o|sqlite3.o|sqlite3.pic.o)$", text, re.M))
                if args.workload == "compile" and len(sample["artifacts"]) != 5:
                    raise RuntimeError("Missing compiled artifact hashes")
                sample["passed"] = True
                save()
                print(mode, "seconds=", sample["wallSeconds"], "workload=pass", flush=True)
        measured = [s for s in report["samples"] if s["phase"] == "measured" and s.get("passed")]
        report["identicalArtifacts"] = (len({json.dumps(s["artifacts"], sort_keys=True) for s in measured}) == 1
                                        if args.workload == "compile" else None)
        if report["identicalArtifacts"] is False:
            raise RuntimeError("Compiled artifacts differ between measured builds")
        report["summary"] = {mode: {
            "medianSeconds": statistics.median(s["wallSeconds"] for s in measured if s["mode"] == mode),
            "seconds": [s["wallSeconds"] for s in measured if s["mode"] == mode]
        } for mode in args.modes}
        report["completed"] = True
        print(json.dumps(report["summary"], indent=2), flush=True)
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
