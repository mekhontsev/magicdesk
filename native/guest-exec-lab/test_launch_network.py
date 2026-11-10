#!/usr/bin/env python3
"""Per-launch networking through the selected executor, with native observers."""
import argparse
from contextlib import ExitStack
from pathlib import Path
import selectors
import socket
import threading

from test_oci_services import Suite, Server
from fixture_executor import FixtureExecutor

CUSTOM = ['--subnet', '172.23.8.0/24', '--ip', '172.23.8.42', '--gateway', '172.23.8.7',
          '--subnet', 'fd42:1234::/64', '--ip6', 'fd42:1234::42', '--gateway', 'fd42:1234::7']


class NativePeer:
    """Local native routing target; no internet service controls the result."""
    def __init__(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect(('192.0.2.1', 9))
            self.address = route.getsockname()[0]
        self.owned = ExitStack()
        self.selector = self.owned.enter_context(selectors.DefaultSelector())
        self.tcp = self.owned.enter_context(socket.socket())
        self.tcp.bind((self.address, 0)); self.tcp.listen()
        self.port = self.tcp.getsockname()[1]
        self.udp = self.owned.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        self.udp.bind((self.address, self.port))
        reader, self.writer = socket.socketpair()
        self.owned.enter_context(reader); self.owned.enter_context(self.writer)
        for stream in (self.tcp, self.udp, reader):
            self.selector.register(stream, selectors.EVENT_READ)
        self.failure = None
        self.thread = threading.Thread(target=self.run)
        self.thread.start()

    def run(self):
        try:
            while True:
                # EVENT_WAIT: native traffic or explicit fixture cancellation.
                for key, _ in self.selector.select():
                    stream = key.fileobj
                    if stream is self.tcp:
                        connection, _ = stream.accept()
                        with connection:
                            connection.settimeout(5)
                            connection.sendall(b'native:' + connection.recv(64))
                    elif stream is self.udp:
                        request, peer = stream.recvfrom(4096)
                        stream.sendto(b'native:' + request, peer)
                    else:
                        return
        except Exception as error:
            self.failure = error

    def close(self):
        self.writer.send(b'x')
        self.thread.join(10)
        self.owned.close()
        assert not self.thread.is_alive(), 'native peer did not stop'
        assert self.failure is None, self.failure


SERVER = r'''
import select, socket, sys
token = sys.argv[1].encode()
family = socket.AF_INET6 if sys.argv[2:] == ['6'] else socket.AF_INET
address = '::' if family == socket.AF_INET6 else '0.0.0.0'
tcp = socket.socket(family)
tcp.bind((address, 18080))
tcp.listen()
udp = socket.socket(family, socket.SOCK_DGRAM)
udp.bind((address, 18080))
print('NETWORK_READY', flush=True)
while True:
    ready, _, _ = select.select([tcp, udp], [], [], 90)
    assert ready, 'fixture deadline'
    for source in ready:
        if source is tcp:
            connection, _ = tcp.accept()
            with connection:
                request = connection.recv(64)
                connection.sendall(token + request)
            if request == b'stop':
                sys.exit(0)
        else:
            data, peer = udp.recvfrom(64)
            udp.sendto(token + data, peer)
'''


def exchange(port, token, family=socket.AF_INET):
    address = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    with socket.create_connection((address, port), timeout=5) as connection:
        connection.sendall(b'tcp')
        assert connection.recv(64) == token + b'tcp'
    with socket.socket(family, socket.SOCK_DGRAM) as connection:
        connection.settimeout(5)
        connection.sendto(b'udp', (address, port))
        assert connection.recvfrom(64)[0] == token + b'udp'


def reserve_pair(family=socket.AF_INET):
    address = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    while True:
        tcp = socket.socket(family)
        tcp.bind((address, 0))
        port = tcp.getsockname()[1]
        udp = socket.socket(family, socket.SOCK_DGRAM)
        try:
            udp.bind((address, port))
            return port, tcp, udp
        except OSError:
            tcp.close()
            udp.close()


def released(port, family=socket.AF_INET):
    address = '::1' if family == socket.AF_INET6 else '127.0.0.1'
    for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
        with socket.socket(family, kind) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind((address, port))


def cli(suite, store):
    before = suite.command(suite.image_command('exec', store, '--', '/bin/cat', '/etc/resolv.conf'))
    for kind in ('exec', 'run', 'login', 'runner'):
        for mode in ('host', 'none', 'bridge', 'routed'):
            args = ['--network', mode]
            code = 'test -r /etc/resolv.conf && printf "CLI_OK\\n"'
            if mode != 'none':
                args += ['--dns', '192.0.2.53']
                code = 'test "$(cat /etc/resolv.conf)" = "nameserver 192.0.2.53" && printf "CLI_OK\\n"'
            tail = [*args, '--', '/bin/sh', '-c', code]
            command = ([suite.runner, '--store', store, *tail] if kind == 'runner'
                       else suite.image_command(kind, store, *tail))
            assert suite.command(command).strip() == 'CLI_OK', (kind, mode)
        tail = ['--network', 'bridge', *CUSTOM, '--', '/bin/sh', '-c',
                'ip -4 address show dev eth0; ip -6 address show dev eth0; ip -4 route; ip -6 route']
        command = ([suite.runner, '--store', store, *tail] if kind == 'runner'
                   else suite.image_command(kind, store, *tail))
        result = suite.command(command)
        for value in ('172.23.8.42/24', 'fd42:1234::42/64', 'default via 172.23.8.7', 'default via fd42:1234::7'):
            assert value in result, (kind, value, result)
    assert suite.command(suite.image_command('exec', store, '--', '/bin/cat', '/etc/resolv.conf')) == before
    print('PASS exec/run/login/low-level CLI policies and isolated resolver', flush=True)


def run(suite, store, python):
    def command(*options, code, expected=0):
        return suite.command(suite.image_command('exec', store, *options, '--', python, '-c', code), expected=expected)

    before = command(code="print(open('/etc/resolv.conf').read(), end='')")
    assert command('--network', 'host', code="print('HOST_OK')").strip() == 'HOST_OK'
    offline = r'''
import errno, socket
server = socket.socket()
server.bind(('127.0.0.1', 18080)); server.listen()
client = socket.create_connection(('127.0.0.1', 18080), 3)
accepted, _ = server.accept()
client.sendall(b'private'); assert accepted.recv(7) == b'private'
external = socket.socket(); external.settimeout(3)
try:
    external.connect(('192.0.2.1', 80))
except OSError as error:
    assert error.errno == errno.ENETUNREACH, error
else:
    raise AssertionError('offline namespace reached an external route')
print('OFFLINE_OK')
'''
    command('--network', 'none', code=offline)
    port, native_tcp, native_udp = reserve_pair()
    try:
        collision = '''
import errno, socket
for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
    with socket.socket(socket.AF_INET, kind) as stream:
        try:
            stream.bind(('127.0.0.1', @PORT@))
        except OSError as error:
            assert @SHARED@ and error.errno == errno.EADDRINUSE, error
        else:
            assert not @SHARED@, 'routed bind bypassed the native reservation'
            assert stream.getsockname()[1] == @PORT@
print('PORT_ISOLATION_OK')
'''.replace('@PORT@', str(port))
        for mode in ('routed', 'none', 'bridge'):
            command('--network', mode, code=collision.replace('@SHARED@', str(mode == 'routed')))
    finally:
        native_tcp.close()
        native_udp.close()
    print('PASS explicit routed reservation and private guest TCP/UDP ports', flush=True)
    command('--network', 'bridge', '--dns', '192.0.2.53', code="""
import errno
assert open('/etc/resolv.conf').read() == 'nameserver 192.0.2.53\\n'
try:
    open('/etc/resolv.conf', 'w')
except OSError as error:
    assert error.errno == errno.EROFS, error
else:
    raise AssertionError('launch DNS is writable')
print('DNS_VIEW_OK')
""")
    command('--network', 'bridge', code="print('DEFAULT_DNS', open('/etc/resolv.conf').read())")
    assert command(code="print(open('/etc/resolv.conf').read(), end='')") == before
    for mode in ('host', 'none', 'routed'):
        command('--network', mode, '-p', '18081:80', code="raise AssertionError('must not execute')", expected=1)
        command('--network', mode, *CUSTOM, code="raise AssertionError('must not execute')", expected=1)

    ports = []
    with ExitStack() as cleanup:
        for token in ('first-', 'second-'):
            port, tcp, udp = reserve_pair()
            options = ['--network', 'bridge', '-p', f'127.0.0.1:{port}:18080/tcp',
                       '-p', f'127.0.0.1:{port}:18080/udp']
            if token == 'second-':
                options += CUSTOM
            tcp.close(); udp.close()
            server = Server(suite, suite.image_command('exec', store, *options, '--', python, '-u', '-c', SERVER, token))
            cleanup.callback(server.close)
            server.ready('NETWORK_READY')
            ports.append((port, token.encode(), server, options))
            if len(ports) == 1:
                # Keep an automation executor free for the negative launch;
                # two long-lived console.execute calls occupy both workers.
                command(*options, code="raise AssertionError('must not execute')", expected=125)
                exchange(port, token.encode())
        for port, token, _, _ in ports:
            exchange(port, token)
        for port, token, server, _ in ports:
            with socket.create_connection(('127.0.0.1', port), timeout=5) as connection:
                connection.sendall(b'stop')
                assert connection.recv(64) == token + b'stop'
            server.completed()
    for port, _, _, _ in ports:
        released(port)
    print('PASS concurrent bridge TCP/UDP publications, occupied port and normal teardown', flush=True)

    port, tcp, udp = reserve_pair(socket.AF_INET6)
    tcp.close(); udp.close()
    options = ['--network', 'bridge', *CUSTOM, '-p', f'[::1]:{port}:18080/tcp', '-p', f'[::1]:{port}:18080/udp']
    server = Server(suite, suite.image_command('exec', store, *options, '--', python, '-u', '-c', SERVER, 'v6-', '6'))
    try:
        server.ready('NETWORK_READY')
        exchange(port, b'v6-', socket.AF_INET6)
        with socket.create_connection(('::1', port), timeout=5) as connection:
            connection.sendall(b'stop')
            assert connection.recv(64) == b'v6-stop'
        server.completed()
    finally:
        server.close()
    released(port, socket.AF_INET6)
    print('PASS custom IPv6 subnet TCP/UDP publication and release', flush=True)

    peer = NativePeer()
    try:
        outbound = f'''
import socket
with socket.create_connection(({peer.address!r}, {peer.port}), 5) as s:
    s.sendall(b'tcp'); assert s.recv(64) == b'native:tcp'
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
    s.settimeout(5)
    s.sendto(b'udp', ({peer.address!r}, {peer.port})); assert s.recvfrom(64)[0] == b'native:udp'
    s.connect(({peer.address!r}, {peer.port}))
    s.send(b'connected'); assert s.recv(64) == b'native:connected'
print('OUTBOUND_OK')
'''
        command('--network', 'bridge', code=outbound)
        command('--network', 'bridge', *CUSTOM, code=outbound)
        # Preserve the existing low-level backend, separately from bridge.
        command('--network', 'routed', code=outbound)
    finally:
        peer.close()

    dns = r'''
import socket, struct, threading
server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
server.settimeout(10)
server.bind(('0.0.0.0', 53))
tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcp.bind(('0.0.0.0', 53))
tcp.listen()
errors = []
def serve():
    try:
        request, peer = server.recvfrom(4096)
        end = 12
        while request[end]:
            end += 1 + request[end]
        end += 1
        assert struct.unpack('!HH', request[end:end + 4]) == (1, 1)
        response = request[:2] + struct.pack('!HHHHH', 0x8180, 1, 1, 0, 0) + request[12:end + 4]
        response += b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 60, 4) + socket.inet_aton('192.0.2.42')
        server.sendto(response, peer)
    except Exception as error:
        errors.append(error)
thread = threading.Thread(target=serve)
thread.start()
assert socket.gethostbyname('launch.shroot.test') == '192.0.2.42'
thread.join(12)
assert not thread.is_alive() and not errors, errors
server.close()
tcp.close()
print('DNS_QUERY_OK')
'''
    command('--user', '0:0', '--network', 'bridge', '--dns', '10.88.0.2', code=dns)

    port, tcp, udp = reserve_pair()
    options = ['--network', 'bridge', '-p', f'127.0.0.1:{port}:18080/tcp',
               '-p', f'127.0.0.1:{port}:18080/udp']
    tcp.close(); udp.close()
    server = Server(suite, suite.image_command('exec', store, *options, '--', python, '-u', '-c', SERVER, 'cancel-'))
    try:
        server.ready('NETWORK_READY')
        exchange(port, b'cancel-')
    finally:
        server.close()
    released(port)
    command(*options, code='import os, signal; os.kill(os.getpid(), signal.SIGKILL)', expected=137)
    released(port)
    assert command(code="print(open('/etc/resolv.conf').read(), end='')") == before
    print('PASS outbound TCP/UDP, real DNS, routed regression, cancellation and SIGKILL cleanup', flush=True)
    suite.report['passed'] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', required=True)
    parser.add_argument('--python', default='/usr/bin/python3')
    parser.add_argument('--build', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/launch-network'))
    parser.add_argument('--native-tests', action='store_true')
    parser.add_argument('--cli-only', action='store_true', help='Shell-only entrypoint matrix, no guest Python required')
    FixtureExecutor.add_argument(parser)
    args = parser.parse_args()
    suite = Suite(args.output, args.build, executor=args.executor)
    try:
        if args.native_tests:
            assert args.build
            for target in ('test_task_resources', 'test_mounts', 'fs_dispatch'):
                filename = 'libmagicdesk_guest_' + target + '.so'
                suite.upload(args.build / filename, suite.base + '/' + filename)
                suite.chmod('700', suite.base + '/' + filename)
                arguments = [] if target == 'test_task_resources' else [suite.base + '/' + target]
                suite.command([suite.base + '/' + filename, *arguments])
        cli(suite, args.store)
        if not args.cli_only:
            run(suite, args.store, args.python)
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
