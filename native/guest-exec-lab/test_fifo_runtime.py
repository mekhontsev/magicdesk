#!/usr/bin/env python3
"""Independent guest launches share FIFO streams, not only persisted names."""
import argparse
from pathlib import Path
import shlex
from test_oci_services import Suite, Server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--store', required=True)
    args = parser.parse_args()
    suite = Suite(args.build, args.build)
    root = '/tmp/fifo-' + suite.report['id']

    def run(script):
        return [suite.runner, '--store', args.store, '--user', '0:0', '--', '/bin/sh', '-ec', script]

    try:
        suite.command(run('mkdir -m 700 ' + shlex.quote(root)))
        for round in range(3):
            token = 'round-' + str(round)
            server = Server(suite, run('cd ' + shlex.quote(root) + '\n'
                'if [ ! -p request ]; then mkfifo request response; fi\n'
                'printf "FIFO ready\\n"\n'
                'IFS= read -r value <request\n'
                'test "$value" = ' + shlex.quote(token) + '\n'
                'printf "%s\\n" "$value" >response\n'))
            try:
                server.ready('FIFO ready')
                suite.command(run('cd ' + shlex.quote(root) + '\n'
                    'printf "%s\\n" ' + shlex.quote(token) + ' >request\n'
                    'IFS= read -r value <response\n'
                    'test "$value" = ' + shlex.quote(token)))
                server.completed()
                suite.report['cases'].append({'round': round, 'passed': True})
            finally:
                server.close()
        suite.command(run('rm ' + shlex.quote(root) + '/request ' + shlex.quote(root) + '/response; rmdir ' + shlex.quote(root)))
        suite.report['passed'] = True
    finally:
        suite.finish()


if __name__ == '__main__':
    main()
