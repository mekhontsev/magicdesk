#!/usr/bin/env python3
"""Test-only GTK peer, installed inside a prepared Linux guest."""
import hashlib
import pathlib
import tempfile
import urllib.parse

import gi
gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, Gtk

directory = tempfile.TemporaryDirectory(prefix="magicdesk-content-")
source = pathlib.Path(directory.name) / "guest.txt"
source.write_text("Guest file transfer\n")
window = Gtk.Window(title="Guest content test")
window.set_default_size(600, 450)
body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
body.set_border_width(20)
window.add(body)
entry = Gtk.Entry(placeholder_text="IME and clipboard")
entry.connect("changed", lambda widget: print("TEXT:" + widget.get_text(), flush=True))
body.pack_start(entry, False, False, 0)
text = Gtk.TextView()
text.get_buffer().set_text("Guest selection")
body.pack_start(text, True, True, 0)
files = Gtk.Button(label="Drag guest file / drop file here")
targets = [Gtk.TargetEntry.new("text/uri-list", 0, 0)]
files.drag_source_set(Gdk.ModifierType.BUTTON1_MASK, targets, Gdk.DragAction.COPY)
files.drag_dest_set(Gtk.DestDefaults.ALL, targets, Gdk.DragAction.COPY)
files.connect("drag-data-get", lambda widget, context, selection, info, time:
              selection.set_uris([source.as_uri()]))


def receive(widget, context, x, y, selection, info, time):
    ok = True
    for uri in selection.get_uris() or []:
        path = pathlib.Path(urllib.parse.unquote(urllib.parse.urlparse(uri).path))
        try:
            data = path.read_bytes()
            print(f"FILE:{path}:{len(data)}:{hashlib.sha256(data).hexdigest()}", flush=True)
        except OSError as error:
            ok = False
            print(f"ERROR:{error}", flush=True)
    Gtk.drag_finish(context, ok, False, time)


files.connect("drag-data-received", receive)
body.pack_start(files, False, False, 0)
window.connect("destroy", Gtk.main_quit)
window.show_all()
Gtk.main()
directory.cleanup()
