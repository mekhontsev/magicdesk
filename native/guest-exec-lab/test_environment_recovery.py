#!/usr/bin/env python3
"""Destructive-to-MagicDesk-process recovery fixture; uses its own disposable library."""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import time
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', default='/data/local/tmp/md-workflow-cold01/check-debian-cold01.tar.zst')
    parser.add_argument('--kill-app', action='store_true', required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '), timeout=90)
    tag = uuid.uuid4().hex
    root = '/data/local/tmp/md-recovery-' + tag
    library = root + '/library'
    report = {'library': library, 'passed': False}
    console = lease = None

    def command(*words):
        result = client.call('console.execute', {'sessionId': console, 'command': shlex.join(words)})
        assert result['exitCode'] == 0, result
        return result['output']

    def finish(operation):
        deadline = time.monotonic() + 180
        while operation['state'] == 'running' and time.monotonic() < deadline:
            operation = client.call('guest.status', {'operationId': operation['operationId'],
                                    'afterRevision': operation['revision'], 'timeoutMillis': 10000})
        assert operation['state'] == 'completed', operation
        return operation

    try:
        before = client.call('get_state')
        assert before['shell']['uid'] == 2000 and not before['workspaces'] and not before['graphics'], before
        baseline = client.call('guest.list')['environments']
        report['before'] = before['app']
        lease = client.call('device.keep_awake', {'durationMillis': 1800000})['leaseId']
        console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
        # Resolve the exported launcher before losing the MCP process. Termux's am is only recovery transport.
        resolved = command('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN',
                           '-c', 'android.intent.category.LAUNCHER', '-p', before['app']['package']).strip().splitlines()[-1]
        assert resolved.startswith(before['app']['package'] + '/'), resolved
        command('mkdir', '-p', root)
        archive = root + '/input.tar.zst'
        command('cp', args.archive, archive)
        operation = client.call('guest.start', {'library': library, 'arguments': ['restore', archive, '--name', 'interrupted']})
        # BOUNDED_STATE_WAIT: observe this exact restore helper, then stop it while it owns unpublished work.
        deadline = time.monotonic() + 30
        helper = None
        while time.monotonic() < deadline:
            output = command('ps', '-A', '-o', 'PID,ARGS')
            rows = [line.split(None, 1) for line in output.splitlines() if 'libmagicdesk_guest_image.so restore ' + archive + ' ' in line]
            if rows:
                assert len(rows) == 1, rows
                helper = int(rows[0][0])
                command('kill', '-STOP', str(helper))
                break
        assert helper, 'Restore finished before the crash fixture could hold it'
        report['helperPid'] = helper
        assert not client.call('guest.list', {'library': library})['environments']
        try:
            client.call('console.execute', {'sessionId': console, 'command': 'am force-stop ' + shlex.quote(before['app']['package'])})
        except (transport.ToolError, OSError):
            pass
        console = lease = None
        subprocess.run(['am', 'start', '-n', resolved], check=True, timeout=20)
        after = client.call('get_state', retry=True)
        assert after['app']['instanceId'] != before['app']['instanceId'], after
        assert after['shell']['uid'] == 2000, after
        report['after'] = after['app']
        lease = client.call('device.keep_awake', {'durationMillis': 1800000})['leaseId']
        console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
        # BOUNDED_STATE_WAIT: binder-owner cleanup terminates the held helper; no PID signal is replayed.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            output = command('ps', '-A', '-o', 'PID,ARGS')
            if not any('libmagicdesk_guest_image.so restore ' + archive + ' ' in line for line in output.splitlines()): break
        else: raise AssertionError('Import helper outlived its MagicDesk operation owner')
        assert client.call('guest.list')['environments'] == baseline
        assert not client.call('guest.list', {'library': library})['environments']
        finish(client.call('guest.start', {'library': library, 'arguments': ['prune']}))
        report['retry'] = finish(client.call('guest.start', {'library': library,
                         'arguments': ['restore', archive, '--name', 'interrupted']}))
        result = finish(client.call('guest.start', {'library': library,
                        'arguments': ['exec', 'interrupted', '--', '/bin/sh', '-c', 'test -r /etc/os-release && echo RECOVERED']}))
        assert 'RECOVERED' in result['output'], result
        finish(client.call('guest.start', {'library': library, 'arguments': ['remove', 'interrupted']}))
        finish(client.call('guest.start', {'library': library, 'arguments': ['prune']}))
        assert client.call('guest.list')['environments'] == baseline
        command('rm', '-r', root)
        report['passed'] = True
        print('PASS app-process death, child cancellation, unchanged independent catalog, prune and retry', flush=True)
    finally:
        if console: client.call('console.close', {'sessionId': console})
        if lease: client.call('device.release_awake', {'leaseId': lease})
        (repo / 'build/environment-recovery.json').write_text(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
