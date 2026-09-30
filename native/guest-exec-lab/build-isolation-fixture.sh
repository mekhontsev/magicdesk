#!/usr/bin/env sh
set -eu
test "$#" = 1
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
mkdir -p "$1"
${CC:-clang} --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror "$src/test_isolation.c" -o "$1/md-isolation-test"
