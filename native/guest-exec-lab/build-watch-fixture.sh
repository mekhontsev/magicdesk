#!/usr/bin/env sh
set -eu
if [ "$#" -ne 1 ]; then
    printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
    exit 2
fi
src=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
runtime="$src/../guest-runtime/src"
mkdir -p "$1"
"${CC:-clang}" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -std=c17 -O2 -Wall -Wextra -Werror \
    "$src/test_watch_queue.c" "$runtime/watch_queue.c" -o "$1/md-watch-queue-test"
"${CC:-clang}" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -DMD_NO_START -DMD_USE_LIBC -std=c17 -O2 -Wall -Wextra -Werror \
    "$src/test_watch_read.c" "$src/test_watch_read.S" "$runtime/watch_queue.c" \
    "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    -o "$1/md-watch-read-test"
"${CC:-clang}" --target=aarch64-linux-android34 -fno-termux-rpath -static \
    -DMD_NO_START -DMD_USE_LIBC -std=c17 -O2 -Wall -Wextra -Werror \
    "$src/test_watch_activation.c" \
    "$runtime/event_wait.c" "$runtime/raw.c" "$runtime/memory.c" "$runtime/raw.S" \
    -o "$1/md-watch-activation-test"
