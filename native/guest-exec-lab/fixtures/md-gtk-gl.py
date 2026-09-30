#!/usr/bin/python3
"""Actual GTK GL rendering with event-qualified frame and input receipts."""
import ctypes
import json
import os
import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, Gdk

gl = ctypes.CDLL("libGL.so.1")
gl.glGetString.argtypes = [ctypes.c_uint]
gl.glGetString.restype = ctypes.c_char_p
gl.glClearColor.argtypes = [ctypes.c_float] * 4
gl.glScissor.argtypes = [ctypes.c_int] * 4
gl.glViewport.argtypes = [ctypes.c_int] * 4
gl.glEnable.argtypes = [ctypes.c_uint]
gl.glDisable.argtypes = [ctypes.c_uint]
gl.glClear.argtypes = [ctypes.c_uint]
gl.glGetError.restype = ctypes.c_uint

window = Gtk.Window(title="Guest GTK GL")
window.set_default_size(640, 480)
area = Gtk.GLArea()
area.set_required_version(3, 2)
window.add(area)
phase = 0
pending = None
completed = set()
renderer = None


def fail(message):
    print("FAIL GTK GL:", message, flush=True)
    os._exit(1)


def render(widget, context):
    global pending, renderer
    if widget.get_error():
        fail(widget.get_error())
    if renderer is None:
        renderer = {key: gl.glGetString(value).decode("utf-8")
                    for key, value in [("vendor", 0x1F00), ("renderer", 0x1F01), ("version", 0x1F02)]}
    width = widget.get_allocated_width() * widget.get_scale_factor()
    height = widget.get_allocated_height() * widget.get_scale_factor()
    gl.glViewport(0, 0, width, height)
    gl.glEnable(0x0C11)
    colors = [(0.9, 0.1, 0.2), (0.1, 0.8, 0.2), (0.1, 0.2, 0.9), (0.9, 0.8, 0.1)]
    for i in range(4):
        gl.glScissor((i % 2) * width // 2, (i // 2) * height // 2, width // 2, height // 2)
        gl.glClearColor(*colors[(i + phase) % 4], 1)
        gl.glClear(0x4000)
    gl.glDisable(0x0C11)
    if gl.glGetError():
        fail("GL error after rendering")
    pending = phase
    return True


def painted(clock):
    if pending is not None and pending not in completed:
        completed.add(pending)
        print("MD_FRAME_READY " + json.dumps({**renderer, "phase": pending}), flush=True)
        if pending == 1:
            print("MD_INPUT_READY", flush=True)


def key(widget, event):
    global phase
    if event.keyval == Gdk.KEY_a:
        phase = 1
        area.queue_render()
    return False


def close(widget, event):
    if completed != {0, 1}:
        fail("input/render stages incomplete: " + str(completed))
    Gtk.main_quit()
    return False


area.connect("render", render)
area.connect("realize", lambda widget: widget.get_frame_clock().connect("after-paint", painted))
window.connect("key-press-event", key)
window.connect("delete-event", close)
window.show_all()
Gtk.main()
