"""Optional MagicDesk transport; no runtime or test assertions belong here."""
import _bootstrap
import importlib.util
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import sys
import threading
import time
import tomllib
import uuid
from fixture_executor import deadline_seconds

PACKAGE = 'io.github.mekhontsev.magicdesk'
PRIVATE_DIRECTORY = 'cache/guest-fixtures'


def source_root():
    return _bootstrap.SOURCE


def executor(output, mode, prefix, *, config=None, server=None):
    server = server or 'magicdesk'
    client, transport = connect(config, server)
    return MagicDeskExecutor(client, transport, output, mode, prefix, config=config, server=server)


def init_prepare():
    return subprocess.check_output(['sh', str(source_root() / 'scripts/install_linux.sh'),
                                   '--print-init-prepare'], text=True)


def init_helper():
    return subprocess.check_output(['sh', str(source_root() / 'scripts/install_linux.sh'),
                                   '--print-init-session'], text=True)


def load_transport():
    script = source_root() / 'scripts/mcp-client.py'
    spec = importlib.util.spec_from_file_location('magicdesk_transport', script)
    transport = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(transport)
    return transport


def connect(config=None, server='magicdesk'):
    transport = load_transport()
    settings = tomllib.loads(Path(config or Path.home() / '.codex/config.toml').read_text())
    selected = settings['mcp_servers'][server]
    client = transport.Client(selected['url'], selected['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=270, request_timeout=240)
    return client, transport


class MagicDeskExecutor:
    shell = '/system/bin/sh'
    command_shell = '"$MAGICDESK_ANDROID_SHELL"'

    def __init__(self, client, transport, output, mode=None, prefix='fixture', *, config=None, server='magicdesk'):
        self.client, self.transport = client, transport
        self.config, self.server = config, server
        state = client.call('get_state')
        self.state = {key: state[key] for key in ('app', 'device')}
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.mode = mode or os.environ.get('SHROOT_TEST_EXECUTOR', 'shell')
        if self.mode not in ('shell', 'app'):
            raise ValueError('Unknown fixture executor: ' + self.mode)
        self.tag = uuid.uuid4().hex
        self.console = client.call('console.open')['sessionId']
        self.files = None
        self.receipts = []
        self.executables = {}
        self.closed = False
        self.active = set()
        self.lock = threading.Lock()
        try:
            if self.mode == 'app':
                from app_files import AppFiles
                self.files = AppFiles(client)
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
        # The debug service owns the launch and receipt; its User console only
        # transports inert artifacts under that same application identity.
        with self.lock:
            if self.closed:
                raise RuntimeError('Fixture executor is closed')
            self.active.add(identity)
        try:
            completed = subprocess.run([sys.executable, str(driver), '--run-id', identity,
                '--console-id', self.console, '--command', command,
                '--server', self.server, *(['--config', str(self.config)] if self.config else []),
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
        self.files.command('rm -rf "$HOME/' + PRIVATE_DIRECTORY + '/runs/' + identity + '"')
        return dict(exitCode=record['exitCode'],
                    output=(directory / (report['runId'] + '-shell.log')).read_text(errors='replace'))

    def execute(self, command, timeout=120, *, capture=False, on_line=None):
        timeout = deadline_seconds(timeout)
        if on_line is not None:
            return self._stream(command, timeout, on_line)
        if getattr(self, 'directory', None):
            command = 'cd ' + shlex.quote(self.directory) + ' || exit\n' + command
        if self.mode == 'app':
            return self._app_command(command, timeout)
        if capture:
            # Console replies are bounded summaries. Diagnostic assertions need
            # the complete stream, transported as a file without rerunning it.
            name = 'command-' + uuid.uuid4().hex + '.log'
            # Cleanup may remove the workload root; its receipt has independent ownership.
            remote, local = self.root + '-' + name, self.output / name
            try:
                value = self._shell_command(
                    '(\n' + command + '\n) > ' + shlex.quote(remote) + ' 2>&1', timeout)
                self.download(remote, local)
                return dict(value, output=local.read_text(errors='replace'))
            finally:
                self._transport_command(shlex.join(['rm', '-f', '--', remote]))
        return self._shell_command(command, timeout)

    def _shell_command(self, command, timeout=120):
        timeout = deadline_seconds(timeout)
        return self.client.call('console.execute', dict(sessionId=self.console, command=command),
                                deadline=time.monotonic() + timeout, request_timeout=timeout)

    def _transport_command(self, command):
        result = self._shell_command(command)
        assert result['exitCode'] == 0, result
        return result['output']

    def upload(self, source, target, *, packaged_name=None, **options):
        source = Path(source)
        if self.mode == 'shell':
            return self.transport.upload(self.client, source, target, **options)
        result = self.files.upload(source, target, self.libraries, packaged_name)
        if 'packaged' in result:
            self.executables[target] = result['packaged']
        return result

    def chmod(self, mode, *targets):
        return self.execute(shlex.join(['chmod', mode, *targets]))

    def native_path(self, target):
        return self.executables.get(str(target), str(target))

    def host_tool(self, name):
        return '/system/bin/' + name

    def fork(self, prefix='server'):
        from fixture_executor import FixtureExecutor
        return FixtureExecutor.connect(self.output, self.mode, prefix,
                                       config=self.config, server=self.server)

    def _stream(self, command, timeout, on_line):
        # MCP returns bounded command summaries. The on-device adapter owns
        # the complete service log channel, independently of its assertions.
        errors, connections = [], []
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            listener.settimeout(min(20, timeout))

            def read():
                try:
                    connection, _ = listener.accept()
                    connections.append(connection)
                    connection.settimeout(timeout)
                    with connection, connection.makefile('r', errors='replace') as stream:
                        for line in stream:
                            on_line(line)
                except Exception as error:
                    errors.append(error)

            reader = threading.Thread(target=read)
            reader.start()
            try:
                pipeline = ('set -o pipefail\n(\n' + command
                            + '\n) 2>&1 | /system/bin/toybox nc -w 10 127.0.0.1 '
                            + str(listener.getsockname()[1]))
                result = self.execute(pipeline, timeout)
                # EVENT_WAIT: log EOF after command completion; failure is not readiness.
                reader.join(20)
                if reader.is_alive():
                    raise TimeoutError('MagicDesk service log did not reach EOF')
                if errors:
                    raise errors[0]
                return result
            finally:
                for connection in connections:
                    try:
                        connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                listener.close()
                reader.join(min(20, timeout) + 1)
                if reader.is_alive():
                    raise TimeoutError('MagicDesk log reader did not stop')

    def download(self, source, target, **options):
        if self.mode == 'shell':
            return self.transport.download(self.client, source, target, **options)
        return self.files.transfer(source, target)

    def close(self):
        if not self.closed:
            try:
                self.cancel()
            finally:
                try:
                    if self.files:
                        self.files.close()
                finally:
                    self.client.call('console.close', dict(sessionId=self.console),
                                     deadline=time.monotonic() + 30, request_timeout=30)
                    self.closed = True

    def cancel(self):
        with self.lock:
            active = tuple(self.active)
        for identity in active:
            self._transport_command(shlex.join(['am', 'start-foreground-service', '-n',
                PACKAGE + '/.GuestFixtureService', '--es', 'operation', 'cancel',
                '--es', 'runId', identity]))
