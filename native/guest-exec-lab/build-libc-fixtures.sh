#!/usr/bin/env sh
set -eu
if [ "$#" -ne 3 ]; then
    printf 'Usage: %s glibc|musl SYSROOT OUTPUT\n' "$0" >&2
    exit 2
fi
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
sysroot=$(CDPATH= cd -- "$2" && pwd)
mkdir -p "$3"
out=$(CDPATH= cd -- "$3" && pwd)
cc=${CC:-clang}
case "$1" in
    glibc)
        target=aarch64-linux-gnu
        headers=$sysroot/usr/include/aarch64-linux-gnu
        crt=$sysroot/usr/lib/aarch64-linux-gnu
        lib=$sysroot/lib/aarch64-linux-gnu
        libc=libc.so.6
        interpreter=/lib/ld-linux-aarch64.so.1 ;;
    musl)
        target=aarch64-linux-musl
        headers=$sysroot/usr/include
        crt=$sysroot/usr/lib
        lib=$sysroot/lib
        libc=ld-musl-aarch64.so.1
        interpreter=/lib/ld-musl-aarch64.so.1 ;;
    *) exit 2 ;;
esac
guest_cc() {
    "$cc" --target="$target" --sysroot="$sysroot" -isystem "$headers" \
        -fuse-ld=lld -std=c17 -O2 -g -Wall -Wextra -Werror -fPIC -nostdlib \
        "$@" -L"$lib" -l:"$libc"
}
for source in guest exec execfd exec_policy ipc process_image; do
    name=md-$source-fixture
    if [ "$source" = guest ]; then name=md-fixture; fi
    guest_cc -pie "$crt/Scrt1.o" "$crt/crti.o" "$src/test_$source.c" \
        "$crt/crtn.o" -Wl,--dynamic-linker="$interpreter" -o "$out/$name"
done
guest_cc -shared "$src/test_plugin.c" -Wl,-z,defs -o "$out/md-fixture.so"
