"""Inert fixture bytes owned by the app, without run-as or a privilege switch."""
import _bootstrap
import hashlib
from pathlib import Path
import secrets
import shlex
import shutil
import socket
import threading


class AppFiles:
    def __init__(self, client, *, console=None):
        self.client = client
        self.owns_console = console is None
        self.console = client.call('console.open', dict(backend='app'))['sessionId'] if self.owns_console else console
        self.packaged = {}

    def upload(self, source, target, libraries, packaged_name=None):
        source = Path(source)
        name = packaged_name or source.name
        if packaged_name and (not name.startswith('libshroot_guest_') or '/' in name):
            raise ValueError('Invalid packaged fixture name')
        if name.startswith('libshroot_guest_'):
            with source.open('rb') as stream:
                if stream.read(4) == b'\x7fELF':
                    stream.seek(0)
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    if not libraries:
                        raise ValueError('Missing APK library directory for native fixture')
                    packaged = libraries + '/' + name
                    if packaged not in self.packaged:
                        self.packaged[packaged] = self.command(shlex.join(['sha256sum', packaged]))['output'].split()[0]
                    if self.packaged[packaged] != digest:
                        raise RuntimeError('Install a fixture APK containing the exact build of ' + name)
                    self.command(shlex.join(['cp', packaged, target]))
                    return dict(path=target, sha256=digest, packaged=packaged)
        return self.transfer(target, source, upload=True)

    def command(self, command, check=True):
        value = self.client.call('console.execute', dict(sessionId=self.console, command=command))
        if check and value['exitCode']:
            raise RuntimeError(value['output'])
        return value

    def transfer(self, remote, local, *, upload=False):
        token = secrets.token_hex(32).encode('ascii')
        errors = []
        stopped = threading.Event()
        connection_lock = threading.Lock()
        active = []
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            listener.settimeout(15)

            def serve():
                try:
                    connection, _ = listener.accept()
                    with connection:
                        with connection_lock:
                            if stopped.is_set():
                                return
                            active.append(connection)
                        connection.settimeout(120)
                        with connection.makefile('rwb') as stream:
                            received = bytearray()
                            while len(received) < len(token):
                                chunk = stream.read(len(token) - len(received))
                                if not chunk:
                                    raise IOError('Incomplete fixture transfer authentication')
                                received.extend(chunk)
                            if not secrets.compare_digest(received, token):
                                raise IOError('Unrecognized fixture transfer peer')
                            with Path(local).open('rb' if upload else 'wb') as file:
                                if upload:
                                    shutil.copyfileobj(file, stream)
                                else:
                                    shutil.copyfileobj(stream, file)
                except BaseException as error:
                    errors.append(error)

            def cancel():
                stopped.set()
                with connection_lock:
                    for channel in [listener, *active]:
                        try:
                            channel.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass

            worker = threading.Thread(target=serve, name='fixture-files')
            worker.start()
            nc = '/system/bin/toybox nc -w 120 127.0.0.1 ' + str(listener.getsockname()[1])
            hello = 'printf %s ' + shlex.quote(token.decode())
            command = (hello + ' | ' + nc + ' > ' + shlex.quote(str(remote)) if upload else
                       '( ' + hello + '; cat ' + shlex.quote(str(remote)) + ' ) | ' + nc)
            try:
                self.command('set -o pipefail\n' + command)
            except BaseException:
                cancel()
                worker.join()
                raise
            else:
                # EVENT_WAIT: transfer EOF or its socket deadline; no replay on failure.
                worker.join(125)
                if worker.is_alive():
                    cancel()
                    worker.join()
                    raise TimeoutError('Fixture transfer did not finish')
            if errors:
                raise errors[0]
        with Path(local).open('rb') as file:
            digest = hashlib.file_digest(file, 'sha256').hexdigest()
        actual = self.command(shlex.join(['sha256sum', str(remote)]))['output'].split()[0]
        if digest != actual:
            raise IOError('Fixture transfer checksum mismatch')
        return dict(path=str(remote), sha256=digest)

    def close(self):
        if self.owns_console:
            self.client.call('console.close', dict(sessionId=self.console))
