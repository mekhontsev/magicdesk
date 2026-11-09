#!/usr/bin/env python3
"""Run the BuildKit OCI/CNI fixture in an existing installed Linux session.

Requires an idle dedicated BuildKit daemon and a prepared container base image.
The caller owns daemon startup and teardown; no daemon options are weakened.
"""
import argparse
import json
from pathlib import Path
import shlex
import urllib.request
import uuid

from test_oci_services import Suite
from boot_fixture import run_fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, required=True)
    parser.add_argument('--prepare', action='store_true', help='Prepare a dedicated worker with the packaged BuildKit')
    parser.add_argument('--archive', type=Path, help='Prepare with an official BuildKit archive when the OS omits it')
    parser.add_argument('--engine', choices=('podman', 'docker'), default='podman',
                        help='Installed engine for base export and result execution; BuildKit remains OCI/CNI')
    parser.add_argument('--image', default='docker.io/library/alpine:3.23')
    args = parser.parse_args()
    state = json.loads(args.session.read_text())
    assert not state.get('closed')
    if args.prepare or args.archive:
        run_fixture(args.session, 'buildkit-service', [state['init'], args.archive or '-'],
                    [args.archive] if args.archive else [])
    suite = Suite(args.session.parent, session=state)
    tag = uuid.uuid4().hex
    suite.report.update(session=state['id'], environment=state['environment'],
                        nativeHashes=state.get('nativeHashes'), entryMode=state.get('entryMode', 'lazy'),
                        engine=args.engine)
    try:
        fixture = Path(__file__).with_name('fixtures') / 'buildkit-build.py'
        suite.upload(fixture, state['directory'] + '/buildkit-build.py', overwrite=True)
        suite.chmod('644', state['directory'] + '/buildkit-build.py')
        result_name = 'buildkit-' + tag + '.json'
        command = shlex.join(['python3', state['target'] + '/buildkit-build.py',
                             state['target'] + '/' + result_name,
                             '--engine', args.engine, '--image', args.image])
        request = urllib.request.Request('http://127.0.0.1:' + str(state['rootPort']),
            data=json.dumps(dict(id=tag, command=command, wait=True)).encode(),
            headers={'Authorization': 'Bearer ' + state['token']})
        print('BuildKit log: ' + state['directory'] + '/' + tag + '.log', flush=True)
        # EVENT_WAIT: exact root-controller job; a timeout never replays the request.
        response = json.loads(urllib.request.urlopen(request, timeout=900).read())
        log = args.session.parent / ('buildkit-' + tag + '.log')
        result = args.session.parent / result_name
        suite.download(state['directory'] + '/' + tag + '.log', log)
        suite.download(state['directory'] + '/' + result_name, result)
        suite.report.update(log=str(log), probe=json.loads(result.read_text()))
        assert response['exitCode'] == 0 and suite.report['probe']['passed'], log.read_text()[-6000:]
        suite.report['passed'] = True
        print('PASS BuildKit build/cache/secret/export/run/cancel/cleanup in ' + state['name'], flush=True)
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
