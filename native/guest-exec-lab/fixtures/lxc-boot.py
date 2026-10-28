#!/usr/bin/env python3
"""Boot an already prepared systemd rootfs under stock LXC common policy."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import uuid
from systemd_ready import wait_systemd
from network_exchange import exchange


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    name = 'md-lxc-' + uuid.uuid4().hex[:10]
    bridge = 'mdlxc' + uuid.uuid4().hex[:8]
    parent = Path('/var/lib/md-fixture-lxc')
    directory = parent / name
    root = directory / 'rootfs'
    root.mkdir(parents=True)
    report = dict(name=name, bridge=bridge, commands=[], rounds=[], passed=False)
    started = False
    connected = False

    def run(*argv, timeout=120, required=True):
        print('RUN', argv, flush=True)
        argv = list(map(str, argv))
        try:
            result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                                    env={**os.environ, 'LC_ALL': 'C'})
        except subprocess.TimeoutExpired as error:
            def text(value):
                return value.decode(errors='replace') if isinstance(value, bytes) else value or ''
            result = subprocess.CompletedProcess(argv, 124, text(error.stdout),
                                                 text(error.stderr) + '\nTimed out: ' + str(timeout))
        report['commands'].append(dict(argv=list(map(str, argv)), code=result.returncode,
                                      stdout=result.stdout, stderr=result.stderr))
        print(result.stdout + result.stderr, end='', flush=True)
        if required:
            assert result.returncode == 0, report['commands'][-1]
        return result

    def lxc(program, *argv, **kwargs):
        return run(program, '-n', name, '-P', parent, *argv, **kwargs)

    def attached(*argv, **kwargs):
        return lxc('lxc-attach', '--clear-env',
                   '--set-var=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
                   '--', *argv, **kwargs)

    def active(timeout):
        assert lxc('lxc-info', '-sH', timeout=timeout).stdout.strip() == 'RUNNING'

    def service(*argv):
        return attached('systemd-run', '--wait', '--pipe', '--collect', *argv)

    try:
        report['version'] = run('lxc-start', '--version').stdout
        package = Path(shutil.which('lxc-start')).resolve().parent.parent
        common = package / 'share/lxc/config/common.conf'
        assert common.is_file(), 'Missing packaged LXC common policy: ' + str(common)
        run('tar', '-xpf', args.archive, '-C', root, timeout=300)
        run('ip', 'link', 'add', bridge, 'type', 'bridge')
        connected = True
        run('ip', 'link', 'set', bridge, 'up')
        run('ip', 'address', 'add', '10.231.246.1/30', 'dev', bridge)
        (directory / 'config').write_text(
            'lxc.include = ' + str(common) + '\n'
            'lxc.rootfs.path = dir:' + str(root) + '\n'
            'lxc.uts.name = ' + name + '\n'
            'lxc.net.0.type = veth\n'
            'lxc.net.0.link = ' + bridge + '\n'
            'lxc.net.0.flags = up\n'
            'lxc.net.0.name = eth0\n')
        report['config'] = (directory / 'config').read_text()
        log = args.report.with_suffix('.trace')
        for repeat in range(3):
            record = dict(round=repeat, passed=False)
            report['rounds'].append(record)
            started = True
            lxc('lxc-start', '-d', '-o', log, '-l', 'TRACE')
            lxc('lxc-wait', '-s', 'RUNNING', '-t', '60')
            wait_systemd(lambda timeout: attached('systemctl', 'is-system-running', '--wait',
                                                   required=False, timeout=timeout), active)
            leader = int(lxc('lxc-info', '-pH').stdout.strip())
            record['leader'] = leader
            assert leader > 1
            result = attached('/bin/sh', '-ec',
                              'test "$(cat /proc/1/comm)" = systemd; '
                              'test "$(id -u)" = 0; test -c /dev/ptmx; '
                              'test "$(findmnt -n -o FSTYPE /proc)" = proc; '
                              'test "$(findmnt -n -o FSTYPE /dev/pts)" = devpts; '
                              'test "$(findmnt -n -o FSTYPE /sys/fs/cgroup)" = cgroup2; '
                              'cat /proc/self/status; systemctl --version; echo LXC_BOOT_OK')
            assert 'LXC_BOOT_OK' in result.stdout and 'Seccomp:\t2' in result.stdout, result.stdout
            assert 'LXC_SERVICE_OK' in service('/bin/echo', 'LXC_SERVICE_OK').stdout
            attached('ip', 'address', 'add', '10.231.246.2/30', 'dev', 'eth0')
            attached('ip', 'link', 'set', 'eth0', 'up')
            peer = int(attached('cat', '/sys/class/net/eth0/iflink').stdout)
            record['network'] = dict(exchange(service, '10.231.246.1'), peer=peer)
            if repeat == 1:
                lxc('lxc-stop', '-k', timeout=90)
                record['exit'] = 'killed'
            else:
                lxc('lxc-stop', '-t', '60', timeout=90)
                record['exit'] = 'shutdown'
            lxc('lxc-wait', '-s', 'STOPPED', '-t', '30')
            started = False
            assert not Path('/proc/' + str(leader)).exists()
            interfaces = socket.if_nameindex()
            record['remainingInterfaces'] = interfaces
            assert not any(index == peer for index, _ in interfaces), interfaces
            assert not Path('/sys/fs/cgroup/lxc.payload.' + name).exists()
            assert not Path('/sys/fs/cgroup/lxc.monitor.' + name).exists()
            record['passed'] = True
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        raise
    finally:
        try:
            if started:
                lxc('lxc-stop', '-k', timeout=60, required=False)
            state = lxc('lxc-info', '-sH', timeout=30, required=False)
            stopped = state.returncode == 0 and state.stdout.strip() == 'STOPPED'
            report['stopped'] = stopped
            if stopped:
                shutil.rmtree(directory)
            else:
                report['retainedRoot'] = str(directory)
            if connected:
                cleanup = run('ip', 'link', 'delete', bridge, required=False)
                report['bridgeCleanup'] = cleanup.returncode == 0
            report['passed'] &= stopped and (not connected or report.get('bridgeCleanup', False))
        except Exception as error:
            report['cleanupFailure'] = repr(error)
            report['passed'] = False
        finally:
            args.report.write_text(json.dumps(report, indent=2) + '\n')
            print('LXC_REPORT', args.report, 'passed=' + str(report['passed']), flush=True)
    assert report['passed'], report


if __name__ == '__main__':
    main()
