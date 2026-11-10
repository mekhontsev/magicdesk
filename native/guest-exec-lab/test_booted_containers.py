#!/usr/bin/env python3
"""Run container network probes in a retained installed init session."""
import argparse
import json
from pathlib import Path
import shlex
import signal
import urllib.request
import uuid

from docker_network_fixture import Observer
from init_fixture import start_service
from test_oci_services import Suite


PRESERVE_SYSTEM_MOUNTS = '''
import json
import os
from pathlib import Path
import subprocess
import sys

def snapshot():
    paths = ('/sys', '/sys/fs/cgroup')
    return dict(mounts=[line for line in Path('/proc/self/mountinfo').read_text().splitlines()
                        if line.split()[4] in paths],
                flags={path: os.statvfs(path).f_flag for path in paths})

before = snapshot()
result = subprocess.run(['/bin/sh', '-ec', sys.argv[1]])
after = snapshot()
print('SYSTEM_MOUNTS_REPORT ' + json.dumps(dict(before=before, after=after)), flush=True)
assert before == after, 'Container probe changed the booted system mounts'
sys.exit(result.returncode)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True, type=Path)
    parser.add_argument('--engine', choices=('docker', 'podman'), required=True)
    parser.add_argument('--docker-profile', choices=('service', 'routed', 'iptables', 'nftables'), default='service')
    parser.add_argument('--internet', action='store_true')
    parser.add_argument('--rootless', action='store_true', help='Use the retained ordinary-user session and stock rootless launcher')
    parser.add_argument('--trace', action='store_true', help='Trace Podman exec and its DNS service for diagnosis')
    parser.add_argument('--trace-runtime', action='store_true',
                        help='Trace runc in the fixture-owned rootless Docker daemon')
    parser.add_argument('--trace-network', action='store_true',
                        help='Attach to the rootless Docker daemon after its stock set-ID startup')
    parser.add_argument('--image-archive', default='',
                        help='Guest Docker image archive to load instead of pulling from a registry')
    parser.add_argument('--network-repeats', type=int, default=20,
                        help='Connections per Docker network exchange')
    parser.add_argument('--path-prefix', default='', help='Installed vendor command directory; no runtime option changes')
    args = parser.parse_args()
    if args.network_repeats < 1 or (args.network_repeats != 20 and args.engine != 'docker'):
        parser.error('--network-repeats requires Docker and a positive count')
    if args.trace and args.engine != 'podman':
        parser.error('--trace requires --engine podman')
    if args.image_archive and args.engine != 'docker':
        parser.error('--image-archive requires --engine docker')
    if args.trace_runtime and (args.engine != 'docker' or not args.rootless):
        parser.error('--trace-runtime requires --engine docker --rootless')
    if args.trace_network and (args.engine != 'docker' or not args.rootless):
        parser.error('--trace-network requires --engine docker --rootless')
    if args.rootless and (args.trace or args.docker_profile != 'service'):
        parser.error('--rootless retains stock set-ID helpers and daemon policy')
    state = json.loads(args.session.read_text())
    assert not state.get('closed')
    if args.rootless:
        assert state.get('userPort') and state.get('user') and not state.get('headless'), (
            'Rootless checks need a retained ordinary-user session; use an offscreen desktop, not root-only headless boot')
    assert state.get('networkProfile') in ('routed', 'host'), (
        'The external port observer requires Android loopback access; start the '
        'fixture with --network-profile routed (containers still use private bridges)')
    suite = Suite(args.session.parent, session=state)
    observer = Observer()
    tag = uuid.uuid4().hex
    suite.report.update(engine=args.engine, session=state['id'], environment=state['environment'],
                        nativeHashes=state.get('nativeHashes'), entryMode=state.get('entryMode', 'lazy'),
                        pathPrefix=args.path_prefix, rootless=args.rootless,
                        imageArchive=args.image_archive, networkRepeats=args.network_repeats,
                        diagnostics=dict(podman=args.trace, runtime=args.trace_runtime, network=args.trace_network))
    try:
        for name in ('probe_docker_runtime.py', 'probe_podman_runtime.py', 'docker_network_fixture.py'):
            remote = state['directory'] + '/' + name
            suite.upload(Path(__file__).with_name(name), remote, overwrite=True)
            suite.chmod('644', remote)
        if args.engine == 'docker':
            options = dict(profile=args.docker_profile, image='python:3.14-alpine',
                           observer=observer.address, internet=args.internet,
                           image_archive=args.image_archive, network_repeats=args.network_repeats)
            if args.docker_profile == 'service':
                if args.rootless:
                    options.update(profile='rootless', cgroup='v2', storage_driver='overlay2',
                                   rootless_user=state['user'], trace_runtime=args.trace_runtime,
                                   trace_network=args.trace_network)
                else:
                    options['endpoint'] = 'unix:///var/run/docker.sock'
            else:
                options.update(storage_driver='overlay2', data_root='/var/lib/md-fixture-docker',
                               userland_proxy=args.docker_profile == 'routed')
            argv = ['python3', '-c', 'import json,sys; from probe_docker_runtime import guest; '
                    'guest(**json.loads(sys.argv[1]))', json.dumps(options)]
            command = ('(' + start_service(state['init'], 'docker') + ') && '
                       if args.docker_profile == 'service' and not args.rootless else '')
            command += 'cd ' + shlex.quote(state['target']) + ' && ' + shlex.join(argv)
        else:
            options = dict(directory='/tmp/md-podman-' + tag, cache='/var/lib/md-fixture-podman',
                storage='overlay', network='bridge', image='docker.io/library/alpine:3.23',
                cgroups='enabled', internet=args.internet, portsImage='docker.io/library/python:3.14-alpine',
                ipv6=False, ipv6Host='::1', ipv6PublishHost='::1', internetIpv6=False,
                trace=args.trace, hostLimits=False, unlimitedPids=False, execRepeats=3, observer=observer.address,
                prepared=True)
            if args.rootless:
                options.update(rootlessUser=state['user'], network='default',
                               cache='/tmp/md-rootless-podman-' + state['user'])
            argv = ['python3', state['target'] + '/probe_podman_runtime.py', '--guest', json.dumps(options)]
            command = shlex.join(argv)
        if args.path_prefix:
            assert args.path_prefix.startswith('/') and ':' not in args.path_prefix
            command = 'PATH=' + shlex.quote(args.path_prefix) + ':"$PATH"; export PATH; ' + command
        command = shlex.join(['python3', '-c', PRESERVE_SYSTEM_MOUNTS, command])
        request = urllib.request.Request('http://127.0.0.1:' + str(state['userPort' if args.rootless else 'rootPort']),
            data=json.dumps(dict(id=tag, command=command, wait=True)).encode(),
            headers={'Authorization': 'Bearer ' + state['token']})
        print('Container log: ' + state['directory'] + '/' + tag + '.log', flush=True)
        # EVENT_WAIT: probe completion through the exact booted session; no replay on timeout.
        response = json.loads(urllib.request.urlopen(request, timeout=1900).read())
        assert (response['uid'] != 0) == args.rootless, 'Controller identity does not match the selected probe'
        suite.report['guestUid'] = response['uid']
        local = args.session.parent / (args.engine + '-' + tag + '.log')
        suite.download(state['directory'] + '/' + tag + '.log', local)
        output = local.read_text()
        suite.report['log'] = str(local)
        marker = args.engine.upper() + '_REPORT '
        reports = [json.loads(line.removeprefix(marker)) for line in output.splitlines() if line.startswith(marker)]
        if len(reports) == 1:
            suite.report['probe'] = reports[0]
        mount_reports = [json.loads(line.removeprefix('SYSTEM_MOUNTS_REPORT '))
                         for line in output.splitlines() if line.startswith('SYSTEM_MOUNTS_REPORT ')]
        assert len(mount_reports) == 1, output[-6000:]
        suite.report['systemMounts'] = mount_reports[0]
        assert mount_reports[0]['before'] == mount_reports[0]['after'], mount_reports[0]
        assert response['exitCode'] == 0, output[-6000:]
        assert len(reports) == 1, output[-6000:]
        report = reports[0]
        if args.engine == 'podman':
            assert report['passed'], report.get('failure')
        else:
            assert report.get('portPublicationPassed') and report.get('containerPeerExchangePassed'), (
                'Docker network acceptance failed', report.get('failure'),
                {key: report.get(key) for key in ('portPublicationPassed',
                    'containerPeerExchangePassed', 'localOutboundPassed')})
            if args.trace_network:
                assert report.get('daemonTraceAttachment') and not report.get('daemonTraceForcedStop'), report
                assert report.get('daemonTraceExitCode') in (0, -signal.SIGINT), report
            if args.docker_profile == 'service' and not args.rootless:
                assert report['externalDaemon'] and report['daemonRetained'], report
            else:
                assert report['daemonExitCode'] == 0 and report['readerDrained'], report
            if args.internet:
                assert report['internetPassed'], report
            for item in report['commands']:
                argv = item['command']
                if report.get('portConflictContainer') in argv and 'run' in argv:
                    continue
                if argv[3:5] == ['image', 'inspect']:
                    continue
                assert item.get('exitCode') == 0, item
        suite.report['passed'] = True
        print('PASS ' + args.engine + ' in booted ' + state['name'], flush=True)
    except Exception as error:
        suite.report.update(passed=False, failure=repr(error))
        raise
    finally:
        observer.close()
        suite.report['externalPortChecks'] = observer.records
        suite.finish()


if __name__ == '__main__':
    main()
