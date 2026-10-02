#!/usr/bin/env python3
"""Opt-in command channel across guest, native Termux and PRoot, without Desktop."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
import tomllib
import uuid


# The identical payload runs under glibc, musl and Android Python. No credentials are printed.
PAYLOAD = r'''
import json, os, subprocess, tempfile
cli = os.environ.get('MD_TEST_CLIENT', 'magicdesk')
def run(*args, status=0, data=None):
    p = subprocess.run([cli, *args], input=data, text=True, capture_output=True, timeout=40)
    assert p.returncode == status, (args, p.returncode, p.stdout, p.stderr)
    return p.stdout
def call(name, args=None):
    value = json.loads(run(name, '--args', json.dumps(args or {})))
    assert value['success'], value
    return value['data']
assert 'get_state' in run('--help')
state = call('get_state')
assert not state['workspaces']
expected = os.environ.get('MD_TEST_ACCESS', 'shell')
assert (state['limits']['active']['maximumAccess'] == 'app_only') == (expected == 'app_only')
assert 'get_state' in run('get_state', '--schema')
run('get_state', '--dry-run')
run('get_state', '--invalid-test-option', status=2)
assert json.loads(run('get_state', '--args', '-', data='{}'))['success']
with tempfile.NamedTemporaryFile(mode='w') as f:
    f.write('{}'); f.flush()
    assert json.loads(run('get_state', '--args', '@'+f.name))['success']
run('get_state', '--args', '{', status=2)
if expected == 'app_only':
    run('console.open', status=1)
else:
    assert run('get_state', '--field', 'data.shell.uid').strip() == '2000'
    session = call('console.open', {'directory':'/data/local/tmp'})['sessionId']
    try:
        output = call('console.execute', {'sessionId':session, 'command':
            'id -u; pm path io.github.mekhontsev.magicdesk; am get-current-user; '
            'test -z "$MAGICDESK_COMMAND_ENDPOINT" && echo NO_BASE_KEY'})
        assert output['exitCode'] == 0 and '2000' in output['output'] and 'NO_BASE_KEY' in output['output'], output
    finally:
        call('console.close', {'sessionId':session})
print('COMMAND_CHECK_OK ' + json.dumps({'uid':os.getuid(),'access':expected}), flush=True)
'''

OWNER_PAYLOAD = r'''
import json, os, signal, subprocess
def call(name, args=None):
    result = subprocess.run(['magicdesk',name,'--args',json.dumps(args or {})],capture_output=True,text=True,timeout=30)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)['data']
session = call('console.open', {'directory':'/data/local/tmp'})['sessionId']
pid = int(call('console.execute', {'sessionId':session,'command':'echo $$'})['output'].strip())
def probe(*unused):
    assert call('console.execute', {'sessionId':session,'command':'echo PEER_ALIVE'})['exitCode'] == 0
    print('PEER_ALIVE',flush=True)
signal.signal(signal.SIGUSR1,probe)
print('OWNER '+json.dumps({'pid':os.getpid(),'consolePid':pid,'sessionId':session}),flush=True)
while True: signal.pause()
'''


def client():
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('transport', repo / 'scripts/mcp-client.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    return module.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '))


def termux_child(args):
    env = dict(os.environ, MD_TEST_ACCESS=args.access)
    commands = [('native', ['magicdesk-connect', '--', 'python', '-c', PAYLOAD])]
    commands.append(('proot', ['magicdesk-connect', '--', 'sh', '-c',
        'exec proot-distro login --isolated --bind "$MAGICDESK_COMMAND_CLIENT:/usr/local/bin/magicdesk" '
        '--env "MAGICDESK_COMMAND_ENDPOINT=$MAGICDESK_COMMAND_ENDPOINT" '
        '--env "MAGICDESK_COMMAND_BUILD=$MAGICDESK_COMMAND_BUILD" '
        '--env MD_TEST_ACCESS=' + shlex.quote(args.access) + ' ' + shlex.quote(args.proot)
        + ' -- python3 -c ' + shlex.quote(PAYLOAD)]))
    report = []
    try:
        for name, command in commands:
            result = subprocess.run(command, env=env, text=True, capture_output=True, timeout=180)
            record = {'name':name,'exitCode':result.returncode,'output':result.stdout,'stderr':result.stderr}
            report.append(record)
            assert result.returncode == 0 and 'COMMAND_CHECK_OK ' in result.stdout, record
        # A wrapper preserves command exit status and revokes its key when the child ends.
        assert subprocess.run(['magicdesk-connect','--','sh','-c','exit 17'], env=env).returncode == 17
        native = os.environ['MAGICDESK_COMMAND_CLIENT']
        owners = []
        try:
            for _ in range(2):
                owner = subprocess.Popen(['magicdesk-connect','--','python','-u','-c',
                    'import os,signal; print(os.environ["MAGICDESK_COMMAND_ENDPOINT"],flush=True); signal.pause()'],
                    env=env, stdout=subprocess.PIPE, text=True)
                owners.append(owner)
            # Secrets stay only in memory, never the result file or terminal transcript.
            channels = [dict(env, MAGICDESK_COMMAND_ENDPOINT=p.stdout.readline().strip()) for p in owners]
            assert channels[0]['MAGICDESK_COMMAND_ENDPOINT'] != channels[1]['MAGICDESK_COMMAND_ENDPOINT']
            for channel in channels:
                assert subprocess.run([native,'get_state'],env=channel,capture_output=True,timeout=20).returncode == 0
            owners[0].terminate(); owners[0].wait(timeout=10)
            # EVENT_WAIT: lease owner EOF is ordered independently of child waitpid.
            deadline = time.monotonic()+10
            while subprocess.run([native,'get_state'],env=channels[0],capture_output=True,timeout=20).returncode == 0:
                assert time.monotonic() < deadline, 'closed owner kept command access'
            assert subprocess.run([native,'get_state'],env=channels[1],capture_output=True,timeout=20).returncode == 0
            report.append({'name':'isolated lease cancellation','exitCode':0,'output':'COMMAND_CHECK_OK'})
        finally:
            for owner in owners:
                if owner.poll() is None: owner.terminate()
                owner.wait(timeout=10)
        inherited = signal.signal(signal.SIGCHLD, signal.SIG_IGN)
        try:
            # The wrapper must reap its child even if its own launcher ignores SIGCHLD.
            probe = subprocess.Popen(['magicdesk-connect','--','sh','-c','echo REAPED; exit 17'],env=env,stdout=subprocess.PIPE,text=True)
            assert probe.stdout.read().strip() == 'REAPED'
            probe.wait(timeout=10)
        finally:
            signal.signal(signal.SIGCHLD,inherited)
        print('TERMUX_CHECK_OK', flush=True)
    finally:
        Path(args.result).write_text(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--names', nargs='+', default=['check-alpine-cold01','check-debian-cold01'])
    parser.add_argument('--proot', default='ubuntu')
    parser.add_argument('--access', default='shell', choices=['shell','app_only'])
    parser.add_argument('--lease')
    parser.add_argument('--termux-child', action='store_true')
    parser.add_argument('--result')
    parser.add_argument('--fixture-activity', action='store_true', help='Root only places the test Activity; the app runs App-only')
    args = parser.parse_args()
    if args.termux_child:
        termux_child(args)
        return
    c = client()
    repo = Path(__file__).resolve().parents[2]
    report = {'checks':[], 'passed':False}
    terminal = console = None
    operations = []
    def check(label, data):
        report['checks'].append({'check':label,'data':data})
        print('PASS '+label, flush=True)
    def execute(arguments):
        operation = c.call('guest.start', {'arguments':arguments})
        operations.append(operation['operationId'])
        deadline = time.monotonic() + 120
        while operation['state'] == 'running' and time.monotonic() < deadline:
            # EVENT_WAIT: exact operation revision or completion, bounded by the test deadline.
            operation = c.call('guest.status', {'operationId':operation['operationId'],
                'afterRevision':operation['revision'],'timeoutMillis':10000})
        assert operation['state'] == 'completed' and operation['exitCode'] == 0, operation
        operations.remove(operation['operationId'])
        return operation['output']
    def wait_output(operation, marker):
        deadline = time.monotonic()+40
        while marker not in operation['output'] and operation['state'] == 'running' and time.monotonic()<deadline:
            operation = c.call('guest.status', {'operationId':operation['operationId'],
                'afterRevision':operation['revision'],'timeoutMillis':10000})
        assert marker in operation['output'], operation
        return operation
    try:
        state = c.call('get_state')
        assert not state['workspaces']
        assert (state['limits']['active']['maximumAccess'] == 'app_only') == (args.access == 'app_only')
        report['app'] = state['app']
        c.call('device.keep_awake', {'durationMillis':1800000, **({'leaseId':args.lease} if args.lease else {})})
        if args.access == 'shell':
            for name in args.names:
                output = execute(['exec',name,'--','/bin/sh','-c',
                    'test -z "$MAGICDESK_COMMAND_ENDPOINT" && test ! -e /run/magicdesk/magicdesk && echo NO_CHANNEL'])
                assert 'NO_CHANNEL' in output, output
                check(name+' opt-out', output)
                output = execute(['exec',name,'--magicdesk','--','python3','-c',PAYLOAD])
                assert 'COMMAND_CHECK_OK ' in output, output
                check(name+' common CLI',output)
            console = c.call('console.open', {'directory':'/data/local/tmp'})['sessionId']
            # The Android CLI and scoped native transport share the same command catalog.
            result = c.call('console.execute', {'sessionId':console,'command':
                'magicdesk get_state --field data.shell.uid'})
            assert result['exitCode'] == 0 and result['output'].strip() == '2000', result
            check('ordinary Android CLI retained',result)
            owners = []
            for _ in range(2):
                operation = c.call('guest.start', {'arguments':['exec',args.names[0],'--magicdesk','--','python3','-u','-c',OWNER_PAYLOAD]})
                operations.append(operation['operationId'])
                operation = wait_output(operation,'OWNER ')
                metadata = json.loads(next(line.removeprefix('OWNER ') for line in operation['output'].splitlines() if line.startswith('OWNER ')))
                owners.append((operation,metadata))
            c.call('guest.cancel', {'operationId':owners[0][0]['operationId']})
            deadline = time.monotonic()+20
            while True:
                # BOUNDED_STATE_WAIT: lease cleanup kills only its lazy console process.
                dead = c.call('console.execute', {'sessionId':console,'command':'test ! -e /proc/'+str(owners[0][1]['consolePid'])})
                if dead['exitCode'] == 0: break
                assert time.monotonic()<deadline, 'owner console survived launch cancellation'
            signal_result = c.call('console.execute', {'sessionId':console,
                'command':'kill -USR1 '+str(owners[1][1]['pid'])})
            assert signal_result['exitCode'] == 0, signal_result
            wait_output(owners[1][0],'PEER_ALIVE')
            c.call('guest.cancel', {'operationId':owners[1][0]['operationId']})
            check('guest cancellation closes its console, retains peer and MCP console', [m for _,m in owners])
        result_file = repo / 'build' / ('command-access-termux-'+args.access+'.json')
        result_file.unlink(missing_ok=True)
        command = shlex.join(['python',str(Path(__file__).resolve()),'--termux-child','--access',args.access,
                             '--proot',args.proot,'--result',str(result_file)])
        if args.fixture_activity:
            assert args.access == 'app_only'
            terminal = 'terminal-'+uuid.uuid4().hex
            prefix = 'io.github.mekhontsev.magicdesk.extra.CONSOLE_'
            intent = ['/system/bin/am','start','-n','io.github.mekhontsev.magicdesk/.CommandConsoleActivity',
                '--es',prefix+'BACKEND','termux','--es',prefix+'TERMINAL_ID',terminal,
                '--es',prefix+'DIRECTORY',str(Path.home()),'--es',prefix+'AUTO_RUN',command]
            launch = subprocess.run(['su','-c',shlex.join(intent)],capture_output=True,text=True,check=True,timeout=30)
            assert 'Error' not in launch.stdout+launch.stderr, (launch.stdout,launch.stderr)
            deadline = time.monotonic()+30
            while terminal not in [t['terminalId'] for t in c.call('terminal.list')['terminals']]:
                assert time.monotonic()<deadline, 'test Activity did not register its terminal'
        else:
            terminal = c.call('terminal.open', {'backend':'termux','command':command})['terminalId']
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            # BOUNDED_STATE_WAIT: the external fixture publishes its bounded result once on exit.
            if result_file.exists(): break
            result = c.call('terminal.read', {'terminalId':terminal,'scope':'transcript'})
            if 'Traceback' in json.dumps(result): raise AssertionError(result)
        assert result_file.exists(), 'Termux fixture did not complete'
        results = json.loads(result_file.read_text())
        assert len(results) == 3 and all(r['exitCode'] == 0 and 'COMMAND_CHECK_OK' in r['output'] for r in results), results
        check('native Termux and PRoot '+args.access, results)
        state = c.call('get_state')
        assert not state['workspaces'] and not state['homeLease'], state
        report['passed'] = True
    finally:
        for operation in operations: c.call('guest.cancel', {'operationId':operation})
        if terminal and terminal in [t['terminalId'] for t in c.call('terminal.list')['terminals']]:
            c.call('terminal.close', {'terminalId':terminal})
        if console: c.call('console.close', {'sessionId':console})
        (repo/'build'/('command-access-'+args.access+'.json')).write_text(json.dumps(report,indent=2))


if __name__ == '__main__':
    main()
