#!/usr/bin/env python3
"""Qualify the APK catalog adapter using the shared native client fixture."""
import _bootstrap
import argparse
import json
from pathlib import Path
import shlex
import time
from client_fixture import archive_fixture
from fixture_executor import FixtureExecutor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--executor', choices=('shell', 'app'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / 'rootfs.tar.gz'
    archive_fixture(args.build, archive)
    executor = FixtureExecutor.connect(args.output, args.executor, 'catalog')
    client = executor.client
    library = executor.root + '/library'
    selection = dict(backend=args.executor, library=library)
    report = dict(executor=args.executor, app=executor.state['app'], operations=[], passed=False)
    active = set()

    def start(*arguments):
        print('RUN ' + ' '.join(arguments), flush=True)
        value = client.call('guest.start', dict(selection, arguments=list(arguments)))
        active.add(value['operationId'])
        return value

    def observe(value, done, timeout=90):
        deadline = time.monotonic() + timeout
        while not done(value) and value['state'] == 'running' and time.monotonic() < deadline:
            value = client.call('guest.status', dict(operationId=value['operationId'],
                                afterRevision=value['revision'], timeoutMillis=10000))
        assert done(value), value
        return value

    def finish(value, code=0):
        value = observe(value, lambda v: v['state'] != 'running')
        active.remove(value['operationId'])
        report['operations'].append(value)
        assert value['exitCode'] == code, value
        return value

    def call(*arguments, code=0):
        return finish(start(*arguments), code)

    def catalog():
        return client.call('guest.list', selection)['environments']

    try:
        remote = executor.root + '/rootfs.tar.gz'
        executor.upload(archive, remote)
        assert catalog() == []
        call('install', remote, '--rootfs', '--name', 'first', '--dns', 'preserve')
        first, = catalog()
        call('exec', 'first', '--env', "LIBSHROOT_TEST=retained ' value", '--',
             '/bin/client-guest', "spaces ' ; $(false)")
        call('exec', 'first', '--', '/bin/client-guest', 'exit42', code=42)
        call('dns', 'first', '192.0.2.1', '--replace')
        backup = executor.root + '/backup.tar.zst'
        call('backup', 'first', backup)
        call('restore', backup, '--name', 'restored')
        restored = next(v for v in catalog() if v['name'] == 'restored')
        assert restored['id'] != first['id'] and restored['store'] != first['store']
        call('exec', 'restored', '--', '/bin/client-guest', 'exit42', code=42)
        owners = []
        for index in range(2):
            owners.append(observe(start('exec', 'first', '--', '/bin/client-guest', 'hold'),
                                  lambda v: v['output'].splitlines() == ['ready', 'ready']))
        launches = client.call('guest.launches', dict(selection, name='first'))['launches']
        assert len(launches) == 2 and len({v['launchId'] for v in launches}) == 2, launches
        client.call('guest.cancel', dict(operationId=owners[0]['operationId']))
        cancelled = observe(owners[0], lambda v: v['state'] != 'running')
        active.remove(cancelled['operationId'])
        report['operations'].append(cancelled)
        remaining = client.call('guest.launches', dict(selection, name='first'))['launches']
        assert len(remaining) == 1, remaining
        client.call('guest.stop', dict(selection, name='first', launchId=remaining[0]['launchId']))
        stopped = observe(owners[1], lambda v: v['state'] != 'running')
        active.remove(stopped['operationId'])
        report['operations'].append(stopped)
        assert client.call('guest.launches', dict(selection, name='first'))['launches'] == []
        assert next(v for v in catalog() if v['name'] == 'first') == first
        call('remove', 'first')
        call('remove', 'restored')
        call('prune')
        assert catalog() == []
        report['passed'] = True
        print('PASS catalog: install, metadata, exit status, backup/restore, cancellation and exact stop')
    finally:
        try:
            for identity in active:
                value = client.call('guest.cancel', dict(operationId=identity))
                observe(value, lambda v: v['state'] != 'running')
            executor.execute('cd "$HOME" && rm -rf -- ' + shlex.quote(executor.root))
        finally:
            try:
                executor.close()
            finally:
                (args.output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
