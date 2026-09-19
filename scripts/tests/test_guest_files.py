"""The real guest helper over an abstract Unix socket; no Android or root needed."""
import array
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
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
        process = subprocess.Popen(argv or [str(self.helper), "--", "/bin/sh", "-c", "printf ready"],
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

    def receive(self, peer, path):
        data = path.encode()
        peer.sendall(struct.pack("!I", len(data)) + data)
        reply, control, flags, _ = peer.recvmsg(1, socket.CMSG_SPACE(4))
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
        self.assertEqual((b"ready", b""), process.communicate(timeout=5))
        source = Path(self.work.name) / "quotes ' and spaces.txt"
        source.write_bytes(b"guest payload")
        self.assertEqual(b"guest payload", self.receive(peer, str(source)))
        self.assertIsNone(self.receive(peer, str(source.parent)))
        self.assertIsNone(self.receive(peer, str(source) + "-missing"))
        self.assertEqual(b"guest payload", self.receive(peer, str(source)))
        peer.shutdown(socket.SHUT_WR)
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
        process.communicate(timeout=5)
        peer.sendall(struct.pack("!I", 4) + b"/a\0b")
        self.assertEqual(b"", peer.recv(1))

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
