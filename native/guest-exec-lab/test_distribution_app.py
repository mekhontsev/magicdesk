#!/usr/bin/env python3
"""Stock distribution application startup, presentation and protocol closure."""
import argparse
import base64
import importlib.util
import io
import json
import re
from pathlib import Path
import shlex
import subprocess
import tomllib
import uuid
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile


OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"


def office_document(path, application):
    """Small actual ODF input, parsed and saved by the unmodified application."""
    kind = "text" if application == "writer" else "spreadsheet"
    mime = "application/vnd.oasis.opendocument." + kind
    root = ET.Element("{" + OFFICE + "}document-content", {"{" + OFFICE + "}version": "1.3"})
    body = ET.SubElement(root, "{" + OFFICE + "}body")
    content = ET.SubElement(body, "{" + OFFICE + "}" + kind)
    if application == "calc":
        sheet = ET.SubElement(content, "{" + TABLE + "}table", {"{" + TABLE + "}name": "Fixture"})
        row = ET.SubElement(sheet, "{" + TABLE + "}table-row")
        content = ET.SubElement(row, "{" + TABLE + "}table-cell", {"{" + OFFICE + "}value-type": "string"})
    ET.SubElement(content, "{" + TEXT + "}p").text = "Original fixture"
    manifest = ET.Element("{" + MANIFEST + "}manifest", {"{" + MANIFEST + "}version": "1.3"})
    for name, media in [("/", mime), ("content.xml", "text/xml")]:
        ET.SubElement(manifest, "{" + MANIFEST + "}file-entry",
            {"{" + MANIFEST + "}full-path": name, "{" + MANIFEST + "}media-type": media})
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("mimetype", mime, compress_type=zipfile.ZIP_STORED)
        archive.writestr("content.xml", ET.tostring(root, encoding="utf-8", xml_declaration=True))
        archive.writestr("META-INF/manifest.xml", ET.tostring(manifest, encoding="utf-8", xml_declaration=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("--store", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--keyboard-directory", required=True)
    parser.add_argument("--protocol", choices=["x11", "wayland"], required=True)
    parser.add_argument("--application", choices=["gimp", "writer", "calc", "firefox", "chromium", "blender", "gles", "gtkgl"], required=True)
    parser.add_argument("--x11-manager", action="store_true", help="Test inside an ordinary X11 viewer with the distribution's xfwm4")
    parser.add_argument("--software", action="store_true", help="Explicit Mesa llvmpipe software-rendering control")
    parser.add_argument("--gpu-prefix", help="Explicit guest directory containing test-only Mesa GL and Turnip")
    parser.add_argument("--trace", help="Optional test-only native fault observer")
    parser.add_argument("--trace-wayland", action="store_true", help="Retain client protocol events for input diagnostics")
    args = parser.parse_args()
    if args.software and args.gpu_prefix:
        parser.error("--software and --gpu-prefix are mutually exclusive")
    if args.x11_manager and args.protocol != "x11":
        parser.error("--x11-manager requires X11")
    if args.application == "gles" and args.protocol != "wayland":
        parser.error("The EGL fixture currently uses the Wayland client")
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location("transport", repo / "scripts/mcp-client.py")
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / ".codex/config.toml").read_text())["mcp_servers"]["magicdesk"]
    client = transport.Client(config["url"], config["http_headers"]["Authorization"].removeprefix("Bearer "))
    before = client.call("get_state")
    assert before["shell"]["uid"] == 2000 and before["readiness"]["interactive"]
    assert not before["readiness"]["deviceLocked"] and not before["readiness"]["keyguardLocked"]
    tag = uuid.uuid4().hex
    result = {"id": tag, "application": args.application, "protocol": args.protocol,
              "store": args.store, "app": before["app"], "software": args.software,
              "x11Manager": args.x11_manager, "gpuPrefix": args.gpu_prefix, "passed": False}
    console = client.call("console.open", {"directory": "/data/local/tmp"})["sessionId"]
    display = session = None
    clipboard = None
    log = args.runtime + "/" + args.application + "-" + tag + ".log"
    receipt = log + ".exit"
    document = "/tmp/md-office-" + tag + (".odt" if args.application == "writer" else ".ods")
    gl_fixture = "/tmp/md-gtk-gl-" + tag + ".py"
    edited_text = "guest42"
    expected_text = edited_text if args.application == "writer" else edited_text + "Original fixture"
    runner = shlex.join([args.runtime + "/libmagicdesk_guest_run.so", "--store", args.store, "--"])

    def command(value):
        response = client.call("console.execute", {"sessionId": console, "command": value})
        assert response["exitCode"] == 0, response
        return response["output"]

    def wait(condition, **selection):
        # EVENT_WAIT: exact native/host lifecycle event; deadline fails this run.
        response = client.call("wait_for_state", {"condition": condition, "timeoutMillis": 30000, **selection})
        assert response.get("matched"), response
        return response

    browser_page = "data:text/html," + urllib.parse.quote("<title>MagicDesk guest fixture</title><h1>Linux browser</h1><input autofocus value='Native guest input'>")
    applications = {
        "gimp": "cp /home/shell/.config/GIMP/3.0/gimprc /tmp/md-gimprc-" + tag + "; "
            "printf '\\n(show-welcome-dialog no)\\n' >> /tmp/md-gimprc-" + tag + "; "
            "exec gimp --new-instance --no-splash --gimprc /tmp/md-gimprc-" + tag,
        "writer": "exec libreoffice --norestore --nologo " + shlex.quote(document),
        "calc": "exec libreoffice --norestore --nologo " + shlex.quote(document),
        "firefox": "mkdir /tmp/firefox-" + tag + "; exec firefox-esr --no-remote --new-instance --profile /tmp/firefox-" + tag + " " + shlex.quote(browser_page),
        "chromium": "exec chromium --no-first-run --no-default-browser-check --user-data-dir=/tmp/chromium-" + tag
            + " --ozone-platform=" + args.protocol + " " + shlex.quote(browser_page),
        "blender": "exec blender --factory-startup --python-expr "
            + shlex.quote((repo / "native/guest-exec-lab/fixtures/md-blender.py").read_text()),
        "gles": "exec es2gears_wayland -info",
        "gtkgl": "exec python3 " + shlex.quote(gl_fixture),
    }
    try:
        if args.application == "gtkgl":
            source = args.runtime + "/" + Path(gl_fixture).name
            result["fixture"] = transport.upload(client, str(repo / "native/guest-exec-lab/fixtures/md-gtk-gl.py"), source)
            command("cat " + shlex.quote(source) + " | " + runner + " /bin/sh -c "
                    + shlex.quote("cat > " + shlex.quote(gl_fixture)))
        if args.application in ("writer", "calc"):
            local = args.build / Path(document).name
            office_document(local, args.application)
            source = args.runtime + "/" + local.name
            result["documentInput"] = transport.upload(client, str(local), source)
            command("cat " + shlex.quote(source) + " | " + runner + " /bin/sh -c "
                    + shlex.quote("cat > " + shlex.quote(document)))
        display = client.call("create_display", {"type": "virtual", "width": 1200, "height": 800, "densityDpi": 160})
        session = client.call("graphics.start", {"protocol": args.protocol, "backend": "shell", "connection": "routed",
            "name": "Distribution " + args.application, "keyboardDirectory": args.keyboard_directory})["sessionId"]
        info = wait("graphics_ready", sessionId=session)["session"]
        assert info["executorUid"] == 2000 and info["serverUid"] not in (0, 2000)
        root_host = None
        if args.x11_manager:
            client.call("graphics.open_window", {"sessionId": session, "windowId": 0,
                "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
            root_host = wait("graphics_host_attached", sessionId=session, windowId=0)["matchingHosts"][0]
        prefix = "export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe; " if args.software else ""
        if args.trace_wayland or (args.application == "writer" and args.protocol == "wayland"):
            prefix += "export WAYLAND_DEBUG=client; "
        if args.x11_manager:
            prefix += "env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe xfwm4 --compositor=off & timeout 20 md-x11-desktop manager || exit $?; "
        if args.gpu_prefix:
            prefix += "export " + shlex.join([
                "LD_LIBRARY_PATH=" + args.gpu_prefix + "/lib",
                "LIBGL_DRIVERS_PATH=" + args.gpu_prefix + "/lib/dri",
                "__EGL_VENDOR_LIBRARY_FILENAMES=" + args.gpu_prefix + "/share/glvnd/egl_vendor.d/50_mesa.json",
                "VK_DRIVER_FILES=" + args.gpu_prefix + "/turnip.json",
                "MESA_LOADER_DRIVER_OVERRIDE=zink", "GALLIUM_DRIVER=zink", "LIBGL_KOPPER_DRI2=true",
            ]) + "; "
        recipe = subprocess.check_output(["java", "-cp", str(args.build / "recipe-classes"),
            "io.github.mekhontsev.magicdesk.GraphicalRecipe", "routed", args.protocol,
            args.store, "/home/shell", prefix + applications[args.application]], text=True)
        launch = ("{ timeout 150 env PATH=" + shlex.quote(args.runtime + ":/system/bin")
            + " " + (shlex.quote(args.trace) + " " if args.trace else "") + "/system/bin/sh -c " + shlex.quote(recipe)
            + "; r=$?; printf '%s\\n' \"$r\" > " + shlex.quote(receipt)
            + "; } > " + shlex.quote(log) + " 2>&1")
        result["launch"] = launch
        client.call("graphics.execute", {"sessionId": session, "command": launch})
        titles = {"blender": "Blender", "gimp": "GNU Image Manipulation Program"}
        if args.application in ("writer", "calc"):
            titles[args.application] = Path(document).name + " \u2014 LibreOffice " + args.application.title()
        selection = {"windowTitle": titles[args.application]} if args.application in titles else {}
        mapped = wait("graphics_window_present", sessionId=session, **selection)["session"]
        result["windows"] = mapped["windows"]
        window = next(w for w in mapped["windows"] if w["mapped"] and not w["parentWindowId"]
                      and (not selection or w["title"] == selection["windowTitle"]))
        if args.application in ("writer", "calc"):
            if args.protocol == "wayland":
                assert window["appId"] == "libreoffice-" + args.application, window
            else:
                # X11 publishes WM_CLASS, not Wayland's per-component app_id.
                assert window["appId"] in ("Soffice", "libreoffice-" + args.application), window
        if args.application == "firefox":
            assert window["appId"] != "crashreporter" and "Crash" not in window["title"], window
        if root_host:
            host = root_host
        else:
            client.call("graphics.open_window", {"sessionId": session, "windowId": window["windowId"],
                "placement": "display", "displayId": display["id"], "uniqueId": display["uniqueId"]})
            host = wait("graphics_host_attached", sessionId=session, windowId=window["windowId"])["matchingHosts"][0]
        assert not host["managed"]
        wait("task_focused", taskId=host["taskId"], displayId=display["id"])
        wait("app_ready", taskId=host["taskId"], displayId=display["id"])
        result["ui"] = client.call("ui.inspect", {"taskId": host["taskId"]})
        if args.application in ("blender", "gtkgl"):
            rendered = command(shlex.join(["timeout", "50", args.runtime + "/md-await-exit",
                "--marker", "MD_FRAME_READY ", log]))
            result["renderer"] = json.loads(rendered.removeprefix("MD_FRAME_READY "))
            if args.software:
                assert "llvmpipe" in result["renderer"]["renderer"].lower(), result["renderer"]
            if args.gpu_prefix:
                assert "zink" in result["renderer"]["renderer"].lower(), result["renderer"]
                assert "turnip" in result["renderer"]["renderer"].lower(), result["renderer"]
        if args.application == "firefox":
            wait("graphics_window_present", sessionId=session, windowId=window["windowId"],
                 windowTitle="MagicDesk guest fixture \u2014 Mozilla Firefox")
        if args.application == "gimp":
            client.call("input.key_chord", {"displayId": display["id"], "keys": ["ESCAPE"]})
            client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "N"]})
            if args.protocol == "wayland":
                dialog = wait("graphics_window_present", sessionId=session, windowTitle="Create a New Image")["session"]
                child = next(w["windowId"] for w in dialog["windows"] if w["mapped"] and w["title"] == "Create a New Image")
                client.call("graphics.close_window", {"sessionId": session, "windowId": child})
                wait("graphics_window_absent", sessionId=session, windowId=child)
            else:
                wait("graphics_family_present", sessionId=session, windowId=window["windowId"], memberType="dialog")
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["ESCAPE"]})
                wait("graphics_family_absent", sessionId=session, windowId=window["windowId"], memberType="dialog")
        if args.application in ("writer", "calc"):
            original_clip = client.call("clipboard.read_text")
            assert original_clip.get("access") in ("available", "empty") and not original_clip.get("truncated"), "Cannot preserve clipboard"
            assert not original_clip.get("itemCount") or original_clip.get("mimeTypes") == ["text/plain"], "Cannot replace non-text clipboard"
            clipboard = original_clip
            if args.application == "writer":
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "A"]})
            else:
                # Calc's documented document-focus shortcut, independent of toolbar focus.
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "F6"]})
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "MOVE_HOME"]})
            client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "C"]})
            # EVENT_WAIT: copied data proves that the document, not just its host, is ready.
            result["contentBefore"] = client.call("clipboard.read_text", {"expectedText": "Original fixture", "timeoutMillis": 10000})
            assert result["contentBefore"].get("text", "").strip() == "Original fixture", result["contentBefore"]
            if args.application == "calc":
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["ESCAPE"]})
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["F2"]})
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["MOVE_HOME"]})
            for character in edited_text:
                client.call("input.key_chord", {"displayId": display["id"], "keys": [character.upper()]})
            if args.application == "calc":
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["ENTER"]})
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "MOVE_HOME"]})
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "C"]})
                # EVENT_WAIT: copied cell content acknowledges edit completion, not just injected keys.
                result["cellAfter"] = client.call("clipboard.read_text", {"expectedText": edited_text + "Original fixture", "timeoutMillis": 10000})
                assert result["cellAfter"].get("text", "").strip() == edited_text + "Original fixture", result["cellAfter"]
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["ESCAPE"]})
            else:
                if args.protocol == "wayland":
                    # EVENT_WAIT: Writer's VCL edit may be queued after GTK receives the keys.
                    # Await its editor publication before sending the next copy/save command.
                    command(shlex.join(["timeout", "25", args.runtime + "/md-await-exit",
                        "--contains", '.set_surrounding_text(' + json.dumps(edited_text)
                        + f', {len(edited_text.encode())}, {len(edited_text.encode())})', log]))
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "A"]})
                client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "C"]})
                # EVENT_WAIT: client publication acknowledges the edited document before saving/closing.
                result["contentAfter"] = client.call("clipboard.read_text", {"expectedText": expected_text, "timeoutMillis": 10000})
                assert result["contentAfter"].get("text", "").strip() == expected_text, result["contentAfter"]
            client.call("input.key_chord", {"displayId": display["id"], "keys": ["CTRL_LEFT", "S"]})
        capture = client.call_result("capture_screenshot", {"taskId": host["taskId"]})
        png = base64.b64decode(next(c["data"] for c in capture["content"] if c["type"] == "image"), validate=True)
        picture = args.build / (args.application + "-" + args.protocol + "-" + tag + ".png")
        picture.write_bytes(png)
        rgb = subprocess.check_output(["magick", "png:-", "-depth", "8", "rgb:-"], input=png)
        if args.application == "gtkgl":
            for color in [(230, 26, 51), (26, 204, 51), (26, 51, 230), (230, 204, 26)]:
                assert sum(all(abs(a - b) <= 3 for a, b in zip(pixel, color))
                           for pixel in zip(rgb[::3], rgb[1::3], rgb[2::3])) > 10000, color
            client.call("input.key_chord", {"displayId": display["id"], "keys": ["A"]})
            # EVENT_WAIT: GTK after-paint for the input-driven second image.
            command(shlex.join(["timeout", "25", args.runtime + "/md-await-exit",
                "--marker", "MD_INPUT_READY", log]))
            second = client.call_result("capture_screenshot", {"taskId": host["taskId"]})
            second_png = base64.b64decode(next(c["data"] for c in second["content"] if c["type"] == "image"))
            second_rgb = subprocess.check_output(["magick", "png:-", "-depth", "8", "rgb:-"], input=second_png)
            assert len(second_rgb) == len(rgb) and sum(a != b for a, b in zip(rgb, second_rgb)) > 100000
            (args.build / (args.application + "-" + args.protocol + "-" + tag + "-input.png")).write_bytes(second_png)
        else:
            assert len(set(zip(rgb[::3], rgb[1::3], rgb[2::3]))) > 100
        result["capture"] = picture.name
        client.call("graphics.close_window", {"sessionId": session, "windowId": window["windowId"]})
        wait("graphics_window_absent", sessionId=session, windowId=window["windowId"])
        result["exit"] = command("timeout 25 " + shlex.quote(args.runtime + "/md-await-exit") + " " + shlex.quote(receipt))
        assert command("cat " + shlex.quote(receipt)).strip() == "0"
        if args.application in ("writer", "calc"):
            encoded = command(runner + " /bin/cat " + shlex.quote(document) + " | base64")
            saved = base64.b64decode(encoded)
            with zipfile.ZipFile(io.BytesIO(saved)) as archive:
                content = ET.fromstring(archive.read("content.xml"))
            paragraphs = ["".join(p.itertext()) for p in content.iter("{" + TEXT + "}p")]
            assert expected_text in paragraphs, paragraphs
            result["documentOutput"] = {"path": document, "paragraphs": paragraphs, "bytes": len(saved)}
            (args.build / ("saved-" + Path(document).name)).write_bytes(saved)
            print("PASS", args.application, "GUI edit/save, process exit and independent ODF readback", flush=True)
        after = client.call("get_state")
        assert before["homeLease"] == after["homeLease"] and before["workspaces"] == after["workspaces"]
        result["passed"] = True
        print("PASS", args.application, args.protocol, "pixels and zero-status closure", flush=True)
    except Exception as error:
        result["failure"] = str(error)
        text = command("test ! -f " + shlex.quote(log) + " || cat " + shlex.quote(log))
        pids = sorted(set(re.findall(r"\(soffice:(\d+)\)", text)))
        if pids:
            result["processWaits"] = command("for p in " + " ".join(pids)
                + "; do for t in /proc/$p/task/*; do printf '%s ' \"$t\"; cat \"$t/wchan\"; "
                + "printf '\\n'; done; done")
        raise
    finally:
        errors = []
        if clipboard is not None:
            try:
                if clipboard.get("text"):
                    client.call("clipboard.write_text", {"text": clipboard["text"], "sensitive": clipboard.get("sensitive", False)})
                else:
                    client.call("clipboard.clear")
            except Exception as error:
                errors.append(str(error))
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
        report = json.dumps(result, indent=2) + "\n"
        (args.build / (args.application + "-" + args.protocol + "-distribution.json")).write_text(report)
        (args.build / (args.application + "-" + args.protocol + "-" + tag + ".json")).write_text(report)
        if errors:
            raise RuntimeError(errors)


if __name__ == "__main__":
    main()
