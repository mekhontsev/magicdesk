#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
PACKAGE=io.github.mekhontsev.magicdesk
ACTIVITY="$PACKAGE/.DebugSelfTestActivity"
RESULT_FILE=files/desktop-self-test.json

if ! command -v "$ADB" >/dev/null 2>&1; then
    printf 'adb is required; set ADB to its executable path\n' >&2
    exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
    printf 'jq is required to read the structured self-test result\n' >&2
    exit 2
fi

"$ADB" shell run-as "$PACKAGE" rm -f "$RESULT_FILE"
"$ADB" shell am start -W --user 0 -n "$ACTIVITY" \
    --es target simulated >/dev/null

attempt=0
output=
while [ "$attempt" -lt 360 ]; do
    output=$("$ADB" shell run-as "$PACKAGE" cat "$RESULT_FILE" \
        2>/dev/null | tr -d '\r' || true)
    if printf '%s\n' "$output" | jq -e '.available == true and .completedAtMillis > 0' >/dev/null 2>&1; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 1
done

printf '%s\n' "$output" | jq -r '.report // .error // "Result unavailable"'
printf '%s\n' "$output" | jq -e '.available == true and .completedAtMillis > 0' >/dev/null || {
    printf 'MagicDesk simulated-display self-test timed out.\n' >&2
    exit 1
}
if ! printf '%s\n' "$output" | jq -e '.outcome == "passed" or .outcome == "warnings"' >/dev/null; then
    printf 'MagicDesk simulated-display self-test did not pass.\n' >&2
    exit 1
fi
