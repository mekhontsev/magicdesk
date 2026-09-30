#!/usr/bin/env sh
set -eu
test "$#" = 1
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
cc=${CC:-clang}
cmake -S "$src/../../guest-runtime" -B "$1" -G Ninja \
    -DCMAKE_C_COMPILER="$cc" -DCMAKE_ASM_COMPILER="$cc" \
    -DCMAKE_C_FLAGS=--target=aarch64-linux-android34 \
    -DCMAKE_ASM_FLAGS=--target=aarch64-linux-android34 \
    -DCMAKE_EXE_LINKER_FLAGS=-fno-termux-rpath
cmake --build "$1" --parallel 2
