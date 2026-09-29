#!/usr/bin/env python3
"""Bounded Debian GUI workflow on a private virtual display, without Desktop."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--gtk", action="store_true", help="Run the unchanged Debian GTK application")
    parser.add_argument("--backend", choices=["direct", "namespace"])
    parser.add_argument("--installed", action="store_true", help="Use the installed APK's guest CLI")
    parser.add_argument("--config", type=Path, default=Path.home() / ".codex/config.toml")
    parser.add_argument("--server", default="magicdesk")
    args = parser.parse_args()
    if args.installed:
        if args.backend == "direct":
            parser.error("The installed CLI uses the namespace backend")
        args.backend = "namespace"
    previous = json.loads((args.build / "device-results.json").read_text())
    manifest = json.loads((args.build / "manifest.json").read_text())
    if manifest.get("profile") not in ("graphics", "gtk") or manifest != previous["manifest"]:
        raise RuntimeError("Run the graphics-profile device fixture from this exact build first")
    if args.gtk and manifest["profile"] != "gtk":
        raise RuntimeError("GTK requires its authenticated toolkit profile")
    if not previous["checks"] or not all(item["passed"] for item in previous["checks"]):
        raise RuntimeError("The prepared device fixture must have passed")
    root = previous["directory"]
    if not re.fullmatch(r"/data/local/tmp/md-guest-lab-[0-9a-f]{32}", root):
        raise RuntimeError("Unexpected retained fixture directory")
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("md_mcp", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads(args.config.read_text())["mcp_servers"][args.server]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    readiness = state["readiness"]
    if (state.get("shell", {}).get("uid") != 2000 or not readiness["interactive"]
            or readiness["deviceLocked"] or readiness["keyguardLocked"]):
        raise RuntimeError("Requires selected UID 2000 and an awake, unlocked device")
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    display = None
    sessions = []
    tag = uuid.uuid4().hex
    results = {"directory": root, "runId": tag, "app": state["app"], "device": state["device"],
               "manifest": manifest, "binaries": {}, "cases": []}
    results["installedRuntime"] = args.installed

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        if result["exitCode"] != 0:
            raise RuntimeError(result["output"])
        return result["output"]

    def wait(condition, session, **extra):
        # EVENT_WAIT: production catalog/host event; timeout fails observation, never proves readiness.
        result = client.call("wait_for_state", {"condition": condition, "sessionId": session,
                                                "timeoutMillis": 20000, **extra})
        if not result.get("matched"):
            raise RuntimeError("Condition did not match: " + condition)
        return result

    def check_pixels(samples):
        rgb = [(p["red"], p["green"], p["blue"]) for p in samples]
        # Android composition/color management may transform exact channel values.
        r, g, b = rgb[0]
        assert r > g + 60 and r > b + 60, rgb
        r, g, b = rgb[1]
        assert g > r + 60 and g > b + 40, rgb
        r, g, b = rgb[2]
        assert b > r + 60 and b > g + 40, rgb
        r, g, b = rgb[3]
        assert r > b + 60 and g > b + 60, rgb

    try:
        executable = "gtk3-demo-application" if args.gtk else "md-wayland-fixture"
        for name in ["libmagicdesk_guest_bootstrap.so", "libmagicdesk_guest_run.so", "rootfs/usr/bin/" + executable]:
            expected = hashlib.sha256((args.build / "bundle" / name).read_bytes()).hexdigest()
            assert command("sha256sum " + shlex.quote(root + "/" + name)).split()[0] == expected, name
            results["binaries"][name] = expected
        display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})
        for backend in ([args.backend] if args.backend else ["direct", "namespace"]):
            for buffer in (["gtk"] if args.gtk else ["memfd", "file"]):
                case = {"backend": backend, "buffer": buffer, "passed": False}
                results["cases"].append(case)
                session = client.call("graphics.start", {"protocol": "wayland", "backend": "shell",
                    "name": "Debian lab " + backend + " " + buffer,
                    "keyboardDirectory": root + "/rootfs/usr/share/X11/xkb"})["sessionId"]
                sessions.append(session)
                info = wait("graphics_ready", session)["session"]
                assert info["executorUid"] == 2000 and info["serverUid"] not in (0, 2000), info
                case["sessionId"], case["serverUid"] = session, info["serverUid"]
                prefix = (root + "/libmagicdesk_guest_bootstrap.so " + root + "/rootfs " if backend == "direct"
                          else root + "/libmagicdesk_guest_run.so --store " + root + "/rootfs/tmp/imported-rootfs -- ")
                if args.installed:
                    cli = command("command -v magicdesk-guest").strip()
                    if not cli.startswith("/") or "\n" in cli:
                        raise RuntimeError("Installed guest CLI is unavailable")
                    prefix = shlex.quote(cli) + " --store " + root + "/rootfs/tmp/imported-rootfs -- "
                if args.gtk:
                    case["preparation"] = []
                    for prepare in [
                        "/usr/lib/aarch64-linux-gnu/glib-2.0/glib-compile-schemas /usr/share/glib-2.0/schemas",
                        "/usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders --update-cache",
                        "/usr/bin/update-mime-database /usr/share/mime",
                        "/usr/bin/fc-match",
                    ]:
                        output = command("timeout 60 env PATH=/usr/bin:/bin HOME=/tmp LC_ALL=C " + prefix + prepare)
                        case["preparation"].append({"command": prepare, "output": output})
                    assert "DejaVu Sans" in case["preparation"][-1]["output"], case["preparation"]
                logfile = root + "/gui-" + tag + "-" + backend + "-" + buffer + ".log"
                case["logfile"] = logfile
                program = ("/usr/bin/gtk3-demo-application" if args.gtk else
                           "/usr/bin/md-wayland-fixture " + shlex.quote("Debian " + backend + " " + buffer) + " " + buffer)
                if backend == "namespace":
                    program = "/bin/sh -c " + shlex.quote(
                        "set -eu; umask 077; XDG_RUNTIME_DIR=$(mktemp -d /tmp/magicdesk-runtime.XXXXXX); "
                        "export XDG_RUNTIME_DIR; trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT; " + program)
                script = ("timeout 90 env PATH=/usr/bin:/bin HOME=/tmp TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp LC_ALL=C "
                          + ("GSETTINGS_BACKEND=keyfile " if args.gtk else "") + prefix + program)
                if args.gtk:
                    script = "{\n" + script + '\nresult=$?\nprintf "MD_GTK_EXIT=%s\\n" "$result"\nexit "$result"\n}'
                script += " >" + shlex.quote(logfile) + " 2>&1"
                client.call("graphics.execute", {"sessionId": session, "command": script})
                try:
                    present = wait("graphics_window_present", session)["session"]
                    assert len(present["windows"]) == 1, present
                    window = present["windows"][0]["windowId"]
                    client.call("graphics.open_window", {"sessionId": session, "windowId": window,
                        "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
                    attached = wait("graphics_host_attached", session, windowId=window, displayId=display["id"])
                    task = attached["matchingHosts"][0]["taskId"]
                    focused = client.call("wait_for_state", {"condition": "task_focused", "taskId": task,
                        "displayId": display["id"], "timeoutMillis": 10000})
                    assert focused["matched"], focused
                    ready = client.call("wait_for_state", {"condition": "app_ready", "taskId": task,
                        "displayId": display["id"], "timeoutMillis": 10000})
                    assert ready["matched"], ready
                    if args.gtk:
                        assert present["windows"][0]["title"] == "Application Class", present
                        # Task-surface capture excludes Android display transition transforms.
                        capture = client.call_result("capture_screenshot", {"taskId": task})
                        png = base64.b64decode(next(item["data"] for item in capture["content"]
                                                  if item["type"] == "image"), validate=True)
                        metadata = capture["structuredContent"]["data"]
                        # EVENT_WAIT: decoder child exit; deadline kills a stuck test decoder.
                        rgb = subprocess.check_output(["magick", "png:-", "-depth", "8", "rgb:-"], input=png, timeout=20)
                        width, height = metadata["width"], metadata["height"]
                        assert len(rgb) == width * height * 3 and width > 220 and height > 105, metadata
                        evidence = args.build / ("gtk-" + tag + "-" + backend + ".png")
                        evidence.write_bytes(png)
                        colors = [tuple(rgb[(y * width + x) * 3:(y * width + x) * 3 + 3])
                                  for y in [75, 85, 95, 105] for x in range(170, 221, 5)]
                        case["capture"] = {"file": evidence.name, "sha256": hashlib.sha256(png).hexdigest(),
                                           "metadata": metadata, "samples": colors}
                        assert len(set(colors)) > 12 and any(max(c) - min(c) > 60 for c in colors), colors
                        try:
                            client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "Q"]})
                        except transport.ToolError as error:
                            # Quit may remove Android's input target before key-up delivery.
                            # No replay: require native disappearance AND successful process exit.
                            detail = json.loads(str(error))
                            if detail.get("error", {}).get("code") != "ACTION_FAILED":
                                raise
                            case["inputDeliveryError"] = detail
                        wait("graphics_window_absent", session, windowId=window)
                        case["log"] = command("cat " + shlex.quote(logfile))
                        assert "MD_GTK_EXIT=0\n" in case["log"], case["log"][-2000:]
                        assert "Bail out!" not in case["log"] and "ERROR **" not in case["log"], case["log"][-2000:]
                        case["monitorWarnings"] = case["log"].count("gdk_monitor_get_scale_factor")
                        case["passed"] = True
                        print("PASS " + backend + ": stock GTK pixels and keyboard application exit", flush=True)
                        continue
                    pixels = client.call("sample_pixels", {"displayId": display["id"], "points": [
                        {"x": 340, "y": 250}, {"x": 660, "y": 250}, {"x": 340, "y": 450}, {"x": 660, "y": 450}]})
                    case["pixels"] = pixels
                    check_pixels(pixels["samples"])
                    client.call("input.gesture", {"displayId": display["id"], "type": "tap", "points": [{"x": 350, "y": 250}]})
                    client.call("input.key_chord", {"displayId": display["id"], "keys": ["A"]})
                    client.call("graphics.close_window", {"sessionId": session, "windowId": window})
                    wait("graphics_window_absent", session, windowId=window)
                    case["log"] = command("cat " + shlex.quote(logfile))
                    for marker in ["CONNECTED clientUid=2000 peerUid=" + str(info["serverUid"]),
                                   "EVENT server keymap FD readable", "EVENT pointer button 272 1",
                                   "EVENT pointer button 272 0", "EVENT key 30 1", "EVENT key 30 0",
                                   "PASS Wayland guest: protocol close"]:
                        assert marker in case["log"], case["log"]
                    case["passed"] = True
                    print("PASS " + backend + " " + buffer + ": cross-UID pixels, pointer/key input and protocol close", flush=True)
                finally:
                    case["log"] = command("cat " + shlex.quote(logfile))
                    client.call("graphics.stop", {"sessionId": session})
                    wait("graphics_session_absent", session)
                    sessions.remove(session)
    finally:
        errors = []
        for session in sessions:
            try:
                client.call("graphics.stop", {"sessionId": session})
                wait("graphics_session_absent", session)
            except Exception as error:
                errors.append(str(error))
        if display:
            try:
                client.call("remove_display", {"displayId": display["id"], "uniqueId": display["uniqueId"]})
                absent = client.call("wait_for_state", {"condition": "display_absent",
                    "displayId": display["id"], "timeoutMillis": 10000})
                if not absent.get("matched"):
                    raise RuntimeError("Owned virtual display was not removed")
            except Exception as error:
                errors.append(str(error))
        try:
            client.call("console.close", {"sessionId": console})
        except Exception as error:
            errors.append(str(error))
        results["cleanupErrors"] = errors
        (args.build / ("gtk-results.json" if args.gtk else "graphics-results.json")).write_text(json.dumps(results, indent=2) + "\n")
        if errors:
            raise RuntimeError("Incomplete cleanup: " + repr(errors))


if __name__ == "__main__":
    main()
