#!/usr/bin/env python3
"""Exercise stock machinectl boot, shell, services, veth IO and restart after exit."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import uuid
from systemd_ready import wait_systemd
from network_exchange import exchange


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--machine')
    source.add_argument('--archive', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    machine = args.machine or 'md-fixture-' + uuid.uuid4().hex
    assert re.fullmatch(r'[a-zA-Z0-9_-]+', machine)
    image = Path('/var/lib/machines') / machine
    created = False
    unit = 'systemd-nspawn@' + machine + '.service'
    report = dict(machine=machine, commands=[], rounds=[], passed=False)
    env = {**os.environ, 'LC_ALL': 'C'}

    def run(*argv, required=True, timeout=60):
        print('RUN', argv, flush=True)
        start = time.monotonic()
        result = subprocess.run(argv, capture_output=True, text=True, env=env, timeout=timeout)
        report['commands'].append(dict(argv=argv, code=result.returncode,
                                       stdout=result.stdout, stderr=result.stderr,
                                       elapsedMs=(time.monotonic() - start) * 1000))
        print(result.stdout + result.stderr, end='', flush=True)
        if required:
            assert result.returncode == 0, report['commands'][-1]
        return result

    def service(*argv):
        return run('systemd-run', '-M', machine, '--wait', '--pipe', '--collect', *argv)

    def ready():
        def active(timeout):
            assert run('systemctl', 'is-active', unit, timeout=timeout).stdout.strip() == 'active'
        # The machine transport uses the public bus. systemd 257's --wait
        # installs a match but does not Subscribe; StartupFinished is then
        # conditional on unrelated subscribers. Reconcile the actual state.
        wait_systemd(lambda timeout: run('systemctl', '-M', machine, 'is-system-running',
                                         required=False, timeout=timeout), active)

    assert run('systemctl', 'is-active', unit, required=False).returncode != 0, 'Machine must be idle'
    started = False
    try:
        if args.archive:
            image.mkdir(parents=True)
            created = True
            run('tar', '-xpf', str(args.archive), '-C', str(image), timeout=180)
        report['versions'] = run('systemd-nspawn', '--version').stdout
        report['template'] = run('systemctl', 'cat', unit).stdout
        for repeat in range(3):
            record = dict(round=repeat, passed=False)
            report['rounds'].append(record)
            started = True
            run('machinectl', 'start', machine, timeout=180)
            assert run('systemctl', 'is-active', unit).stdout.strip() == 'active'
            ready()
            properties = dict(line.split('=', 1) for line in run('machinectl', 'show', machine).stdout.splitlines()
                              if '=' in line)
            leader = int(properties['Leader'])
            assert properties['State'] == 'running' and leader > 1
            record['machine'] = properties
            result = service('/bin/sh', '-ec', 'test "$(cat /proc/1/comm)" = systemd; '
                             'test "$(id -u)" = 0; cat /proc/self/uid_map; echo NS_SERVICE_OK')
            assert 'NS_SERVICE_OK' in result.stdout
            mapping = next(line for line in result.stdout.splitlines() if len(line.split()) == 3)
            inside, outside, count = map(int, mapping.split())
            assert inside == 0 and outside > 0 and count >= 65536
            record['uidMap'] = mapping
            marker = '/run/md-shell-' + uuid.uuid4().hex
            argv = ['machinectl', 'shell', 'root@' + machine, '/bin/sh', '-ec',
                    'printf shell-ok >' + marker + '; echo NS_SHELL_OK']
            # EVENT_WAIT: retain stdin until the remote command exits, not EOF at startup.
            with subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, env=env) as process:
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    raise
                output = process.stdout.read()
                report['commands'].append(dict(argv=argv, code=process.returncode, stdout=output))
                assert process.returncode == 0 and 'NS_SHELL_OK' in output, output
            assert service('/bin/cat', marker).stdout == 'shell-ok'
            links = json.loads(service('/usr/bin/env', 'ip', '-j', 'link', 'show', 'host0').stdout)
            assert links[0]['ifname'] == 'host0'
            peer_index = int(service('/bin/cat', '/sys/class/net/host0/iflink').stdout)
            outer = next(link for link in json.loads(run('ip', '-j', 'link').stdout)
                         if link['ifindex'] == peer_index)
            interface = outer['ifname']
            run('ip', 'address', 'add', '10.231.247.1/30', 'dev', interface)
            run('ip', 'link', 'set', interface, 'up')
            service('/usr/bin/env', 'ip', 'address', 'add', '10.231.247.2/30', 'dev', 'host0')
            service('/usr/bin/env', 'ip', 'link', 'set', 'host0', 'up')
            record['network'] = dict(exchange(service, '10.231.247.1'), interface=interface)
            if repeat == 1:
                run('machinectl', '--kill-whom=leader', '--signal=KILL', 'kill', machine)
                deadline = time.monotonic() + 90
                while True:
                    state = run('systemctl', 'show', '-p', 'ActiveState', '--value', unit).stdout.strip()
                    if state in ('inactive', 'failed'):
                        break
                    assert time.monotonic() < deadline, 'Killed container did not retire'
                    # EVENT_WAIT: bounded unit-state reconciliation after abrupt leader death.
                    threading.Event().wait(0.1)
                result = run('systemctl', 'show', '-p', 'Result', '--value', unit).stdout.strip()
                assert result in ('exit-code', 'signal'), result
                record['exit'] = 'killed'
                run('systemctl', 'reset-failed', unit)
            else:
                run('machinectl', 'poweroff', machine)
                # EVENT_WAIT: systemd stop job waits for guest poweroff and ExecStopPost cleanup.
                run('systemctl', 'stop', unit, timeout=180)
                assert run('systemctl', 'show', '-p', 'Result', '--value', unit).stdout.strip() == 'success'
                record['exit'] = 'poweroff'
            started = False
            assert run('systemctl', 'show', '-p', 'ActiveState', '--value', unit).stdout.strip() == 'inactive'
            assert run('machinectl', 'show', machine, required=False).returncode != 0
            assert not Path('/proc/' + str(leader)).exists()
            assert not any(link['ifindex'] == peer_index for link in json.loads(run('ip', '-j', 'link').stdout))
            record['passed'] = True
        report['passed'] = True
    except Exception as error:
        report['failure'] = repr(error)
        run('journalctl', '-u', unit, '-n', '100', '--no-pager', required=False)
        raise
    finally:
        try:
            if started:
                run('machinectl', 'poweroff', machine, required=False)
                run('systemctl', 'stop', unit, timeout=180)
            if created:
                shutil.rmtree(image)
        finally:
            args.report.write_text(json.dumps(report, indent=2) + '\n')
            print('NSPAWN_REPORT ' + str(args.report) + ' passed=' + str(report['passed']), flush=True)


if __name__ == '__main__':
    main()
