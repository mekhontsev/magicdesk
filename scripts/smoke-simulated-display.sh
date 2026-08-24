#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
PACKAGE=io.github.mekhontsev.magicdesk
ACTIVITY="$PACKAGE/.DebugSelfTestActivity"
RESULT_FILE=files/desktop-self-test.txt

if ! command -v "$ADB" >/dev/null 2>&1; then
    printf 'adb is required; set ADB to its executable path\n' >&2
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
    if printf '%s\n' "$output" | grep -q '^Outcome: '; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 1
done

printf '%s\n' "$output"
printf '%s\n' "$output" | grep -q '^Outcome: ' || {
    printf 'MagicDesk simulated-display self-test timed out.\n' >&2
    exit 1
}
printf '%s\n' "$output" | grep -q '^Outcome: FAIL' && {
    printf 'MagicDesk simulated-display self-test failed.\n' >&2
    exit 1
}
