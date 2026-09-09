#!/usr/bin/env python3
"""Bounded file transport and same-app update client; no ADB dependency."""
import argparse
import base64
import hashlib
import ipaddress
import json
import os
import shutil
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class ToolError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ToolError("MCP redirects are refused; verify the configured endpoint")


class Client:
    def __init__(self, endpoint, token, timeout=300, allow_plaintext=False):
        url = urllib.parse.urlsplit(endpoint)
        if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password:
            raise ToolError("Expected an HTTP(S) endpoint without embedded credentials")
        try:
            local = ipaddress.ip_address(url.hostname).is_loopback
        except ValueError:
            local = False
        if url.scheme == "http" and not local and not allow_plaintext:
            raise ToolError("Unencrypted network transport requires --allow-plaintext-network (use a trusted test LAN or protected VPN)")
        if not token or "\n" in token or "\r" in token:
            raise ToolError("A valid MCP bearer token is required")
        self.endpoint = endpoint
        self.token = token
        self.timeout = timeout
        self.sequence = 0
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, name, arguments=None, retry=False, deadline=None):
        deadline = deadline or time.monotonic() + self.timeout
        backoff = 0.25
        while True:
            self.sequence += 1
            body = json.dumps({"jsonrpc": "2.0", "id": self.sequence, "method": "tools/call",
                               "params": {"name": name, "arguments": arguments or {}}}).encode()
            request = urllib.request.Request(self.endpoint, data=body, headers={
                "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                "Authorization": "Bearer " + self.token})
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("MCP reconnect deadline expired; operation outcome may still be pending")
            try:
                with self.opener.open(request, timeout=min(120, remaining)) as response:
                    payload = response.read(2 * 1024 * 1024 + 1)
                if len(payload) > 2 * 1024 * 1024:
                    raise ToolError("MCP response exceeds client limit")
                rpc = json.loads(payload)
                if "error" in rpc:
                    raise ToolError(str(rpc["error"].get("message", "JSON-RPC error")))
                result = rpc["result"]["structuredContent"]
                if not result.get("success"):
                    error = result.get("error") or {}
                    raise ToolError(json.dumps({"message": result.get("message"), "error": error}, ensure_ascii=False))
                return result["data"]
            except urllib.error.HTTPError as error:
                if not retry or error.code < 500:
                    raise ToolError("MCP HTTP error " + str(error.code)) from error
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if not retry:
                    raise
            # Protocol reconnect backoff, never a guessed Android operation-completion delay.
            time.sleep(min(backoff, max(0, deadline - time.monotonic())))
            backoff = min(2, backoff * 2)


def digest(path):
    with Path(path).open("rb") as stream:
        value = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def upload(client, source, target, transfer_id=None, overwrite=False):
    source = Path(source)
    transfer_id = transfer_id or uuid.uuid4().hex
    print("Upload transferId=" + transfer_id, file=sys.stderr)
    result = client.call("files.upload_begin", {"transferId": transfer_id, "path": target,
                         "size": source.stat().st_size, "sha256": digest(source), "overwrite": overwrite}, retry=True)
    if result["state"] == "completed":
        return result
    if result["state"] != "active":
        raise ToolError("Transfer is not active: " + result["state"])
    offset = result["offset"]
    with source.open("rb") as stream:
        stream.seek(offset)
        while True:
            data = stream.read(result["chunkBytes"])
            if not data:
                break
            result = client.call("files.upload_chunk", {"transferId": transfer_id, "offset": offset,
                                 "data": base64.b64encode(data).decode()}, retry=True)
            offset = result["offset"]
    return client.call("files.upload_commit", {"transferId": transfer_id}, retry=True)


def download(client, source, target, transfer_id=None, overwrite=False):
    target = Path(target)
    transfer_id = transfer_id or uuid.uuid4().hex
    print("Download transferId=" + transfer_id, file=sys.stderr)
    if target.exists() and not overwrite:
        raise ToolError("Destination exists; pass --overwrite to replace it")
    part = target.with_name(target.name + "." + transfer_id + ".part")
    result = client.call("files.download_begin", {"transferId": transfer_id, "path": source}, retry=True)
    if result["state"] != "active":
        raise ToolError("Download is already finished; use a new transferId")
    offset = part.stat().st_size if part.exists() else 0
    if offset > result["size"]:
        raise ToolError("Local partial file is longer than source")
    with part.open("ab") as stream:
        while offset < result["size"]:
            chunk = client.call("files.download_chunk", {"transferId": transfer_id, "offset": offset}, retry=True)
            data = base64.b64decode(chunk["data"], validate=True)
            if not data or chunk["nextOffset"] != offset + len(data):
                raise ToolError("Invalid download range response")
            stream.write(data)
            stream.flush()
            offset += len(data)
        os.fsync(stream.fileno())
    if digest(part) != result["sha256"]:
        raise ToolError("Download SHA-256 mismatch; partial file retained for inspection")
    if overwrite:
        os.replace(part, target)
    else:
        # Exclusive creation also works on Android Python builds without hard-link support.
        with target.open("xb") as output:
            identity = os.fstat(output.fileno())
            try:
                with part.open("rb") as source_file:
                    shutil.copyfileobj(source_file, output, 1024 * 1024)
                output.flush()
                os.fsync(output.fileno())
            except OSError:
                current = target.stat()
                if (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                    target.unlink()
                raise
        part.unlink()
    client.call("files.download_finish", {"transferId": transfer_id}, retry=True)
    return {"path": str(target), "size": result["size"], "sha256": result["sha256"]}


def update(client, apk, update_id=None):
    update_id = update_id or uuid.uuid4().hex
    print("Update updateId=" + update_id, file=sys.stderr)
    before = client.call("get_state", retry=True)
    previous = client.call("app.update_status", {"updateId": update_id}, retry=True)
    sha = digest(apk)
    if previous["state"] == "unknown":
        if before["session"].get("active") or before["session"].get("starting"):
            client.call("close_desktop")
        closed = client.call("wait_for_state", {"condition": "desktop_inactive", "timeoutMillis": 30000})
        if not closed.get("matched"):
            raise ToolError("Desktop cleanup has not completed; update was not started")
        transfer = upload(client, apk, "/data/local/tmp/magicdesk-" + update_id + ".apk", update_id)
        try:
            # Reinstallation may kill the HTTP request. Do not repeat this mutation on uncertainty.
            client.call("app.update", {"updateId": update_id, "path": transfer["path"], "sha256": sha})
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
    elif previous.get("sha256") != sha:
        raise ToolError("updateId belongs to a different APK")
    deadline = time.monotonic() + client.timeout
    while time.monotonic() < deadline:
        result = client.call("app.update_status", {"updateId": update_id}, retry=True, deadline=deadline)
        if result["state"] in ("failed", "user_action_required"):
            raise ToolError(json.dumps(result, ensure_ascii=False))
        if result["state"] == "completion_unknown":
            raise ToolError("Installer outcome is unknown; do not repeat installation automatically: "
                            + json.dumps(result, ensure_ascii=False))
        if result["state"] == "installed":
            state = client.call("get_state", retry=True, deadline=deadline)
            if state["app"]["versionCode"] == result["versionCode"]:
                return {"update": result, "app": state["app"],
                        "processChanged": before["app"]["instanceId"] != state["app"]["instanceId"]}
        time.sleep(min(0.5, max(0, deadline - time.monotonic())))
    raise TimeoutError("Installer has not reported completion; query app.update_status with updateId=" + update_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8765/mcp")
    parser.add_argument("--token-file", type=Path, help="Defaults to MAGICDESK_TOKEN environment variable")
    parser.add_argument("--timeout", type=float, default=300)
    parser.add_argument("--allow-plaintext-network", action="store_true")
    commands = parser.add_subparsers(dest="command", required=True)
    call = commands.add_parser("call")
    call.add_argument("tool")
    call.add_argument("arguments", nargs="?", default="{}")
    for name in ("upload", "download"):
        command = commands.add_parser(name)
        command.add_argument("source")
        command.add_argument("target")
        command.add_argument("--transfer-id")
        command.add_argument("--overwrite", action="store_true")
    command = commands.add_parser("update")
    command.add_argument("apk", type=Path)
    command.add_argument("--update-id")
    args = parser.parse_args()
    token = args.token_file.read_text().strip() if args.token_file else os.environ.get("MAGICDESK_TOKEN", "")
    client = Client(args.endpoint, token, args.timeout, args.allow_plaintext_network)
    if args.command == "call":
        result = client.call(args.tool, json.loads(args.arguments))
    elif args.command == "update":
        result = update(client, args.apk, args.update_id)
    else:
        result = globals()[args.command](client, args.source, args.target, args.transfer_id, args.overwrite)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ToolError, OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
