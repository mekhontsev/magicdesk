#!/usr/bin/env sh
set -eu

ADB=${ADB:-adb}
PACKAGE=io.github.mekhontsev.magicdesk
COMPONENT="$PACKAGE/.DesktopActivity"
STACK_COMPONENT="$PACKAGE/$PACKAGE.DesktopActivity"
EXPECTED_DISPLAY_EXTRA=magicdesk_expected_display_id
OVERLAY_SPEC=${OVERLAY_SPEC:-1920x1080/160}

if ! command -v "$ADB" >/dev/null 2>&1; then
    printf 'adb is required; set ADB to its executable path\n' >&2
    exit 2
fi

shell() {
    "$ADB" shell "$@" | tr -d '\r'
}

previous_overlay=$(shell settings get global overlay_display_devices)

restore_overlay() {
    if [ -z "$previous_overlay" ] || [ "$previous_overlay" = null ]; then
        shell settings delete global overlay_display_devices >/dev/null
    else
        shell settings put global overlay_display_devices \
            "$previous_overlay" >/dev/null
    fi
}
trap restore_overlay EXIT HUP INT TERM

shell settings put global overlay_display_devices "$OVERLAY_SPEC" >/dev/null

display_id=
attempt=0
while [ "$attempt" -lt 50 ]; do
    display_id=$(shell cmd display get-displays --ids-only --type overlay \
        | awk '$1 ~ /^[0-9]+$/ && $1 > 0 { print $1; exit }')
    [ -n "$display_id" ] && break
    attempt=$((attempt + 1))
    sleep 0.1
done

if [ -z "$display_id" ]; then
    printf 'Android did not create overlay display %s\n' "$OVERLAY_SPEC" >&2
    exit 1
fi

launch_output=$(shell am start -W \
    --display "$display_id" \
    --windowingMode 5 \
    -f 0x18000000 \
    -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER \
    --ei "$EXPECTED_DISPLAY_EXTRA" "$display_id" \
    -n "$COMPONENT")

printf '%s\n' "$launch_output"
printf '%s\n' "$launch_output" | grep -q 'Status: ok' || {
    printf 'MagicDesk did not start on overlay display %s\n' \
        "$display_id" >&2
    exit 1
}

stack_output=$(shell cmd activity stack list)
desktop_task_id=$(printf '%s\n' "$stack_output" | awk \
    -v display="displayId=$display_id" \
    -v component="topActivity=ComponentInfo{$STACK_COMPONENT}" '
        /^RootTask id=/ { in_display = index($0, display) > 0 }
        in_display && index($0, component) > 0 {
            line = $0
            sub(/^ *taskId=/, "", line)
            sub(/:.*/, "", line)
            print line
            exit
        }
    ')
if [ -z "$desktop_task_id" ]; then
        printf 'MagicDesk task was not found on overlay display %s\n' \
            "$display_id" >&2
        exit 1
fi

restore_overlay
trap - EXIT HUP INT TERM

attempt=0
while [ "$attempt" -lt 50 ]; do
    stack_output=$(shell cmd activity stack list)
    if ! printf '%s\n' "$stack_output" \
            | grep -q "taskId=$desktop_task_id:"; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 0.1
done

if printf '%s\n' "$stack_output" | grep -q "taskId=$desktop_task_id:"; then
    printf 'MagicDesk task %s survived removal of overlay display %s\n' \
        "$desktop_task_id" "$display_id" >&2
    exit 1
fi

printf 'Simulated display smoke passed on display %s (%s).\n' \
    "$display_id" "$OVERLAY_SPEC"
