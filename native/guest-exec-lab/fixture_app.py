#!/usr/bin/env python3
"""Bounded shell command in MagicDesk's real Zygote identity, never in run-as."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shlex
import time
import tomllib
import uuid

PACKAGE = 'io.github.mekhontsev.magicdesk'
PRIVATE_DIRECTORY = 'cache/guest-fixtures'
MAX_DEADLINE_SECONDS = 7200


def deadline_seconds(value):
    seconds = int(value)
    if not 1 <= seconds <= MAX_DEADLINE_SECONDS:
        raise argparse.ArgumentTypeError(f'deadline must be between 1 and {MAX_DEADLINE_SECONDS} seconds')
    return seconds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path.home() / '.codex/config.toml')
    parser.add_argument('--server', default='magicdesk')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/guest-fixtures'))
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--command')
    action.add_argument('--observe-run', help='Retrieve an exact existing run; never replay it')
    parser.add_argument('--deadline-seconds', type=deadline_seconds, default=60)
    parser.add_argument('--run-id', help='Caller-owned launch ID for targeted cancellation')
    parser.add_argument('--console-id', help='Borrow the fixture owner\'s transport console')
    args = parser.parse_args()
    if args.run_id and args.observe_run:
        parser.error('--run-id applies only to a new command')
    identity = args.observe_run or args.run_id or uuid.uuid4().hex
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', identity):
        parser.error('Invalid run ID')
    if args.observe_run and args.apk:
        parser.error('Observing a run must not replace its APK')
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('md_mcp', repo / 'scripts/mcp-client.py')
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    config = tomllib.loads(args.config.read_text())['mcp_servers'][args.server]
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '))
    args.output.mkdir(parents=True, exist_ok=True)
    if args.apk:
        transport.update(client, args.apk)
    before = client.call('get_state')
    session = args.console_id or client.call('console.open')['sessionId']
    staging = '/data/local/tmp/magicdesk/cache/tmp/fixture-artifacts-' + uuid.uuid4().hex
    remote = PRIVATE_DIRECTORY + '/runs/' + identity

    def command(argv, check=True):
        reply = client.call('console.execute', dict(sessionId=session, command=shlex.join(argv)))
        if check and reply['exitCode']:
            raise RuntimeError(reply['output'])
        return reply

    def download(relative, target):
        source = remote + '/' + relative
        copied = command(['/system/bin/sh', '-c',
            shlex.join(['run-as', PACKAGE, 'cat', source]) + ' > ' + shlex.quote(staging)], False)
        if copied['exitCode']:
            raise RuntimeError('Missing exact fixture artifact: ' + source)
        transport.download(client, staging, target, overwrite=True)

    try:
        if not args.observe_run:
            argv = ['am', 'start-foreground-service', '-n', PACKAGE + '/.GuestFixtureService',
                    '--es', 'operation', 'shell', '--es', 'runId', identity,
                    '--ei', 'deadlineSeconds', str(args.deadline_seconds), '--es', 'command', args.command]
            # The inherited CLI channel is never stored in artifacts or output.
            started = client.call('console.execute', dict(sessionId=session,
                command=shlex.join(argv) + ' --es endpoint "$MAGICDESK_COMMAND_ENDPOINT" >/dev/null'))
            if started['exitCode'] or 'Error' in started['output']:
                raise RuntimeError('Could not start the app-owned shell: ' + started['output'])
            print('Started app-owned shell runId=' + identity, flush=True)
        else:
            print('Observing existing runId=' + identity, flush=True)
        deadline = time.monotonic() + args.deadline_seconds + 180
        delay = 0.1
        while time.monotonic() < deadline:
            # Bounded observation of the atomic receipt, not a settling delay.
            observed = command(['run-as', PACKAGE, 'cat', remote + '/result.json'], False)
            if not observed['exitCode']:
                try:
                    report = json.loads(observed['output'])
                except json.JSONDecodeError:
                    snapshot = args.output / (identity + '-observed.json')
                    download('result.json', snapshot)
                    report = json.loads(snapshot.read_text())
                assert report['runId'] == identity, 'Report identity mismatch'
                if report['finished']:
                    (args.output / (identity + '.json')).write_text(json.dumps(report, indent=2))
                    for record in report['commands']:
                        assert record['name'] == 'shell', record['name']
                        download('shell.log', args.output / (identity + '-shell.log'))
                        print('shell exit=' + str(record.get('exitCode')), flush=True)
                    assert report['uid'] >= 10000 and 'untrusted_app' in report.get('selinux', ''), 'Wrong Android identity'
                    assert report['buildId'] == before['app']['buildId'], 'Fixture APK mismatch'
                    after = client.call('get_state')
                    assert after['app']['instanceId'] == before['app']['instanceId'], 'Main process restarted'
                    print('uid=' + str(report['uid']) + ' targetSdk=' + str(report['targetSdk'])
                          + ' selinux=' + report['selinux'].rstrip('\0'), flush=True)
                    print('PASS main MagicDesk process retained during observation', flush=True)
                    if not report['success']:
                        raise RuntimeError(report.get('error', 'Fixture transport failed'))
                    return
            time.sleep(delay)
            delay = min(1, delay * 1.5)
        raise TimeoutError('Unconfirmed fixture run: ' + identity + '; not replayed')
    finally:
        try:
            command(['rm', '-f', staging], False)
        finally:
            if not args.console_id:
                client.call('console.close', dict(sessionId=session))


if __name__ == '__main__':
    main()
