#!/usr/bin/env sh
set -eu
test "$#" = 1
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
mkdir -p "$1"
${CC:-clang} --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror -iquote "$src/../../guest-runtime/src" \
    "$src/../../guest-runtime/src/guest_identity.c" "$src/test_identity.c" -o "$1/md-browser-identity"
