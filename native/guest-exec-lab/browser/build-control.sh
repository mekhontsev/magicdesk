#!/usr/bin/env sh
set -eu
test "$#" = 2
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
sysroot=$(CDPATH= cd -- "$1" && pwd)
mkdir -p "$2"
${CC:-clang} --target=aarch64-linux-gnu --sysroot="$sysroot" -isystem "$sysroot/usr/include/aarch64-linux-gnu" \
    -fuse-ld=lld -std=c17 -O2 -Wall -Wextra -Werror -fPIC -nostdlib -pie \
    "$sysroot/usr/lib/aarch64-linux-gnu/Scrt1.o" "$sysroot/usr/lib/aarch64-linux-gnu/crti.o" \
    "$src/control.c" "$sysroot/usr/lib/aarch64-linux-gnu/crtn.o" \
    -L"$sysroot/lib/aarch64-linux-gnu" -l:libc.so.6 \
    -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 -o "$2/md-browser-control"
