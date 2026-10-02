#!/usr/bin/env python3
"""Real SysV IPC consumers, without substituting TCP or file-lock backends."""
import argparse
import http.client
from pathlib import Path
import uuid

from test_oci_services import Suite, Server, port, write_script

SYMFONY = r'''<?php
require '/usr/share/php/Symfony/Component/Lock/autoload.php';
use Symfony\Component\Lock\LockFactory;
use Symfony\Component\Lock\Store\SemaphoreStore;
function check($ok) { if (!$ok) throw new RuntimeException('fixture assertion'); }
$factory = new LockFactory(new SemaphoreStore());
$name = 'md-fixture-'.getmypid();
$sockets = stream_socket_pair(STREAM_PF_UNIX, STREAM_SOCK_STREAM, 0);
$pid = pcntl_fork(); check($pid >= 0);
if ($pid === 0) {
    fclose($sockets[0]);
    check(fread($sockets[1], 1) === 'L');
    $lock = $factory->createLock($name);
    check(!$lock->acquire(false));
    fwrite($sockets[1], 'B');
    check($lock->acquire(true));
    $lock->release(); fclose($sockets[1]); exit(0);
}
fclose($sockets[1]);
$lock = $factory->createLock($name); check($lock->acquire());
fwrite($sockets[0], 'L'); check(fread($sockets[0], 1) === 'B');
$lock->release();
check(pcntl_waitpid($pid, $status) === $pid && pcntl_wexitstatus($status) === 0);
fclose($sockets[0]);
echo "PASS symfony\n";
'''

PHP_MESSAGES = r'''<?php
function check($ok) { if (!$ok) throw new RuntimeException('fixture assertion'); }
$q = msg_get_queue(ftok(__FILE__, 'M'), 0600); check($q !== false);
try {
    check(msg_send($q, 9, 'nine', false));
    check(msg_send($q, 2, 'two', false));
    check(msg_receive($q, -9, $type, 32, $text, false, MSG_IPC_NOWAIT));
    check($type === 2 && $text === 'two');
    check(msg_receive($q, 0, $type, 32, $text, false, MSG_IPC_NOWAIT));
    check($type === 9 && $text === 'nine');
    $pid = pcntl_fork(); check($pid >= 0);
    if ($pid === 0) {
        check(msg_receive($q, 4, $type, 32, $text, false));
        check($type === 4 && $text === 'child'); exit(0);
    }
    check(msg_send($q, 4, 'child', false));
    check(pcntl_waitpid($pid, $status) === $pid && pcntl_wexitstatus($status) === 0);
    check(msg_stat_queue($q)['msg_qnum'] === 0);
} finally { check(msg_remove_queue($q)); }
echo "PASS php-messages\n";
'''


def fakeroot(suite, store):
    script = r'''set -eu
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export work
fakeroot-sysv -- /bin/sh -ec '
    touch "$work/file"
    chown 1234:2345 "$work/file"
    test "$(stat -c %u:%g "$work/file")" = 1234:2345
    /bin/sh -ec '\''test "$(stat -c %u:%g "$work/file")" = 1234:2345'\''
'
test "$(stat -c %u:%g "$work/file")" = 2000:2000
echo PASS fakeroot
'''
    output = suite.command(suite.run(store, '--user', '2000:2000', '--', '/bin/sh', '-ec', script), timeout=35)
    assert 'PASS fakeroot' in output


def php(suite, store, name, script):
    path = '/tmp/md-sysv-' + name + '.php'
    write_script(suite, store, path, script)
    output = suite.command(suite.run(store, '--user', '2000:2000', '--', 'php', path), timeout=35)
    assert 'PASS ' + name in output


def apache(suite, store, diagnostics=False):
    number, second, marker = port(), port(), uuid.uuid4().hex
    config = f'''ServerRoot /etc/apache2
DefaultRuntimeDir /tmp
Listen 127.0.0.1:{number}
Listen 127.0.0.1:{second}
ServerName localhost
PidFile /tmp/md-sysv-apache.pid
LoadModule mpm_prefork_module /usr/lib/apache2/modules/mod_mpm_prefork.so
LoadModule authz_core_module /usr/lib/apache2/modules/mod_authz_core.so
User www-data
Group www-data
Mutex sysvsem default
ErrorLog /proc/self/fd/2
LogLevel info
StartServers 2
MinSpareServers 1
MaxSpareServers 2
DocumentRoot /var/www/html
<Directory /var/www/html>
    Require all granted
</Directory>
'''
    write_script(suite, store, '/tmp/md-sysv-apache.conf', config)
    write_script(suite, store, '/var/www/html/sysv.txt', marker)
    program = ['/usr/sbin/apache2', '-DFOREGROUND', '-f', '/tmp/md-sysv-apache.conf']
    command = ([suite.runner, '--diagnostics', '--store', store, '--', *program]
               if diagnostics else suite.run(store, '--', *program))
    server = Server(suite, command)
    try:
        server.ready('resuming normal operations', timeout=20)
        for _ in range(8):
            client = http.client.HTTPConnection('127.0.0.1', number, timeout=10)
            try:
                client.request('GET', '/sysv.txt')
                response = client.getresponse()
                assert response.status == 200 and response.read() == marker.encode()
            finally:
                client.close()
        suite.command(suite.run(store, '--', '/usr/sbin/apache2', '-f', '/tmp/md-sysv-apache.conf', '-k', 'graceful-stop'))
        server.completed()
    finally:
        server.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--instance', required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--diagnostics', action='store_true')
    parser.add_argument('--case', action='append', choices=['fakeroot', 'symfony', 'php-messages', 'apache'])
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    try:
        suite.report['packages'] = suite.command(suite.run(args.instance, '--', 'dpkg-query', '-W',
            'fakeroot', 'php8.3-cli', 'php-symfony-lock', 'apache2-bin'), quiet=True)
        cases = {'fakeroot': lambda: fakeroot(suite, args.instance),
                 'symfony': lambda: php(suite, args.instance, 'symfony', SYMFONY),
                 'php-messages': lambda: php(suite, args.instance, 'php-messages', PHP_MESSAGES),
                 'apache': lambda: apache(suite, args.instance, args.diagnostics)}
        for name in args.case or cases:
            case = {'name': name, 'passed': False}; suite.report['cases'].append(case)
            try:
                cases[name](); case['passed'] = True
            except Exception as error:
                case['error'] = str(error)
            print(name, case, flush=True)
        suite.report['passed'] = all(c['passed'] for c in suite.report['cases'])
    finally:
        suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
