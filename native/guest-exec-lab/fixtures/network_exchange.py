"""Bidirectional TCP/UDP checks shared by nested-container boot fixtures."""
import socket
import threading
import shutil
import subprocess
from contextlib import contextmanager


@contextmanager
def inbound_port(address, port, kind):
    firewall = shutil.which('iptables')
    rule = ['INPUT', '-d', address, '-p',
            'tcp' if kind == socket.SOCK_STREAM else 'udp', '--dport', str(port), '-j', 'ACCEPT']
    if firewall:
        subprocess.run([firewall, '-w', '5', '-I', *rule], check=True, timeout=15)
    try:
        yield
    finally:
        if firewall:
            subprocess.run([firewall, '-w', '5', '-D', *rule], check=True, timeout=15)


def exchange(service, address, count=20):
    errors = []

    def echo(stream):
        try:
            for _ in range(count):
                if stream.type == socket.SOCK_STREAM:
                    connection, _ = stream.accept()
                    with connection:
                        connection.settimeout(10)
                        data = b''
                        while len(data) < 5:
                            part = connection.recv(5 - len(data))
                            assert part, 'Premature TCP EOF'
                            data += part
                        connection.sendall(data)
                else:
                    data, peer = stream.recvfrom(64)
                    stream.sendto(data, peer)
        except Exception as error:
            errors.append(repr(error))

    for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
        with socket.socket(type=kind) as stream:
            stream.bind((address, 0))
            stream.settimeout(10)
            if kind == socket.SOCK_STREAM:
                stream.listen(4)
            # Each responder's idle bound starts with its own client phase.
            thread = threading.Thread(target=echo, args=(stream,), daemon=True)
            thread.start()
            endpoint = (address, stream.getsockname()[1])
            code = ('import socket\nfor i in range(' + str(count) + '):\n'
                    ' with socket.socket(type=' + str(int(kind)) + ') as s:\n'
                    '  s.settimeout(5); s.connect(' + repr(endpoint) + '); s.sendall(b"guest")\n'
                    '  data=b""\n'
                    '  while len(data)<5:\n'
                    '   part=s.recv(5-len(data)); assert part; data+=part\n'
                    '  assert data==b"guest"\nprint("NETWORK_EXCHANGE_OK")')
            try:
                # Permit only this temporary listener through the host policy;
                # an enabled distribution firewall must remain enabled.
                with inbound_port(*endpoint, kind):
                    assert 'NETWORK_EXCHANGE_OK' in service('/usr/bin/python3', '-c', code).stdout
            finally:
                # EVENT_WAIT: responder completion; socket timeout bounds failed guests.
                thread.join(timeout=12)
            assert not errors and not thread.is_alive(), errors
    return dict(tcp=count, udp=count)
