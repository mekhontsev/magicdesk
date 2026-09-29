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
    parser.add_argument("--application", choices=["mousepad", "galculator"], help="Exercise a real application through the installed runtime")
    parser.add_argument("--network", action="store_true", help="Check DNS and authenticated HTTPS inside the application fixture")
    parser.add_argument("--routed", action="store_true", help="Use independent guest connections through executor-owned admission")
    parser.add_argument("--protocol", choices=["wayland", "x11"], default="wayland")
    parser.add_argument("--backend", choices=["direct", "namespace"])
    parser.add_argument("--installed", action="store_true", help="Use the installed APK's guest CLI")
    parser.add_argument("--config", type=Path, default=Path.home() / ".codex/config.toml")
    parser.add_argument("--server", default="magicdesk")
    args = parser.parse_args()
    if args.protocol == "x11" and (not args.routed or not args.application):
        parser.error("X11 fixture requires --routed and --application")
    if args.routed:
        args.installed = True
    if args.application:
        args.gtk = args.installed = True
    if args.installed:
        if args.backend == "direct":
            parser.error("The installed CLI uses the namespace backend")
        args.backend = "namespace"
    previous = json.loads((args.build / "device-results.json").read_text())
    manifest = json.loads((args.build / "manifest.json").read_text())
    if manifest.get("profile") not in ("graphics", "gtk", "applications") or manifest != previous["manifest"]:
        raise RuntimeError("Run the graphics-profile device fixture from this exact build first")
    if args.gtk and manifest["profile"] not in ("gtk", "applications"):
        raise RuntimeError("GTK requires its authenticated toolkit profile")
    if args.application and manifest["profile"] != "applications":
        raise RuntimeError("Prepare the authenticated application profile first")
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
    results["connection"] = "routed" if args.routed else "auto"
    results["protocol"] = args.protocol

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

    def read_log(remote):
        local = args.build / Path(remote).name
        transport.download(client, remote, local, overwrite=True)
        return local.read_text()

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
        executable = args.application or ("gtk3-demo-application" if args.gtk else "md-wayland-fixture")
        for name in ["libmagicdesk_guest_bootstrap.so", "libmagicdesk_guest_run.so", "rootfs/usr/bin/" + executable]:
            expected = hashlib.sha256((args.build / "bundle" / name).read_bytes()).hexdigest()
            assert command("sha256sum " + shlex.quote(root + "/" + name)).split()[0] == expected, name
            results["binaries"][name] = expected
        awaiter = root + "/md-await-" + tag
        transport.upload(client, args.build / "bundle/md-await-exit", awaiter)
        command("chmod 700 " + shlex.quote(awaiter))
        cli = command("command -v magicdesk-guest").strip() if args.installed else ""
        home = " --home /home/shell" if args.application else ""
        installed = shlex.quote(cli) + " --store " + root + "/rootfs/tmp/imported-rootfs" + home + " -- "

        def session_script(program):
            return subprocess.check_output(["java", "-cp", str(args.build / "recipe-classes"),
                "io.github.mekhontsev.magicdesk.GraphicalRecipe", program], text=True, timeout=20)

        if args.application:
            results["preparation"] = command("timeout 60 " + installed + "/bin/sh -c " + shlex.quote(
                "set -eu; if test ! -e /etc/passwd; then /bin/sh /usr/bin/md-prepare-applications; fi; "
                "getent passwd 2000; getent group 2000; cat /proc/self/comm"))
            assert "shell:x:2000:" in results["preparation"] and "\ncat\n" in results["preparation"], results["preparation"]
            for operation in ["gsettings set org.xfce.mousepad.preferences.view show-line-numbers true",
                              "gsettings get org.xfce.mousepad.preferences.view show-line-numbers"]:
                result = command("timeout 30 " + installed + "/bin/sh -c " + shlex.quote(session_script(operation)))
                results.setdefault("settings", []).append(result)
            assert results["settings"][-1].rstrip().endswith("true"), results["settings"]
            if args.network:
                results["network"] = command("timeout 30 " + installed + "/bin/sh -c " + shlex.quote(
                    "set -eu; getent hosts deb.debian.org; "
                    "curl --silent --show-error --fail --connect-timeout 8 --max-time 15 --head https://deb.debian.org/debian/README"))
                assert "200" in results["network"], results["network"]
            document = "/home/shell/md-" + tag + ".txt"
            if args.application == "mousepad":
                command("timeout 15 " + installed + "/bin/sh -c " + shlex.quote("printf 'seed\\n' > " + document))
        display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})
        modes = ([("gtk", False), ("gtk", True)] if args.application == "mousepad" else
                 [("gtk", False)] if args.gtk else [("memfd", False), ("file", False)])
        retained_session = None
        for backend in ([args.backend] if args.backend else ["direct", "namespace"]):
            for buffer, reopen in modes:
                case = {"backend": backend, "buffer": buffer, "reopen": reopen, "passed": False}
                results["cases"].append(case)
                session = retained_session
                if session is None:
                    session = client.call("graphics.start", {"protocol": args.protocol, "backend": "shell",
                        "name": "Debian lab " + backend + " " + buffer,
                        "connection": "routed" if args.routed else "auto",
                        "keyboardDirectory": root + "/rootfs/usr/share/X11/xkb"})["sessionId"]
                    sessions.append(session)
                    if args.routed:
                        retained_session = session
                info = wait("graphics_ready", session)["session"]
                assert info["executorUid"] == 2000 and info["serverUid"] not in (0, 2000), info
                case["sessionId"], case["serverUid"] = session, info["serverUid"]
                if args.protocol == "x11" and not reopen:
                    negative_log = root + "/auth-" + tag + ".log"
                    negative_exit = negative_log + ".exit"
                    negative = subprocess.check_output(["java", "-cp", str(args.build / "recipe-classes"),
                        "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", "x11",
                        root + "/rootfs/tmp/imported-rootfs", "/home/shell", "/usr/bin/galculator"], text=True, timeout=20)
                    cookie = b"MIT-MAGIC-COOKIE-1"
                    authority = b"\xff\xff\x00\x00\x00\x00" + len(cookie).to_bytes(2, "big") + cookie + b"\x00\x10" + bytes(16)
                    script = ("{ export MAGICDESK_X11_AUTHORITY=" + shlex.quote(base64.b64encode(authority).decode())
                              + "; timeout 15 sh -c " + shlex.quote(negative)
                              + "; result=$?; printf '%s\\n' \"$result\" > " + shlex.quote(negative_exit) + "; } >"
                              + shlex.quote(negative_log) + " 2>&1")
                    client.call("graphics.execute", {"sessionId": session, "command": script})
                    receipt = client.call("console.execute", {"sessionId": console,
                        "command": "timeout 20 " + shlex.quote(awaiter) + " " + shlex.quote(negative_exit)})
                    negative_text = command("cat " + shlex.quote(negative_exit)).strip()
                    case["authorization"] = {"exit": negative_text, "log": read_log(negative_log)}
                    assert negative_text == "1" and "cannot open display" in case["authorization"]["log"], case["authorization"]
                    assert not next(s for s in client.call("graphics.list")["sessions"] if s["sessionId"] == session)["windows"]
                    print("PASS X11 rejects an incorrect MIT cookie before mapping a client", flush=True)
                prefix = (root + "/libmagicdesk_guest_bootstrap.so " + root + "/rootfs " if backend == "direct"
                          else root + "/libmagicdesk_guest_run.so --store " + root + "/rootfs/tmp/imported-rootfs -- ")
                if args.installed:
                    if not cli.startswith("/") or "\n" in cli:
                        raise RuntimeError("Installed guest CLI is unavailable")
                    prefix = installed
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
                suffix = "-reopen" if reopen else ""
                logfile = root + "/gui-" + tag + "-" + backend + "-" + buffer + suffix + ".log"
                receipt = logfile + ".exit"
                case["logfile"] = logfile
                program = ("/usr/bin/gtk3-demo-application" if args.gtk else
                           "/usr/bin/md-wayland-fixture " + shlex.quote("Debian " + backend + " " + buffer) + " " + buffer)
                if args.application:
                    program = "/bin/sh -c " + shlex.quote(session_script(
                        "/usr/bin/" + args.application + (" " + document if args.application == "mousepad" else "")))
                elif backend == "namespace":
                    program = "/bin/sh -c " + shlex.quote(
                        "set -eu; umask 077; XDG_RUNTIME_DIR=$(mktemp -d /tmp/magicdesk-runtime.XXXXXX); "
                        "export XDG_RUNTIME_DIR; trap 'rm -rf -- \"$XDG_RUNTIME_DIR\"' EXIT; " + program)
                script = ("timeout 90 env PATH=/usr/bin:/bin HOME=/tmp TMPDIR=/tmp XDG_RUNTIME_DIR=/tmp LC_ALL=C "
                          + ("GSETTINGS_BACKEND=keyfile " if args.gtk and not args.application else "") + prefix + program)
                if args.routed:
                    program = ("/usr/bin/" + args.application + (" " + document if args.application == "mousepad" else "")) if args.application else program
                    routed = subprocess.check_output(["java", "-cp", str(args.build / "recipe-classes"),
                        "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", args.protocol,
                        root + "/rootfs/tmp/imported-rootfs", "/home/shell" if args.application else "/tmp", program], text=True, timeout=20)
                    script = "timeout 90 sh -c " + shlex.quote(routed)
                if args.gtk:
                    script = "{\n" + script + '\nresult=$?\nprintf "%s\\n" "$result" > ' + shlex.quote(receipt) + '\nprintf "MD_GTK_EXIT=%s\\n" "$result"\nexit "$result"\n}'
                script += " >" + shlex.quote(logfile) + " 2>&1"
                client.call("graphics.execute", {"sessionId": session, "command": script})
                try:
                    present = wait("graphics_window_present", session)["session"]
                    assert len(present["windows"]) == 1, present
                    window = present["windows"][0]["windowId"]
                    case["mappedWindows"] = present["windows"]
                    # Wayland retains the first host's destination for subsequent toplevels.
                    if not (args.routed and reopen and args.protocol == "wayland"):
                        client.call("graphics.open_window", {"sessionId": session, "windowId": window,
                            "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
                    attached = wait("graphics_host_attached", session, windowId=window, displayId=display["id"])
                    case["host"] = attached
                    assert len(attached["matchingHosts"]) == 1, attached
                    task = attached["matchingHosts"][0]["taskId"]
                    focused = client.call("wait_for_state", {"condition": "task_focused", "taskId": task,
                        "displayId": display["id"], "timeoutMillis": 10000})
                    case["focus"] = focused
                    assert focused["matched"], focused
                    ready = client.call("wait_for_state", {"condition": "app_ready", "taskId": task,
                        "displayId": display["id"], "timeoutMillis": 10000})
                    assert ready["matched"], ready
                    if args.gtk:
                        if args.application:
                            case["window"] = present["windows"][0]
                            if args.application == "mousepad":
                                assert tag in case["window"]["title"], case
                                if not reopen:
                                    for keys in [["CTRL_LEFT", "A"], ["M"], ["A"], ["G"], ["I"], ["C"],
                                                 ["D"], ["E"], ["S"], ["K"], ["ENTER"], ["CTRL_LEFT", "S"]]:
                                        client.call("input.key_chord", {"displayId": display["id"], "keys": keys})
                            else:
                                for keys in [["2"], ["PLUS"], ["2"], ["ENTER"]]:
                                    client.call("input.key_chord", {"displayId": display["id"], "keys": keys})
                        else:
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
                        evidence = args.build / ("gtk-" + tag + "-" + backend + suffix + ".png")
                        evidence.write_bytes(png)
                        colors = [tuple(rgb[(y * width + x) * 3:(y * width + x) * 3 + 3])
                                  for y in [75, 85, 95, 105] for x in range(170, 221, 5)]
                        case["capture"] = {"file": evidence.name, "sha256": hashlib.sha256(png).hexdigest(),
                                           "metadata": metadata, "samples": colors}
                        if args.application:
                            assert len(set(zip(rgb[0::3], rgb[1::3], rgb[2::3]))) > 100, metadata
                        else:
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
                        case["completion"] = command("timeout 20 " + shlex.quote(awaiter) + " " + shlex.quote(receipt))
                        case["log"] = read_log(logfile)
                        assert "MD_GTK_EXIT=0\n" in case["log"], case["log"][-2000:]
                        assert "Bail out!" not in case["log"] and "ERROR **" not in case["log"], case["log"][-2000:]
                        case["monitorWarnings"] = case["log"].count("gdk_monitor_get_scale_factor")
                        if args.application == "mousepad":
                            case["savedText"] = command("timeout 20 " + installed + "/bin/cat " + document)
                            assert case["savedText"] == "magicdesk\n", case
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
                    case["log"] = read_log(logfile)
                    for marker in ["CONNECTED clientUid=2000 peerUid=" + str(info["serverUid"]),
                                   "EVENT server keymap FD readable", "EVENT pointer button 272 1",
                                   "EVENT pointer button 272 0", "EVENT key 30 1", "EVENT key 30 0",
                                   "PASS Wayland guest: protocol close"]:
                        assert marker in case["log"], case["log"]
                    case["passed"] = True
                    print("PASS " + backend + " " + buffer + ": cross-UID pixels, pointer/key input and protocol close", flush=True)
                finally:
                    case["log"] = read_log(logfile)
                    if not args.routed:
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
        report = args.application + "-results.json" if args.application else "gtk-results.json" if args.gtk else "graphics-results.json"
        if args.routed:
            report = args.protocol + "-routed-" + report
        (args.build / report).write_text(json.dumps(results, indent=2) + "\n")
        if errors:
            raise RuntimeError("Incomplete cleanup: " + repr(errors))


if __name__ == "__main__":
    main()
