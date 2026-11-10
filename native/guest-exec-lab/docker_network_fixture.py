"""External port observer and a tiny container service for Docker networking tests."""
from contextlib import ExitStack
import http.client
import errno
import ipaddress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import queue
import secrets
import select
import socket
import socketserver
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request


def wait_service(command):
    logs = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    ready = queue.Queue()
    def read_ready():
        try:
            for line in logs.stdout:
                if 'DOCKER_PORTS_READY' in line:
                    ready.put(True)
                    return
        finally:
            ready.put(False)
    waiter = threading.Thread(target=read_ready)
    waiter.start()
    try:
        # EVENT_WAIT: service announces both bound sockets; timeout fails readiness.
        assert ready.get(timeout=30), 'Service exited before binding: ' + repr(command)
    finally:
        logs.terminate()
        logs.wait(timeout=10)
        waiter.join(10)
        logs.stdout.close()
        assert not waiter.is_alive()


def exchange(host, port, token, repeats=1, family=socket.AF_INET):
    host = socket.getaddrinfo(host, port, family, socket.SOCK_STREAM)[0][4][0]
    for index in range(repeats):
        stage = 'tcp-connect'
        local = None
        try:
            with socket.create_connection((host, port), timeout=10) as connection:
                local = connection.getsockname()
                stage = 'tcp-send'
                connection.sendall(b'PING')
                stage = 'tcp-receive'
                data = connection.recv(100).decode()
                assert data == token, ('TCP reply', host, port, data, token)
            with socket.socket(family, socket.SOCK_DGRAM) as connection:
                connection.settimeout(10)
                stage = 'udp-send'
                connection.sendto(b'PING', (host, port))
                local = connection.getsockname()
                stage = 'udp-receive'
                data, peer = connection.recvfrom(100)
                assert data.decode() == token and peer[:2] == (host, port), (data, peer)
        except Exception as error:
            raise AssertionError(f'{stage} {index + 1}/{repeats} from {local} at {host}:{port}: {error!r}') from error


def container_service(token, family=socket.AF_INET):
    class TCP(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(10)
            if self.request.recv(100) == b'PING':
                self.request.sendall(token.encode())

    class UDP(socketserver.BaseRequestHandler):
        def handle(self):
            data, connection = self.request
            if data == b'PING':
                connection.sendto(token.encode(), self.client_address)

    class TCPServer(socketserver.ThreadingTCPServer):
        address_family = family

    class UDPServer(socketserver.ThreadingUDPServer):
        address_family = family

    host = '::' if family == socket.AF_INET6 else '0.0.0.0'
    with TCPServer((host, 8080), TCP) as tcp, UDPServer((host, 8080), UDP) as udp:
        worker = threading.Thread(target=udp.serve_forever, daemon=True)
        worker.start()
        print('DOCKER_PORTS_READY', flush=True)
        tcp.serve_forever()


def diagnose_dns():
    config = open('/etc/resolv.conf').read()
    print('DNS_CONFIG', config, flush=True)
    servers = [line.split()[1] for line in config.splitlines()
               if line.split()[:1] == ['nameserver']]
    for server in servers:
        for kind in (1, 28):
            query = struct.pack('!6H', 0x4d44, 0x100, 1, 0, 0, 0)
            query += b'\x07example\x03com\0' + struct.pack('!2H', kind, 1)
            start = time.monotonic()
            try:
                with socket.socket(socket.AF_INET6 if ':' in server else socket.AF_INET, socket.SOCK_DGRAM) as connection:
                    connection.settimeout(5)
                    connection.sendto(query, (server, 53))
                    data, peer = connection.recvfrom(4096)
                result = {'peer': peer, 'header': struct.unpack('!6H', data[:12]),
                          'bytes': len(data)}
            except Exception as error:
                result = {'error': repr(error)}
            print('DNS_DIAGNOSTIC', json.dumps(dict(result, server=server, kind=kind,
                  seconds=time.monotonic()-start)), flush=True)


def internet():
    timings = []
    for index in range(20):
        start = time.monotonic()
        try:
            addresses = socket.getaddrinfo('example.com', 80)
            assert any(item[0] == socket.AF_INET for item in addresses), addresses
        except Exception:
            print('DNS_FAILURE', index, flush=True)
            diagnose_dns()
            raise
        timings.append(time.monotonic() - start)
    print('CONTAINER_DNS_OK', json.dumps({'dnsSeconds': timings}), flush=True)
    with urllib.request.urlopen('http://example.com', timeout=15) as response:
        assert response.status == 200
        size = len(response.read())
        assert size
    print('CONTAINER_INTERNET_OK', json.dumps({'dnsSeconds': timings, 'httpBytes': size}), flush=True)


def internet_ipv6():
    """One public endpoint, literal IPv6 only: no IPv4 or resolver fallback."""
    host = '2606:4700:4700::1111'
    query_id = secrets.randbelow(65536)
    query = struct.pack('!6H', query_id, 0x100, 1, 0, 0, 0)
    query += b'\x07example\x03com\0' + struct.pack('!2H', 28, 1)
    results = []
    for kind in (socket.SOCK_DGRAM, socket.SOCK_STREAM):
        start = time.monotonic()
        with socket.socket(socket.AF_INET6, kind) as connection:
            connection.settimeout(15)
            connection.connect((host, 53))
            local = connection.getsockname()
            if kind == socket.SOCK_DGRAM:
                connection.send(query)
                data = connection.recv(4096)
            else:
                connection.sendall(struct.pack('!H', len(query)) + query)
                with connection.makefile('rb') as stream:
                    size = stream.read(2)
                    assert len(size) == 2, ('DNS TCP header', size)
                    size = struct.unpack('!H', size)[0]
                    data = stream.read(size)
                    assert len(data) == size, ('DNS TCP truncated', len(data), size)
            ident, flags, questions, answers, _, _ = struct.unpack('!6H', data[:12])
            assert ident == query_id and flags & 0x8000 and not flags & 0x20f, (ident, flags)
            assert questions == 1 and answers > 0, (questions, answers)
            results.append({'protocol': 'udp-dns' if kind == socket.SOCK_DGRAM else 'tcp-dns',
                            'local': local, 'peer': connection.getpeername(),
                            'bytes': len(data), 'seconds': time.monotonic() - start})
    start = time.monotonic()
    with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as connection:
        connection.settimeout(15)
        connection.connect((host, 443))
        with ssl.create_default_context().wrap_socket(connection, server_hostname='one.one.one.one') as tls:
            tls.sendall(b'GET /cdn-cgi/trace HTTP/1.1\r\nHost: one.one.one.one\r\nConnection: close\r\n\r\n')
            response = http.client.HTTPResponse(tls)
            response.begin()
            assert response.status == 200, response.status
            body = response.read(4097)
            assert len(body) <= 4096, 'Unexpectedly large HTTPS response'
            fields = dict(line.split('=', 1) for line in body.decode().splitlines() if '=' in line)
            assert ipaddress.ip_address(fields['ip']).version == 6, fields
            results.append({'protocol': 'https', 'peer': tls.getpeername(), 'tls': tls.version(),
                            'observedAddress': fields['ip'], 'bytes': len(body),
                            'seconds': time.monotonic() - start})
    print('CONTAINER_IPV6_INTERNET_OK', json.dumps(results), flush=True)


class Observer:
    """Runs as Termux's own UID, outside the guest and its namespace fabric."""
    def __init__(self, ipv6_host='::1'):
        self.ipv6_host = str(ipaddress.IPv6Address(ipv6_host))
        self.token = secrets.token_hex(24)
        self.records = []
        self.reservations = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                if self.path != '/' + owner.token + '/outbound':
                    self.send_error(404)
                    return
                owner.records.append({'operation': 'outbound-request', 'peer': self.client_address})
                body = owner.token.encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                if self.path != '/' + owner.token:
                    self.send_error(404)
                    return
                size = int(self.headers['Content-Length'])
                if size > 4096:
                    self.send_error(413)
                    return
                item = json.loads(self.rfile.read(size))
                result = {'request': item, 'uid': os.getuid()}
                try:
                    port = int(item.get('port', 0))
                    family = socket.AF_INET6 if item.get('ipv6') else socket.AF_INET
                    host = owner.ipv6_host if item.get('ipv6') else '127.0.0.1'
                    result['host'] = host
                    if item['operation'] == 'allocate':
                        result['port'] = owner.allocate_port(family)
                    elif item['operation'] == 'outbound':
                        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
                            route.connect(('192.0.2.1', 9))
                            host = route.getsockname()[0]
                        result['url'] = f'http://{host}:{owner.server.server_port}/{owner.token}/outbound'
                    elif item['operation'] == 'handoff':
                        connections = [s for s in owner.reservations if s.getsockname()[1] == port]
                        assert len(connections) == 2, 'No paired TCP/UDP reservation'
                        for connection in connections:
                            owner.reservations.remove(connection)
                            connection.close()
                    elif item['operation'] == 'occupy':
                        connection = socket.socket(family)
                        connection.bind((host, port))
                        connection.listen()
                        owner.reservations.append(connection)
                    elif item['operation'] == 'released':
                        owner.wait_released(family, host, port)
                    elif item['operation'] == 'denied':
                        for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
                            with socket.socket(family, kind) as connection:
                                connection.settimeout(0.5)
                                try:
                                    connection.connect((host, port))
                                    connection.send(b'PING')
                                    data = connection.recv(100)
                                    assert not data, ('denied payload received', data)
                                except (TimeoutError, ConnectionRefusedError, ConnectionResetError):
                                    pass
                    elif item['operation'] == 'exchange':
                        exchange(host, port, item['token'], item.get('repeats', 1), family)
                    else:
                        raise ValueError('Unknown observer operation')
                    result['passed'] = True
                except Exception as error:
                    result.update(passed=False, error=repr(error))
                owner.records.append(result)
                body = json.dumps(result).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        # The token-scoped fixture also accepts container egress through the
        # host's real interface, without depending on a public web service.
        self.server = ThreadingHTTPServer(('0.0.0.0', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.address = f"{self.server.server_port}/{self.token}"

    def allocate_port(self, family=socket.AF_INET):
        # Reserve both protocols outside Shroot; release just before publication.
        # Service ports stay below Linux's default ephemeral range: automatic
        # forwarding deliberately excludes ephemeral client ports.
        # Only allocation races are retried, never a failed container publication.
        for _ in range(32):
            tcp = socket.socket(family, socket.SOCK_STREAM)
            udp = socket.socket(family, socket.SOCK_DGRAM)
            try:
                host = self.ipv6_host if family == socket.AF_INET6 else '127.0.0.1'
                tcp.bind((host, 1024 + secrets.randbelow(32768 - 1024)))
                port = tcp.getsockname()[1]
                udp.bind((host, port))
                tcp.listen()
            except OSError as error:
                tcp.close()
                udp.close()
                if error.errno != errno.EADDRINUSE:
                    raise
            else:
                self.reservations.extend((tcp, udp))
                return port
        raise OSError(errno.EADDRINUSE, 'No free paired TCP/UDP port')

    @staticmethod
    def wait_released(family, host, port, timeout=5):
        # Bounded state wait: automatic forwarders observe container teardown
        # asynchronously. Require both real host ports to become available.
        deadline = time.monotonic() + timeout
        while True:
            try:
                with ExitStack() as sockets:
                    for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
                        connection = socket.socket(family, kind)
                        sockets.callback(connection.close)
                        connection.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                        connection.bind((host, port))
                return
            except OSError as error:
                remaining = deadline - time.monotonic()
                if error.errno != errno.EADDRINUSE or remaining <= 0:
                    raise
                select.select([], [], [], min(.05, remaining))

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(10)
        assert not self.thread.is_alive()
        for connection in self.reservations:
            connection.close()


def observe(address, **request):
    port, token = address.split('/')
    # The control request contains sequential TCP/UDP exchanges. Its deadline
    # must cover their unchanged per-operation bounds, not cancel a busy peer.
    timeout = 30 + 40 * request.get('repeats', 1) if request.get('operation') == 'exchange' else 30
    connection = http.client.HTTPConnection('127.0.0.1', int(port), timeout=timeout)
    try:
        connection.request('POST', '/' + token, json.dumps(request))
        response = connection.getresponse()
        assert response.status == 200
        result = json.loads(response.read())
        assert result['passed'], result
        return result
    finally:
        connection.close()


def http_exchange(url, repeats=20):
    token = url.split('/')[-2]
    for index in range(repeats):
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                assert response.status == 200
                assert response.read().decode() == token
        except Exception as error:
            raise AssertionError(f'Outbound HTTP connection {index + 1}/{repeats} failed') from error
    print('CONTAINER_OUTBOUND_OK', repeats, flush=True)


def file_operations():
    """Exercise the writable container layer and another exec under its policy."""
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='md-container-files-') as directory:
        root = Path(directory)
        original, renamed, link = (root/name for name in ('original', 'renamed', 'link'))
        data = bytes(range(256))*512
        original.write_bytes(data)
        with original.open('rb') as opened:
            original.rename(renamed)
            os.link(renamed, link)
            assert os.stat(renamed).st_ino == os.stat(link).st_ino
            assert os.fstat(opened.fileno()).st_nlink == 2
            renamed.unlink()
            assert opened.read() == data and os.fstat(opened.fileno()).st_nlink == 1
        child = subprocess.run([sys.executable, __file__, '--files-child', str(link)],
                               capture_output=True, text=True, timeout=30)
        assert child.returncode == 0 and 'CONTAINER_FILES_CHILD_OK' in child.stdout, child
        for _ in range(128):
            original.write_bytes(b'cycle')
            original.rename(renamed)
            assert renamed.read_bytes() == b'cycle'
            renamed.unlink()
        for _ in range(4096):
            with link.open('rb') as opened:
                assert os.fstat(opened.fileno()).st_size == len(data)
                assert opened.read(4) == data[:4]
        link.unlink()
        assert not list(root.iterdir())
    print('CONTAINER_FILES_OK', json.dumps(dict(seconds=time.monotonic()-started,
          cycles=128, opens=4096, child=child.stdout.strip())), flush=True)


if __name__ == '__main__':
    if sys.argv[1] == '--http':
        http_exchange(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 20)
    elif sys.argv[1] == '--internet':
        internet()
    elif sys.argv[1] == '--internet-ipv6':
        internet_ipv6()
    elif sys.argv[1] == '--files':
        file_operations()
    elif sys.argv[1] == '--files-child':
        assert Path(sys.argv[2]).read_bytes() == bytes(range(256))*512
        assert Path('/proc/self/status').read_text().split('Seccomp:')[1].split()[0] == '2'
        print('CONTAINER_FILES_CHILD_OK', flush=True)
    elif sys.argv[1] == '--client':
        exchange(sys.argv[2], int(sys.argv[3]), sys.argv[4], int(sys.argv[5]) if len(sys.argv)>5 else 1,
                 socket.AF_INET6 if '--ipv6' in sys.argv[6:] else socket.AF_INET)
        print('DOCKER_PEER_EXCHANGE_OK', flush=True)
    else:
        container_service(sys.argv[1], socket.AF_INET6 if '--ipv6' in sys.argv[2:] else socket.AF_INET)
