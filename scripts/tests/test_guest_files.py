"""The real guest helper over an abstract Unix socket; no Android or root needed."""
import array
import os
from pathlib import Path
import selectors
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import uuid


@unittest.skipUnless(hasattr(socket, "SCM_RIGHTS") and sys.platform.startswith(("linux", "android")), "Linux descriptor passing")
class GuestFilesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cc = shutil.which(os.environ.get("CC", "clang")) or shutil.which("cc")
        if not cc:
            raise unittest.SkipTest("C compiler required")
        cls.work = tempfile.TemporaryDirectory(prefix="magicdesk-guest-test-")
        cls.addClassCleanup(cls.work.cleanup)
        cls.helper = Path(cls.work.name) / "guest-files"
        source = Path(__file__).resolve().parents[2] / "native/magicdesk_guest_files.c"
        subprocess.run([cc, "-std=c17", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(cls.helper)], check=True)

    def launch(self, argv=None):
        endpoint = "magicdesk-test-" + uuid.uuid4().hex
        listener = socket.socket(socket.AF_UNIX)
        listener.bind("\0" + endpoint)
        listener.listen(1)
        listener.settimeout(10)
        self.addCleanup(listener.close)
        token = uuid.uuid4().hex + uuid.uuid4().hex
        environment = dict(os.environ, MAGICDESK_GUEST_FILES_SOCKET=endpoint, MAGICDESK_GUEST_FILES_TOKEN=token)
        if argv and argv[0] == "proot-distro":
            argv = argv[:2] + ["--env", "MAGICDESK_GUEST_FILES_SOCKET=" + endpoint,
                              "--env", "MAGICDESK_GUEST_FILES_TOKEN=" + token] + argv[2:]
        command = [str(self.helper), "--", sys.executable, "-c",
                   "import os; os.write(1, b'ready'); os.close(1); os.close(2); os.read(0, 1)"]
        process = subprocess.Popen(argv or command,
                                   env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def cleanup():
            if process.poll() is None:
                process.terminate()
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=5)
        self.addCleanup(cleanup)
        peer, _ = listener.accept()
        peer.settimeout(10)
        self.addCleanup(peer.close)
        supplied = b""
        while len(supplied) < 64:
            chunk = peer.recv(64 - len(supplied))
            self.assertTrue(chunk, "Helper disconnected during authorization")
            supplied += chunk
        self.assertEqual(token.encode(), supplied)
        return process, peer

    def assert_command_ready(self, process):
        output = {process.stdout: bytearray(), process.stderr: bytearray()}
        with selectors.DefaultSelector() as selector:
            for stream in output:
                selector.register(stream, selectors.EVENT_READ)
            deadline = time.monotonic() + 5
            # EVENT_WAIT: command readiness and pipe EOF; timeout detects retained writers.
            while selector.get_map():
                events = selector.select(max(0, deadline - time.monotonic()))
                self.assertTrue(events, "Command output pipes did not close")
                for key, _ in events:
                    chunk = os.read(key.fd, 4096)
                    if chunk:
                        output[key.fileobj].extend(chunk)
                    else:
                        selector.unregister(key.fileobj)
        self.assertEqual(b"ready", output[process.stdout])
        self.assertEqual(b"", output[process.stderr])
        self.assertIsNone(process.poll(), "Command must remain alive while serving files")

    def receive(self, peer, path):
        data = path.encode()
        peer.sendall(struct.pack("!BI", 1, len(data)) + data)
        reply, control, flags, _ = peer.recvmsg(1, socket.CMSG_SPACE(4))
        self.assertEqual(1, len(reply), "File service disconnected before replying")
        self.assertFalse(flags & socket.MSG_CTRUNC)
        if reply != b"\0":
            self.assertFalse(control)
            return None
        self.assertEqual(1, len(control))
        level, kind, data = control[0]
        self.assertEqual((socket.SOL_SOCKET, socket.SCM_RIGHTS), (level, kind))
        descriptors = array.array("i", data)
        self.assertEqual(1, len(descriptors))
        with os.fdopen(descriptors[0], "rb") as source:
            return source.read()

    def test_command_pipes_close_while_worker_retains_file_service(self):
        process, peer = self.launch()
        peer.sendall(b"\0")
        self.assert_command_ready(process)
        source = Path(self.work.name) / "quotes ' and spaces.txt"
        source.write_bytes(b"guest payload")
        self.assertEqual(b"guest payload", self.receive(peer, str(source)))
        self.assertIsNone(self.receive(peer, str(source.parent)))
        self.assertIsNone(self.receive(peer, str(source) + "-missing"))
        self.assertEqual(b"guest payload", self.receive(peer, str(source)))
        peer.shutdown(socket.SHUT_WR)
        self.assertEqual(b"", peer.recv(1))
        self.assertIsNone(process.poll(), "Closing the file channel must not kill its command")
        process.communicate(timeout=5)
        self.assertEqual(0, process.returncode)

    def test_command_exit_closes_file_service(self):
        process, peer = self.launch()
        peer.sendall(b"\0")
        self.assert_command_ready(process)
        source = Path(self.work.name) / "owner-exit.txt"
        source.write_bytes(b"owned payload")
        self.assertEqual(b"owned payload", self.receive(peer, str(source)))
        process.communicate(timeout=5)
        self.assertEqual(0, process.returncode)
        # EVENT_WAIT: owner death closes the service socket; socket timeout fails cleanup.
        self.assertEqual(b"", peer.recv(1))

    def test_rejected_authorization_never_launches_command(self):
        process, peer = self.launch()
        peer.sendall(b"\1")
        output, _ = process.communicate(timeout=5)
        self.assertNotEqual(0, process.returncode)
        self.assertEqual(b"", output)

    def test_malformed_path_ends_only_its_connection(self):
        process, peer = self.launch()
        peer.sendall(b"\0")
        self.assert_command_ready(process)
        other, other_peer = self.launch()
        other_peer.sendall(b"\0")
        self.assert_command_ready(other)
        peer.sendall(struct.pack("!BI", 1, 4) + b"/a\0b")
        self.assertEqual(b"", peer.recv(1))
        self.assertIsNone(process.poll())
        source = Path(self.work.name) / "other-session.txt"
        source.write_bytes(b"unaffected")
        self.assertEqual(b"unaffected", self.receive(other_peer, str(source)))

    @unittest.skipUnless(os.environ.get("MAGICDESK_GUEST_FILE_HELPER") and shutil.which("proot-distro"), "Opt-in PRoot fixture")
    def test_static_helper_resolves_guest_paths_without_android_root(self):
        source = Path(self.work.name) / "guest-only.txt"
        source.write_bytes(b"PRoot namespace")
        command = ["proot-distro", "login", "--isolated", "--bind", self.work.name + ":/magicdesk-files-test",
                   "--bind", os.environ.get("MAGICDESK_GUEST_FILE_HELPER") + ":/magicdesk-helper",
                   os.environ.get("MAGICDESK_TEST_PROOT", "ubuntu"), "--", "/magicdesk-helper", "--", "cat"]
        process, peer = self.launch(command)
        peer.sendall(b"\0")
        self.assertEqual(b"PRoot namespace", self.receive(peer, "/magicdesk-files-test/guest-only.txt"))
        peer.shutdown(socket.SHUT_WR)
        self.assertEqual(b"", peer.recv(1))
        process.communicate(timeout=5)
        self.assertEqual(0, process.returncode)
