"""Explicit Android identity for shared native/guest fixtures and inert transfers."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import threading
import uuid
from fixture_app import PRIVATE_DIRECTORY, deadline_seconds


def add_diagnostics_argument(parser):
    parser.add_argument('--diagnostics', nargs='?', const='all', choices=('all', 'signals'),
                        help='Record Shroot transitions or only guest faults/abnormal exits; not for timing')


def diagnostic_options(mode):
    return ['--diagnostics' if mode == 'all' else '--diagnostics=signals'] if mode else []


def probe_native_capability(observe, report, fixture, name):
    result = observe([fixture, '--probe-' + name], quiet=True, timeout=30)
    assert result['exitCode'] in (0, 77, 159), result
    available = result['exitCode'] == 0
    report.setdefault('nativeCapabilities', []).append(
        dict(fixture=fixture, capability=name, available=available, **result))
    if not available:
        print('UNAVAILABLE native ' + name + ': ' + str(result), flush=True)
    return available


class FixtureExecutor:
    choices = ('shell', 'app')

    @classmethod
    def add_argument(cls, parser):
        parser.add_argument('--executor', choices=cls.choices,
                            default=os.environ.get('SHROOT_TEST_EXECUTOR', 'shell'),
                            help='Real Android execution identity; guest credentials are independent')

    def __init__(self, client, transport, output, mode=None, prefix='fixture'):
        self.client, self.transport = client, transport
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.mode = mode or os.environ.get('SHROOT_TEST_EXECUTOR', 'shell')
        if self.mode not in self.choices:
            raise ValueError('Unknown fixture executor: ' + self.mode)
        self.tag = uuid.uuid4().hex
        self.console = client.call('console.open')['sessionId']
        self.receipts = []
        self.packaged = {}
        self.executables = {}
        self.closed = False
        self.active = set()
        self.lock = threading.Lock()
        try:
            if self.mode == 'app':
                result = self._app_command('printf "%s\\n" "$HOME"', 30)
                self.home = result['output'].strip()
                assert self.home.startswith('/data/'), self.home
            else:
                result = self.execute('id -u; cat /proc/self/attr/current')
                uid, selinux = result['output'].strip().splitlines()
                assert uid == '2000' and selinux.rstrip('\0') == 'u:r:shell:s0', result
                self.uid, self.selinux = int(uid), selinux.rstrip('\0')
                self.home = '/data/local/tmp/magicdesk'
            self.root = self.home + '/cache/tmp/' + prefix + '-' + self.tag
            result = self.execute('mkdir -p ' + shlex.quote(self.root))
            assert result['exitCode'] == 0, result
            self.directory = self.root
        except BaseException:
            self.close()
            raise

    @property
    def evidence(self):
        return dict(mode=self.mode, uid=self.uid, selinux=self.selinux,
                    receipts=self.receipts, closed=self.closed)

    def _app_command(self, command, timeout):
        timeout = deadline_seconds(timeout)
        identity = uuid.uuid4().hex
        directory = self.output / ('app-command-' + identity)
        driver = Path(__file__).with_name('fixture_app.py')
        # The existing debug service is the sole launch owner. run-as below
        # transports inert bytes; it never executes a native or guest test.
        with self.lock:
            if self.closed:
                raise RuntimeError('Fixture executor is closed')
            self.active.add(identity)
        try:
            completed = subprocess.run([sys.executable, str(driver), '--run-id', identity,
                '--console-id', self.console, '--command', command,
                '--deadline-seconds', str(timeout), '--output', str(directory)],
                capture_output=True, text=True)
        finally:
            with self.lock:
                self.active.discard(identity)
        paths = [p for p in directory.glob('*.json') if not p.name.endswith('-observed.json')]
        if len(paths) != 1:
            raise RuntimeError('Missing exact app launch receipt: ' + completed.stdout + completed.stderr)
        report = json.loads(paths[0].read_text())
        self.receipts.append(str(paths[0]))
        assert report['finished'] and report['uid'] >= 10000, report
        assert 'untrusted_app' in report['selinux'], report['selinux']
        self.uid, self.selinux = report['uid'], report['selinux'].rstrip('\0')
        self.libraries = report['nativeLibraryDir']
        records = report['commands']
        assert len(records) == 1 and records[0]['name'] == 'shell', records
        record = records[0]
        if not record.get('completed'):
            raise TimeoutError('App command did not complete: ' + str(paths[0]))
        # A test's nonzero exit is data, not a transport failure. The driver
        # still verifies that the main MagicDesk process retained its identity.
        if 'PASS main MagicDesk process retained' not in completed.stdout:
            raise RuntimeError(completed.stdout + completed.stderr)
        self._transport_command(shlex.join(['run-as', 'io.github.mekhontsev.magicdesk', 'rm', '-rf',
            PRIVATE_DIRECTORY + '/runs/' + report['runId']]))
        return dict(exitCode=record['exitCode'],
                    output=(directory / (report['runId'] + '-shell.log')).read_text(errors='replace'))

    def execute(self, command, timeout=120):
        if getattr(self, 'directory', None):
            command = 'cd ' + shlex.quote(self.directory) + ' || exit\n' + command
        if self.mode == 'app':
            return self._app_command(command, timeout)
        return self.client.call('console.execute', dict(sessionId=self.console, command=command))

    def _transport_command(self, command):
        result = self.client.call('console.execute', dict(sessionId=self.console, command=command))
        assert result['exitCode'] == 0, result
        return result['output']

    def upload(self, source, target, **options):
        source = Path(source)
        if self.mode == 'shell':
            return self.transport.upload(self.client, source, target, **options)
        with source.open('rb') as stream:
            executable = stream.read(4) == b'\x7fELF'
            stream.seek(0)
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if executable and source.name.startswith('libmagicdesk_guest_'):
            packaged = self.libraries + '/' + source.name
            if source.name not in self.packaged:
                output = self._transport_command(shlex.join(['sha256sum', packaged]))
                self.packaged[source.name] = output.split()[0]
            if self.packaged[source.name] != digest:
                raise RuntimeError('Install a fixture APK containing the exact build of ' + source.name)
            # Guest mounts need a regular file, not a symlink escaping their
            # root. Native execution separately selects the immutable APK ELF.
            self._transport_command(shlex.join(['run-as', 'io.github.mekhontsev.magicdesk',
                                               'cp', packaged, target]))
            self.executables[target] = packaged
            return dict(path=target, sha256=digest, packaged=packaged)
        staging = '/data/local/tmp/magicdesk/cache/tmp/fixture-transfer-' + uuid.uuid4().hex
        try:
            self.transport.upload(self.client, source, staging)
            self._transport_command(shlex.join(['run-as', 'io.github.mekhontsev.magicdesk',
                '/system/bin/sh', '-c', 'cat > ' + shlex.quote(target)]) + ' < ' + shlex.quote(staging))
        finally:
            self._transport_command(shlex.join(['rm', '-f', staging]))
        return dict(path=target, sha256=digest)

    def chmod(self, mode, *targets):
        return self.execute(shlex.join(['chmod', mode, *targets]))

    def native_path(self, target):
        return self.executables.get(str(target), str(target))

    def download(self, source, target, **options):
        if self.mode == 'shell':
            return self.transport.download(self.client, source, target, **options)
        staging = '/data/local/tmp/magicdesk/cache/tmp/fixture-transfer-' + uuid.uuid4().hex
        try:
            self._transport_command(shlex.join(['run-as', 'io.github.mekhontsev.magicdesk',
                'cat', source]) + ' > ' + shlex.quote(staging))
            return self.transport.download(self.client, staging, target, **options)
        finally:
            self._transport_command(shlex.join(['rm', '-f', staging]))

    def close(self):
        if not self.closed:
            self.cancel()
            self.client.call('console.close', dict(sessionId=self.console))
            self.closed = True

    def cancel(self):
        with self.lock:
            active = tuple(self.active)
        for identity in active:
            self._transport_command(shlex.join(['am', 'start-foreground-service', '-n',
                'io.github.mekhontsev.magicdesk/.GuestFixtureService', '--es', 'operation', 'cancel',
                '--es', 'runId', identity]))
