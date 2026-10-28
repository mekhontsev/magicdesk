#!/usr/bin/env python3
"""Guest TCP completion, SO_ERROR and readiness with optional nft mediation."""
import argparse
import errno
import os
import select
import socket
import subprocess
import threading
import time


def completed(sock, mode, queue=None):
    # EVENT_WAIT: handshake completion; timeout fails, never implies success.
    if mode == 'poll':
        waiter = select.poll()
        waiter.register(sock, select.POLLOUT)
        events = waiter.poll(5000)
    else:
        events = queue.poll(5)
    assert events and events[0][0] == sock.fileno(), (mode, events)
    return events[0][1]


def exercise(family, mode, listening):
    loopback = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    with socket.socket(family) as server, socket.socket(family) as client, select.epoll() as queue:
        server.bind((loopback, 0))
        if listening:
            server.listen(2)
        address = server.getsockname()
        if mode == 'blocking':
            queue.register(client, select.EPOLLOUT)
            # A process-level deadline bounds a stuck blocking connect.
            result = client.connect_ex(address)
        else:
            client.setblocking(False)
            flags = select.EPOLLOUT
            if mode == 'edge':
                flags |= select.EPOLLET
            elif mode == 'oneshot':
                flags |= select.EPOLLONESHOT
            # Exercise subscriptions installed before connect, not just after.
            queue.register(client, flags)
            result = client.connect_ex(address)
            assert result in (0, errno.EINPROGRESS, errno.ECONNREFUSED), result
            if result == errno.EINPROGRESS:
                completed(client, mode, queue)
                with socket.socket(fileno=os.dup(client.fileno())) as alias:
                    result = alias.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
                assert client.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) == 0
        assert result == (0 if listening else errno.ECONNREFUSED), (family, mode, listening, result)
        assert client.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR) == 0
        if not listening:
            try:
                client.getpeername()
            except OSError as error:
                assert error.errno == errno.ENOTCONN, error
            else:
                raise AssertionError('Refused destination reported a connected peer')
            return
        client.settimeout(5)
        server.settimeout(5)
        peer, _ = server.accept()
        with peer:
            peer.settimeout(5)
            # All data IO uses ordinary read/write, including after epoll MOD.
            queue.modify(client, select.EPOLLIN | select.EPOLLONESHOT)
            for iteration in range(2):
                payload = b'completion-' + str(iteration).encode()
                os.write(peer.fileno(), payload)
                completed(client, 'level', queue)
                assert os.read(client.fileno(), 100) == payload
                assert queue.poll(0) == []
                queue.modify(client, select.EPOLLIN | select.EPOLLONESHOT)
                os.write(client.fileno(), payload)
                assert peer.recv(100) == payload


def mixed_queue(family):
    loopback = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    with socket.socket(family) as server, select.epoll() as queue:
        server.bind((loopback, 0))
        server.listen(4)
        server.setblocking(False)
        queue.register(server, select.EPOLLIN)
        for _ in range(8):
            with socket.socket(family) as client:
                client.settimeout(5)
                client.connect(server.getsockname())
                assert queue.poll(5) == [(server.fileno(), select.EPOLLIN)]
                peer, _ = server.accept()
                with peer, select.epoll() as second:
                    peer.setblocking(False)
                    queue.register(peer, select.EPOLLIN)
                    second.register(peer, select.EPOLLIN | select.EPOLLET)
                    payload = b'accepted-data'
                    os.write(client.fileno(), payload)
                    assert queue.poll(5) == [(peer.fileno(), select.EPOLLIN)]
                    assert second.poll(5) == [(peer.fileno(), select.EPOLLIN)]
                    assert os.read(peer.fileno(), 100) == payload
                    assert queue.poll(0) == []
                    queue.unregister(peer)
    print(f'PASS TCP family={family} mixed listener/accepted/multiple epoll', flush=True)


def connection_observers(family):
    loopback = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    with socket.socket(family) as server, socket.socket(family) as client:
        server.bind((loopback, 0))
        server.listen(1)
        client.settimeout(5)
        client.connect(server.getsockname())
        peer, _ = server.accept()
        with peer, select.epoll() as first, select.epoll() as second:
            first.register(client, select.EPOLLIN | select.EPOLLET)
            second.register(client, select.EPOLLIN | select.EPOLLET)
            for _ in range(4):
                os.write(peer.fileno(), b'x')
                assert first.poll(5) == [(client.fileno(), select.EPOLLIN)]
                assert second.poll(5) == [(client.fileno(), select.EPOLLIN)]
                assert os.read(client.fileno(), 1) == b'x'
                assert first.poll(0) == []
                assert second.poll(0) == []
    print(f'PASS TCP family={family} connected multiple observers', flush=True)


def listener_transitions(family):
    loopback = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    for mode in ('level', 'edge', 'oneshot'):
        flags = select.EPOLLIN
        if mode == 'edge':
            flags |= select.EPOLLET
        elif mode == 'oneshot':
            flags |= select.EPOLLONESHOT
        with socket.socket(family) as server, select.epoll() as queue:
            server.setblocking(False)
            queue.register(server, flags)
            # An unconnected native TCP socket can already report HUP.
            queue.poll(0)
            server.bind((loopback, 0))
            server.listen(4)
            queue.modify(server, flags)
            for _ in range(8):
                assert queue.poll(0) == []
                with socket.socket(family) as client:
                    client.settimeout(5)
                    client.connect(server.getsockname())
                    assert queue.poll(5) == [(server.fileno(), select.EPOLLIN)]
                    peer, _ = server.accept()
                    peer.close()
                if mode == 'oneshot':
                    queue.modify(server, flags)
        print(f'PASS TCP family={family} listener transition mode={mode}', flush=True)


def blocked_admission(family):
    loopback = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    with socket.socket(family) as server, socket.socket(family) as client, select.epoll() as queue:
        server.bind((loopback, 0))
        server.listen(1)
        started = threading.Event()
        result = []

        def wait():
            started.set()
            # EVENT_WAIT: first subscription must wake an already waiting caller.
            result.extend(queue.poll(2))

        thread = threading.Thread(target=wait)
        thread.start()
        assert started.wait(2)
        # Schedule the waiter before cold queue activation, without signalling it.
        thread.join(0.1)
        assert thread.is_alive()
        client.connect(server.getsockname())
        queue.register(server, select.EPOLLIN)
        thread.join(3)
        assert not thread.is_alive()
        assert result == [(server.fileno(), select.EPOLLIN)], (result, queue.poll(0))
    print(f'PASS TCP family={family} blocked native wait during first subscription', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mediated', action='store_true')
    args = parser.parse_args()
    table = 'md_connect_' + str(os.getpid())
    if args.mediated:
        rules = (f'add table inet {table}\n'
                 f'add chain inet {table} output {{ type filter hook output priority 0; policy accept; }}\n'
                 f'add rule inet {table} output counter\n')
        subprocess.run(['nft', '-f', '-'], input=rules, text=True, check=True, timeout=10)
    try:
        for family in (socket.AF_INET, socket.AF_INET6):
            blocked_admission(family)
            mixed_queue(family)
            connection_observers(family)
            listener_transitions(family)
            for mode in ('blocking', 'poll', 'level', 'edge', 'oneshot'):
                start = time.monotonic()
                for _ in range(4):
                    for listening in (False, True):
                        exercise(family, mode, listening)
                print(f'PASS TCP family={family} mode={mode} mediated={args.mediated} '
                      f'refusal/success/dup/SO_ERROR/peer/read-write {time.monotonic()-start:.3f}s', flush=True)
    finally:
        if args.mediated:
            subprocess.run(['nft', 'delete', 'table', 'inet', table], check=True, timeout=10)


if __name__ == '__main__':
    main()
