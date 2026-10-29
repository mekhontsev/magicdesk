"""Small real toolkit fixture; all interactions go through the production semantic UI tools."""
import sys

TEXT = "alpha\n\u041f\u0440\u0438\u0432\u0435\u0442 \U0001f600"

if sys.argv[1] in ("gtk", "gtk4"):
    import gi
    modern = sys.argv[1] == "gtk4"
    gi.require_version("Gtk", "4.0" if modern else "3.0")
    from gi.repository import Gtk, GLib
    Gtk.init()
    window = Gtk.Window(title="MagicDesk automation GTK")
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
    if modern:
        window.set_child(box)
    else:
        window.add(box)
    entry = Gtk.Entry()
    if modern:
        entry.update_property([Gtk.AccessibleProperty.LABEL], ["Editable fixture"])
    else:
        entry.get_accessible().set_name("Editable fixture")
    entry.set_text("initial")
    password = Gtk.Entry()
    password.set_input_purpose(Gtk.InputPurpose.PASSWORD)
    password.set_visibility(False)
    password.set_text("SECRET-MUST-NOT-LEAK")
    result = Gtk.Label(label="Result: 0")
    button = Gtk.Button(label="Apply fixture")
    button.connect("clicked", lambda *_: result.set_text("Result: 1"))
    for widget in (entry, password, button, result):
        if modern:
            box.append(widget)
        else:
            box.pack_start(widget, False, False, 4)
    if modern:
        loop = GLib.MainLoop()
        window.connect("close-request", lambda *_: loop.quit())
        window.present()
        loop.run()
    else:
        window.connect("destroy", Gtk.main_quit)
        window.show_all()
        Gtk.main()
else:
    from PySide6.QtWidgets import QApplication, QWidget, QVBoxLayout, QLineEdit, QPushButton, QLabel
    app = QApplication([])
    window = QWidget()
    window.setWindowTitle("MagicDesk automation Qt")
    layout = QVBoxLayout(window)
    entry = QLineEdit("initial")
    entry.setAccessibleName("Editable fixture")
    password = QLineEdit("SECRET-MUST-NOT-LEAK")
    password.setEchoMode(QLineEdit.Password)
    result = QLabel("Result: 0")
    button = QPushButton("Apply fixture")
    button.clicked.connect(lambda: result.setText("Result: 1"))
    for widget in (entry, password, button, result):
        layout.addWidget(widget)
    window.show()
    app.exec()
