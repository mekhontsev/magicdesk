#!/usr/bin/env python3
"""One graphical contract for an explicitly prepared glibc or musl store."""
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
    parser.add_argument("--runtime", required=True, help="Staged native bundle directory, not an APK replacement")
    parser.add_argument("--installed", action="store_true", help="Use the installed APK's guest CLI instead of staged executables")
    parser.add_argument("--keyboard-directory", required=True)
    parser.add_argument("--protocol", choices=["x11", "wayland"], required=True)
    parser.add_argument("--recipes", type=Path, required=True)
    parser.add_argument("--trace", help="Optional native test observer executable")
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
    result = {"id": tag, "store": args.store, "protocol": args.protocol, "app": state["app"], "passed": False}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    display = session = None
    log = args.runtime + "/gui-" + tag + ".log"
    receipt = log + ".exit"
    document = "/tmp/userspace-" + tag + ".txt"
    runner = "magicdesk-guest" if args.installed else args.runtime + "/libmagicdesk_guest_run.so"

    def command(value):
        response = client.call("console.execute", {"sessionId": console, "command": value})
        assert response["exitCode"] == 0, response
        return response["output"]

    def guest(value):
        return command(shlex.join(["timeout", "60", runner,
            "--store", args.store, "--home", "/home/shell", "--", "/bin/sh", "-c", value]))

    def wait(condition, **selection):
        # EVENT_WAIT: native catalog/Android host event; timeout fails this workflow.
        response = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 20000, **selection})
        assert response.get("matched"), response
        return response

    try:
        result["identity"] = guest("id; cat /etc/os-release; getent passwd 2000")
        assert "uid=2000" in result["identity"]
        guest("printf 'seed\\n' > " + document)
        if not args.installed:
            command("ln -sf libmagicdesk_guest_run.so " + shlex.quote(args.runtime + "/magicdesk-guest"))
        display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})
        session = client.call("graphics.start", {"protocol": args.protocol, "backend": "shell", "connection": "routed",
            "name": "Userspace " + tag, "keyboardDirectory": args.keyboard_directory})["sessionId"]
        info = wait("graphics_ready", sessionId=session)["session"]
        assert info["executorUid"] == 2000 and info["serverUid"] not in (0, 2000)
        result["serverUid"] = info["serverUid"]
        recipe = subprocess.check_output(["java", "-cp", str(args.recipes),
            "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", args.protocol,
            args.store, "/home/shell", "/usr/bin/mousepad " + document], text=True, timeout=20)
        script = ("{ timeout 180 " + ("" if args.installed else "env PATH=" + shlex.quote(args.runtime + ":/system/bin"))
            + " " + (shlex.quote(args.trace) + " " if args.trace else "") + "/system/bin/sh -c " + shlex.quote(recipe)
            + "; r=$?; printf '%s\\n' \"$r\" > " + shlex.quote(receipt)
            + "; } > " + shlex.quote(log) + " 2>&1")
        client.call("graphics.execute", {"sessionId": session, "command": script})
        mapped = wait("graphics_window_present", sessionId=session)["session"]
        windows = [w for w in mapped["windows"] if w["mapped"] and not w["parentWindowId"]]
        assert len(windows) == 1 and Path(document).name in windows[0]["title"], windows
        window = windows[0]["windowId"]
        client.call("graphics.open_window", {"sessionId": session, "windowId": window,
            "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
        hosts = wait("graphics_host_attached", sessionId=session, windowId=window)["matchingHosts"]
        assert len(hosts) == 1 and not hosts[0]["managed"]
        task = hosts[0]["taskId"]
        wait("task_focused", taskId=task, displayId=display["id"])
        wait("app_ready", taskId=task, displayId=display["id"])
        for keys in [["CTRL_LEFT", "A"], ["M"], ["U"], ["S"], ["L"], ["ENTER"], ["CTRL_LEFT", "S"]]:
            client.call("input.key_chord", {"displayId": display["id"], "keys": keys})
        capture = client.call_result("capture_screenshot", {"taskId": task})
        png = base64.b64decode(next(c["data"] for c in capture["content"] if c["type"] == "image"), validate=True)
        picture = args.build / ("userspace-" + tag + ".png")
        picture.write_bytes(png)
        rgb = subprocess.check_output(["magick", "png:-", "-depth", "8", "rgb:-"], input=png, timeout=20)
        assert len(set(zip(rgb[::3], rgb[1::3], rgb[2::3]))) > 100
        result["capture"] = picture.name
        result["saved"] = guest("cat " + document)
        assert result["saved"] == "musl\n", result
        client.call("graphics.close_window", {"sessionId": session, "windowId": window})
        wait("graphics_window_absent", sessionId=session, windowId=window)
        result["exit"] = command("timeout 25 " + shlex.quote(args.runtime + "/md-await-exit") + " " + shlex.quote(receipt))
        assert command("cat " + shlex.quote(receipt)).strip() == "0"
        final = client.call("get_state")
        assert final["homeLease"] == state["homeLease"] and final["workspaces"] == state["workspaces"]
        result["passed"] = True
        print("PASS stock userspace: real pixels, keyboard editing, saved file, zero process exit", flush=True)
    except Exception as error:
        result["failure"] = str(error)
        raise
    finally:
        errors = []
        try:
            result["log"] = command("test ! -f " + shlex.quote(log) + " || cat " + shlex.quote(log))
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
        result["cleanupErrors"] = errors
        result["passed"] &= not errors
        (args.build / (args.protocol + "-userspace.json")).write_text(json.dumps(result, indent=2) + "\n")
        if errors:
            raise RuntimeError(errors)


if __name__ == "__main__":
    main()
