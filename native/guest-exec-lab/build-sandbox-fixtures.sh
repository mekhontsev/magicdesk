#!/usr/bin/env sh
set -eu
if [ "$#" -ne 1 ]; then
    printf 'Usage: %s PREPARED_BUILD\n' "$0" >&2
    exit 2
fi
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
runtime="$src/../guest-runtime/src"
work=$(CDPATH= cd -- "$1" && pwd)
sysroot="$work/sysroot"
cc=${CC:-clang}
"$cc" --target=aarch64-linux-android34 -fno-termux-rpath -static -DMD_NO_START -DMD_USE_LIBC \
    -std=c17 -O2 -Wall -Wextra -Werror "$src/test_sandbox.c" "$src/test_policy_gate.S" \
    "$runtime/fs_client.c" "$runtime/fs_wire.c" "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    -o "$work/bundle/md-sandbox-native-test"
"$cc" --target=aarch64-linux-gnu --sysroot="$sysroot" -isystem "$sysroot/usr/include/aarch64-linux-gnu" \
    -fuse-ld=lld -std=c17 -O2 -Wall -Wextra -Werror -fPIC -nostdlib -pie -DMD_GUEST_PROBE -DMD_NO_START \
    "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/test_sandbox.c" "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" -L"$sysroot/lib/aarch64-linux-gnu" -l:libc.so.6 \
    -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$work/md-sandbox-guest-fixture"
