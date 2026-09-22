#!/usr/bin/env sh
set -eu

if [ "$#" -ne 0 ]; then
    printf 'Usage: %s (optional CC=host-compiler)\n' "$0" >&2
    exit 2
fi

if [ "$(uname -s)" != Linux ]; then
    printf 'Native fixtures require a Linux host (including Termux).\n' >&2
    exit 2
fi

compiler=${CC:-}
if [ -z "$compiler" ]; then
    if command -v clang >/dev/null 2>&1; then
        compiler=clang
    else
        compiler=cc
    fi
fi
if ! command -v "$compiler" >/dev/null 2>&1; then
    printf 'Host C compiler not found: %s\n' "$compiler" >&2
    exit 2
fi
if ! command -v timeout >/dev/null 2>&1; then
    printf 'Native fixtures require the host coreutils timeout command.\n' >&2
    exit 2
fi

project_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
sh "$project_dir/vendor/magicdesk-x11/scripts/verify-native.sh"
temp_dir=$(CDPATH= cd -- "${TMPDIR:-/tmp}" && pwd)
work=$(mktemp -d "$temp_dir/magicdesk-native.XXXXXX")
trap 'rm -rf -- "$work"' 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

for fixture in magicdesk_hosted_keycodes_test magicdesk_guest_files_test magicdesk_process_signal_test magicdesk_pty_working_directory_test magicdesk_pty_lifecycle_test magicdesk_pty_peer_output_test magicdesk_pipe_shell_test magicdesk_virtual_mouse_test magicdesk_virtual_mouse_setup_test; do
    printf 'Compile: %s (%s)\n' "$fixture" "$compiler"
    "$compiler" -std=c17 -D_GNU_SOURCE -O2 -Wall -Wextra -UNDEBUG \
        "$project_dir/native/tests/$fixture.c" -o "$work/$fixture"
done

"$compiler" -std=c17 -O2 -Wall -Wextra -UNDEBUG \
    "$project_dir/vendor/magicdesk-x11/examples/window-icon-test.c" -o "$work/x11_window_icon_test"
"$compiler" -std=c17 -O2 -Wall -Wextra -UNDEBUG \
    "$project_dir/vendor/magicdesk-x11/examples/density-settings-test.c" -o "$work/x11_density_settings_test"

# Fixtures use only their own PTYs/processes; keep their files under this owner.
TMPDIR=$work
export TMPDIR
cd -- "$work"
timeout --kill-after=2s 15s ./magicdesk_pty_working_directory_test
timeout --kill-after=2s 15s ./magicdesk_pty_peer_output_test
timeout --kill-after=2s 15s ./magicdesk_pipe_shell_test
timeout --kill-after=2s 15s ./magicdesk_process_signal_test
for mode in pressure fragmented metadata hup signal oversized jobs jobs-signal; do
    timeout --kill-after=2s 15s ./magicdesk_pty_lifecycle_test "$mode"
done
timeout --kill-after=2s 15s ./magicdesk_virtual_mouse_test
timeout --kill-after=2s 15s ./magicdesk_virtual_mouse_setup_test
timeout --kill-after=2s 15s ./magicdesk_guest_files_test
timeout --kill-after=2s 15s ./magicdesk_hosted_keycodes_test
timeout --kill-after=2s 15s ./x11_window_icon_test
timeout --kill-after=2s 15s ./x11_density_settings_test
printf 'Native host fixtures verified (18 runs).\n'
