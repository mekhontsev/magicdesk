#!/usr/bin/env python3
"""Installed guest recipes and content workflows, on an independent virtual display."""
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
    parser.add_argument("--protocol", choices=["x11", "wayland"], required=True)
    parser.add_argument("--repeat-digit", action="store_true", help="Investigate rapid repeated GTK accelerator input with 2+2")
    parser.add_argument("--trace-wayland", action="store_true", help="Retain client-side Wayland protocol events for the calculator")
    parser.add_argument("--config", type=Path, default=Path.home() / ".codex/config.toml")
    args = parser.parse_args()
    if args.trace_wayland and args.protocol != "wayland":
        parser.error("--trace-wayland requires --protocol wayland")
    prepared = json.loads((args.build / "device-results.json").read_text())
    manifest = json.loads((args.build / "manifest.json").read_text())
    assert manifest == prepared["manifest"] and manifest["profile"] == "applications"
    assert prepared["checks"] and all(item["passed"] for item in prepared["checks"])
    root = prepared["directory"]
    assert re.fullmatch(r"/data/local/tmp/md-guest-lab-[0-9a-f]{32}", root)
    spec = importlib.util.spec_from_file_location("md_mcp", Path(__file__).resolve().parents[2] / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads(args.config.read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    state = client.call("get_state")
    assert state["shell"]["uid"] == 2000 and state["readiness"]["interactive"]
    assert not state["readiness"]["deviceLocked"] and not state["readiness"]["keyguardLocked"]
    console = client.call("console.open", {"directory": root})["sessionId"]
    tag = uuid.uuid4().hex
    report = {"runId": tag, "protocol": args.protocol, "app": state["app"], "cases": [],
              "waylandTrace": args.trace_wayland}
    display = None
    sessions = []
    recipes = []
    clipboard = None
    clipboard_changed = False
    completed = False

    def command(value):
        result = client.call("console.execute", {"sessionId": console, "command": value})
        assert result["exitCode"] == 0, result
        return result["output"]

    def wait(condition, **selection):
        # EVENT_WAIT: shared task/catalog publication; timeout fails this exact workflow.
        result = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 20000, **selection})
        assert result.get("matched"), result
        return result

    def keys(*values):
        return client.call("input.key_chord", {"displayId": display["id"], "keys": list(values)})

    def type_path(value):
        assert re.fullmatch(r"[a-z0-9/.-]+", value), value
        for character in value:
            keys({"/": "SLASH", ".": "PERIOD", "-": "MINUS"}.get(character, character.upper()))

    def clipboard_text(expected):
        # EVENT_WAIT: asynchronous native selection transfer publishes Android's primary clip.
        result = client.call("clipboard.read_text", {"expectedText": expected, "timeoutMillis": 10000})
        assert result.get("matched") and result.get("text") == expected, result
        return result["text"]

    def capture(task, suffix):
        result = client.call_result("capture_screenshot", {"taskId": task} if task is not None else {"displayId": display["id"]})
        data = next(item["data"] for item in result["content"] if item["type"] == "image")
        path = args.build / ("shortcut-" + tag + "-" + suffix + ".png")
        path.write_bytes(base64.b64decode(data, validate=True))
        return path.name

    try:
        report["rejectedFilters"] = []
        for name, value in [("windowTitle", "Document"), ("parentWindowId", 1)]:
            try:
                client.call("wait_for_state", {"condition": "display_present", "displayId": 0,
                    "sessionId": "fixture", name: value, "timeoutMillis": 1})
                raise AssertionError("Inapplicable presence filter was ignored: " + name)
            except transport.ToolError as error:
                rejected = json.loads(str(error))
                assert rejected["error"]["code"] == "INVALID_ARGUMENT", rejected
                report["rejectedFilters"].append(name)
        cli = command("command -v magicdesk-guest").strip()
        assert cli.startswith("/") and "\n" not in cli
        installed = shlex.join([cli, "--store", root + "/rootfs/tmp/imported-rootfs", "--", "/bin/sh", "-c"])

        def guest(value):
            return command("timeout 30 " + installed + " " + shlex.quote(value))

        guest("set -eu; if test ! -e /etc/passwd; then /bin/sh /usr/bin/md-prepare-applications; fi")
        document = "/tmp/md-shortcut-" + tag + ".txt"
        guest("printf 'seed\\n' > " + shlex.quote(document))
        opened_document = "/tmp/md-open-" + tag + ".txt"
        opened_text = "File picker content: " + tag
        guest("printf %s " + shlex.quote(opened_text) + " > " + shlex.quote(opened_document))
        apk = command("pm path io.github.mekhontsev.magicdesk").strip().removeprefix("package:")
        assert apk.startswith("/") and "\n" not in apk
        awaiter = root + "/md-shortcut-await-" + tag
        transport.upload(client, args.build / "bundle/md-await-exit", awaiter)
        command("chmod 700 " + shlex.quote(awaiter))
        display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})

        def focused_host(session, window):
            host = wait("graphics_host_attached", sessionId=session, windowId=window)["matchingHosts"]
            assert len(host) == 1 and host[0]["displayId"] == display["id"] and not host[0]["managed"], host
            task = host[0]["taskId"]
            wait("task_focused", taskId=task, displayId=display["id"])
            wait("app_ready", taskId=task, displayId=display["id"])
            return task

        def launch(application, command_line):
            name = "Guest recipe " + tag + " " + application
            path = root + "/" + tag + "-" + application + ".desktop"
            recipes.append(path)
            encoder = shlex.join(["/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                "io.github.mekhontsev.magicdesk.GuestRecipeFixture", args.protocol,
                root + "/rootfs/tmp/imported-rootfs", root + "/rootfs/usr/share/X11/xkb", name, command_line, path])
            command("env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + shlex.quote(apk)
                    + " " + encoder + " > " + shlex.quote(path))
            accepted = client.call("launch_desktop_entry", {"desktopPath": path, "displayId": display["id"],
                "placement": "display", "instance": "new"})
            assert accepted.get("accepted"), accepted
            # EVENT_WAIT: executor starts this exact recipe; no settling delay or repeated launch.
            command("timeout 25 " + shlex.quote(awaiter) + " " + shlex.quote(path + ".started"))
            found = [s for s in client.call("graphics.list")["sessions"] if s["name"] == name]
            assert len(found) == 1, found
            session = found[0]["sessionId"]
            sessions.append(session)
            ready = wait("graphics_window_present", sessionId=session)["session"]
            assert ready["executorUid"] == 2000 and ready["serverUid"] not in (0, 2000), ready
            windows = [w for w in ready["windows"] if w["mapped"] and not w["parentWindowId"]]
            assert len(windows) == 1, windows
            window = windows[0]["windowId"]
            task = focused_host(session, window)
            case = {"application": application, "path": path, "sessionId": session,
                    "taskId": task, "windowId": window, "serverUid": ready["serverUid"], "title": windows[0]["title"]}
            report["cases"].append(case)
            print("PASS recipe launch " + application, flush=True)
            return case

        editor = launch("mousepad", "/usr/bin/mousepad " + shlex.quote(document))
        editor["capture"] = capture(editor["taskId"], "editor")
        clipboard = client.call("clipboard.read_text")
        assert clipboard.get("access") in ("available", "empty") and not clipboard.get("truncated"), "Cannot preserve clipboard"
        assert not clipboard.get("itemCount") or clipboard.get("mimeTypes") == ["text/plain"], "Non-text clipboard must not be replaced"
        text = "MagicDesk guest: " + chr(0x416) + chr(0x1f600) + "\n" + tag
        client.call("clipboard.write_text", {"text": text})
        clipboard_changed = True
        keys("CTRL_LEFT", "A")
        keys("CTRL_LEFT", "V")
        keys("CTRL_LEFT", "S")
        editor["savedText"] = guest("cat " + shlex.quote(document))
        assert editor["savedText"] == text, editor["savedText"]
        # Change text in the guest, so a stale Android clipboard cannot satisfy the assertion.
        keys("CTRL_LEFT", "MOVE_HOME")
        keys("G")
        keys("2")
        keys("2")
        keys("CTRL_LEFT", "A")
        keys("CTRL_LEFT", "C")
        keys("CTRL_LEFT", "S")
        editor["savedText"] = guest("cat " + shlex.quote(document))
        assert editor["savedText"] == "g22" + text, editor["savedText"]
        clipboard_text("g22" + text)
        print("PASS bidirectional Unicode clipboard, repeated keys and guest file save", flush=True)

        if args.protocol == "wayland":
            keys("CTRL_LEFT", "O")
            wait("graphics_window_present", sessionId=editor["sessionId"], parentWindowId=editor["windowId"])
            family = client.call("graphics.inspect_window", {"sessionId": editor["sessionId"], "windowId": editor["windowId"]})
            dialogs = [w for w in family["catalog"] if w["parentWindowId"] == editor["windowId"] and w["mapped"]]
            assert len(dialogs) == 1, family
            editor["fileDialog"] = dialogs[0]
            dialog_task = focused_host(editor["sessionId"], dialogs[0]["windowId"])
            editor["dialogCapture"] = capture(dialog_task, "file-dialog")
            keys("ESCAPE")
            wait("graphics_window_absent", sessionId=editor["sessionId"], windowId=dialogs[0]["windowId"])
            focused_host(editor["sessionId"], editor["windowId"])
            print("PASS guest file dialog mapping and keyboard dismissal", flush=True)

            keys("CTRL_LEFT", "O")
            opened = wait("graphics_window_present", sessionId=editor["sessionId"], parentWindowId=editor["windowId"])
            dialogs = [w for w in opened["session"]["windows"] if w["parentWindowId"] == editor["windowId"] and w["mapped"]]
            assert len(dialogs) == 1, opened
            dialog_task = focused_host(editor["sessionId"], dialogs[0]["windowId"])
            keys("CTRL_LEFT", "L")
            keys("CTRL_LEFT", "A")
            type_path(opened_document)
            editor["locationCapture"] = capture(dialog_task, "file-location")
            keys("ENTER")
            wait("graphics_window_absent", sessionId=editor["sessionId"], windowId=dialogs[0]["windowId"])
            assert editor["title"].count(Path(document).name) == 1, editor["title"]
            expected_title = editor["title"].replace(Path(document).name, Path(opened_document).name)
            wait("graphics_window_present", sessionId=editor["sessionId"], windowId=editor["windowId"], windowTitle=expected_title)
            focused_host(editor["sessionId"], editor["windowId"])
            keys("CTRL_LEFT", "A")
            keys("CTRL_LEFT", "C")
            editor["openedText"] = clipboard_text(opened_text)
            editor["openedCapture"] = capture(editor["taskId"], "opened-document")
            print("PASS file picker open, document title publication and actual content", flush=True)
        else:
            # X11 transients belong to native inspection, not the observed toplevel catalog.
            editor["fileDialogCoverage"] = "not tested: native-family event observation required"

        # A unique application config keeps another run's notation/preferences out of this case.
        calculator_command = shlex.join(["env", "GALCULATOR_CONFIG=/tmp/md-galculator-" + tag,
            *(["WAYLAND_DEBUG=client"] if args.trace_wayland else []), "/usr/bin/galculator"])
        calculator = launch("galculator", calculator_command)
        first, second, expected = ("2", "2", "4") if args.repeat_digit else ("7", "8", "15")
        calculator["expression"] = first + "+" + second
        keys(first)
        keys("NUMPAD_ADD")
        keys(second)
        keys("NUMPAD_ENTER")
        keys("CTRL_LEFT", "C")
        calculator["clipboard"] = client.call("clipboard.read_text", {"expectedText": expected, "timeoutMillis": 10000})
        calculator["capture"] = capture(calculator["taskId"], "calculator")
        assert calculator["clipboard"].get("matched") and calculator["clipboard"].get("text") == expected, calculator["clipboard"]
        calculator["result"] = calculator["clipboard"]["text"]
        active = client.call("graphics.list")["sessions"]
        assert all(any(s["sessionId"] == case["sessionId"] and any(w["windowId"] == case["windowId"]
            for w in s["windows"]) for s in active) for case in [editor, calculator])
        client.call("graphics.close_window", {"sessionId": calculator["sessionId"], "windowId": calculator["windowId"]})
        wait("graphics_window_absent", sessionId=calculator["sessionId"], windowId=calculator["windowId"])
        calculator["exit"] = command("timeout 25 " + shlex.quote(awaiter) + " " + shlex.quote(calculator["path"] + ".exit"))
        client.call("launch_desktop_entry", {"desktopPath": editor["path"], "displayId": display["id"],
            "placement": "display", "instance": "reuse"})
        wait("task_focused", taskId=editor["taskId"], displayId=display["id"])
        attached = wait("graphics_host_attached", sessionId=editor["sessionId"], windowId=editor["windowId"])
        assert len(attached["matchingHosts"]) == 1 and attached["matchingHosts"][0]["taskId"] == editor["taskId"]
        print("PASS simultaneous applications, independent close and recipe reuse", flush=True)
        client.call("graphics.close_window", {"sessionId": editor["sessionId"], "windowId": editor["windowId"]})
        wait("graphics_window_absent", sessionId=editor["sessionId"], windowId=editor["windowId"])
        editor["exit"] = command("timeout 25 " + shlex.quote(awaiter) + " " + shlex.quote(editor["path"] + ".exit"))
        final = client.call("get_state")
        assert not final["workspaces"] and final["homeLease"] is None
        completed = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        errors = []
        report["logs"] = {}
        for path in recipes:
            try:
                if command("test ! -f " + shlex.quote(path + ".log") + " || printf present"):
                    local = args.build / Path(path + ".log").name
                    transport.download(client, path + ".log", local, overwrite=True)
                    content = local.read_text()
                    report["logs"][path] = {"file": local.name, "bytes": local.stat().st_size,
                        "monitorWarnings": content.count("GDK_IS_MONITOR")}
            except Exception as error:
                errors.append(str(error))
        # A failed observation must still clean up a launch already accepted by the app.
        for session in client.call("graphics.list")["sessions"]:
            if session["name"].startswith("Guest recipe " + tag + " ") and session["sessionId"] not in sessions:
                sessions.append(session["sessionId"])
        for session in sessions:
            try:
                if any(s["sessionId"] == session for s in client.call("graphics.list")["sessions"]):
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
        if clipboard_changed:
            try:
                if clipboard.get("text"):
                    client.call("clipboard.write_text", {"text": clipboard.get("text", ""), "sensitive": clipboard.get("sensitive", False)})
                else:
                    client.call("clipboard.clear")
            except Exception as error:
                errors.append(str(error))
        client.call("console.close", {"sessionId": console})
        report["cleanupErrors"] = errors
        report["passed"] = completed and not errors
        suffix = "-repeat-digit" if args.repeat_digit else ""
        (args.build / (args.protocol + "-shortcuts" + suffix + "-results.json")).write_text(json.dumps(report, indent=2) + "\n")
        if errors:
            raise RuntimeError("Incomplete fixture cleanup: " + repr(errors))


if __name__ == "__main__":
    main()
