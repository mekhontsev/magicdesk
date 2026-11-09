#!/usr/bin/env python3
"""Exercise an existing OCI BuildKit worker without pulling a base image."""
import argparse
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--address', default='unix:///run/md-fixture-buildkit.sock')
    parser.add_argument('--image', default='docker.io/library/alpine:3.23')
    parser.add_argument('--engine', choices=('podman', 'docker'), default='podman')
    parser.add_argument('--podman-root', default='/var/lib/md-fixture-podman')
    parser.add_argument('--podman-runroot', default='/var/lib/md-fixture-podman-run')
    args = parser.parse_args()
    report = dict(passed=False, commands=[], engine=args.engine)
    engine = (['podman', '--root', args.podman_root, '--runroot', args.podman_runroot,
               '--storage-driver', 'overlay'] if args.engine == 'podman'
              else ['docker', '--host', 'unix:///var/run/docker.sock'])
    name = 'md-buildkit-' + uuid.uuid4().hex
    image = 'localhost/' + name + ':test'
    created = False
    loaded = False
    report['containerLimits'] = 'engine defaults; no --ulimit overrides'

    def run(*argv, timeout=300, required=True):
        print('RUN', argv, flush=True)
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        report['commands'].append(dict(argv=argv, code=result.returncode,
                                      stdout=result.stdout, stderr=result.stderr))
        print(result.stdout + result.stderr, end='', flush=True)
        if required:
            assert result.returncode == 0, report['commands'][-1]
        return result

    try:
        report['versions'] = dict(client=run('buildctl', '--version').stdout,
                                  daemon=run('buildkitd', '--version').stdout)
        report['workers'] = json.loads(run('buildctl', '--addr', args.address, 'debug', 'workers',
                                          '--format', '{{json .}}').stdout)
        assert any(worker['labels'].get('org.mobyproject.buildkit.worker.network') == 'cni'
                   and worker['labels'].get('org.mobyproject.buildkit.worker.oci.process-mode') == 'sandbox'
                   for worker in report['workers']), report['workers']
        report['base'] = json.loads(run(*engine, 'image', 'inspect', args.image).stdout)
        with tempfile.TemporaryDirectory(prefix=name + '-', dir='/var/tmp') as temporary:
            root = Path(temporary)
            context = root / 'context'
            context.mkdir()
            run(*engine, 'create', '--pull=never', '--name', name, args.image, '/bin/true')
            created = True
            run(*engine, 'export', '-o', str(context / 'rootfs.tar'), name)
            run(*engine, 'rm', name)
            created = False
            secret = root / 'secret'
            secret.write_text('private-build-input\n')
            (context / 'seed').write_text('one\n')
            dockerfile = '''FROM scratch AS base
ADD rootfs.tar /
FROM base AS left
COPY seed /seed
RUN --mount=type=cache,id=CACHE_ID,target=/cache --mount=type=secret,id=probe,required=true test "$(cat /run/secrets/probe)" = private-build-input && if test "$(cat /seed)" = one; then test ! -e /cache/value && echo cache-ok >/cache/value; else test "$(cat /cache/value)" = cache-ok; fi && cp /seed /left
FROM base AS right
RUN printf right-ok >/right
FROM base
COPY --from=left /left /left
COPY --from=right /right /right
RUN test "$(cat /right)" = right-ok && test ! -e /run/secrets/probe && test ! -e /cache/value
CMD ["/bin/sh", "-ec", "cat /left /right"]
'''.replace('CACHE_ID', name)
            (context / 'Dockerfile').write_text(dockerfile)
            command = ['buildctl', '--addr', args.address, 'build', '--frontend', 'dockerfile.v0',
                       '--local', 'context=' + str(context), '--local', 'dockerfile=' + str(context),
                       '--secret', 'id=probe,src=' + str(secret), '--progress', 'plain',
                       '--output', 'type=oci,name=' + image + ',dest=' + str(root / 'image.tar')]
            run(*command)
            repeated = run(*command)
            assert 'CACHED' in repeated.stderr, 'No cached vertex on identical rebuild'
            (context / 'seed').write_text('two\n')
            run(*command)
            run(*engine, 'load', '-i', str(root / 'image.tar'))
            loaded = True
            result = run(*engine, 'run', '--rm', '--pull=never', image)
            assert result.stdout == 'two\nright-ok', result.stdout
            run(*engine, 'rmi', image)
            loaded = False

            marker = name + '-cancel'
            (context / 'Dockerfile').write_text(
                'FROM scratch\nADD rootfs.tar /\n'
                'RUN sh -c \'echo CANCEL_READY; sleep 300\' ' + marker + '\n')
            output = bytearray()
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
                try:
                    with selectors.DefaultSelector() as selector:
                        selector.register(process.stdout, selectors.EVENT_READ)
                        deadline = time.monotonic() + 120
                        # EVENT_WAIT: BuildKit's running vertex announces readiness;
                        # the deadline fails the test rather than guessing startup.
                        while b'\nCANCEL_READY\n' not in output and not any(
                                line.endswith(b' CANCEL_READY') for line in output.splitlines()):
                            assert time.monotonic() < deadline, output.decode(errors='replace')
                            assert selector.select(max(0, deadline - time.monotonic())), 'No vertex progress'
                            data = os.read(process.stdout.fileno(), 65536)
                            assert data, output.decode(errors='replace')
                            output.extend(data)
                    process.send_signal(signal.SIGINT)
                    tail, _ = process.communicate(timeout=60)
                    output.extend(tail)
                    assert process.returncode != 0, 'Cancelled build succeeded'
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=15)
            report['cancellation'] = dict(code=process.returncode, output=output.decode(errors='replace'))
            deadline = time.monotonic() + 30
            while True:
                remaining = []
                for path in Path('/proc').glob('[0-9]*/cmdline'):
                    try:
                        if marker.encode() in path.read_bytes():
                            remaining.append(str(path))
                    except (FileNotFoundError, ProcessLookupError, PermissionError):
                        pass
                if not remaining:
                    break
                assert time.monotonic() < deadline, ('Cancelled vertex still running', remaining)
                # EVENT_WAIT: bounded reconciliation of the cancelled vertex's
                # process lifetime; an expired deadline fails cleanup.
                time.sleep(0.1)
            # A fresh build after cancellation must still use the worker normally.
            (context / 'Dockerfile').write_text(dockerfile)
            run(*command)
            usage = json.loads(run('buildctl', '--addr', args.address, 'du', '--format', '{{json .}}').stdout)
            assert not any(item['inUse'] for item in usage), usage
            report['releasedCacheRecords'] = len(usage)
            containers = run(*engine, 'ps', '-a', '--format', '{{.Names}}').stdout.splitlines()
            assert name not in containers, containers
        report['passed'] = True
    finally:
        if created:
            run(*engine, 'rm', '-f', name, required=False)
        if loaded:
            run(*engine, 'rmi', image, required=False)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print('BUILDKIT_REPORT', args.report, 'passed=' + str(report['passed']), flush=True)


if __name__ == '__main__':
    main()
