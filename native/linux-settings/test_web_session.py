"""Exercise the capsule web relay under a real Shroot private network (no public server needed)."""
import argparse
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import uuid


def main():
    args = argparse.ArgumentParser()
    for option in ("shroot", "helper", "rootfs"):
        args.add_argument("--" + option, type=Path, required=True)
    args = args.parse_args()
    name = "magicdesk-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory() as directory, socket.socket(socket.AF_UNIX) as server:
        server.bind("\0" + name)
        server.listen()
        server.settimeout(30)
        base = [args.shroot.resolve(), "--library", directory]
        subprocess.run([*base, "install", args.rootfs.resolve(), "--rootfs", "--name", "capsule"], check=True)
        requests = []
        def mirror():
            with server.accept()[0] as client:
                client.settimeout(10)
                with client.makefile("rb") as data:
                    requests.append(data.readline(8192))
                    while data.readline(8192) not in (b"\r\n", b"\n", b""):
                        pass
                    client.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\ncapsule-web")
        peer = threading.Thread(target=mirror, daemon=True)
        peer.start()
        argv = [*base, "exec", "capsule", "--network", "none", "--bind-ro-create", args.helper.resolve().parent,
                "/tmp/tools", "--socket-path", "/tmp/magicdesk-web.sock", name,
                "--env", "MAGICDESK_WEB_ENDPOINT=/tmp/magicdesk-web.sock", "--", "/bin/sh", "-c",
                'exec /tmp/tools/' + args.helper.name + ' --connected-login /bin/sh -c '
                + "'wget -qO- http://mirror.invalid/probe'"]
        run = subprocess.run(argv, stdout=subprocess.PIPE, timeout=45)
        if run.returncode or run.stdout != b"capsule-web":
            raise RuntimeError((run.returncode, run.stdout, requests))
        peer.join(10)
        assert requests and requests[0].startswith(b"GET http://mirror.invalid/probe ")
        print("PASS Alpine private loopback -> UID Unix endpoint -> HTTP response")


if __name__ == "__main__":
    main()
