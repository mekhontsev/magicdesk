#!/usr/bin/env python3
"""Stock OCI entrypoints and persistent services through the installed shell runtime.

Run on the Android device hosting the configured local MCP endpoint. Python only
controls fixtures; every Linux process runs through the APK's UID 2000 executor.
Services bind loopback, use private instances and leave no running processes.
"""
import argparse
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import queue
import shlex
import socket
import tarfile
import tempfile
import threading
import time
import tomllib
import uuid

NAMES = ('nginx', 'redis', 'postgres', 'python', 'node', 'caddy', 'mariadb',
         'httpd', 'memcached', 'java', 'php', 'ruby', 'dotnet')


class Server:
    def __init__(self, suite, argv):
        self.suite = suite
        self.client = suite.new_client()
        self.console = self.client.call('console.open', {'directory': suite.base})['sessionId']
        self.lines = queue.Queue()
        self.log = []
        self.result = None
        self.error = None
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(1)
        self.listener.settimeout(20)
        self.command = 'set -o pipefail\n' + shlex.join(['timeout', '180', *argv])
        self.command += ' 2>&1 | /system/bin/toybox nc -w 10 127.0.0.1 '
        self.command += str(self.listener.getsockname()[1])
        self.connection = None
        self.reader = threading.Thread(target=self.read)
        self.process = threading.Thread(target=self.execute)
        self.reader.start()
        self.process.start()

    def execute(self):
        try:
            self.result = self.client.call('console.execute',
                                          {'sessionId': self.console, 'command': self.command})
        except Exception as error:
            self.error = str(error)
        finally:
            self.lines.put(None)

    def read(self):
        try:
            # EVENT_WAIT: log-transport connection and output; timeout fails readiness.
            self.connection, _ = self.listener.accept()
            self.connection.settimeout(200)
            with self.connection, self.connection.makefile('r', errors='replace') as stream:
                for line in stream:
                    self.log.append(line)
                    print(line, end='', flush=True)
                    self.lines.put(line)
        except OSError as error:
            self.log.append('log transport: ' + str(error) + '\n')
        finally:
            self.lines.put(None)

    def ready(self, text, timeout=90):
        deadline = time.monotonic() + timeout
        while True:
            # EVENT_WAIT: service log record; timeout/EOF fails, never implies readiness.
            line = self.lines.get(timeout=max(0, deadline - time.monotonic()))
            if line is None:
                raise AssertionError('server ended before ' + text + ': ' + ''.join(self.log)
                                     + ' result=' + repr(self.result) + ' error=' + repr(self.error))
            if text in line:
                return

    def completed(self):
        # EVENT_WAIT: supervised command completion; timeout is a test failure.
        self.process.join(30)
        assert not self.process.is_alive(), 'server did not exit after shutdown'
        assert self.error is None, self.error
        assert self.result['exitCode'] == 0, self.result

    def close(self):
        self.suite.client.call('console.close', {'sessionId': self.console})
        # EVENT_WAIT: cancellation/EOF drains log and command owners before fixture exit.
        self.process.join(30)
        if self.connection:
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.listener.close()
        self.reader.join(25)
        self.suite.report['servers'].append({'command': self.command, 'log': ''.join(self.log),
                                            'result': self.result, 'error': self.error})
        assert not self.process.is_alive() and not self.reader.is_alive(), 'fixture owner still active'


class Suite:
    def __init__(self, output, build=None):
        repo = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
        self.transport = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.transport)
        self.config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
        self.client = self.new_client()
        state = self.client.call('get_state')
        assert state['shell']['uid'] == 2000, state['shell']
        tag = uuid.uuid4().hex
        self.base = '/data/local/tmp/md-oci-services-' + tag
        self.output = output / ('oci-services-' + tag + '.json')
        self.report = {'id': tag, 'directory': self.base, 'app': state['app'], 'device': state['device'],
                       'uid': 2000, 'images': [], 'commands': [], 'servers': [], 'cases': [], 'passed': False}
        self.console = self.client.call('console.open', {'directory': '/data/local/tmp'})['sessionId']
        self.report['consoleId'] = self.console
        self.command(['mkdir', self.base])
        self.runner = self.command(['/system/bin/sh', '-c', 'command -v magicdesk-guest']).strip()
        self.image = None
        if build:
            hashes = {}
            for target in ('bootstrap', 'supervisor', 'run', 'image'):
                filename = 'libmagicdesk_guest_' + target + '.so'
                with (build / filename).open('rb') as source:
                    hashes[filename] = hashlib.file_digest(source, 'sha256').hexdigest()
                self.transport.upload(self.client, build / filename, self.base + '/' + filename)
                actual = self.command(['sha256sum', self.base + '/' + filename], quiet=True).split()[0]
                assert actual == hashes[filename], (filename, actual, hashes[filename])
                self.command(['chmod', '700', self.base + '/' + filename])
            self.runner = self.base + '/libmagicdesk_guest_run.so'
            self.image = self.base + '/libmagicdesk_guest_image.so'
            self.report['nativeBuild'] = str(build)
            self.report['nativeHashes'] = hashes

    def image_command(self, *args):
        return [self.image, *args] if self.image else [self.runner, 'image', *args]

    def new_client(self):
        return self.transport.Client(self.config['url'],
                                     self.config['http_headers']['Authorization'].removeprefix('Bearer '),
                                     timeout=270, request_timeout=240)

    def command(self, argv, quiet=False, timeout=180):
        text = shlex.join(['timeout', '-k', '15', str(timeout), *map(str, argv)])
        self.client.request_timeout = timeout + 30
        result = self.client.call('console.execute', {'sessionId': self.console, 'command': text},
                                  deadline=time.monotonic() + timeout + 30)
        self.report['commands'].append({'command': text, **result})
        if not quiet:
            print(result['output'], end='', flush=True)
        assert result['exitCode'] == 0, result
        return result['output']

    def prepare(self, name, layout):
        base = self.base + '/' + name
        self.command(['mkdir', '-p', base + '/layout'])
        with tempfile.TemporaryDirectory(prefix='md-oci-services-') as temporary:
            archive = Path(temporary) / 'layout.tar'
            with tarfile.open(archive, 'w') as stream:
                for item in ('oci-layout', 'index.json', 'blobs'):
                    stream.add(layout / item, arcname=item)
            self.transport.upload(self.client, archive, base + '/layout.tar')
        self.command(['tar', '-xf', base + '/layout.tar', '-C', base + '/layout'])
        self.command(self.image_command('import', base + '/layout', base + '/image', '--preserve-ownership'))
        self.command(self.image_command('create', base + '/image', base + '/instance'))
        info = json.loads(self.command(self.image_command('inspect', base + '/instance'), quiet=True))
        self.report['images'].append({'name': name, 'index': json.loads((layout / 'index.json').read_text()),
                                     'inspection': info, 'directory': base})
        return base + '/instance'

    def run(self, store, *options):
        return self.image_command('run', store, *options)

    def finish(self):
        try:
            self.client.request_timeout = 30
            self.client.call('console.close', {'sessionId': self.console})
        except Exception as error:
            self.report['cleanupFailure'] = repr(error)
            raise
        finally:
            self.output.write_text(json.dumps(self.report, indent=2) + '\n')
            print('Report:', self.output, flush=True)


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def nginx(suite, store, controls=False):
    number = port()
    config = f'''user nginx;
worker_processes 1;
error_log {'stderr' if controls else '/dev/stderr'} info;
pid /tmp/md-nginx.pid;
events {{ worker_connections 32; }}
http {{ access_log off; server {{ listen 127.0.0.1:{number}; root /usr/share/nginx/html; }} }}
'''
    suite.command(suite.run(store, '--entrypoint', '/bin/sh', '--', '-ec',
                            'printf %s "$1" > /tmp/md-nginx.conf', 'fixture', config))
    server = Server(suite, suite.run(store, '--', 'nginx', '-c', '/tmp/md-nginx.conf', '-g', 'daemon off;'))
    try:
        server.ready('start worker process')
        client = http.client.HTTPConnection('127.0.0.1', number, timeout=10)
        try:
            client.request('GET', '/')
            response = client.getresponse()
            body = response.read()
            assert response.status == 200 and b'Welcome to nginx!' in body, (response.status, body)
        finally:
            client.close()
        suite.command(suite.run(store, '--entrypoint', 'nginx', '--', '-c', '/tmp/md-nginx.conf', '-s', 'quit'))
        server.completed()
    finally:
        server.close()
    print('PASS nginx workers and HTTP response; logging control=' + str(controls), flush=True)


def redis(suite, store, controls=False):
    number = str(port())
    value = 'persistent-' + uuid.uuid4().hex
    client = suite.run(store, '--entrypoint', 'redis-cli', '--', '-h', '127.0.0.1', '-p', number, '--raw')
    for cycle in range(2):
        user = ['--user', 'redis'] if controls else []
        server = Server(suite, suite.run(store, *user, '--', 'redis-server', '--bind', '127.0.0.1', '--port', number))
        try:
            server.ready('Ready to accept connections')
            assert suite.command(client + ['PING']).strip() == 'PONG'
            if cycle == 0:
                assert suite.command(client + ['SET', 'magicdesk-fixture', value]).strip() == 'OK'
                assert suite.command(client + ['SAVE']).strip() == 'OK'
            assert suite.command(client + ['GET', 'magicdesk-fixture']).strip() == value
            suite.command(client + ['SHUTDOWN', 'SAVE'])
            server.completed()
        finally:
            server.close()
    print('PASS redis network, save and restart; explicit-user control=' + str(controls), flush=True)


def postgres(suite, store, controls=False):
    number = str(port())
    password = uuid.uuid4().hex
    environment = ['--env', 'POSTGRES_PASSWORD=' + password]
    for cycle in range(2):
        server = Server(suite, suite.run(store, *environment, '--', 'postgres', '-c', 'listen_addresses=127.0.0.1',
                                         '-c', 'port=' + number))
        try:
            if cycle == 0:
                server.ready('PostgreSQL init process complete; ready for start up.')
            server.ready('database system is ready to accept connections')
            client = suite.run(store, '--env', 'PGPASSWORD=' + password, '--entrypoint', 'psql', '--',
                               '-h', '127.0.0.1', '-p', number, '-U', 'postgres', '-d', 'postgres',
                               '-v', 'ON_ERROR_STOP=1', '-Atc')
            if cycle == 0:
                suite.command(client + ['CREATE TABLE md_fixture (value text NOT NULL); '
                                         "INSERT INTO md_fixture VALUES ('persistent-value');"])
            assert suite.command(client + ['SELECT value FROM md_fixture;']).strip() == 'persistent-value'
            suite.command(suite.run(store, '--user', 'postgres', '--entrypoint', 'pg_ctl', '--',
                                    'stop', '-m', 'fast', '-w', '-t', '30'))
            server.completed()
        finally:
            server.close()
    print('PASS postgres stock initdb/entrypoint, SQL, shutdown and restart', flush=True)


def write_script(suite, store, path, text):
    suite.command(suite.run(store, '--entrypoint', '/bin/sh', '--', '-ec',
                            'printf %s "$1" > "$2"', 'fixture', text, path))


def get_http(number, expected):
    client = http.client.HTTPConnection('127.0.0.1', number, timeout=15)
    try:
        client.request('GET', '/')
        response = client.getresponse()
        body = response.read()
        assert response.status == 200 and body == expected, (response.status, body)
    finally:
        client.close()


def python_runtime(suite, store, controls=False):
    number, marker = port(), uuid.uuid4().hex
    script = '''import concurrent.futures, http.server, multiprocessing, sqlite3, subprocess, sys
from multiprocessing import shared_memory
def square(n): return n*n
if __name__ == '__main__':
    mode, marker, port = sys.argv[1:]
    db=sqlite3.connect('/tmp/md-fixture.db')
    db.execute('CREATE TABLE IF NOT EXISTS values_fixture(value TEXT)')
    if mode=='write':
        db.execute('DELETE FROM values_fixture')
        db.execute('INSERT INTO values_fixture VALUES (?)',(marker,)); db.commit()
    assert db.execute('SELECT value FROM values_fixture').fetchall()==[(marker,)]
    db.close()
    with concurrent.futures.ProcessPoolExecutor(2, mp_context=multiprocessing.get_context('spawn')) as pool:
        assert sum(pool.map(square,range(20)))==2470
    shm=shared_memory.SharedMemory(create=True,size=4096)
    try:
        shm.buf[:4]=b'test'
        subprocess.run([sys.executable,'-c',
            'from multiprocessing.shared_memory import SharedMemory; import sys; '
            'import inspect; '
            's=SharedMemory(sys.argv[1],**({"track":False} if "track" in inspect.signature(SharedMemory).parameters else {})); '
            'from multiprocessing import resource_tracker; '
            'None if "track" in inspect.signature(SharedMemory).parameters else resource_tracker.unregister(s._name,"shared_memory"); '
            'assert bytes(s.buf[:4])==b"test"; '
            's.buf[:4]=b"done"; s.close()',shm.name],check=True)
        assert bytes(shm.buf[:4])==b'done'
    finally: shm.close(); shm.unlink()
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200); self.end_headers(); self.wfile.write(marker.encode())
    with http.server.HTTPServer(('127.0.0.1',int(port)),Handler) as server:
        print('MD-PYTHON-READY',flush=True); server.handle_request()
'''
    write_script(suite, store, '/tmp/md-fixture.py', script)
    for mode in ('write', 'read'):
        server = Server(suite, suite.run(store, '--', 'python3', '/tmp/md-fixture.py', mode, marker, str(number)))
        try:
            server.ready('MD-PYTHON-READY')
            get_http(number, marker.encode())
            server.completed()
        finally:
            server.close()
    print('PASS Python SQLite persistence, spawn workers, POSIX shared memory and HTTP', flush=True)


def node_runtime(suite, store, controls=False):
    number, marker = port(), uuid.uuid4().hex
    script = r'''const fs=require('fs'), http=require('http'), cp=require('child_process');
const {Worker}=require('worker_threads');
const assert=require('assert');
const [mode,marker,port]=process.argv.slice(2);
(async()=>{
  if(mode==='write') fs.writeFileSync('/tmp/md-node-value',marker);
  assert.strictEqual(fs.readFileSync('/tmp/md-node-value','utf8'),marker);
  assert.strictEqual(cp.execFileSync(process.execPath,['-e','process.stdout.write("child")']).toString(),'child');
  await new Promise((resolve,reject)=>{
    const worker=new Worker('require("worker_threads").parentPort.postMessage(6*7)',{eval:true});
    worker.on('message',n=>assert.strictEqual(n,42)); worker.on('error',reject);
    worker.on('exit',code=>code?reject(new Error('worker exit '+code)):resolve());
  });
  fs.writeFileSync('/tmp/md-node-watch','old');
  await new Promise((resolve,reject)=>{
    const watcher=fs.watch('/tmp/md-node-watch',()=>{watcher.close();resolve();});
    watcher.on('error',reject); fs.writeFileSync('/tmp/md-node-watch','changed');
  });
  const server=http.createServer((req,res)=>{res.end(marker);server.close();});
  server.listen(Number(port),'127.0.0.1',()=>console.log('MD-NODE-READY'));
})().catch(error=>{console.error(error);process.exitCode=1;});
'''
    write_script(suite, store, '/tmp/md-fixture.js', script)
    for mode in ('write', 'read'):
        server = Server(suite, suite.run(store, '--', 'node', '/tmp/md-fixture.js', mode, marker, str(number)))
        try:
            server.ready('MD-NODE-READY')
            get_http(number, marker.encode())
            server.completed()
        finally:
            server.close()
    print('PASS Node workers, subprocess, file persistence, inotify and HTTP', flush=True)


def caddy(suite, store, controls=False):
    number, admin, marker = port(), port(), uuid.uuid4().hex
    config = f'''{{
    admin 127.0.0.1:{admin}
    auto_https off
}}
http://127.0.0.1:{number} {{
    bind 127.0.0.1
    respond "{marker}"
}}
'''
    write_script(suite, store, '/tmp/md-Caddyfile', config)
    server = Server(suite, suite.run(store, '--', 'caddy', 'run', '--config', '/tmp/md-Caddyfile', '--adapter', 'caddyfile'))
    try:
        server.ready('serving initial configuration')
        get_http(number, marker.encode())
        suite.command(suite.run(store, '--', 'caddy', 'stop', '--address', '127.0.0.1:' + str(admin)))
        server.completed()
    finally:
        server.close()
    print('PASS Caddy Go runtime, HTTP and administrative shutdown', flush=True)


def mariadb(suite, store, controls=False):
    number, password, marker = str(port()), uuid.uuid4().hex, uuid.uuid4().hex
    env = ['--env', 'MARIADB_ROOT_PASSWORD=' + password]
    for cycle in range(2):
        server = Server(suite, suite.run(store, *env, '--', 'mariadbd', '--bind-address=127.0.0.1', '--port=' + number))
        try:
            if cycle == 0:
                server.ready('MariaDB init process done. Ready for start up.', timeout=150)
            server.ready('ready for connections.', timeout=150)
            client = suite.run(store, '--env', 'MYSQL_PWD=' + password, '--entrypoint', 'mariadb', '--',
                               '--protocol=TCP', '--skip-ssl', '-h127.0.0.1', '-P' + number, '-uroot', '--batch', '--skip-column-names', '-e')
            if cycle == 0:
                suite.command(client + ['CREATE DATABASE md_fixture; CREATE TABLE md_fixture.t(value TEXT); '
                                         f"INSERT INTO md_fixture.t VALUES ('{marker}');"])
            assert suite.command(client + ['SELECT value FROM md_fixture.t;']).strip() == marker
            suite.command(suite.run(store, '--env', 'MYSQL_PWD=' + password, '--entrypoint', 'mariadb-admin', '--',
                                    '--protocol=TCP', '--skip-ssl', '-h127.0.0.1', '-P' + number, '-uroot', 'shutdown'))
            server.completed()
        finally:
            server.close()
    print('PASS MariaDB stock initialization, SQL, shutdown and persistence', flush=True)


def httpd(suite, store, controls=False):
    number, marker = port(), uuid.uuid4().hex
    config = f'''ServerRoot /usr/local/apache2
Listen 127.0.0.1:{number}
ServerName localhost
PidFile /tmp/md-httpd.pid
LoadModule mpm_event_module modules/mod_mpm_event.so
LoadModule unixd_module modules/mod_unixd.so
LoadModule authz_core_module modules/mod_authz_core.so
User daemon
Group daemon
ErrorLog /proc/self/fd/2
LogLevel info
DocumentRoot /usr/local/apache2/htdocs
<Directory /usr/local/apache2/htdocs>
    Require all granted
</Directory>
'''
    write_script(suite, store, '/tmp/md-httpd.conf', config)
    write_script(suite, store, '/usr/local/apache2/htdocs/fixture.txt', marker)
    server = Server(suite, suite.run(store, '--', 'httpd-foreground', '-f', '/tmp/md-httpd.conf'))
    try:
        server.ready('resuming normal operations')
        client = http.client.HTTPConnection('127.0.0.1', number, timeout=15)
        try:
            for _ in range(3):
                client.request('GET', '/fixture.txt')
                response = client.getresponse()
                assert response.status == 200 and response.read() == marker.encode()
        finally:
            client.close()
        suite.command(suite.run(store, '--', 'httpd', '-f', '/tmp/md-httpd.conf', '-k', 'graceful-stop'))
        server.completed()
    finally:
        server.close()
    print('PASS Apache event workers, repeated HTTP requests and graceful shutdown', flush=True)


def memcached(suite, store, controls=False):
    number = port()
    server = Server(suite, suite.run(store, '--', 'memcached', '-l', '127.0.0.1', '-p', str(number),
                                     '-m', '16', '-c', '64', '-t', '2', '-vv', '-A'))
    try:
        server.ready('server listening')
        with socket.create_connection(('127.0.0.1', number), timeout=10) as client:
            with client.makefile('rb') as stream:
                client.sendall(b'set md-fixture 0 0 2\r\n41\r\n')
                assert stream.readline() == b'STORED\r\n'
                client.sendall(b'incr md-fixture 1\r\n')
                assert stream.readline() == b'42\r\n'
                client.sendall(b'get md-fixture\r\n')
                assert stream.readline() == b'VALUE md-fixture 0 2\r\n'
                assert stream.readline() == b'42\r\n' and stream.readline() == b'END\r\n'
                client.sendall(b'delete md-fixture\r\n')
                assert stream.readline() == b'DELETED\r\n'
                client.sendall(b'shutdown\r\n')
        server.completed()
    finally:
        server.close()
    print('PASS Memcached configured non-root user, TCP storage, atomic increment and shutdown', flush=True)


def java_runtime(suite, store, controls=False):
    number, marker = port(), uuid.uuid4().hex
    script = '''import java.nio.file.*;
import java.nio.channels.*;
import java.net.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
class Fixture {
    static void check(boolean result) { if (!result) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path data=Path.of("/tmp/md-java-data");
        if (args[0].equals("write")) Files.writeString(data,args[1]);
        check(Files.readString(data).equals(args[1]));
        try (FileChannel channel=FileChannel.open(data,StandardOpenOption.READ)) {
            var mapped=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size());
            byte[] bytes=new byte[mapped.remaining()]; mapped.get(bytes);
            check(new String(bytes).equals(args[1]));
        }
        try (var pool=Executors.newFixedThreadPool(2)) {
            check(pool.submit(()->6*7).get()==42);
        }
        Process child=new ProcessBuilder("/bin/sh","-c","printf child").start();
        check(new String(child.getInputStream().readAllBytes()).equals("child"));
        check(child.waitFor()==0);
        try (var watcher=FileSystems.getDefault().newWatchService()) {
            Path.of("/tmp").register(watcher,StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY);
            Files.writeString(Path.of("/tmp/md-java-watch"),args[1]);
            // EVENT_WAIT: inotify-backed watch; expiry is a failure.
            var key=watcher.poll(10,TimeUnit.SECONDS); check(key!=null);
            check(key.pollEvents().stream().anyMatch(e->e.context().toString().equals("md-java-watch")));
        }
        var done=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[2])),0);
        server.createContext("/",exchange->{
            byte[] bytes=args[1].getBytes(); exchange.sendResponseHeaders(200,bytes.length);
            try(var body=exchange.getResponseBody()){body.write(bytes);} done.countDown();
        });
        server.start(); System.out.println("MD-JAVA-READY");
        try { check(done.await(30,TimeUnit.SECONDS)); } finally { server.stop(0); }
    }
}
'''
    write_script(suite, store, '/tmp/Fixture.java', script)
    for mode in ('write', 'read'):
        server = Server(suite, suite.run(store, '--', 'java', '-Xmx128m', '/tmp/Fixture.java',
                                         mode, marker, str(number)))
        try:
            server.ready('MD-JAVA-READY')
            get_http(number, marker.encode())
            server.completed()
        finally:
            server.close()
    print('PASS Java source compiler, workers, mmap persistence, subprocess, inotify and HTTP', flush=True)


def php_runtime(suite, store, controls=False):
    marker = uuid.uuid4().hex
    script = '''<?php
function check($v) { if (!$v) throw new Exception("fixture assertion"); }
$db = new PDO('sqlite:/tmp/md-php.sqlite');
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
check($db->query('PRAGMA journal_mode=WAL')->fetchColumn() === 'wal');
$db->exec('CREATE TABLE IF NOT EXISTS records(value TEXT)');
if ($argv[1] === 'write') {
    $db->beginTransaction();
    $q = $db->prepare('INSERT INTO records VALUES(?)'); $q->execute([$argv[2]]);
    $db->commit();
    $db->beginTransaction(); $db->exec("INSERT INTO records VALUES('rollback')"); $db->rollBack();
}
check($db->query('SELECT value FROM records')->fetchAll(PDO::FETCH_COLUMN) === [$argv[2]]);
$f = fopen('/tmp/md-php.lock', 'a+'); check(flock($f, LOCK_EX));
$child = proc_open(['/usr/local/bin/php', '-r',
    '$f=fopen("/tmp/md-php.lock","a+"); exit(flock($f,LOCK_EX|LOCK_NB)?1:0);'], [], $pipes);
check(proc_close($child) === 0); check(flock($f, LOCK_UN)); fclose($f);
check(hash('sha256', gzdecode(gzencode($argv[2]))) === hash('sha256', $argv[2]));
echo "MD-PHP-PASS\\n";
'''
    write_script(suite, store, '/tmp/fixture.php', script)
    for mode in ('write', 'read'):
        output = suite.command(suite.run(store, '--', 'php', '/tmp/fixture.php', mode, marker))
        assert 'MD-PHP-PASS' in output
    print('PASS PHP SQLite WAL, commit/rollback, restart persistence, subprocess locks and compression', flush=True)


def ruby_runtime(suite, store, controls=False):
    number, marker = port(), uuid.uuid4().hex
    script = '''require 'socket'
require 'json'
require 'digest'
require 'zlib'
require 'open3'
def check(value); raise 'fixture assertion' unless value; end
mode, marker, port = ARGV
path = '/tmp/md-ruby.json'
File.write(path, JSON.generate({'value'=>marker})) if mode == 'write'
check(JSON.parse(File.read(path))['value'] == marker)
check(Zlib::Inflate.inflate(Zlib::Deflate.deflate(marker)) == marker)
check(Thread.new { Digest::SHA256.hexdigest(marker) }.value.size == 64)
text, status = Open3.capture2('/bin/sh', '-c', 'printf child')
check(text == 'child' && status.success?)
reader, writer = IO.pipe
pid = fork { reader.close; writer.write(marker); writer.close; exit! 0 }
writer.close; check(reader.read == marker); reader.close
check(Process.wait2(pid)[1].success?)
server = TCPServer.new('127.0.0.1', Integer(port))
$stdout.sync = true; puts 'MD-RUBY-READY'
client = server.accept
request = client.gets
check(request.start_with?('GET / '))
while (line = client.gets) && line != "\\r\\n"; end
client.write("HTTP/1.1 200 OK\\r\\nContent-Length: #{marker.bytesize}\\r\\nConnection: close\\r\\n\\r\\n#{marker}")
client.close; server.close
'''
    write_script(suite, store, '/tmp/fixture.rb', script)
    for mode in ('write', 'read'):
        server = Server(suite, suite.run(store, '--', 'ruby', '/tmp/fixture.rb', mode, marker, str(number)))
        try:
            server.ready('MD-RUBY-READY')
            get_http(number, marker.encode())
            server.completed()
        finally:
            server.close()
    print('PASS Ruby threads, fork/exec pipes, HTTP, compression and persistent JSON', flush=True)


def dotnet_runtime(suite, store, controls=False):
    number, marker = port(), uuid.uuid4().hex
    project = '''<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup>
<OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework>
<ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable>
</PropertyGroup></Project>'''
    source = '''using System.Diagnostics;
using System.IO.MemoryMappedFiles;
using System.Net;
using System.Net.Sockets;
using System.Text;
static void Check(bool value) { if (!value) throw new Exception("fixture assertion"); }
string path="/tmp/md-dotnet-data", marker=args[1];
if(args[0]=="write") await File.WriteAllTextAsync(path,marker);
Check(await File.ReadAllTextAsync(path)==marker);
using(var map=MemoryMappedFile.CreateFromFile(path,FileMode.Open))
using(var view=map.CreateViewAccessor()) {
    byte[] bytes=new byte[marker.Length]; view.ReadArray(0,bytes,0,bytes.Length);
    Check(Encoding.UTF8.GetString(bytes)==marker);
}
Check(await Task.Run(()=>6*7)==42);
using(var child=Process.Start(new ProcessStartInfo("/bin/sh") {
    ArgumentList={"-c","printf child"},RedirectStandardOutput=true})!) {
    Check(await child.StandardOutput.ReadToEndAsync()=="child");
    await child.WaitForExitAsync(); Check(child.ExitCode==0);
}
var changed=new TaskCompletionSource();
using(var watch=new FileSystemWatcher("/tmp","md-dotnet-watch")) {
    watch.Created+=(_,_)=>changed.TrySetResult(); watch.Changed+=(_,_)=>changed.TrySetResult();
    watch.EnableRaisingEvents=true; await File.WriteAllTextAsync("/tmp/md-dotnet-watch",marker);
    // EVENT_WAIT: filesystem event; timeout fails the fixture.
    await changed.Task.WaitAsync(TimeSpan.FromSeconds(10));
}
var server=new TcpListener(IPAddress.Loopback,int.Parse(args[2]));
server.Start(); Console.WriteLine("MD-DOTNET-READY");
using(var client=await server.AcceptTcpClientAsync()) {
    using var stream=client.GetStream(); using var reader=new StreamReader(stream,leaveOpen:true);
    Check((await reader.ReadLineAsync())!.StartsWith("GET / "));
    while(await reader.ReadLineAsync() is {Length:>0}) {}
    byte[] body=Encoding.UTF8.GetBytes("HTTP/1.1 200 OK\\r\\nContent-Length: "+marker.Length+
        "\\r\\nConnection: close\\r\\n\\r\\n"+marker);
    await stream.WriteAsync(body);
}
server.Stop();
'''
    suite.command(suite.run(store, '--entrypoint', '/bin/sh', '--', '-ec', 'mkdir /tmp/md-dotnet'))
    write_script(suite, store, '/tmp/md-dotnet/Fixture.csproj', project)
    write_script(suite, store, '/tmp/md-dotnet/Program.cs', source)
    write_script(suite, store, '/tmp/md-dotnet/NuGet.Config',
                 '<configuration><packageSources><clear /></packageSources></configuration>')
    options = ('--env', 'DOTNET_CLI_TELEMETRY_OPTOUT=1', '--env', 'DOTNET_NOLOGO=1')
    suite.command(suite.run(store, *options, '--', 'dotnet', 'build', '/tmp/md-dotnet/Fixture.csproj',
                            '--disable-build-servers', '-o', '/tmp/md-dotnet/out'))
    for mode in ('write', 'read'):
        server = Server(suite, suite.run(store, *options, '--', 'dotnet', '/tmp/md-dotnet/out/Fixture.dll',
                                         mode, marker, str(number)))
        try:
            server.ready('MD-DOTNET-READY')
            get_http(number, marker.encode())
            server.completed()
        finally:
            server.close()
    print('PASS .NET compile/JIT, async IO, mmap, subprocess, watch, TCP and restart persistence', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--build', type=Path, help='Explicit native build instead of installed APK helpers')
    parser.add_argument('--controls', action='store_true',
                        help='Explicit controls: nginx stderr directive and redis --user redis, not default-entrypoint passes')
    for name in NAMES:
        group = parser.add_mutually_exclusive_group()
        group.add_argument('--' + name, type=Path, help='Local immutable OCI layout')
        group.add_argument('--' + name + '-instance', help='Explicit disposable prepared instance on device')
    args = parser.parse_args()
    assert any(getattr(args, name) or getattr(args, name + '_instance') for name in NAMES)
    suite = Suite(args.output, args.build)
    suite.report['controls'] = args.controls
    try:
        for name, test in (('nginx', nginx), ('redis', redis), ('postgres', postgres),
                           ('python', python_runtime), ('node', node_runtime), ('caddy', caddy), ('mariadb', mariadb),
                           ('httpd', httpd), ('memcached', memcached), ('java', java_runtime),
                           ('php', php_runtime), ('ruby', ruby_runtime), ('dotnet', dotnet_runtime)):
            layout = getattr(args, name)
            store = getattr(args, name + '_instance')
            if not layout and not store:
                continue
            result = {'name': name, 'passed': False}
            suite.report['cases'].append(result)
            try:
                if layout:
                    store = suite.prepare(name, layout)
                else:
                    info = json.loads(suite.command(suite.image_command('inspect', store), quiet=True))
                    suite.report['images'].append({'name': name, 'instance': store, 'inspection': info})
                test(suite, store, args.controls)
                result['passed'] = True
            except Exception as error:
                result['error'] = repr(error)
                print('FAIL', name, repr(error), flush=True)
        suite.report['passed'] = all(item['passed'] for item in suite.report['cases'])
    finally:
        suite.finish()
    assert suite.report['passed'], suite.report['cases']


if __name__ == '__main__':
    main()
