#!/usr/bin/env python3
"""Installed GUI clients observing changes from independent guest launches."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import time
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--store", required=True)
    parser.add_argument("--runtime", required=True, help="Fixture logs and md-await-exit directory")
    parser.add_argument("--keyboard-directory", required=True)
    parser.add_argument("--recipes", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000 and state["readiness"]["interactive"]
    assert not state["readiness"]["deviceLocked"] and not state["readiness"]["keyguardLocked"]
    tag = uuid.uuid4().hex
    report = {"id": tag, "store": args.store, "app": state["app"], "cases": [], "passed": False}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    display = None
    sessions = []
    clipboard = None
    clipboard_changed = False

    def command(value):
        response = client.call("console.execute", {"sessionId": console, "command": value})
        assert response["exitCode"] == 0, response
        return response["output"]

    def guest(value):
        return command(shlex.join(["timeout", "60", "magicdesk-guest", "--store", args.store,
            "--home", "/home/shell", "--", "/bin/sh", "-c", value]))

    def wait(condition, **selection):
        # EVENT_WAIT: native window/focus/host publication; expiry fails this workflow.
        value = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 20000, **selection})
        assert value.get("matched"), value
        return value

    def keys(*values):
        client.call("input.key_chord", {"displayId": display["id"], "keys": list(values)})

    def capture(case, name):
        value = client.call_result("capture_screenshot", {"displayId": display["id"]})
        png = base64.b64decode(next(c["data"] for c in value["content"] if c["type"] == "image"), validate=True)
        path = args.build / ("watch-apps-" + tag + "-" + name + ".png")
        path.write_bytes(png)
        case[name] = path.name

    def launch(name, executable):
        session = client.call("graphics.start", {"protocol": "wayland", "backend": "shell", "connection": "routed",
            "name": "Watch " + name + " " + tag, "keyboardDirectory": args.keyboard_directory})["sessionId"]
        sessions.append(session)
        ready = wait("graphics_ready", sessionId=session)["session"]
        assert ready["executorUid"] == 2000 and ready["serverUid"] not in (0, 2000)
        recipe = subprocess.check_output(["java", "-cp", str(args.recipes),
            "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", "wayland", args.store,
            "/home/shell", "export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe; " + executable], text=True, timeout=20)
        log = args.runtime + "/" + name + "-" + tag + ".log"
        receipt = log + ".exit"
        script = "{ timeout 180 /system/bin/sh -c " + shlex.quote(recipe) + "; r=$?; printf '%s\\n' \"$r\" > " + shlex.quote(receipt) + "; } > " + shlex.quote(log) + " 2>&1"
        case = {"application": name, "session": session, "logPath": log, "receipt": receipt}
        report["cases"].append(case)
        client.call("graphics.execute", {"sessionId": session, "command": script})
        mapped = wait("graphics_window_present", sessionId=session)["session"]
        windows = [w for w in mapped["windows"] if w["mapped"] and not w["parentWindowId"]]
        assert len(windows) == 1, windows
        case["window"] = windows[0]["windowId"]
        case["title"] = windows[0]["title"]
        client.call("graphics.open_window", {"sessionId": session, "windowId": case["window"],
            "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
        host = wait("graphics_host_attached", sessionId=session, windowId=case["window"])["matchingHosts"]
        assert len(host) == 1 and not host[0]["managed"]
        case["task"] = host[0]["taskId"]
        wait("task_focused", taskId=case["task"], displayId=display["id"])
        wait("app_ready", taskId=case["task"], displayId=display["id"])
        return case

    def dialog(case, title=None):
        selection = {"windowTitle": title} if title else {"parentWindowId": case["window"]}
        mapped = wait("graphics_window_present", sessionId=case["session"], **selection)["session"]
        found = [w for w in mapped["windows"] if w["mapped"] and
                 (w["title"] == title if title else w["parentWindowId"] == case["window"])]
        assert len(found) == 1, found
        native = found[0]["windowId"]
        host = wait("graphics_host_attached", sessionId=case["session"], windowId=native)["matchingHosts"]
        wait("task_focused", taskId=host[0]["taskId"], displayId=display["id"])
        wait("app_ready", taskId=host[0]["taskId"], displayId=display["id"])
        return native

    def close(case):
        client.call("graphics.close_window", {"sessionId": case["session"], "windowId": case["window"]})
        wait("graphics_window_absent", sessionId=case["session"], windowId=case["window"])
        command(shlex.join(["timeout", "25", args.runtime + "/md-await-exit", case["receipt"]]))
        assert command("cat " + shlex.quote(case["receipt"])).strip() == "0"

    try:
        display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})
        root = "/tmp/watch-" + tag
        guest("mkdir " + root + "; printf 'seed\\n' > " + root + "/note.txt")
        editor = launch("mousepad", "/usr/bin/mousepad " + root + "/note.txt")
        clipboard = client.call("clipboard.read_text")
        assert clipboard.get("access") in ("empty", "available") and not clipboard.get("truncated")
        assert not clipboard.get("itemCount") or clipboard.get("mimeTypes") == ["text/plain"]
        expected = "External content " + tag + "\n"
        guest("printf %s " + shlex.quote(expected) + " > " + root + "/note.txt")
        changed = dialog(editor)
        capture(editor, "external-change-dialog")
        keys("ALT_LEFT", "L")
        wait("graphics_window_absent", sessionId=editor["session"], windowId=changed)
        wait("task_focused", taskId=editor["task"], displayId=display["id"])
        keys("CTRL_LEFT", "A")
        clipboard_changed = True
        keys("CTRL_LEFT", "C")
        # EVENT_WAIT: native selection transfer; stale clipboard cannot satisfy the unique text.
        text = client.call("clipboard.read_text", {"expectedText": expected, "timeoutMillis": 10000})
        assert text.get("matched") and text.get("text") == expected, text
        editor["externalText"] = text["text"]
        capture(editor, "reloaded")
        close(editor)
        print("PASS Mousepad external write, reload notification, actual reloaded text and zero exit", flush=True)

        folder = root + "/before"
        moved = root + "/after"
        guest("mkdir " + folder)
        manager = launch("thunar", "/usr/bin/thunar " + folder)
        capture(manager, "empty")
        guest("printf 'external file\\n' > " + folder + "/created.txt")
        deadline = time.monotonic() + 20
        manager["selectionAttempts"] = 0
        # BOUNDED_STATE_WAIT: GTK does not publish row readiness in the native
        # window catalog. Select/open Rename against its current model; these
        # actions do not refresh the directory or mutate the file. Observe the
        # resulting dialog before any filename input; expiry fails the case.
        while time.monotonic() < deadline:
            keys("CTRL_LEFT", "A")
            keys("F2")
            manager["selectionAttempts"] += 1
            observed = client.call("wait_for_state", {"condition": "graphics_window_present",
                "sessionId": manager["session"], "windowTitle": 'Rename "created.txt"', "timeoutMillis": 1000})
            if observed.get("matched"):
                break
            assert observed.get("sessionPresent") and observed["session"]["ready"], observed
        else:
            raise AssertionError("Thunar did not expose the externally created file for rename")
        rename = dialog(manager, 'Rename "created.txt"')
        keys("CTRL_LEFT", "A")
        for key in ("R", "E", "N", "A", "M", "E", "D", "PERIOD", "T", "X", "T"):
            keys(key)
        keys("ENTER")
        wait("graphics_window_absent", sessionId=manager["session"], windowId=rename)
        assert guest("test ! -e " + folder + "/created.txt && cat " + folder + "/renamed.txt") == "external file\n"
        guest("mv " + folder + " " + moved)
        assert "before" in manager["title"], manager
        title = manager["title"].replace("before", Path(root).name)
        wait("graphics_window_present", sessionId=manager["session"], windowId=manager["window"], windowTitle=title)
        manager["externalCreateAndMove"] = True
        capture(manager, "renamed")
        close(manager)
        print("PASS Thunar external directory move and create, visible selection/rename, independent readback and zero exit", flush=True)
        final = client.call("get_state")
        assert final["homeLease"] == state["homeLease"] and final["workspaces"] == state["workspaces"]
        report["passed"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        errors = []
        for case in report["cases"]:
            try:
                case["log"] = command("cat " + shlex.quote(case["logPath"]))
            except Exception as error:
                errors.append(str(error))
        if clipboard_changed:
            try:
                if clipboard.get("itemCount"):
                    client.call("clipboard.write_text", {"text": clipboard["text"]})
                else:
                    client.call("clipboard.clear")
            except Exception as error:
                errors.append(str(error))
        for session in sessions:
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
        try:
            client.call("console.close", {"sessionId": console})
        except Exception as error:
            errors.append(str(error))
        report["cleanupErrors"] = errors
        report["passed"] &= not errors
        path = args.build / ("watch-apps-" + tag + ".json")
        path.write_text(json.dumps(report, indent=2) + "\n")
        print("Report:", path, flush=True)
        if errors and "failure" not in report:
            raise RuntimeError(errors)


if __name__ == "__main__":
    main()
