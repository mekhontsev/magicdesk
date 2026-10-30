"""Typed bridge checks on a private session bus; no Android or user bus changes."""
import contextlib
import ctypes
import importlib.util
import os
from pathlib import Path
import select
import signal
import socket
import struct
import subprocess
import sys
import time
import unittest
import uuid

HELPER = Path(os.environ.get("MAGICDESK_SETTINGS_HELPER", "app/build/linuxSettings/libmagicdesk_linux_settings.so")).resolve()


def pidfd_open(pid):
    if hasattr(os, "pidfd_open"):
        return os.pidfd_open(pid)
    # Android Python omits pidfd_open; 434 is shared by the ARM64 and x86-64 test hosts.
    libc = ctypes.CDLL(None, use_errno=True)
    fd = libc.syscall(ctypes.c_long(434), ctypes.c_int(pid), ctypes.c_uint(0))
    if fd < 0:
        raise OSError(ctypes.get_errno(), "pidfd_open")
    return fd


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


@contextlib.contextmanager
def native_session(command=None, admit=True, extra_env=None, ignore_sigchld=False):
    endpoint = "md-session-test-" + uuid.uuid4().hex
    token = "cd" * 32
    with socket.socket(socket.AF_UNIX) as server:
        server.bind("\0" + endpoint)
        server.listen(1)
        server.settimeout(5)
        env = dict(os.environ, MAGICDESK_AUTOMATION_SOCKET=endpoint,
                   MAGICDESK_AUTOMATION_TOKEN=token, MAGICDESK_AUTOMATION_HELPER=str(HELPER))
        env.pop("MAGICDESK_SESSION_BUS_ADDRESS", None)
        env.pop("DBUS_SESSION_BUS_ADDRESS", None)
        env.update(extra_env or {})
        command = command or [sys.executable, "-c", "import os;assert not any(k.startswith('MAGICDESK_AUTOMATION_') for k in os.environ);print(os.environ['DBUS_SESSION_BUS_ADDRESS'])"]
        process = subprocess.Popen([str(HELPER), "--session", *command], env=env,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   preexec_fn=(lambda: signal.signal(signal.SIGCHLD, signal.SIG_IGN)) if ignore_sigchld else None)
        peer, pidfd = None, None
        try:
            peer, _ = server.accept()
            peer.settimeout(5)
            auth = b""
            while len(auth) < 64:
                chunk = peer.recv(64 - len(auth))
                assert chunk, "Admission closed"
                auth += chunk
            assert auth == token.encode()
            peer.sendall(b"\0" if admit else b"\1")
            hello = receive(peer) if admit else None
            if hello:
                assert hello["kind"] == 3, hello
                pidfd = pidfd_open(int(hello["values"][1][1]))
            yield peer, process, hello
        finally:
            if peer:
                peer.close()
            try:
                process.communicate(timeout=8)
                if pidfd is not None:
                    # EVENT_WAIT: closing the session owner must terminate the bus agent.
                    assert select.select([pidfd], [], [], 8)[0], "Session agent leaked"
            finally:
                if pidfd is not None:
                    os.close(pidfd)
                if process.poll() is None:
                    process.kill()
                    process.communicate()


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

    def test_native_bus_survives_launcher_and_closes_with_owner(self):
        with native_session() as (peer, process, hello):
            output, errors = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, errors)
            self.assertEqual(output.decode().strip(), hello["values"][7][1])
            self.assertEqual(self.call(peer, "GetId")["kind"], 0)
            pid = int(self.call(peer, "GetConnectionUnixProcessID", [("s", "org.freedesktop.DBus")])["values"][0][1])
            with os.fdopen(pidfd_open(pid), "rb", buffering=0) as bus:
                peer.shutdown(socket.SHUT_RDWR)
                self.assertTrue(select.select([bus], [], [], 8)[0], "Private bus leaked after owner closed")

    def test_native_bus_reuse_does_not_spawn_more_agents(self):
        with native_session() as (peer, process, hello):
            output, errors = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, errors)
            address = output.decode().strip()
            bus_id = self.call(peer, "GetId")["values"]
            env = dict(os.environ, MAGICDESK_SESSION_BUS_ADDRESS=address,
                       MAGICDESK_AUTOMATION_SOCKET="invalid", MAGICDESK_AUTOMATION_TOKEN="invalid")
            for _ in range(20):
                result = subprocess.run([str(HELPER), "--session", "dbus-send", "--session", "--print-reply",
                                         "--dest=org.freedesktop.DBus", "/", "org.freedesktop.DBus.GetId"],
                                        env=env, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(bus_id[0][1], result.stdout.decode())
            self.assertEqual(self.call(peer, "GetId")["values"], bus_id)

    def test_native_bus_rejected_owner_does_not_start_application(self):
        with native_session(admit=False) as (_, process, _):
            output, errors = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertEqual(output, b"")
            self.assertIn(b"Linux session bus unavailable", errors)

    def test_native_bus_missing_command_reports_failure(self):
        with native_session(command=["/no-such-magicdesk-test-executable"]) as (peer, process, _):
            _, errors = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 127, errors)
            self.assertEqual(self.call(peer, "GetId")["kind"], 0)

    def test_native_bus_preserves_ignored_sigchld_and_failed_start_cleanup(self):
        command = [sys.executable, "-c", "import signal;assert signal.getsignal(signal.SIGCHLD)==signal.SIG_IGN"]
        for admit in (True, False):
            with native_session(command=command, admit=admit, ignore_sigchld=True) as (_, process, _):
                _, errors = process.communicate(timeout=5)
                self.assertEqual(process.returncode, 0 if admit else 1, errors)

    def test_native_bus_replaces_inherited_activation_context(self):
        stale = dict.fromkeys(("DBUS_SESSION_BUS_PID", "DBUS_SESSION_BUS_WINDOWID",
                               "DBUS_STARTER_ADDRESS", "DBUS_STARTER_BUS_TYPE"), "stale")
        command = [sys.executable, "-c", "import os;assert all(k not in os.environ for k in " + repr(list(stale))
                   + ");print(os.environ['DBUS_SESSION_BUS_ADDRESS'])"]
        with native_session(command=command, extra_env=stale) as (_, process, hello):
            output, errors = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, errors)
            address = hello["values"][7][1]
            self.assertEqual(output.decode().strip(), address)
            env = dict(os.environ, **stale, MAGICDESK_SESSION_BUS_ADDRESS=address)
            result = subprocess.run([str(HELPER), "--session", *command], env=env, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, output)

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
