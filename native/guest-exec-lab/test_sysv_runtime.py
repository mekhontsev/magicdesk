#!/usr/bin/env python3
"""SysV atomicity, ownership, lifecycle and independent-launch IPC contracts."""
import argparse
from pathlib import Path
import uuid
from test_oci_services import Suite, Server, write_script

CASES = ('atomic', 'sem-wake', 'sem-signal', 'sem-remove', 'timed', 'undo-fork-exec',
         'undo-thread', 'undo-kill', 'undo-clear', 'zero-wait', 'messages', 'message-wait',
         'permissions', 'protected', 'group-signal', 'cross-launch', 'owner-death')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--instance', required=True)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--case', choices=CASES, action='append')
    parser.add_argument('--diagnostics', action='store_true')
    args = parser.parse_args()
    suite = Suite(args.output, args.build)
    try:
        fixture = '/tmp/md-sysv-contract'
        write_script(suite, args.instance, fixture+'.c', Path(__file__).with_name('test_sysv_guest.c').read_text())
        suite.command(suite.run(args.instance, '--', 'gcc', '-O2', '-Wall', '-Wextra', '-Werror', '-pthread',
                                fixture+'.c', '-o', fixture))
        def command(*argv):
            if args.diagnostics:
                return [suite.runner, '--diagnostics', '--store', args.instance, '--user', '2000:2000', '--', *argv]
            return suite.run(args.instance, '--user', '2000:2000', '--', *argv)
        for name in args.case or CASES:
            case = {'name': name, 'passed': False}; suite.report['cases'].append(case)
            try:
                if name == 'cross-launch':
                    key = str(int(uuid.uuid4().hex[:7], 16))
                    server = Server(suite, command(fixture, 'serve', key))
                    try:
                        server.ready('SYSV-READY', timeout=10)
                        suite.command(command(fixture, 'wake', key), timeout=25)
                        server.completed()
                    finally:
                        server.close()
                elif name == 'owner-death':
                    key = str(int(uuid.uuid4().hex[:7], 16))
                    owner = Server(suite, command(fixture, 'owner', key))
                    try:
                        owner.ready('SYSV-OWNER ', timeout=10)
                        supervisor = int(next(line.split()[1] for line in owner.log if line.startswith('SYSV-OWNER ')))
                        output = suite.command(command(fixture, 'peer', key, str(supervisor)), timeout=25)
                        assert 'PASS peer' in output
                        owner.completed(expected=125)
                    finally:
                        owner.close()
                else:
                    output = suite.command(command(fixture, name), timeout=25)
                    assert 'PASS '+name in output
                case['passed'] = True
            except Exception as error:
                case['error'] = str(error)
            print(name, case, flush=True)
        suite.report['passed'] = all(c['passed'] for c in suite.report['cases'])
    finally:
        suite.finish()
    return 0 if suite.report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
