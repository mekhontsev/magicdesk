"""Typed bridge checks on a private session bus; no Android or user bus changes."""
import contextlib
import importlib.util
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import time
import unittest
import uuid

HELPER = Path(os.environ.get("MAGICDESK_SETTINGS_HELPER", "app/build/linuxSettings/libmagicdesk_linux_settings.so")).resolve()


def number(value):
    return struct.pack(">I", value)


def text(value):
    value = value.encode("utf-8")
    return number(len(value)) + value


def value(signature, item):
    result = text(signature)
    if signature == "v":
        return result + text(item[0]) + number(1) + value(*item)
    if signature[0] in "a({":
        return result + number(len(item)) + b"".join(value(*child) for child in item)
    return result + text(str(item))


class Reader:
    def __init__(self, data):
        self.data, self.at = data, 0

    def number(self):
        n = struct.unpack_from(">I", self.data, self.at)[0]
        self.at += 4
        return n

    def text(self):
        n = self.number()
        s = self.data[self.at:self.at + n].decode("utf-8")
        self.at += n
        return s

    def value(self):
        sig = self.text()
        if sig[0] in "a(v{":
            if sig == "v":
                self.text()
            return sig, [self.value() for _ in range(self.number())]
        return sig, self.text()


def receive(peer):
    def read(n):
        result = b""
        while len(result) < n:
            data = peer.recv(n - len(result))
            if not data:
                raise EOFError("Agent closed")
            result += data
        return result
    r = Reader(read(struct.unpack(">I", read(4))[0]))
    result = dict(id=r.number(), kind=r.number(), bus=r.number())
    for key in ("sender", "path", "interface", "member", "error", "detail"):
        result[key] = r.text()
    result["values"] = [r.value() for _ in range(r.number())]
    assert r.at == len(r.data), result
    return result


@contextlib.contextmanager
def agent():
    endpoint = "md-bus-test-" + uuid.uuid4().hex
    token = "ab" * 32
    server = socket.socket(socket.AF_UNIX)
    server.bind("\0" + endpoint)
    server.listen(1)
    server.settimeout(5)
    env = dict(os.environ, MAGICDESK_AUTOMATION_SOCKET=endpoint, MAGICDESK_AUTOMATION_TOKEN=token)
    env.pop("DBUS_SESSION_BUS_ADDRESS", None)
    process = subprocess.Popen(["dbus-run-session", "--", str(HELPER), "--automation",
                                sys.executable, "-c", "import os,sys;assert 'MAGICDESK_AUTOMATION_TOKEN' not in os.environ;sys.stdin.read()"],
                               env=env, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    peer = None
    try:
        peer, _ = server.accept()
        peer.settimeout(5)
        auth = b""
        while len(auth) < 64:
            auth += peer.recv(64 - len(auth))
        assert auth == token.encode()
        peer.sendall(b"\0")
        assert receive(peer)["kind"] == 3
        yield peer
    finally:
        if peer:
            peer.close()
        server.close()
        process.stdin.close()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            raise
        errors = process.stderr.read().decode()
        process.stderr.close()
        assert process.returncode == 0, errors


class BusTest(unittest.TestCase):
    def call(self, peer, member, args=(), interface="org.freedesktop.DBus", path="/org/freedesktop/DBus",
             destination="org.freedesktop.DBus", bus=0, timeout=1000):
        request = number(17) + number(1) + number(bus) + number(timeout)
        request += text(destination) + text(path) + text(interface) + text(member)
        request += number(len(args)) + b"".join(value(*arg) for arg in args)
        peer.sendall(number(len(request)) + request)
        while True:
            result = receive(peer)
            if result["id"] == 17:
                return result

    def test_list_names_and_typed_credentials(self):
        with agent() as peer:
            result = self.call(peer, "ListNames")
            self.assertEqual(result["kind"], 0, result)
            self.assertIn(("s", "org.freedesktop.DBus"), result["values"][0][1])
            result = self.call(peer, "GetConnectionCredentials", [("s", "org.freedesktop.DBus")])
            self.assertEqual(result["kind"], 0, result)
            self.assertEqual(result["values"][0][0], "a{sv}")

    def test_errors_and_invalid_types_do_not_kill_connection(self):
        with agent() as peer:
            result = self.call(peer, "NoSuchMethod", [("u", "-1")])
            self.assertEqual(result["error"], "INVALID_ARGUMENT", result)
            for args in ([("v", ("s", "nested"))], [("(su)", [("s", "name"), ("u", "3")])],
                         [("a{sv}", [("{sv}", [("s", "k"), ("v", ("s", "v"))])])]):
                result = self.call(peer, "NoSuchMethod", args)
                self.assertEqual(result["kind"], 1, result)
                self.assertEqual(result["error"], "org.freedesktop.DBus.Error.UnknownMethod", result)
            result = self.call(peer, "GetId")
            self.assertEqual(result["kind"], 0, result)
            self.assertTrue(result["values"][0][1])

    def test_signal_subscription(self):
        with agent() as peer:
            rule = "type='signal',interface='org.freedesktop.DBus',member='NameOwnerChanged'"
            data = number(18) + number(2) + number(0) + number(1000) + text(rule)
            peer.sendall(number(len(data)) + data)
            while receive(peer)["id"] != 18:
                pass
            result = self.call(peer, "RequestName", [("s", "io.github.magicdesk.Test"), ("u", "0")])
            self.assertEqual(result["kind"], 0, result)
            event = receive(peer)
            self.assertEqual(event["kind"], 2, event)
            self.assertEqual(event["member"], "NameOwnerChanged", event)

    def test_controlled_launch_reports_exec_failure_and_preserves_bus(self):
        with agent() as peer:
            for executable, expected in (("/no-such-magicdesk-test-executable", 1), (sys.executable, 0)):
                argv = [executable, "-c", "pass"]
                data = number(19) + number(6) + number(0) + number(1000)
                data += number(len(argv)) + b"".join(text(arg) for arg in argv) + number(0)
                peer.sendall(number(len(data)) + data)
                result = receive(peer)
                self.assertEqual(result["id"], 19)
                self.assertEqual(result["kind"], expected, result)
            self.assertEqual(self.call(peer, "GetId")["kind"], 0)

    @unittest.skipUnless(importlib.util.find_spec("gi"), "requires the GLib D-Bus fixture")
    def test_accessibility_address_and_method_share_one_timeout(self):
        service = """
import os
from gi.repository import Gio, GLib
connection = Gio.bus_get_sync(Gio.BusType.SESSION, None)
xml = '<node><interface name="org.a11y.Bus"><method name="GetAddress"><arg type="s" direction="out"/></method><method name="Slow"><arg type="s" direction="out"/></method></interface></node>'
info = Gio.DBusNodeInfo.new_for_xml(xml).interfaces[0]
def handle(conn, sender, path, interface, method, params, invocation):
    def reply():
        invocation.return_value(GLib.Variant('(s)', (os.environ['DBUS_SESSION_BUS_ADDRESS'],)))
        return False
    GLib.timeout_add(150, reply)
connection.register_object('/org/a11y/bus', info, handle, None, None)
Gio.bus_own_name_on_connection(connection, 'org.a11y.Bus', Gio.BusNameOwnerFlags.NONE, None, None)
GLib.MainLoop().run()
"""
        with agent() as peer:
            argv = [sys.executable, "-c", service]
            data = number(19) + number(6) + number(0) + number(1000)
            data += number(len(argv)) + b"".join(text(arg) for arg in argv) + number(0)
            peer.sendall(number(len(data)) + data)
            launched = receive(peer)
            self.assertEqual(launched["kind"], 0, launched)
            deadline = time.monotonic() + 5
            while self.call(peer, "NameHasOwner", [("s", "org.a11y.Bus")])["values"][0][1] != "true":
                self.assertLess(time.monotonic(), deadline, "fixture bus name not acquired")
                time.sleep(.01)  # Bounded test-service startup, not production observation polling.
            started = time.monotonic()
            result = self.call(peer, "Slow", interface="org.a11y.Bus", path="/org/a11y/bus",
                               destination="org.a11y.Bus", bus=2, timeout=250)
            self.assertEqual(result["kind"], 1, result)
            self.assertIn(result["error"], ("org.freedesktop.DBus.Error.NoReply", "org.freedesktop.DBus.Error.Timeout"))
            self.assertLess(time.monotonic() - started, 1, result)
            self.assertEqual(self.call(peer, "GetId", bus=2)["kind"], 0)


if __name__ == "__main__":
    unittest.main()
