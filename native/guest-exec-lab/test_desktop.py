#!/usr/bin/env python3
"""Exercise a prepared Xfce session in one retained X11 viewer, without Desktop."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--store", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--installed", action="store_true", help="Use the installed APK's guest CLI")
    parser.add_argument("--keyboard-directory", required=True)
    parser.add_argument("--trace", help="Optional native observer wrapper executable")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec); spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    before = client.call("get_state")
    assert before["shell"]["uid"] == 2000 and before["readiness"]["interactive"]
    assert not before["readiness"]["deviceLocked"]
    tag = uuid.uuid4().hex
    report = {"id": tag, "passed": False, "app": before["app"]}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    display = session = None
    log = args.runtime + "/desktop-" + tag + ".log"
    receipt = log + ".exit"
    bus_file = "/tmp/desktop-" + tag + ".bus"
    profile = "/tmp/desktop-" + tag + ".profile"
    runner = "magicdesk-guest" if args.installed else args.runtime + "/libmagicdesk_guest_run.so"

    def command(text):
        response = client.call("console.execute", {"sessionId": console, "command": text})
        assert response["exitCode"] == 0, response
        return response["output"]

    def wait(condition, **selection):
        # EVENT_WAIT: native catalog/host lifecycle; timeout fails rather than establishes readiness.
        response = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 30000, **selection})
        assert response.get("matched"), response
        return response

    def launch(command_text, completion):
        recipe = subprocess.check_output(["java", "-cp", str(args.build / "recipe-classes"),
            "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", "x11", args.store, "/home/shell", command_text], text=True)
        script = ("{ timeout 180 " + ("" if args.installed else "env PATH=" + shlex.quote(args.runtime + ":/system/bin"))
            + " " + (shlex.quote(args.trace) + " " if args.trace else "") + "/system/bin/sh -c " + shlex.quote(recipe)
            + "; r=$?; printf '%s\\n' \"$r\" > " + shlex.quote(completion)
            + "; } >> " + shlex.quote(log) + " 2>&1")
        client.call("graphics.execute", {"sessionId": session, "command": script})

    def completed(completion):
        result = command("timeout 30 " + shlex.quote(args.runtime + "/md-await-exit") + " " + shlex.quote(completion))
        assert command("cat " + shlex.quote(completion)).strip() == "0"
        return result

    try:
        if not args.installed:
            command("ln -sf libmagicdesk_guest_run.so " + shlex.quote(args.runtime + "/magicdesk-guest"))
        display = client.call("create_display", {"type": "virtual", "width": 1200, "height": 800, "densityDpi": 160})
        session = client.call("graphics.start", {"protocol": "x11", "backend": "shell", "connection": "routed",
            "name": "Guest Xfce " + tag, "keyboardDirectory": args.keyboard_directory})["sessionId"]
        ready = wait("graphics_ready", sessionId=session)["session"]
        assert ready["executorUid"] == 2000 and ready["serverUid"] not in (0, 2000)
        client.call("graphics.open_window", {"sessionId": session, "windowId": 0,
            "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
        host = wait("graphics_host_attached", sessionId=session, windowId=0)["matchingHosts"][0]
        assert host["wholeDesktop"] and not host["managed"]
        task = host["taskId"]
        wait("task_focused", taskId=task, displayId=display["id"])
        document = "/tmp/desktop-" + tag + ".txt"
        script = ("export XDG_CURRENT_DESKTOP=XFCE XDG_SESSION_TYPE=x11 XFSM_VERBOSE=1 "
            "LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=softpipe; "
            "export XDG_CONFIG_HOME=" + shlex.quote(profile + "/config") + " XDG_CACHE_HOME="
            + shlex.quote(profile + "/cache") + "; "
            "mkdir -p \"$XDG_CONFIG_HOME\" \"$XDG_CACHE_HOME\"; "
            "printf '%s\\n' \"$DBUS_SESSION_BUS_ADDRESS\" > " + shlex.quote(bus_file) + "; "
            "xfce4-session & session=$!; "
            "timeout 40 md-x11-desktop || { kill $session; wait $session; exit 1; }; "
            "md-xfce-session || { kill $session; wait $session; exit 1; }; "
            "printf 'desktop seed\\n' > " + shlex.quote(document) + "; "
            "xfce4-terminal --disable-server --title=Guest-Terminal & "
            "mousepad " + shlex.quote(document) + " & wait $session")
        launch(script, receipt)
        mapped = wait("graphics_window_present", sessionId=session, windowTitle=document + " - Mousepad")
        report["mapped"] = mapped
        editor = next(w for w in mapped["session"]["windows"] if w["appId"] == "Mousepad")
        launch("md-x11-desktop activate " + str(editor["windowId"]), receipt + ".focus")
        completed(receipt + ".focus")
        for keys in [["CTRL_LEFT", "A"], ["X"], ["F"], ["C"], ["E"], ["ENTER"], ["CTRL_LEFT", "S"]]:
            client.call("input.key_chord", {"displayId": display["id"], "keys": keys})
        report["saved"] = command(shlex.join([runner,
            "--store", args.store, "--", "/bin/cat", document]))
        assert report["saved"] == "xfce\n"
        launch("md-x11-desktop resize " + str(editor["windowId"]) + " 640 420", receipt + ".resize")
        completed(receipt + ".resize")
        capture = client.call_result("capture_screenshot", {"taskId": task})
        png = base64.b64decode(next(c["data"] for c in capture["content"] if c["type"] == "image"))
        (args.build / "guest-desktop.png").write_bytes(png)
        rgb = subprocess.check_output(["magick", "png:-", "-depth", "8", "rgb:-"], input=png)
        assert len(set(zip(rgb[::3], rgb[1::3], rgb[2::3]))) > 100
        report["capture"] = "guest-desktop.png"
        report["shellLog"] = command("cat " + shlex.quote(log))
        assert "PASS Xfce shell:" in report["shellLog"]
        client.call("graphics.detach_viewer", {"sessionId": session, "taskId": task})
        wait("task_absent", taskId=task)
        retained = client.call("graphics.list")
        report["retained"] = retained
        client.call("graphics.open_window", {"sessionId": session, "windowId": 0,
            "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
        wait("graphics_host_attached", sessionId=session, windowId=0)
        launch("export DBUS_SESSION_BUS_ADDRESS=$(cat " + shlex.quote(bus_file) + "); "
            "export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=softpipe; "
            "xfce4-session-logout --logout --fast", receipt + ".logout")
        completed(receipt + ".logout")
        report["exit"] = completed(receipt)
        command(shlex.join([runner, "--store", args.store,
            "--", "/bin/rm", "-rf", "--", profile, bus_file, document]))
        after = client.call("get_state")
        assert before["homeLease"] == after["homeLease"] and before["workspaces"] == after["workspaces"]
        report["passed"] = True
        print("PASS guest Xfce: shell, apps, save, resize, retained viewer and graceful logout", flush=True)
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        errors = []
        try:
            report["log"] = command("test ! -f " + shlex.quote(log) + " || cat " + shlex.quote(log))
            processes = command("ps -A -o PID,PPID,NAME,WCHAN")
            report["processes"] = [line for line in processes.splitlines()
                if any(name in line for name in ("guest", "xfce", "xfwm", "xfdesktop", "dbus", "mousepad"))]
        except Exception as error:
            errors.append(str(error))
        if session:
            try:
                client.call("graphics.stop", {"sessionId": session})
                wait("graphics_session_absent", sessionId=session)
            except Exception as error:
                errors.append(str(error))
        if display:
            try:
                client.call("remove_display", {"displayId": display["id"], "uniqueId": display["uniqueId"]})
                wait("display_absent", displayId=display["id"])
            except Exception as error:
                errors.append(str(error))
        client.call("console.close", {"sessionId": console})
        report["cleanupErrors"] = errors
        report["passed"] &= not errors
        (args.build / "desktop.json").write_text(json.dumps(report, indent=2) + "\n")
        if errors:
            raise RuntimeError(errors)


if __name__ == "__main__":
    main()
