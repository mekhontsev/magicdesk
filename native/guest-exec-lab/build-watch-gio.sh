#!/usr/bin/env sh
set -eu
if [ "$#" -ne 2 ]; then
    printf 'Usage: %s DEBIAN_SYSROOT OUTPUT\n' "$0" >&2
    exit 2
fi
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
root=$(CDPATH= cd -- "$1" && pwd)
lib=$root/usr/lib/aarch64-linux-gnu
${CC:-clang} --target=aarch64-linux-gnu --sysroot="$root" \
    -isystem "$root/usr/include/aarch64-linux-gnu" \
    -isystem "$root/usr/include/glib-2.0" -isystem "$lib/glib-2.0/include" \
    -fuse-ld=lld -std=c17 -O2 -g -Wall -Wextra -Werror -fPIC -nostdlib -pie \
    "$lib/Scrt1.o" "$lib/crti.o" "$src/test_watch_gio.c" "$lib/crtn.o" \
    -L"$lib" -L"$root/lib/aarch64-linux-gnu" -l:libgio-2.0.so.0 -l:libgobject-2.0.so.0 \
    -l:libglib-2.0.so.0 -l:libc.so.6 -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$2"
