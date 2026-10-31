#!/usr/bin/env python3
"""Alternate installed Wayland pacing against an optional baseline native library."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import time
import tomllib

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", required=True, help="Termux-readable wayland-window-test executable")
    parser.add_argument("--baseline", help="Termux-readable previous executor library")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("transport", ROOT / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    console = client.call("console.open")["sessionId"]
    runs = []
    try:
        for round_index in range(args.rounds):
            variants = ["current", "baseline"] if args.baseline else ["current"]
            if round_index % 2:
                variants.reverse()
            for variant in variants:
                command = ["am", "instrument", "--no-restart", "-w", "-e", "client", args.client,
                           "-e", "pacing", "true", "-e", "width", str(args.width), "-e", "height", str(args.height)]
                if variant == "baseline":
                    command += ["-e", "server_library", args.baseline]
                command += ["io.github.mekhontsev.magicdesk/.WaylandRuntimeInstrumentation"]
                frequencies = {}
                for path in sorted(Path("/sys/devices/system/cpu/cpufreq").glob("policy*/scaling_cur_freq")):
                    try:
                        frequencies[path.parent.name] = int(path.read_text().strip())
                    except (OSError, ValueError):
                        pass
                started = time.time()
                result = client.call("console.execute", {"sessionId": console, "command": shlex.join(command)})
                passed = result.get("exitCode") == 0 and "INSTRUMENTATION_CODE: -1" in result.get("output", "")
                run = dict(variant=variant, round=round_index, width=args.width, height=args.height,
                           started=started, frequencyKHz=frequencies, passed=passed, result=result)
                runs.append(run)
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(runs, indent=2) + "\n")
                print(json.dumps(run), flush=True)
                if not passed:
                    raise RuntimeError("Wayland pacing fixture failed")
    finally:
        client.call("console.close", {"sessionId": console})


if __name__ == "__main__":
    main()
