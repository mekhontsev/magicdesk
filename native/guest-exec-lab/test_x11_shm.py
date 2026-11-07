#!/usr/bin/env python3
"""MIT-SHM through the production routed admission, without Desktop or a host window."""
import argparse
from pathlib import Path
import shlex
import subprocess
import tempfile
from test_oci_services import Suite
from graphical_recipe import compile_recipe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('store')
    parser.add_argument('--installed', action='store_true', help='Use the installed APK runtime and shell launcher')
    args = parser.parse_args()
    suite = Suite(args.build, None if args.installed else args.build)
    client = suite.client
    session = None
    target_created = False
    target = '/tmp/md-mitshm-' + suite.report['id']
    prefix = [suite.runner, '--store', args.store, '--user', '0:0']
    log, receipt = suite.base + '/test.log', suite.base + '/exit'
    try:
        before = client.call('get_state')
        suite.command(prefix + ['--', '/bin/mkdir', target])
        target_created = True
        suite.command(suite.image_command('export-tree', args.store, '/usr/share/X11/xkb', suite.base + '/xkb'))
        suite.upload(Path(__file__).with_name('mitshm_guest.py'), suite.base + '/test.py')
        suite.upload(args.build / 'md-await-exit', suite.base + '/await')
        with tempfile.TemporaryDirectory() as directory:
            wrapper = Path(directory) / 'shroot'
            wrapper.write_text('#!/system/bin/sh\nexec ' + shlex.join([suite.runner,
                '--deadline-seconds', '90', '--bind-ro', suite.base, target]) + ' "$@"\n')
            suite.upload(wrapper, suite.base + '/shroot')
        suite.chmod('700', suite.base + '/shroot', suite.base + '/await')
        classes = args.build / 'recipe-classes'
        suite.report['connectionSourceHashes'] = compile_recipe(classes)
        session = client.call('graphics.start', dict(protocol='x11', backend='shell', connection='routed',
            name='MIT-SHM contract', keyboardDirectory=suite.base + '/xkb'))['sessionId']
        suite.report['sessionId'] = session
        ready = client.call('wait_for_state', dict(condition='graphics_ready', sessionId=session, timeoutMillis=30000))
        assert ready['matched'] and ready['session']['executorUid'] == 2000
        assert ready['session']['serverUid'] not in (0, 2000)
        for mode in ('resources', 'plain'):
            # The plain case exercises ordinary X11 admission without the preface.
            # Its modern FD operations do not use the virtual SysV broker.
            program = 'exec unshare -i python3 ' + shlex.quote(target + '/test.py')
            if mode == 'plain':
                program += ' --fd-only'
            recipe = subprocess.check_output(['java', '-cp', str(classes),
                'io.github.mekhontsev.magicdesk.GraphicalRecipe', 'routed', 'x11', args.store,
                '/', program, '0:0'], text=True, timeout=20)
            if mode == 'plain':
                recipe = recipe.replace('--socket-resource-', '--socket-')
            suite.command(['rm', '-f', receipt])
            launch = '{ env PATH=' + shlex.quote(suite.base + ':/system/bin') + ' /system/bin/sh -c ' + shlex.quote(recipe)
            launch += '; result=$?; printf "%s\\n" "$result" > ' + shlex.quote(receipt) + '; } >> ' + shlex.quote(log) + ' 2>&1'
            client.call('graphics.execute', dict(sessionId=session, command=launch))
            suite.command([suite.base + '/await', receipt], timeout=110)
            output = suite.command(['cat', log])
            assert suite.command(['cat', receipt], quiet=True).strip() == '0', output
            assert 'PASS MIT-SHM:' in output
            suite.report['cases'].append(dict(name=mode, passed=True))
        after = client.call('get_state')
        assert before['homeLease'] == after['homeLease'] and before['workspaces'] == after['workspaces']
        suite.report['passed'] = True
    finally:
        try:
            suite.report['log'] = suite.command(['/system/bin/sh', '-c', 'test ! -f "$1" || cat "$1"', 'test', log])
            if session:
                client.call('graphics.stop', dict(sessionId=session))
                assert client.call('wait_for_state', dict(condition='graphics_session_absent',
                    sessionId=session, timeoutMillis=30000))['matched']
            if target_created:
                suite.command(prefix + ['--', '/bin/rmdir', target])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
