#!/usr/bin/env python3
"""Real Linux Vulkan WSI through the guest runtime and MagicDesk's graphical hosts."""
import _bootstrap
import argparse
from magicdesk_executor import load_transport
import base64
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
    parser.add_argument("--protocol", choices=["x11", "wayland"], default="wayland")
    parser.add_argument("--frames", type=int, default=600, help="Bounded rendering workload, not a readiness delay")
    parser.add_argument("--trace", help="Optional native fault observer")
    parser.add_argument("--display", type=int, help="Borrow this connected non-phone display")
    parser.add_argument("--icd", default="/opt/md-gpu/turnip.json", help="Guest Turnip ICD manifest")
    parser.add_argument("--home", default="/home/shell", help="Guest home directory")
    parser.add_argument("--user", default="", help="Guest account or numeric identity")
    args = parser.parse_args()
    if not 1 <= args.frames <= 1000000:
        parser.error("--frames must be between 1 and 1000000")
    transport = load_transport()
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    before = client.call("get_state")
    assert before["shell"]["uid"] == 2000 and before["readiness"]["interactive"]
    assert not before["readiness"]["deviceLocked"]
    tag = uuid.uuid4().hex
    report = {"id": tag, "protocol": args.protocol, "frames": args.frames,
              "store": args.store, "runtime": args.runtime, "installed": args.installed,
              "passed": False, "app": before["app"]}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    display = session = None
    owned_display = False
    log = args.runtime + "/gpu-" + tag + ".log"
    receipt = log + ".exit"

    def command(text):
        response = client.call("console.execute", {"sessionId": console, "command": text})
        assert response["exitCode"] == 0, response
        return response["output"]

    def wait(condition, **selection):
        # EVENT_WAIT: client/host event; expiration fails the workflow.
        response = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 30000, **selection})
        assert response.get("matched"), response
        return response

    try:
        if not args.installed:
            command("ln -sf libshroot_guest_run.so " + shlex.quote(args.runtime + "/shroot"))
        runner = "shroot" if args.installed else args.runtime + "/libshroot_guest_run.so"
        report["preflight"] = command(shlex.join([runner, "--store", args.store,
            *(["--user", args.user] if args.user else []), "--", "/bin/sh", "-lec",
            "command -v vulkaninfo; command -v vkcube; test -r " + shlex.quote(args.icd)]))
        if args.display is None:
            display = client.call("create_display", {"type": "virtual", "width": 1000, "height": 700, "densityDpi": 160})
            owned_display = True
        else:
            displays = client.call("list_displays")["displays"]
            display = next((d for d in displays if d["id"] == args.display), None)
            assert display and not display["defaultDisplay"] and display["state"] == 2, displays
        report["display"] = display
        session = client.call("graphics.start", {"protocol": args.protocol, "backend": "shell", "connection": "routed",
            "name": "Guest Vulkan " + tag, "keyboardDirectory": args.keyboard_directory})["sessionId"]
        ready = wait("graphics_ready", sessionId=session)["session"]
        assert ready["executorUid"] == 2000 and ready["serverUid"] not in (0, 2000)
        root_host = None
        if args.protocol == "x11":
            client.call("graphics.open_window", {"sessionId": session, "windowId": 0,
                "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
            root_host = wait("graphics_host_attached", sessionId=session, windowId=0)["matchingHosts"][0]
        from graphical_recipe import compile_recipe
        report["recipeSources"] = compile_recipe(args.build / "recipe-classes")
        recipe = subprocess.check_output(["java", "-cp", str(args.build / "recipe-classes"),
            "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", args.protocol, args.store, args.home,
            "export VK_DRIVER_FILES=" + shlex.quote(args.icd) + "; vulkaninfo --summary; "
            # XCB vkcube has no ICCCM identity; a normal WM publishes its WM_STATE.
            + ("env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=softpipe xfwm4 --compositor=off & "
               "timeout 20 md-x11-desktop manager || exit $?; " if args.protocol == "x11" else "") +
            "exec vkcube --wsi " + ("xcb" if args.protocol == "x11" else "wayland")
            + " --c " + str(args.frames) + " --width 640 --height 480", args.user], text=True)
        recipe_file = args.build / ("gpu-" + tag + ".sh")
        recipe_file.write_text(recipe + "\n")
        remote_recipe = args.runtime + "/" + recipe_file.name
        report["recipeUpload"] = transport.upload(client, recipe_file, remote_recipe)
        launch = ("{ timeout 90 " + ("" if args.installed else "env PATH=" + shlex.quote(args.runtime + ":/system/bin"))
            + " " + (shlex.quote(args.trace) + " " if args.trace else "") + "/system/bin/sh " + shlex.quote(remote_recipe)
            + "; r=$?; printf '%s\\n' \"$r\" > " + shlex.quote(receipt)
            + "; } > " + shlex.quote(log) + " 2>&1")
        client.call("graphics.execute", {"sessionId": session, "command": launch})
        mapped = wait("graphics_window_present", sessionId=session)["session"]
        window = next(w["windowId"] for w in mapped["windows"] if w["mapped"])
        if root_host:
            host = root_host
        else:
            client.call("graphics.open_window", {"sessionId": session, "windowId": window,
                "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
            host = wait("graphics_host_attached", sessionId=session, windowId=window)["matchingHosts"][0]
        assert not host["managed"]
        assert host["displayId"] == display["id"], host
        wait("app_ready", taskId=host["taskId"], displayId=display["id"])
        for frame in range(2):
            capture = client.call_result("capture_screenshot", {"taskId": host["taskId"]})
            png = base64.b64decode(next(c["data"] for c in capture["content"] if c["type"] == "image"))
            (args.build / (args.protocol + "-gpu-" + str(frame) + ".png")).write_bytes(png)
            rgb = subprocess.check_output(["magick", "png:-", "-depth", "8", "rgb:-"], input=png)
            assert len(set(zip(rgb[::3], rgb[1::3], rgb[2::3]))) > 100
            if frame:
                assert previous != rgb, "GPU presentation did not change"
            previous = rgb
        report["exit"] = command("timeout 60 " + shlex.quote(args.runtime + "/md-await-exit") + " " + shlex.quote(receipt))
        assert command("cat " + shlex.quote(receipt)).strip() == "0"
        wait("graphics_window_absent", sessionId=session, windowId=window)
        report["log"] = command("cat " + shlex.quote(log))
        assert "DRIVER_ID_MESA_TURNIP" in report["log"]
        after = client.call("get_state")
        assert before["homeLease"] == after["homeLease"] and before["workspaces"] == after["workspaces"]
        report["passed"] = True
        print("PASS guest Vulkan: hardware ICD, changing WSI frames, " + str(args.frames)
            + " submissions and zero exit", flush=True)
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        errors = []
        try:
            report["log"] = command("test ! -f " + shlex.quote(log) + " || cat " + shlex.quote(log))
        except Exception as error:
            errors.append(str(error))
        if session:
            try:
                client.call("graphics.stop", {"sessionId": session})
                wait("graphics_session_absent", sessionId=session)
            except Exception as error:
                errors.append(str(error))
        if display and owned_display:
            try:
                client.call("remove_display", {"displayId": display["id"], "uniqueId": display["uniqueId"]})
                wait("display_absent", displayId=display["id"])
            except Exception as error:
                errors.append(str(error))
        client.call("console.close", {"sessionId": console})
        report["cleanupErrors"] = errors
        report["passed"] &= not errors
        (args.build / (args.protocol + "-gpu.json")).write_text(json.dumps(report, indent=2) + "\n")
        if errors:
            raise RuntimeError(errors)


if __name__ == "__main__":
    main()
