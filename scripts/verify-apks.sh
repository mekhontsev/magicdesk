#!/usr/bin/env sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    printf 'Usage: %s CORE_APK [KERNEL_FIXES_APK]\n' \
        "$0" >&2
    exit 2
fi

core_apk=$1
kernel_fixes_apk=${2-}

if [ ! -f "$core_apk" ]; then
    printf 'Missing APK: %s\n' "$core_apk" >&2
    exit 1
fi

core_contents=$(unzip -Z1 "$core_apk")

printf '%s\n' "$core_contents" \
    | grep -qx 'lib/arm64-v8a/libmagicdesk_uinput_bridge.so' \
    || {
        printf 'Core APK is missing libmagicdesk_uinput_bridge.so\n' >&2
        exit 1
    }

printf '%s\n' "$core_contents" \
    | grep -qx 'lib/arm64-v8a/libmagicdesk_keyboard_bridge.so' \
    || {
        printf 'Core APK is missing libmagicdesk_keyboard_bridge.so\n' >&2
        exit 1
    }

if printf '%s\n' "$core_contents" | grep -q '\.ko$'; then
    printf 'Core APK must not contain a kernel module\n' >&2
    exit 1
fi

if [ -n "$kernel_fixes_apk" ]; then
    if [ ! -f "$kernel_fixes_apk" ]; then
        printf 'Missing APK: %s\n' "$kernel_fixes_apk" >&2
        exit 1
    fi

    project_dir=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
    reviewed_module="$project_dir/kernel-fixes/src/main/res/raw/dp_mode_reset.ko"
    kernel_fixes_contents=$(unzip -Z1 "$kernel_fixes_apk")

    module_entries=$(printf '%s\n' "$kernel_fixes_contents" \
        | grep '\.ko$' || true)
    if [ "$(printf '%s\n' "$module_entries" | grep -c .)" -ne 1 ]; then
        printf 'Kernel fixes APK must contain exactly one kernel module\n' >&2
        exit 1
    fi

    packaged_hash=$(unzip -p "$kernel_fixes_apk" "$module_entries" \
        | sha256sum | cut -d ' ' -f 1)
    reviewed_hash=$(sha256sum "$reviewed_module" | cut -d ' ' -f 1)
    if [ "$packaged_hash" != "$reviewed_hash" ]; then
        printf 'Packaged kernel module differs from the reviewed binary\n' >&2
        exit 1
    fi

    if printf '%s\n' "$kernel_fixes_contents" \
            | grep -Eq 'libmagicdesk_(uinput_bridge|keyboard_bridge)\.so$'; then
        printf 'Kernel fixes APK must not contain an input helper\n' >&2
        exit 1
    fi
fi

printf 'APK boundaries verified.\n'
