import base64
import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("mcp_client", pathlib.Path(__file__).parents[1] / "mcp-client.py")
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)


class ClientTest(unittest.TestCase):
    def test_network_requires_explicit_plaintext_consent(self):
        with self.assertRaises(mcp.ToolError):
            mcp.Client("http://192.168.1.9:8765/mcp", "token")
        mcp.Client("http://127.0.0.1:8765/mcp", "token")
        mcp.Client("http://192.168.1.9:8765/mcp", "token", allow_plaintext=True)
        with self.assertRaises(mcp.ToolError):
            mcp.Client("https://user:password@example.test/mcp", "token")

    def test_upload_starts_at_acknowledged_offset(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "source.bin"
            source.write_bytes(b"binary\0content")
            received = bytearray(b"bin")

            class Fake:
                def call(self, name, args, **kwargs):
                    if name == "files.upload_begin":
                        return {"state": "active", "offset": 3, "chunkBytes": 4}
                    if name == "files.upload_commit":
                        return {"state": "completed"}
                    self.assert_offset(args)
                    received.extend(base64.b64decode(args["data"]))
                    return {"offset": len(received), "chunkBytes": 4}

                def assert_offset(self, args):
                    assert args["offset"] == len(received)

            self.assertEqual("completed", mcp.upload(Fake(), source, "/data/local/tmp/file", "transfer1234567890")["state"])
            self.assertEqual(source.read_bytes(), received)

    def test_download_keeps_existing_destination_and_verifies_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "target.bin"
            expected = b"binary\0content"

            class Fake:
                def call(self, name, args, **kwargs):
                    if name == "files.download_begin":
                        return {"state": "active", "size": len(expected), "sha256": mcp.hashlib.sha256(expected).hexdigest()}
                    if name == "files.download_finish":
                        return {}
                    data = expected[args["offset"]:]
                    return {"data": base64.b64encode(data).decode(), "nextOffset": len(expected)}

            mcp.download(Fake(), "/file", target, "transfer1234567890")
            self.assertEqual(expected, target.read_bytes())
            with self.assertRaises(mcp.ToolError):
                mcp.download(Fake(), "/file", target)

    def test_lost_update_response_does_not_repeat_install(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "app.apk"
            apk.write_bytes(b"apk")
            calls = []

            class Fake:
                timeout = 1

                def call(self, name, args=None, **kwargs):
                    calls.append(name)
                    if name == "get_state":
                        return {"app": {"versionCode": 2, "instanceId": str(len(calls))}, "workspaces": []}
                    if name == "app.update_status":
                        return ({"state": "unknown"} if calls.count(name) == 1 else
                                {"state": "installed", "versionCode": 2})
                    if name == "wait_for_state":
                        assert args["timeoutMillis"] == 30000
                        return {"matched": True}
                    if name == "files.upload_begin":
                        return {"state": "completed", "path": "/apk"}
                    if name == "app.update":
                        raise ConnectionResetError("app replaced")
                    raise AssertionError(name)

            result = mcp.update(Fake(), apk, "update1234567890")
            self.assertTrue(result["processChanged"])
            self.assertEqual(1, calls.count("app.update"))
            self.assertNotIn("close_desktop", calls)

    def test_unknown_completion_never_repeats_an_accepted_update(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "app.apk"
            apk.write_bytes(b"apk")
            calls = []

            class Fake:
                timeout = 1

                def call(self, name, args=None, **kwargs):
                    calls.append(name)
                    if name == "get_state":
                        return {"app": {"versionCode": 2, "instanceId": "new"}, "workspaces": []}
                    if name == "app.update_status":
                        return {"state": "completion_unknown", "sha256": mcp.digest(apk)}
                    raise AssertionError(name)

            with self.assertRaisesRegex(mcp.ToolError, "outcome is unknown"):
                mcp.update(Fake(), apk, "update1234567890")
            self.assertNotIn("app.update", calls)

    def test_update_waits_for_each_workspace_and_stops_on_incomplete_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "app.apk"
            apk.write_bytes(b"apk")
            for incomplete_display in (None, 0, 52):
                with self.subTest(incomplete_display=incomplete_display):
                    calls = []

                    class Fake:
                        timeout = 1
                        pending = None
                        remaining = [0, 52]
                        installed = False

                        def call(self, name, args=None, **kwargs):
                            calls.append((name, args))
                            if name == "get_state":
                                return {"app": {"versionCode": 2,
                                                "instanceId": "new" if self.installed else "old"},
                                        "workspaces": [{"displayId": display_id}
                                                       for display_id in reversed(self.remaining)]}
                            if name == "app.update_status":
                                return {"state": "installed" if self.installed else "unknown",
                                        "versionCode": 2}
                            if name == "close_desktop":
                                assert self.pending is None
                                assert args["displayId"] == self.remaining[0]
                                self.pending = args["displayId"]
                                return {}
                            if name == "wait_for_state":
                                assert args["condition"] == "desktop_inactive"
                                assert args["timeoutMillis"] == 30000
                                if "displayId" in args:
                                    assert args["displayId"] == self.pending
                                    if self.pending == incomplete_display:
                                        return {"matched": False, "waitExpired": True}
                                    self.remaining.remove(self.pending)
                                    self.pending = None
                                else:
                                    assert not self.remaining
                                return {"matched": True}
                            if name == "files.upload_begin":
                                assert self.pending is None and not self.remaining
                                return {"state": "completed", "path": "/apk"}
                            if name == "app.update":
                                assert self.pending is None and not self.remaining
                                self.installed = True
                                return {}
                            raise AssertionError(name)

                    if incomplete_display is None:
                        self.assertTrue(mcp.update(Fake(), apk, "update1234567890")["processChanged"])
                        self.assertEqual([0, 52], [args["displayId"] for name, args in calls
                                                  if name == "close_desktop"])
                    else:
                        with self.assertRaisesRegex(mcp.ToolError, "cleanup has not completed"):
                            mcp.update(Fake(), apk, "update1234567890")
                        self.assertFalse(any(name in ("files.upload_begin", "app.update")
                                             for name, args in calls))


if __name__ == "__main__":
    unittest.main()
