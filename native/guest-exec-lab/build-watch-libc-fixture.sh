#!/usr/bin/env sh
set -eu
if [ "$#" -ne 3 ]; then
    printf 'Usage: %s glibc|musl SYSROOT OUTPUT\n' "$0" >&2
    exit 2
fi
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
sysroot=$(CDPATH= cd -- "$2" && pwd)
mkdir -p "$3/rootfs/bin" "$3/rootfs/tmp" "$3/rootfs/lib"
out=$(CDPATH= cd -- "$3" && pwd)
case "$1" in
    glibc)
        target=aarch64-linux-gnu
        headers=$sysroot/usr/include/aarch64-linux-gnu
        crt=$sysroot/usr/lib/aarch64-linux-gnu
        lib=$sysroot/lib/aarch64-linux-gnu
        libc=libc.so.6
        interpreter=/lib/ld-linux-aarch64.so.1
        mkdir -p "$out/rootfs/lib/aarch64-linux-gnu"
        cp -L "$lib/libc.so.6" "$out/rootfs/lib/aarch64-linux-gnu/libc.so.6"
        cp -L "$sysroot/lib/ld-linux-aarch64.so.1" "$out/rootfs/lib/ld-linux-aarch64.so.1" ;;
    musl)
        target=aarch64-linux-musl
        headers=$sysroot/usr/include
        crt=$sysroot/usr/lib
        lib=$sysroot/lib
        libc=ld-musl-aarch64.so.1
        interpreter=/lib/ld-musl-aarch64.so.1
        cp -L "$lib/ld-musl-aarch64.so.1" "$out/rootfs/lib/ld-musl-aarch64.so.1" ;;
    *) exit 2 ;;
esac
${CC:-clang} --target="$target" --sysroot="$sysroot" -isystem "$headers" \
    -fuse-ld=lld -std=c17 -O2 -g -Wall -Wextra -Werror -fPIC -nostdlib -pie \
    "$crt/Scrt1.o" "$crt/crti.o" "$src/test_watch_guest.c" "$crt/crtn.o" \
    -L"$lib" -l:"$libc" -Wl,--dynamic-linker="$interpreter" -o "$out/rootfs/bin/watch"
tar -czf "$out/rootfs.tar.gz" -C "$out/rootfs" .
