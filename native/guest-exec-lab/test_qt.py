#!/usr/bin/env python3
"""Qt guest through production recipes, Android IME and an independent display."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import re
import shlex
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--config", type=Path, default=Path.home() / ".codex/config.toml")
    parser.add_argument("--skip-correction", action="store_true", help="Separate coverage for a toolkit with a diagnosed correction limitation")
    args = parser.parse_args()
    prepared = json.loads((args.build / "device-results.json").read_text())
    assert prepared["checks"] and all(c["passed"] for c in prepared["checks"])
    root = prepared["directory"]
    assert re.fullmatch(r"/data/local/tmp/md-guest-lab-[0-9a-f]{32}", root)
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("md_mcp", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads(args.config.read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000 and state["readiness"]["interactive"]
    assert not state["readiness"]["deviceLocked"] and not state["workspaces"]
    console = client.call("console.open", {"directory": root})["sessionId"]
    tag = uuid.uuid4().hex
    name = "Guest Qt " + tag
    report = {"runId": tag, "app": state["app"], "correction": not args.skip_correction, "passed": False}
    display = None
    clipboard = None
    clipboard_changed = False
    prior_ime = None
    enabled = None
    fixture_ime = "io.github.mekhontsev.magicdesk/.HostedFixtureIme"
    path = None

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        assert result["exitCode"] == 0, result
        return result["output"]

    def wait(condition, **values):
        # EVENT_WAIT: exact host/catalog publication; timeout fails this workflow.
        result = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 20000, **values})
        assert result.get("matched"), result
        return result

    def keys(*values):
        client.call("input.key_chord", {"displayId": display["id"], "keys": list(values)})

    try:
        cli = command("command -v magicdesk-guest").strip()
        store = root + "/rootfs/tmp/imported-rootfs"
        installed = shlex.join([cli, "--store", store, "--", "/bin/sh", "-c"])

        def guest(value):
            return command("timeout 30 " + installed + " " + shlex.quote(value))

        guest("if test ! -e /etc/passwd; then /bin/sh /usr/bin/md-prepare-applications; fi")
        source = root + "/editor-" + tag + ".qml"
        transport.upload(client, repo / "wayland-runtime/tests/toolkits/editor.qml", source)
        command(installed + " " + shlex.quote("cat > /tmp/md-editor.qml") + " < " + shlex.quote(source))
        launch = "env WAYLAND_DEBUG=client QT_QPA_PLATFORM=wayland QT_WAYLAND_TEXT_INPUT_PROTOCOL=zwp_text_input_v3 QT_QUICK_BACKEND=software QT_QUICK_CONTROLS_STYLE=Basic /usr/lib/qt6/bin/qml /tmp/md-editor.qml"
        display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})
        prior_ime = command("settings get secure default_input_method").strip()
        enabled = command("settings get secure enabled_input_methods").strip()
        assert prior_ime and prior_ime != "null"
        command("ime enable " + fixture_ime)
        command("ime set " + fixture_ime)
        trace = "/tmp/md-qt-ime-" + tag + ".log"
        report["instrumentation"] = command(shlex.join(["am", "instrument", "--no-restart", "-w",
            "-e", "display", str(display["id"]), "-e", "ime", "true", "-e", "entryY", "95",
            "-e", "correction", "false" if args.skip_correction else "true",
            "-e", "guestStore", store, "-e", "keyboard", root + "/rootfs/usr/share/X11/xkb",
            "-e", "command", launch + " 2> " + trace,
            "io.github.mekhontsev.magicdesk/.HostedGuestEditorInstrumentation"]))
        trace_copy = root + "/" + tag + "-qt-ime.log"
        command(installed + " " + shlex.quote("cat " + trace) + " > " + shlex.quote(trace_copy))
        transport.download(client, trace_copy, args.build / (tag + "-qt-ime.log"))
        report["imeTrace"] = tag + "-qt-ime.log"
        assert "guest_editor=PASS" in report["instrumentation"], report["instrumentation"]
        print("PASS Qt Android IME, Unicode, purpose/privacy, caret and dialog", flush=True)
        apk = command("pm path io.github.mekhontsev.magicdesk").strip().removeprefix("package:")
        path = root + "/" + tag + "-qt.desktop"
        encoder = shlex.join(["/system/bin/app_process", "-Xnoimage-dex2oat", "/",
            "io.github.mekhontsev.magicdesk.GuestRecipeFixture", "wayland", store,
            root + "/rootfs/usr/share/X11/xkb", name, launch, path])
        command("env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + shlex.quote(apk) + " " + encoder + " > " + shlex.quote(path))
        awaiter = root + "/md-qt-await-" + tag
        transport.upload(client, args.build / "bundle/md-await-exit", awaiter)
        command("chmod 700 " + shlex.quote(awaiter))
        client.call("launch_desktop_entry", {"desktopPath": path, "displayId": display["id"], "placement": "display", "instance": "new"})
        # EVENT_WAIT: exact execution receipt, not a startup delay or repeated launch.
        command("timeout 25 " + shlex.quote(awaiter) + " " + shlex.quote(path + ".started"))
        session = next(s["sessionId"] for s in client.call("graphics.list")["sessions"] if s["name"] == name)
        window = wait("graphics_window_present", sessionId=session)["session"]["windows"][0]["windowId"]
        host = wait("graphics_host_attached", sessionId=session, windowId=window)["matchingHosts"][0]
        assert host["displayId"] == display["id"] and not host["managed"]
        wait("app_ready", taskId=host["taskId"], displayId=display["id"])
        wait("task_focused", taskId=host["taskId"], displayId=display["id"])
        # Fixture entry coordinates, as in HostedGuestEditorInstrumentation.
        client.call("input.gesture", {"displayId": display["id"], "type": "tap",
            "points": [{"x": 100, "y": 95}]})
        clipboard = client.call("clipboard.read_text")
        assert clipboard.get("access") in ("available", "empty") and not clipboard.get("truncated"), "Cannot preserve clipboard"
        assert not clipboard.get("itemCount") or clipboard.get("mimeTypes") == ["text/plain"]
        text = "Qt " + chr(0x416) + chr(0x1f600) + " " + tag
        client.call("clipboard.write_text", {"text": text})
        clipboard_changed = True
        keys("CTRL_LEFT", "V")
        keys("MOVE_HOME")
        keys("Q")
        keys("CTRL_LEFT", "A")
        keys("CTRL_LEFT", "C")
        copied = client.call("clipboard.read_text", {"expectedText": "q" + text, "timeoutMillis": 10000})
        assert copied.get("matched"), copied
        report["clipboard"] = copied["text"]
        image = client.call_result("capture_screenshot", {"taskId": host["taskId"]})
        (args.build / (tag + "-qt.png")).write_bytes(base64.b64decode(next(c["data"] for c in image["content"] if c["type"] == "image")))
        for _ in range(6): keys("TAB")
        keys("SPACE")
        child = wait("graphics_window_present", sessionId=session, parentWindowId=window)["session"]["windows"]
        child = next(w["windowId"] for w in child if w["mapped"] and w["parentWindowId"] == window)
        wait("graphics_host_attached", sessionId=session, windowId=child)
        client.call("graphics.close_window", {"sessionId": session, "windowId": child})
        wait("graphics_window_absent", sessionId=session, windowId=child)
        client.call("graphics.close_window", {"sessionId": session, "windowId": window})
        wait("graphics_window_absent", sessionId=session, windowId=window)
        report["exit"] = command("timeout 25 " + shlex.quote(awaiter) + " " + shlex.quote(path + ".exit"))
        assert not client.call("get_state")["workspaces"]
        print("PASS Qt Unicode clipboard, dependent dialog and clean protocol close", flush=True)
        report["passed"] = True
    except Exception as error:
        report["failure"] = str(error)
        report["failureState"] = client.call("get_state")
        if display:
            captured = client.call_result("capture_screenshot", {"displayId": display["id"]})
            image = next(c["data"] for c in captured["content"] if c["type"] == "image")
            (args.build / (tag + "-qt-failure.png")).write_bytes(base64.b64decode(image))
        raise
    finally:
        errors = []
        if path:
            try: transport.download(client, path + ".log", args.build / (tag + "-qt.log"), overwrite=True)
            except Exception as error: errors.append(str(error))
        for session in client.call("graphics.list")["sessions"]:
            if session["name"] == name:
                try: client.call("graphics.stop", {"sessionId": session["sessionId"]})
                except Exception as error: errors.append(str(error))
        if prior_ime:
            try: command("ime set " + shlex.quote(prior_ime))
            except Exception as error: errors.append(str(error))
        if enabled is not None and fixture_ime not in [s.split(";")[0] for s in enabled.split(":")]:
            try: command("ime disable " + fixture_ime)
            except Exception as error: errors.append(str(error))
        if display:
            try:
                client.call("remove_display", {"displayId": display["id"], "uniqueId": display["uniqueId"]})
                wait("display_absent", displayId=display["id"])
            except Exception as error: errors.append(str(error))
        if clipboard_changed:
            try:
                client.call("clipboard.write_text", {"text": clipboard["text"], "sensitive": clipboard.get("sensitive", False)}) if clipboard.get("text") else client.call("clipboard.clear")
            except Exception as error: errors.append(str(error))
        client.call("console.close", {"sessionId": console})
        report["cleanupErrors"] = errors
        report["passed"] = report["passed"] and not errors
        (args.build / ("qt-results" + ("-without-correction" if args.skip_correction else "") + ".json")).write_text(json.dumps(report, indent=2) + "\n")
        if errors: raise RuntimeError(errors)


if __name__ == "__main__":
    main()
