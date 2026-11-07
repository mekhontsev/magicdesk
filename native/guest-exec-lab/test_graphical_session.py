#!/usr/bin/env python3
"""Packaged guest helper with real distribution D-Bus; no Desktop or APK reinstall."""
import argparse
from pathlib import Path
import shlex

from test_oci_services import Suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--helper', type=Path, required=True)
    parser.add_argument('--store', action='append', required=True)
    parser.add_argument('--user', default='root', help='Existing guest account for D-Bus authentication')
    parser.add_argument('--output', type=Path, default=Path('build/graphical-session-results'))
    args = parser.parse_args()
    suite = Suite(args.output)
    try:
        suite.upload(args.helper, suite.base + '/guest-helper')
        suite.chmod('755', suite.base + '/guest-helper')
        suite.report['helperSha256'] = suite.transport.digest(args.helper)
        suite.report['guestUser'] = args.user
        probe = '''set -eu
printf '%s' "$XDG_RUNTIME_DIR" > "$1"
test "$(stat -c %a "$XDG_RUNTIME_DIR")" = 700
test "$3" = "two words"
test "$4" = "literal ' \\$; 100%"
test -z "$5"
case "$2" in
    x11-routed)
        test "$XDG_SESSION_TYPE" = x11
        test "$(cat "$XAUTHORITY")" = cookie
        test -z "${MAGICDESK_X11_AUTHORITY+x}" ;;
    x11) test "$XDG_SESSION_TYPE" = x11 ;;
    wayland) test "$XDG_SESSION_TYPE" = wayland ;;
esac
dbus-send --session --type=method_call --print-reply --dest=org.freedesktop.DBus \\
    /org/freedesktop/DBus org.freedesktop.DBus.ListNames >/dev/null
printf 'private data' > "$XDG_RUNTIME_DIR/local"
exit 23
'''
        command = '''set -eu
receipt=$(mktemp /tmp/magicdesk-session-test.XXXXXX)
trap 'rm -f "$receipt"' EXIT
set +e
/mnt/guest-helper --session "$1" -- /bin/sh -c ''' + shlex.quote(probe) + ''' fixture "$receipt" "$@"
status=$?
set -e
test "$status" = 23
runtime=$(cat "$receipt")
test -n "$runtime"
test ! -e "$runtime"
printf 'PASS %s: D-Bus, literal arguments, environment, exit status and cleanup\\n' "$1"
'''
        for store in args.store:
            for protocol in ('x11', 'x11-routed', 'wayland'):
                suite.command(suite.guest(store, '--user', args.user, '--bind-ro', suite.base, '/mnt',
                    '--env', 'MAGICDESK_X11_AUTHORITY=Y29va2ll', '--', '/bin/sh', '-c', command,
                    'fixture', protocol, 'two words', "literal ' $; 100%", ''), timeout=60)
                suite.report['cases'].append({'store': store, 'protocol': protocol, 'passed': True})
        suite.report['passed'] = True
    finally:
        try:
            suite.command(['rm', '-rf', suite.base])
        finally:
            suite.finish()


if __name__ == '__main__':
    main()
