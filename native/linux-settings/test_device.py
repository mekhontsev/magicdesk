#!/usr/bin/env python3
"""Opt-in MCP checks of the real Android/Linux UI adapters, without managed Desktop."""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import shlex
import socket
import time
import tomllib
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("transport", ROOT / "scripts/mcp-client.py")
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


def client():
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    return transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))


def await_target(c, graphics):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        targets = c.call("linux.list")["sessions"]
        for target in targets:
            if target["graphicsSessionId"] == graphics:
                return target["sessionId"]
        time.sleep(.1)  # Bounded fixture startup observation; not production UI polling.
    raise AssertionError({"missingAgent": graphics, "graphics": c.call("graphics.list")})


def inspect(c, session, **args):
    return c.call("ui.inspect", dict(sessionId=session, **args))


def test_console(c, session):
    context = c.call("linux.inspect", {"sessionId": session})
    console = c.call("console.open", {"linuxSessionId": session})["sessionId"]
    try:
        def execute(command):
            return c.call("console.execute", {"sessionId": console, "command": command})
        result = execute("id -u; printf '%s\\n' \"$DISPLAY\" \"$WAYLAND_DISPLAY\"; export MD_AUTOMATION_FIXTURE=kept; cd /")
        assert result["exitCode"] == 0, result
        assert result["output"].splitlines() == [str(context["guestUid"]), context["display"], context["waylandDisplay"]], result
        result = execute("printf '%s:%s' \"$MD_AUTOMATION_FIXTURE\" \"$PWD\"")
        assert result["output"] == "kept:/" and result["workingDirectory"] == "/", result
        result = execute("(exit 7)")
        assert result["exitCode"] == 7, result
        assert any(s["sessionId"] == console and s["linuxSessionId"] == session for s in c.call("console.list")["sessions"])
        with concurrent.futures.ThreadPoolExecutor() as pool:
            running = pool.submit(client().call, "console.execute", {"sessionId": console, "command": "sleep 60"})
            time.sleep(.3)  # Fixture overlap only; cancellation is verified by completion and shell restart.
            c.call("console.cancel", {"sessionId": console})
            try:
                running.result(timeout=10)
            except transport.ToolError:
                pass
        result = execute("printf 'resumed:%s' \"$PWD\"")
        assert result["exitCode"] == 0 and result["output"] == "resumed:/", result
        print(json.dumps({"console": session, "context": "retained", "cancel": "passed"}), flush=True)
    finally:
        c.call("console.close", {"sessionId": console})


def test_ui(c, session, toolkit):
    fixture = (ROOT / "native/linux-settings/fixtures/automation_gui.py").read_text()
    c.call("linux.launch", {"sessionId": session, "argv": ["python3", "-c", fixture, toolkit],
                         "environment": {"GTK_A11Y": "atspi", "QT_LINUX_ACCESSIBILITY_ALWAYS_ON": "1",
                                         "QT_ACCESSIBILITY": "1", "NO_AT_BRIDGE": "0"}})
    observed = c.call("ui.wait", {"sessionId": session, "selector": {"name": "Apply fixture"}, "timeoutMillis": 15000})
    assert observed["matched"], observed
    application = observed["matches"][0]["applicationId"]
    all_nodes = inspect(c, session, applicationId=application)
    assert "SECRET-MUST-NOT-LEAK" not in json.dumps(all_nodes), all_nodes
    passwords = [node for node in all_nodes["nodes"] if node.get("password")]
    assert passwords, all_nodes
    redacted = c.call("ui.read_text", {"elementId": passwords[0]["elementId"]})
    assert redacted["redacted"] and redacted["totalLength"] is None, redacted
    editors = [node for node in all_nodes["nodes"] if node.get("name") == "Editable fixture"]
    assert editors, all_nodes
    text = "unicode \u041f\u0440\u0438\u0432\u0435\u0442 \U0001f600"
    c.call("ui.perform", {"elementId": editors[0]["elementId"], "action": "set_text", "text": text})
    edited = c.call("ui.wait", {"sessionId": session, "applicationId": application, "selector": {"text": text}, "timeoutMillis": 5000})
    assert edited["matched"], edited
    read = c.call("ui.read_text", {"elementId": edited["matches"][0]["elementId"], "limit": 32768})
    assert read["text"] == text, read
    buttons = inspect(c, session, applicationId=application, selector={"name": "Apply fixture"})
    button = buttons["nodes"][0]
    action = "click" if "click" in button["actions"] else next(a for a in button["actions"] if a.startswith("atspi:"))
    with concurrent.futures.ThreadPoolExecutor() as pool:
        waiting = pool.submit(client().call, "ui.wait", {"sessionId": session, "applicationId": application,
                              "selector": {"text": "Result: 1"}, "timeoutMillis": 5000})
        accepted = c.call("ui.perform", {"elementId": button["elementId"], "action": action})
        assert accepted["accepted"], accepted
        changed = waiting.result()
        assert changed["matched"], changed
    missing = c.call("ui.wait", {"sessionId": session, "selector": {"name": "not-present-fixture"}, "timeoutMillis": 50})
    assert missing["timedOut"] and not missing["matched"], missing
    print(json.dumps({"toolkit": toolkit, "sessionId": session, "nodes": len(all_nodes["nodes"]), "complete": all_nodes["complete"]}), flush=True)


def test_deadlines(c, session):
    fixture = """
from PySide6.QtWidgets import QApplication, QWidget, QVBoxLayout, QPushButton
app = QApplication([])
window = QWidget()
window.setWindowTitle('AT-SPI deadline fixture')
layout = QVBoxLayout(window)
for index in range(1000):
    layout.addWidget(QPushButton('Deadline row %03d' % index))
window.show()
app.exec()
"""
    launch = c.call("linux.launch", {"sessionId": session, "argv": ["python3", "-c", fixture],
                    "environment": {"QT_LINUX_ACCESSIBILITY_ALWAYS_ON": "1", "QT_ACCESSIBILITY": "1"}})
    console = c.call("console.open", {"linuxSessionId": session})["sessionId"]
    pid = int(launch["pid"])
    try:
        found = c.call("ui.wait", {"sessionId": session, "selector": {"name": "Deadline row 000"}, "timeoutMillis": 15000})
        assert found["matched"], found
        application = found["matches"][0]["applicationId"]
        scope = dict(sessionId=session, applicationId=application)
        whole = c.call("ui.inspect", dict(scope, selector={"name": "Deadline row 999"}))
        assert whole["complete"] and whole["visitedNodes"] == 1002 and len(whole["nodes"]) == 1, whole
        last = c.call("ui.wait", dict(scope, selector={"name": "Deadline row 999"}, timeoutMillis=60000))
        assert last["matched"] and last["visitedNodes"] == 1002, last
        short = c.call("ui.wait", dict(scope, selector={"name": "absent-deadline-fixture"}, timeoutMillis=50))
        assert short["timedOut"] and not short["complete"] and short["truncationReason"] == "deadline", short
        assert short["durationMillis"] < 250 and short["visitedNodes"] < 1002, short
        first = c.call("ui.wait", dict(scope, selector={"name": "Deadline row 000"}, timeoutMillis=5000))
        assert first["matched"] and first["visitedNodes"] == 3 and first["truncationReason"] == "match", first

        def signal(name):
            result = c.call("console.execute", {"sessionId": console, "command": "kill -" + name + " " + str(pid)})
            assert result["exitCode"] == 0, result

        signal("STOP")
        try:
            stalled = c.call("ui.inspect", scope)
            assert not stalled["complete"] and stalled["errors"], stalled
            assert any(application in error and "GetAll" in error and "NoReply" in error for error in stalled["errors"]), stalled
            healthy = c.call("ui.wait", {"sessionId": session, "selector": {"name": "Apply fixture"}, "timeoutMillis": 3000})
            assert healthy["matched"], healthy
        finally:
            signal("CONT")
        resumed = c.call("ui.wait", dict(scope, selector={"name": "Deadline row 000"}, timeoutMillis=5000))
        assert resumed["matched"], resumed
        print(json.dumps({"deadlines": "passed", "fullTreeMillis": whole["durationMillis"],
                          "visitedNodes": whole["visitedNodes"], "lastMatchMillis": last["durationMillis"],
                          "shortWaitMillis": short["durationMillis"], "firstMatchMillis": first["durationMillis"],
                          "timeoutAttribution": stalled["errors"], "recovered": True}), flush=True)
    finally:
        c.call("console.execute", {"sessionId": console, "command": "kill -CONT " + str(pid) + "; kill -TERM " + str(pid)})
        c.call("console.close", {"sessionId": console})


def test_native(c, protocol):
    owned = []
    try:
        for toolkit in ("qt", "gtk4"):
            graphics = c.call("graphics.start", {"backend": "termux", "protocol": protocol, "name": "Automation " + toolkit,
                                                "command": "exec sleep 900"})["sessionId"]
            owned.append(graphics)
            session = await_target(c, graphics)
            test_bus_reuse(c, graphics, session)
            test_console(c, session)
            test_ui(c, session, toolkit)
            if toolkit == "qt":
                test_deadlines(c, session)
        targets = c.call("linux.list")["sessions"]
        assert len([t for t in targets if t["graphicsSessionId"] in owned]) == 2, targets
        session = await_target(c, owned[0])
        snapshot = inspect(c, session)
        handle = snapshot["nodes"][0]["elementId"]
        c.call("ui.release", {"sessionId": session})
        try:
            c.call("ui.read_text", {"elementId": handle})
            raise AssertionError("Released handle accepted")
        except transport.ToolError:
            pass
        assert inspect(c, await_target(c, owned[1]))["targetAvailable"]
        console = c.call("console.open", {"linuxSessionId": session})["sessionId"]
        try:
            with concurrent.futures.ThreadPoolExecutor() as pool:
                pending = pool.submit(client().call, "console.execute", {"sessionId": console, "command": "sleep 60"})
                time.sleep(.3)  # Exercise peer loss with an in-flight relay read.
                c.call("graphics.stop", {"sessionId": owned.pop(0)})
                try:
                    pending.result(timeout=10)
                    raise AssertionError("Ended guest command reported successful completion")
                except transport.ToolError:
                    pass
            assert not c.call("console.status", {"sessionId": console})["available"]
            try:
                c.call("console.execute", {"sessionId": console, "command": "printf unexpected-host-fallback"})
                raise AssertionError("Ended guest fell back to a host shell")
            except transport.ToolError:
                pass
        finally:
            c.call("console.close", {"sessionId": console})
    finally:
        for session in reversed(owned):
            c.call("graphics.stop", {"sessionId": session})


def test_bus_reuse(c, graphics, session):
    endpoint = "md-bus-reuse-" + uuid.uuid4().hex
    script = "import os,socket; s=socket.socket(socket.AF_UNIX); s.connect(" + repr("\0" + endpoint) + "); s.sendall(os.environ['DBUS_SESSION_BUS_ADDRESS'].encode()); s.close()"
    address = None
    with socket.socket(socket.AF_UNIX) as listener:
        listener.bind("\0" + endpoint)
        listener.listen(1)
        listener.settimeout(10)
        for _ in range(20):
            c.call("graphics.execute", {"sessionId": graphics, "command": shlex.join(["python3", "-c", script])})
            # EVENT_WAIT: exact command receipt; failure does not imply successful execution.
            peer, _ = listener.accept()
            with peer:
                peer.settimeout(5)
                with peer.makefile("rb") as result:
                    observed = result.read(4096)
            assert observed, "Missing session bus"
            if address is None:
                address = observed
            assert observed == address, "Each command started a different session bus"
        targets = [s["sessionId"] for s in c.call("linux.list")["sessions"] if s["graphicsSessionId"] == graphics]
        assert targets == [session], targets
    print(json.dumps({"busReuse": graphics, "commands": 20, "agents": 1}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-apk", type=Path)
    parser.add_argument("--call")
    parser.add_argument("--args", default="{}")
    parser.add_argument("--session", help="Run fixtures inside an explicitly selected existing Linux session")
    parser.add_argument("--protocol", default="x11", choices=("x11", "wayland"))
    parser.add_argument("--toolkit", default="gtk", choices=("gtk", "gtk4", "qt"))
    options = parser.parse_args()
    c = client()
    if options.install_apk:
        state = c.call("get_state")
        assert not state["workspaces"] and not state["graphics"], "Close owned sessions before install"
        path = "/data/local/tmp/md-linux-automation.apk"
        transport.upload(c, options.install_apk, path, overwrite=True)
        update = "linux-automation-" + uuid.uuid4().hex
        print("updateId=" + update, flush=True)
        c.call("app.update", {"path": path, "sha256": transport.digest(options.install_apk), "updateId": update})
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = c.call("app.update_status", {"updateId": update}, retry=True)
            if result["state"] in ("installed", "failed", "user_action_required"):
                print(json.dumps(result), flush=True)
                assert result["state"] == "installed", result
                print(json.dumps(c.call("get_state")["app"]), flush=True)
                return
            time.sleep(.25)
        raise TimeoutError(update)
    if options.session:
        test_console(c, options.session)
        test_ui(c, options.session, options.toolkit)
    elif options.call:
        print(json.dumps(c.call(options.call, json.loads(options.args)), ensure_ascii=False))
    else:
        test_native(c, options.protocol)


if __name__ == "__main__":
    main()
