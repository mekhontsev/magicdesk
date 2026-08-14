#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
PACKAGE=io.github.mekhontsev.magicdesk
INSTRUMENTATION="$PACKAGE/.DesktopLifecycleInstrumentation"

if ! command -v "$ADB" >/dev/null 2>&1; then
    printf 'adb is required; set ADB to its executable path\n' >&2
    exit 2
fi

output=$("$ADB" shell am instrument -w --user 0 "$INSTRUMENTATION" \
    | tr -d '\r')
printf '%s\n' "$output"

printf '%s\n' "$output" | grep -q 'INSTRUMENTATION_CODE: -1' || {
    printf 'MagicDesk simulated-display instrumentation failed.\n' >&2
    exit 1
}
