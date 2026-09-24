#!/usr/bin/env python3
"""GTK peer for Android IME, clipboard and file-drag checks in a selected Linux guest."""
import os
import hashlib
import pathlib
import tempfile
import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, Gtk

window = Gtk.Window(title="Wayland guest exchange")
window.set_default_size(700, 500)
window.connect("destroy", Gtk.main_quit)
body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin=24)
window.add(body)
status = Gtk.Label(label=f"Guest UID {os.getuid()}")
body.pack_start(status, False, False, 0)


def report(message):
    status.set_text(message)
    print(message, flush=True)


for purpose, label in [(Gtk.InputPurpose.FREE_FORM, "Text"),
                       (Gtk.InputPurpose.EMAIL, "Email"), (Gtk.InputPurpose.PIN, "PIN")]:
    field = Gtk.Entry(placeholder_text=label)
    field.set_input_purpose(purpose)
    if purpose == Gtk.InputPurpose.PIN:
        field.set_visibility(False)
    field.connect("changed", lambda entry: print("edited", entry.get_input_purpose(), len(entry.get_text()), flush=True))
    body.pack_start(field, False, False, 0)

clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
copy = Gtk.Button(label="Copy guest text")
copy.connect("clicked", lambda _: clipboard.set_text("Wayland guest transfer \u03b1\u03b2", -1))
body.pack_start(copy, False, False, 0)
paste = Gtk.Button(label="Read clipboard")
paste.connect("clicked", lambda _: clipboard.request_text(lambda _clip, text, _data: report("Clipboard: " + str(text)), None))
body.pack_start(paste, False, False, 0)

export_dir = tempfile.TemporaryDirectory(prefix="magicdesk-export-")
export = pathlib.Path(export_dir.name) / "guest.txt"
export.write_text("Guest file transfer\n", encoding="utf-8")
source = Gtk.Button(label="Drag guest file")
target = Gtk.TargetEntry.new("text/uri-list", 0, 0)
source.drag_source_set(Gdk.ModifierType.BUTTON1_MASK, [target], Gdk.DragAction.COPY)
source.connect("drag-data-get", lambda _widget, _drag, data, _info, _time: data.set_uris([export.as_uri()]))
body.pack_start(source, False, False, 0)
drop = Gtk.Label(label="Drop file here")
drop.drag_dest_set(Gtk.DestDefaults.ALL, [target], Gdk.DragAction.COPY)


def received(_widget, context, _x, _y, data, _info, time):
    from urllib.parse import unquote, urlparse
    try:
        paths = [pathlib.Path(unquote(urlparse(uri).path)) for uri in data.get_uris() or []]
        if not paths:
            raise ValueError("No file URIs")
        for path in paths:
            content = path.read_bytes()
            report(f"FILE:{len(content)}:{hashlib.sha256(content).hexdigest()}")
        Gtk.drag_finish(context, True, False, time)
    except Exception as error:
        report("ERROR " + str(error))
        Gtk.drag_finish(context, False, False, time)


drop.connect("drag-data-received", received)
body.pack_start(drop, True, True, 0)
dialog = Gtk.Button(label="Open dialog")


def show_dialog(_button):
    child = Gtk.MessageDialog(transient_for=window, modal=True, buttons=Gtk.ButtonsType.OK_CANCEL,
                              text="Guest dependent dialog")
    child.connect("response", lambda dialog, response: (report(f"Dialog response {response}"), dialog.destroy()))
    child.show_all()


dialog.connect("clicked", show_dialog)
body.pack_start(dialog, False, False, 0)
window.show_all()
Gtk.main()
export_dir.cleanup()
