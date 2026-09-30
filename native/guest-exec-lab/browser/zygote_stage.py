#!/usr/bin/python3
"""Lab IPC host for an actual Debian helper and Chromium zygote, not a browser."""
import fcntl
import array
import os
import select
import socket
import struct
import sys
import tempfile
import time

assert os.getuid() == os.geteuid() == 2000
assert len(sys.argv) in (3, 4) and (len(sys.argv) == 3 or sys.argv[3] == "--fork-renderer")
fork_renderer = len(sys.argv) == 4 and sys.argv[3] == "--fork-renderer"
host, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
broker, broker_child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
dummy, closed = os.pipe()
os.close(closed)
sources = [fcntl.fcntl(fd, fcntl.F_DUPFD_CLOEXEC, 100)
           for fd in (child.fileno(), broker_child.fileno(), dummy)]
pid = os.fork()
if not pid:
    for source, target in zip(sources, (3, 4, 7)):
        os.dup2(source, target, inheritable=True)
    os.closerange(8, 1024)
    for fd in (5, 6):
        try:
            os.close(fd)
        except OSError:
            pass
    environment = dict(os.environ, SBX_CHROME_API_RQ="1")
    os.execve(sys.argv[1], [sys.argv[1], sys.argv[2], "--type=zygote", "--headless"], environment)

for fd in sources:
    os.close(fd)
os.close(dummy)
child.close()
broker_child.close()
deadline = time.monotonic() + 15


def receive(channel=host):
    # EVENT_WAIT: actual zygote IPC; the deadline fails, never implies readiness.
    remaining = deadline - time.monotonic()
    assert remaining > 0 and select.select([channel], [], [], remaining)[0], "zygote IPC deadline"
    data = channel.recv(4096)
    print("ZYGOTE_PACKET", repr(data), flush=True)
    assert data, "zygote disconnected"
    return data


def integer(value):
    return struct.pack("<i", value)


def string(value, wide=False):
    data = value.encode("utf-16-le" if wide else "utf-8")
    return integer(len(data) // (2 if wide else 1)) + data + bytes(-len(data) % 4)


def send_pickle(payload, descriptors=()):
    ancillary = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", descriptors))] if descriptors else []
    packet = integer(len(payload)) + payload
    assert host.sendmsg([packet], ancillary) == len(packet)


try:
    assert receive() == b"ZYGOTE_BOOT\0"
    assert receive() == b"ZYGOTE_OK\0"
    host.send(struct.pack("<II", 4, 3))
    answer = receive()
    assert len(answer) == 4 and struct.unpack("<I", answer)[0] == 0x29, answer
    print("ZYGOTE_STATUS", answer.hex(), flush=True)
    print("PASS stock helper and real Chromium zygote IPC initialization", flush=True)
    if fork_renderer:
        oracle, oracle_child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        oracle.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
        mojo, mojo_child = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        crash, crash_child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        salt_writer, salt_path = tempfile.mkstemp(prefix="zygote-stage-salt-", dir="/dev/shm")
        os.write(salt_writer, struct.pack("<I", int.from_bytes(os.urandom(4), "little") or 1))
        salt = os.open(salt_path, os.O_RDONLY | os.O_CLOEXEC)
        os.unlink(salt_path)
        os.close(salt_writer)
        high, low = struct.unpack("<QQ", os.urandom(16))
        argv = [sys.argv[2], "--type=renderer", "--headless", "--lang=en-US",
                "--enable-logging=stderr", "--v=1",
                f"--pseudonymization-salt-handle=7,i,{high},{low},4"]
        payload = integer(0) + string("renderer") + integer(len(argv))
        payload += b"".join(string(arg) for arg in argv)
        payload += string("UTC", wide=True) + integer(4) + integer(0) + integer(2) + integer(7)
        send_pickle(payload, [oracle_child.fileno(), crash_child.fileno(), mojo_child.fileno(), salt])
        os.close(salt)
        oracle_child.close()
        mojo_child.close()
        crash_child.close()
        # EVENT_WAIT: Chromium's child PID handshake; timeout fails the stage.
        assert select.select([oracle], [], [], max(0, deadline - time.monotonic()))[0], "child PID deadline"
        ping, ancillary, flags, _ = oracle.recvmsg(128, socket.CMSG_SPACE(12))
        assert ping == b"CHILD_PING\0" and not flags, (ping, flags)
        credentials = [struct.unpack("3i", data) for level, kind, data in ancillary
                       if level == socket.SOL_SOCKET and kind == socket.SCM_CREDENTIALS]
        assert len(credentials) == 1 and credentials[0][1:] == (2000, 2000), credentials
        renderer = credentials[0][0]
        print("RENDERER_PID", renderer, flush=True)
        send_pickle(integer(4) + integer(renderer))
        reply = receive()
        assert len(reply) >= 12 and struct.unpack_from("<i", reply, 4)[0] == renderer, reply
        oracle.close()
        print("PASS real Chromium zygote renderer fork", flush=True)
        # EVENT_WAIT: Mojo/crash traffic or renderer death. No browser invitation
        # is fabricated here; this stage does not certify a usable renderer.
        pidfd = os.pidfd_open(renderer)
        ready = select.select([mojo, crash, pidfd], [], [], max(0, deadline - time.monotonic()))[0]
        print("RENDERER_EVENTS", ["mojo" if item is mojo else "crash" if item is crash else "exit"
                                  for item in ready], flush=True)
        assert ready and all(item is mojo for item in ready), "renderer initialization failed or timed out"
        packet = mojo.recv(4096, socket.MSG_PEEK | socket.MSG_DONTWAIT)
        assert packet, "Mojo readiness was EOF, not renderer initialization"
        status = {}
        with open(f"/proc/{renderer}/status") as stream:
            for line in stream:
                key, _, value = line.partition(":")
                status[key] = value.strip()
        assert status["Uid"].split() == ["2000"] * 4, status
        assert status["NoNewPrivs"] == "1" and status["Seccomp"] == "2", status
        assert int(status["Seccomp_filters"]) >= 2, status
        print("RENDERER_KERNEL", {key: status[key] for key in
              ("Uid", "NoNewPrivs", "Seccomp", "Seccomp_filters", "Threads")}, flush=True)
        print("RENDERER_MOJO", len(packet), packet[:32].hex(), flush=True)
        print("PASS stock Chromium renderer filter and nonempty Mojo startup", flush=True)
        mojo.close()
        crash.close()
        os.close(pidfd)
finally:
    host.close()
    broker.close()
    # EVENT_WAIT: owned child exit after IPC closure; the supervisor bounds cleanup.
    reaped, status = os.waitpid(pid, 0)
    assert reaped == pid and os.waitstatus_to_exitcode(status) == 0, status
print("NOT CERTIFIED: full browser startup, set-ID credentials and arbitrary confinement", flush=True)
