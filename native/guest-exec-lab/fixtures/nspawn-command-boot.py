#!/usr/bin/env python3
"""Exercise nspawn command mode, independent of a nested init/service manager."""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import signal
import socket
import subprocess
import tempfile
import threading
import uuid

from network_exchange import exchange


def namespace_children(parent, namespace, proc=Path('/proc')):
    result = []
    for task in proc.iterdir():
        if not task.name.isdecimal():
            continue
        try:
            status = dict(line.split(':', 1) for line in (task / 'status').read_text().splitlines()
                          if ':' in line)
            if int(status['PPid']) == parent and os.readlink(task / 'ns/pid') == namespace:
                result.append(int(task.name))
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    return result


GUEST = r'''
import errno, json, os, pathlib, socket, subprocess, sys, threading
root = pathlib.Path('/probe')
assert os.getpid() == 1 and os.getuid() == 0
assert pathlib.Path('/dev/ptmx').is_char_device()
assert pathlib.Path('/proc/self/status').read_text().split('Seccomp:')[1].split()[0] == '2'
assert pathlib.Path('/readonly/input').read_text() == 'read-only binding\n'
try:
    pathlib.Path('/readonly/input').write_text('unexpected')
except OSError as error:
    assert error.errno == errno.EROFS, error
else:
    raise AssertionError('Read-only bind accepted a write')
(root / 'writeback').write_text('guest writeback\n')
for args in (['/usr/bin/ip', 'address', 'add', '10.231.245.2/30', 'dev', 'host0'],
             ['/usr/bin/ip', 'link', 'set', 'host0', 'up']):
    subprocess.run(args, check=True)
threads = []
def respond(stream):
    with stream:
        for _ in range(20):
            if stream.type == socket.SOCK_STREAM:
                connection, _ = stream.accept()
                with connection:
                    connection.settimeout(10)
                    data = b''
                    while len(data) < 5:
                        part = connection.recv(5 - len(data))
                        assert part
                        data += part
                    connection.sendall(data)
            else:
                data, peer = stream.recvfrom(64)
                stream.sendto(data, peer)
for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
    stream = socket.socket(type=kind)
    stream.settimeout(90)
    stream.bind(('0.0.0.0', 24567))
    if kind == socket.SOCK_STREAM:
        stream.listen(4)
    thread = threading.Thread(target=respond, args=(stream,), daemon=True)
    thread.start()
    threads.append(thread)
print(json.dumps(dict(ready=True, namespaces={n: os.readlink('/proc/self/ns/' + n)
    for n in ('pid', 'mnt', 'net', 'user')}, uidMap=pathlib.Path('/proc/self/uid_map').read_text())), flush=True)
for line in sys.stdin:
    args = json.loads(line)
    if args is None:
        break
    result = subprocess.run(args, capture_output=True, text=True, timeout=30)
    print(json.dumps(dict(code=result.returncode, stdout=result.stdout, stderr=result.stderr)), flush=True)
for thread in threads:
    thread.join(timeout=5)
    assert not thread.is_alive(), 'Published-port exchanges did not finish'
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--machine', required=True)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_-]+', args.machine)
    image = Path('/var/lib/machines') / args.machine
    assert image.is_dir(), image
    name = 'mdnc' + uuid.uuid4().hex[:8]
    bridge = 'mdnc' + uuid.uuid4().hex[:8]
    report = dict(passed=False, machine=args.machine, rounds=[], commands=[])
    connected = False
    process = None
    stderr = None

    def run(*argv, required=True):
        result = subprocess.run(argv, capture_output=True, text=True, timeout=60,
                                env={**os.environ, 'LC_ALL': 'C'})
        report['commands'].append(dict(argv=argv, code=result.returncode,
                                       stdout=result.stdout, stderr=result.stderr))
        print('RUN', argv, result.returncode, result.stdout, result.stderr, flush=True)
        if required:
            assert result.returncode == 0, report['commands'][-1]
        return result

    try:
        report['version'] = run('systemd-nspawn', '--version').stdout
        run('ip', 'link', 'add', bridge, 'type', 'bridge')
        connected = True
        run('ip', 'address', 'add', '10.231.245.1/30', 'dev', bridge)
        run('ip', 'link', 'set', bridge, 'up')
        with tempfile.TemporaryDirectory(prefix='md-nspawn-command-') as temporary:
            work = Path(temporary)
            # Shifted guest root is deliberately not the outer directory owner.
            work.chmod(0o777)
            (work / 'readonly').mkdir()
            (work / 'readonly/input').write_text('read-only binding\n')
            for repeat in range(3):
                record = dict(round=repeat, passed=False)
                report['rounds'].append(record)
                # Reserve an available host port for both protocols before launch.
                with socket.socket() as tcp, socket.socket(type=socket.SOCK_DGRAM) as udp:
                    tcp.bind(('10.231.245.1', 0))
                    port = tcp.getsockname()[1]
                    udp.bind(('10.231.245.1', port))
                argv = ['systemd-nspawn', '--quiet', '--directory=' + str(image),
                        '--machine=' + name, '--register=no', '--console=pipe',
                        '--private-users=pick', '--private-users-ownership=auto',
                        '--network-bridge=' + bridge, '--port=tcp:' + str(port) + ':24567',
                        '--port=udp:' + str(port) + ':24567',
                        '--bind=' + str(work) + ':/probe',
                        '--bind-ro=' + str(work / 'readonly') + ':/readonly',
                        '/usr/bin/python3', '-u', '-c', GUEST]
                record['command'] = argv
                received = queue.Queue()
                stderr = args.report.with_suffix('.' + str(repeat) + '.stderr')
                with stderr.open('w') as log:
                    process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                               stderr=log, text=True, start_new_session=True)

                    def collect(stream):
                        for line in stream:
                            received.put(line)
                        received.put(None)

                    reader = threading.Thread(target=collect, args=(process.stdout,), daemon=True)
                    reader.start()

                    def reply():
                        # EVENT_WAIT: child's protocol reply/EOF; deadline fails this exact launch.
                        line = received.get(timeout=60)
                        assert line is not None, stderr.read_text()
                        return json.loads(line)

                    def service(*command):
                        process.stdin.write(json.dumps(command) + '\n')
                        process.stdin.flush()
                        result = reply()
                        assert result['code'] == 0, result
                        return subprocess.CompletedProcess(command, result['code'],
                                                           result['stdout'], result['stderr'])

                    ready = reply()
                    assert ready['ready'], ready
                    for kind, value in ready['namespaces'].items():
                        assert os.readlink('/proc/self/ns/' + kind) != value, kind
                    start, outside, count = map(int, ready['uidMap'].split())
                    assert start == 0 and outside > 0 and count >= 65536, ready
                    record['guest'] = ready
                    assert (work / 'writeback').read_text() == 'guest writeback\n'
                    assert (work / 'readonly/input').read_text() == 'read-only binding\n'
                    record['outbound'] = exchange(service, '10.231.245.1')
                    record['nat'] = run('nft', '-j', 'list', 'table', 'ip', 'io.systemd.nat', required=False).stdout
                    for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
                        for _ in range(20):
                            with socket.socket(type=kind) as stream:
                                stream.settimeout(5)
                                # systemd's OUTPUT DNAT intentionally excludes 127/8.
                                stream.connect(('10.231.245.1', port))
                                stream.sendall(b'outer')
                                data = b''
                                while len(data) < 5:
                                    part = stream.recv(5 - len(data))
                                    assert part, 'Premature published-port EOF'
                                    data += part
                                assert data == b'outer', data
                    record['published'] = dict(tcp=20, udp=20, port=port)
                    if repeat == 1:
                        leaders = namespace_children(process.pid, ready['namespaces']['pid'])
                        assert len(leaders) == 1, leaders
                        record['killedLeader'] = leaders[0]
                        os.kill(leaders[0], signal.SIGKILL)
                    else:
                        process.stdin.write('null\n')
                        process.stdin.flush()
                    # EVENT_WAIT: nspawn command exit and its namespace cleanup.
                    result = process.wait(timeout=60)
                    assert result == (1 if repeat == 1 else 0), result
                    record['exitCode'] = result
                    process.stdin.close()
                    reader.join(timeout=10)
                    assert not reader.is_alive(), 'Guest retained the stdout pipe'
                    process.stdout.close()
                    process = None
                record['stderr'] = stderr.read_text()
                assert 'Failed to modify' not in record['stderr'], record['stderr']
                if repeat == 1:
                    assert 'terminated by signal KILL' in record['stderr'], record['stderr']
                tables = json.loads(run('nft', '-j', 'list', 'table', 'ip', 'io.systemd.nat').stdout)
                ports = [item['map'] for item in tables['nftables'] if 'map' in item
                         and item['map']['name'] == 'map_port_ipport']
                assert len(ports) == 1 and not ports[0].get('elem'), ports
                links = json.loads(run('ip', '-j', 'link').stdout)
                assert not any(link.get('master') == bridge for link in links), links
                for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
                    with socket.socket(type=kind) as stream:
                        stream.bind(('10.231.245.1', port))
                record['passed'] = True
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        try:
            if process is not None:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=30)
                process.stdin.close()
                process.stdout.close()
            if connected:
                run('ip', 'link', 'delete', bridge)
        except Exception as error:
            report['passed'] = False
            report['cleanupFailure'] = repr(error)
            raise
        finally:
            if stderr is not None and stderr.exists():
                report['rounds'][-1]['stderr'] = stderr.read_text()
            args.report.write_text(json.dumps(report, indent=2) + '\n')
            print('NSPAWN_COMMAND_REPORT', args.report, 'passed=' + str(report['passed']), flush=True)


if __name__ == '__main__':
    main()
