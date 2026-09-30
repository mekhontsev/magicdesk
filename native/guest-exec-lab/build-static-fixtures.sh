#!/usr/bin/env sh
set -eu
test "$#" = 3
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
sysroot=$(CDPATH= cd -- "$2" && pwd)
mkdir -p "$3"
out=$(CDPATH= cd -- "$3" && pwd)
cc=${CC:-clang}
builtins=
case "$1" in
    glibc)
        target=aarch64-linux-gnu
        headers=$sysroot/usr/include/aarch64-linux-gnu
        lib=$sysroot/usr/lib/aarch64-linux-gnu
        gcc=$sysroot/usr/lib/gcc/aarch64-linux-gnu/14 ;;
    musl)
        target=aarch64-linux-musl
        headers=$sysroot/usr/include
        lib=$sysroot/usr/lib
        gcc=
        builtins=${COMPILER_BUILTINS:-$("$cc" --target="$target" --rtlib=compiler-rt -print-libgcc-file-name)}
        test -f "$builtins" ;;
    *) exit 2 ;;
esac
for kind in exec pie; do
    if [ "$kind" = exec ]; then
        crt=crt1.o
        flags='-static -fno-pie'
        begin=crtbeginT.o
        end=crtend.o
    else
        crt=rcrt1.o
        flags='-static-pie -fPIE'
        begin=crtbeginS.o
        end=crtendS.o
    fi
    for alignment in 65536 2097152; do
    suffix=
    if [ "$alignment" = 2097152 ]; then suffix=-2m; fi
    # This fixture is linked against the distribution's static libc, never Bionic.
    "$cc" --target="$target" --sysroot="$sysroot" -isystem "$headers" \
        -fuse-ld=lld -std=c17 -O2 -g -Wall -Wextra -Werror -nostdlib $flags \
        "$lib/$crt" "$lib/crti.o" ${gcc:+"$gcc/$begin"} "$src/test_static.c" \
        -L"$lib" ${gcc:+-L"$gcc"} -Wl,--start-group -lc ${gcc:+-lgcc -lgcc_eh} ${builtins:+"$builtins"} -Wl,--end-group \
        ${gcc:+"$gcc/$end"} "$lib/crtn.o" -Wl,-z,max-page-size="$alignment" \
        -o "$out/md-static-$kind$suffix"
    done
done
