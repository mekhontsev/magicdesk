#!/usr/bin/env python3
"""OCI launch, copy-on-write and directory attachments under actual shell UID.

No Desktop, root, Termux execution, daemon or timing-based readiness checks.
Inputs are immutable OCI layouts; report retains their digests and exact outputs.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import tarfile
import tempfile
import tomllib
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('layouts', type=Path, nargs='+')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '))
    state = client.call('get_state')
    assert state['shell']['uid'] == 2000
    tag = uuid.uuid4().hex
    directory = '/data/local/tmp/md-oci-runtime-' + tag
    console = client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
    report = {'id': tag, 'directory': directory, 'app': state['app'], 'device': state['device'],
              'uid': 2000, 'checks': [], 'uploads': [], 'passed': False}

    def command(argv, timeout=120):
        text = shlex.join(['timeout', str(timeout), *map(str, argv)])
        result = client.call('console.execute', {'sessionId': console, 'command': text})
        report['checks'].append({'command': text, **result})
        print(result['output'], end='', flush=True)
        assert result['exitCode'] == 0, result
        return result['output']

    try:
        command(['mkdir', directory])
        for name in ('bootstrap', 'supervisor', 'run', 'service', 'image', 'test_snapshot', 'test_mounts'):
            filename = 'libmagicdesk_guest_' + name + '.so'
            report['uploads'].append(transport.upload(client, args.build / filename, directory + '/' + filename))
            command(['chmod', '700', directory + '/' + filename])
        for fixture in ('snapshot', 'mounts'):
            command([directory + '/libmagicdesk_guest_test_' + fixture + '.so', directory + '/contract-' + fixture])
        image = directory + '/libmagicdesk_guest_image.so'
        for number, layout in enumerate(args.layouts):
            base = directory + '/' + str(number)
            command(['mkdir', '-p', base + '/layout', base + '/volume', base + '/readonly'])
            with tempfile.TemporaryDirectory(prefix='md-oci-layout-') as tmp:
                archive = Path(tmp) / 'layout.tar'
                with tarfile.open(archive, 'w') as out:
                    out.add(layout / 'oci-layout', arcname='oci-layout')
                    out.add(layout / 'index.json', arcname='index.json')
                    out.add(layout / 'blobs', arcname='blobs')
                report['uploads'].append(transport.upload(client, archive, base + '/layout.tar'))
            command(['tar', '-xf', base + '/layout.tar', '-C', base + '/layout'])
            command([image, 'import', base + '/layout', base + '/image', '--map-current-user'], 180)
            command([image, 'create', base + '/image', base + '/a'])
            command([image, 'create', base + '/image', base + '/b'])
            inspected = json.loads(command([image, 'inspect', base + '/a']))
            assert inspected['kind'] == 'instance' and inspected['sources']
            common = [image, 'run', base + '/a', '--user', 'current']
            output = command([*common, '--env', 'MD_FIXTURE=hello world', '--', '/bin/sh', '-c',
                              'set -eu\ntest "$(id -u)" = 2000\ntest "$MD_FIXTURE" = "hello world"\n'
                              'mkdir -p /tmp/oci /mnt /media\nprintf private >/tmp/oci/a\n'
                              'printf modified >/etc/os-release\ncat /etc/os-release\n'])
            assert 'modified' in output
            command([image, 'run', base + '/b', '--user', 'current', '--', '/bin/sh', '-c',
                     'set -eu\ntest ! -e /tmp/oci/a\ntest "$(cat /etc/os-release)" != modified\n'])
            output = command([*common, '--bind', base + '/volume', '/mnt', '--bind-ro', base + '/readonly', '/media',
                              '--', '/bin/sh', '-c', 'set -eu\nprintf attached >/mnt/value\n'
                              'mkdir /mnt/directory/\nln -s /etc/os-release /mnt/link\n'
                              'test "$(cat /mnt/link)" = modified\ncd /mnt/directory\n'
                              'test "$(pwd)" = /mnt/directory\ncd ../..\ntest "$(pwd)" = /\n'
                              'if printf forbidden >/media/file; then exit 90; fi\n'
                              'test ! -e /media/file\nprintf "PASS OCI attached volume\\n"'])
            assert 'Read-only file system' in output and 'PASS OCI attached volume' in output
            assert command(['cat', base + '/volume/value']) == 'attached'
            command(['/system/bin/sh', '-c', 'printf %s "$1" >"$2"', 'fixture',
                     '#!/bin/sh\nset -eu\ntest "$1" = "literal argument"\n'
                     'test "$PWD" = /mnt\nprintf "PASS executable volume\\n"\n', base + '/volume/entry'])
            command(['chmod', '700', base + '/volume/entry'])
            output = command([*common, '--bind-ro', base + '/volume', '/mnt', '--cwd', '/mnt',
                              '--env', 'PATH=/mnt:/usr/bin:/bin', '--entrypoint', 'entry', '--', 'literal argument'])
            assert 'PASS executable volume' in output
            script = 'set -eu\npids=""\n'
            for instance, text in (('a', 'first'), ('b', 'second')):
                script += shlex.join([image, 'run', base + '/' + instance, '--user', 'current', '--',
                                      '/bin/sh', '-c', 'printf %s "$1" >/tmp/independent', 'fixture', text])
                script += ' &\npids="$pids $!"\n'
            script += 'for pid in $pids; do wait "$pid"; done\n'
            command(['/system/bin/sh', '-c', script])
            for instance, text in (('a', 'first'), ('b', 'second')):
                assert command([image, 'run', base + '/' + instance, '--user', 'current', '--',
                                '/bin/cat', '/tmp/independent']) == text
            command([*common, '--', '/bin/sh', '-c', 'test ! -e /mnt/value'])
        report['passed'] = True
    except Exception as error:
        report['failure'] = str(error)
        raise
    finally:
        try:
            client.call('console.close', {'sessionId': console})
        finally:
            path = args.build / ('oci-runtime-' + tag + '.json')
            path.write_text(json.dumps(report, indent=2) + '\n')
            print('Report:', path, flush=True)


if __name__ == '__main__':
    main()
