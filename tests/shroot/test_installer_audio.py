#!/usr/bin/env python3
"""Verify installer-owned desktop audio, mixer control and silent playback."""
import _bootstrap
import argparse
from magicdesk_executor import load_transport
import json
from pathlib import Path
import time
import tomllib


CHECK = r'''
set -eu
test -n "$MAGICDESK_AUDIO_PULSE"
exec timeout 45 dbus-run-session -- magicdesk-audio-session bash -c '
set -eu
test "$MAGICDESK_AUDIO_SESSION" = active
test "$PULSE_SERVER" != "unix:$MAGICDESK_AUDIO_PULSE"
work=$(mktemp -d)
mkfifo "$work/events"
exec 3<>"$work/events"
stdbuf -oL pactl subscribe >&3 & monitor=$!
playback=
trap '\''kill "$monitor" ${playback:+"$playback"} 2>/dev/null || :; wait "$monitor" 2>/dev/null || :; [ -z "$playback" ] || wait "$playback" 2>/dev/null || :; rm -rf "$work"'\'' EXIT
await_property() {
    local operation=$1 expected=$2 deadline=$((SECONDS + 5))
    # EVENT_WAIT: Pulse sink-property notification, not a graph-settling delay.
    while ! pactl "$operation" "$sink" | grep -q "$expected"; do
        local remaining=$((deadline - SECONDS))
        if ! { (( remaining > 0 )) && read -r -t "$remaining" event <&3; }; then
            pactl "$operation" "$sink" >&2
            echo "Timed out waiting for $operation: $expected" >&2
            return 1
        fi
    done
}
sink=$(pactl get-default-sink)
test "$sink" = magicdesk.android
paplay --raw --format=s16le --rate=48000 --channels=2 /dev/zero & playback=$!
deadline=$((SECONDS + 10))
# EVENT_WAIT: playback activates the sink; do not test unnegotiated stream controls.
while ! pactl list short sinks | grep -q RUNNING; do
    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) && read -r -t "$remaining" event <&3 || exit 1
done
pactl set-sink-volume "$sink" 65%
await_property get-sink-volume "65%"
pactl get-sink-volume "$sink"
pactl set-sink-mute "$sink" 1
await_property get-sink-mute yes
pactl set-sink-mute "$sink" 0
await_property get-sink-mute no
dd if=/dev/zero of="$work/silence" bs=19200 count=1 2>/dev/null
paplay --raw --format=s16le --rate=48000 --channels=2 "$work/silence"
printf "PASS private guest mixer and silent Android playback\n"
'
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--names', nargs='+', required=True)
    parser.add_argument('--library', help='Same shell-owned library as the installer fixture')
    parser.add_argument('--user', default='mdtester', help='Existing guest account')
    args = parser.parse_args()
    repo = _bootstrap.SHROOT
    transport = load_transport()
    config = tomllib.loads((Path.home() / '.codex/config.toml').read_text())['mcp_servers']['magicdesk']
    client = transport.Client(config['url'], config['http_headers']['Authorization'].removeprefix('Bearer '),
                              timeout=60, request_timeout=60)
    report = {'checks': [], 'passed': False}
    operation = None
    try:
        for name in args.names:
            operation = client.call('guest.start', {**({'library': args.library} if args.library else {}), 'arguments': [
                'exec', name, '--user', args.user, '--env', 'LC_ALL=C', '--', '/bin/sh', '-lc', CHECK]})
            report['checks'].append({'name': name, 'operation': operation})
            deadline = time.monotonic() + 90
            while operation['state'] == 'running':
                assert time.monotonic() < deadline, operation
                # EVENT_WAIT: exact command output/completion revision; expiry fails the fixture.
                operation = client.call('guest.status', {'operationId': operation['operationId'],
                                        'afterRevision': operation['revision'], 'timeoutMillis': 30000})
                report['checks'][-1]['operation'] = operation
            print(name + ':\n' + operation.get('output', ''), flush=True)
            assert operation.get('exitCode') == 0, operation
            assert 'PASS private guest mixer' in operation['output'], operation
        report['passed'] = True
    finally:
        if operation and operation['state'] == 'running':
            client.call('guest.cancel', {'operationId': operation['operationId']})
            # EVENT_WAIT: cancellation must release the owned launch before returning.
            deadline = time.monotonic() + 30
            while operation['state'] == 'running' and time.monotonic() < deadline:
                operation = client.call('guest.status', {'operationId': operation['operationId'],
                                        'afterRevision': operation['revision'], 'timeoutMillis': 10000})
            report['checks'][-1]['operation'] = operation
            assert operation['state'] != 'running', operation
        destination = repo / 'build' / ('installer-audio-' + '-'.join(args.names) + '.json')
        destination.write_text(json.dumps(report, indent=2) + '\n')
        print(destination, flush=True)


if __name__ == '__main__':
    main()
