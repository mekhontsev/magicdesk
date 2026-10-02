#!/usr/bin/env python3
"""Real Git/SSH, CMake/Ninja, npm and pip workflows in disposable Ubuntu."""
import argparse
import json
from pathlib import Path
import shlex
from test_oci_services import Suite, Server, port, write_script


class Workflows:
    def __init__(self, suite, store):
        self.suite, self.store = suite, store
        self.root = '/home/mdcode/workflows-' + suite.report['id']
        suite.command(suite.run(store, '--', '/bin/sh', '-ec',
            'mkdir -m 700 "$1"; chown mdcode:mdcode "$1"', 'fixture', self.root))

    def command(self, script, timeout=180):
        return self.suite.command([self.suite.runner, '--store', self.store, '--user', 'mdcode',
            '--home', '/home/mdcode', '--cwd', self.root, '--', '/bin/sh', '-ec', script], timeout=timeout)

    def write(self, name, text):
        self.command('mkdir -p ' + shlex.quote(str(Path(name).parent)))
        write_script(self.suite, self.store, self.root + '/' + name, text)
        self.suite.command(self.suite.run(self.store, '--', '/bin/chown', 'mdcode:mdcode', self.root + '/' + name))

    def git_ssh(self):
        number = port()
        self.command('''
mkdir -m 700 ssh
ssh-keygen -q -t ed25519 -N '' -f ssh/host
ssh-keygen -q -t ed25519 -N '' -f ssh/client
cp ssh/client.pub ssh/authorized_keys
chmod 600 ssh/authorized_keys
git init --bare --initial-branch=main origin.git
git clone origin.git seed
cd seed
git config user.name Fixture
git config user.email fixture@example.invalid
printf 'first\n' > value
ln -s value alias
git add .
git commit -m initial
git push origin main
git gc
git fsck --full
''')
        self.write('ssh/config', f'''Port {number}
ListenAddress 127.0.0.1
HostKey {self.root}/ssh/host
PidFile {self.root}/ssh/pid
AuthorizedKeysFile {self.root}/ssh/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
AllowUsers mdcode
AllowTcpForwarding no
X11Forwarding no
PrintMotd no
LogLevel VERBOSE
''')
        self.command(f'''awk '{{print "[127.0.0.1]:{number} " $1 " " $2}}' ssh/host.pub > ssh/known_hosts''')
        server = Server(self.suite, [self.suite.runner, '--store', self.store, '--user', 'mdcode',
            '--home', '/home/mdcode', '--', '/usr/sbin/sshd', '-D', '-e', '-f', self.root + '/ssh/config'])
        try:
            server.ready('Server listening on 127.0.0.1')
            ssh = shlex.join(['ssh', '-i', self.root + '/ssh/client', '-p', str(number),
                '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'UserKnownHostsFile=' + self.root + '/ssh/known_hosts'])
            self.command('export GIT_SSH_COMMAND=' + shlex.quote(ssh) + '\n' + f'''
git clone mdcode@127.0.0.1:{self.root}/origin.git over-ssh
cd over-ssh
test "$(cat alias)" = first
git config user.name Fixture
git config user.email fixture@example.invalid
printf 'second\n' > value
git add value
git commit -m second
git push origin main
git fsck --full
cd ..
test "$(git --git-dir=origin.git show main:value)" = second
kill "$(cat ssh/pid)"
''')
            server.completed()
        finally:
            server.close()

    def cmake(self):
        self.write('cmake/CMakeLists.txt', '''cmake_minimum_required(VERSION 3.20)
project(GuestFixture C)
enable_testing()
add_library(answer SHARED answer.c)
add_executable(check main.c)
target_link_libraries(check answer)
add_test(NAME answer COMMAND check 42)
install(TARGETS answer check)
''')
        self.write('cmake/value.h', '#define ANSWER 42\n')
        self.write('cmake/answer.c', '#include "value.h"\nint answer(void) { return ANSWER; }\n')
        self.write('cmake/main.c', '#include <stdlib.h>\nextern int answer(void);\n'
                   'int main(int n,char **v) { return n != 2 || answer() != atoi(v[1]); }\n')
        self.command('''
cmake -S cmake -B cmake-build -G Ninja -DCMAKE_BUILD_TYPE=Debug -DCMAKE_INSTALL_PREFIX="$PWD/installed"
cmake --build cmake-build --parallel 2
ctest --test-dir cmake-build --output-on-failure
cmake --install cmake-build
LD_LIBRARY_PATH="$PWD/installed/lib" installed/bin/check 42
printf '#define ANSWER 43\n' > cmake/value.h
cmake --build cmake-build --parallel 2
cmake-build/check 43
test "$(ninja -C cmake-build -n | tail -n 1)" = 'ninja: no work to do.'
''')

    def npm(self):
        self.write('node/package.json', json.dumps({'name': 'md-guest-fixture', 'version': '1.0.0', 'private': True,
            'scripts': {'test': 'node test.js', 'postinstall': 'node -e "require(\'fs\').writeFileSync(\'installed\',\'ok\')"'},
            'dependencies': {'semver': '7.7.2'}}))
        self.write('node/test.js', '''const assert=require('node:assert/strict');
const fs=require('node:fs'), http=require('node:http');
const {spawnSync}=require('node:child_process');
const {Worker}=require('node:worker_threads');
const {once}=require('node:events');
(async()=>{
  // EVENT_WAIT: worker, watch and network events; watchdog fails a stuck test.
  const deadline=setTimeout(()=>{throw Error('workflow timed out');},15000);
  assert(require('semver').satisfies('1.2.3','^1.0.0'));
  assert.equal(fs.readFileSync('installed','utf8'),'ok');
  assert.equal(spawnSync('/bin/sh',['-c','printf child'],{encoding:'utf8'}).stdout,'child');
  const worker=new Worker('require("node:worker_threads").parentPort.postMessage(42)',{eval:true});
  const exited=once(worker,'exit');
  assert.equal((await once(worker,'message'))[0],42);
  assert.equal((await exited)[0],0);
  fs.mkdirSync('watched',{recursive:true});
  const watch=fs.watch('watched'); const changed=once(watch,'change');
  fs.writeFileSync('watched/value','42');
  assert.equal((await changed)[1],'value'); watch.close();
  const server=http.createServer((request,response)=>response.end('guest-http'));
  server.listen(0,'127.0.0.1'); await once(server,'listening');
  const body=await new Promise((resolve,reject)=>{
    http.get('http://127.0.0.1:'+server.address().port,r=>{
      let data='';r.on('data',b=>data+=b);r.on('end',()=>resolve(data));
    }).on('error',reject);
  });
  assert.equal(body,'guest-http'); server.close(); await once(server,'close');
  clearTimeout(deadline); console.log('PASS npm dependencies, workers, child, watch and HTTP');
})().catch(e=>{console.error(e);process.exit(1);});
''')
        self.command('cd node\nnpm install --no-audit --no-fund\nnpm ci --no-audit --no-fund\n'
                     'test -L node_modules/.bin/semver\nnpm test\n', timeout=240)

    def pip(self):
        self.write('python/pyproject.toml', '[build-system]\nrequires=["setuptools==80.9.0", "wheel==0.45.1"]\n'
                   'build-backend="setuptools.build_meta"\n')
        self.write('python/setup.py', 'from setuptools import setup, Extension\n'
                   'setup(name="mdguestfixture", version="1.0", ext_modules=[Extension("mdguest", ["module.c"])])\n')
        self.write('python/module.c', '''#include <Python.h>
static PyObject *answer(PyObject *self,PyObject *args) {(void)self;(void)args;return PyLong_FromLong(42);}
static PyMethodDef methods[]={{"answer",answer,METH_NOARGS,NULL},{NULL,NULL,0,NULL}};
static struct PyModuleDef module={PyModuleDef_HEAD_INIT,"mdguest",NULL,-1,methods};
PyMODINIT_FUNC PyInit_mdguest(void) {return PyModule_Create(&module);}
''')
        self.write('python/check.py', '''import mdguest, requests, sqlite3, subprocess, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread
assert sys.prefix != sys.base_prefix and mdguest.answer() == 42
assert subprocess.check_output([sys.executable,'-c','print(42)']).strip() == b'42'
with sqlite3.connect('fixture.db') as db:
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('CREATE TABLE value (n INTEGER)')
    db.execute('INSERT INTO value VALUES (42)')
    assert db.execute('SELECT n FROM value').fetchone() == (42,)
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200);self.end_headers();self.wfile.write(b'guest-http')
    def log_message(self,*args): pass
server=HTTPServer(('127.0.0.1',0),Handler)
thread=Thread(target=server.handle_request);thread.start()
try:
    assert requests.get('http://127.0.0.1:'+str(server.server_port),timeout=10).text == 'guest-http'
finally:
    # EVENT_WAIT: handled request returns; deadline fails a stuck server.
    thread.join(15);server.server_close();assert not thread.is_alive()
print('PASS venv, PEP517 C-extension wheel, subprocess, SQLite and requests')
''')
        self.command('''
python3 -m venv venv
venv/bin/pip install requests==2.32.5
venv/bin/pip wheel --no-deps --wheel-dir wheels ./python
venv/bin/pip install wheels/mdguestfixture-*.whl
venv/bin/pip check
venv/bin/python python/check.py
venv/bin/pip uninstall -y mdguestfixture
! venv/bin/python -c 'import mdguest'
''', timeout=240)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--store', required=True)
    parser.add_argument('--case', action='append', choices=['git_ssh', 'cmake', 'npm', 'pip'])
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    try:
        fixture = Workflows(suite, args.store)
        suite.report.update(instance=args.store, fixture=fixture.root)
        for name in args.case or ['git_ssh', 'cmake', 'npm', 'pip']:
            result = {'name': name, 'passed': False}
            suite.report['cases'].append(result)
            try:
                getattr(fixture, name)()
                result['passed'] = True
                print('PASS', name, flush=True)
            except Exception as error:
                result['failure'] = repr(error)
                print('FAIL', name, error, flush=True)
        suite.report['passed'] = all(c['passed'] for c in suite.report['cases'])
    finally:
        suite.finish()
    assert suite.report['passed'], 'Developer workflows contain failures'


if __name__ == '__main__':
    main()
