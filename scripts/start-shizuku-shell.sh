#!/system/bin/sh

set -eu

SHIZUKU_PACKAGE=moe.shizuku.privileged.api
SHELL_GROUPS="1004 1007 1011 1015 1028 1078 1079 3001 3002 3003 3006 3009 3011 3012"

apk_path=$(su -c "pm path $SHIZUKU_PACKAGE" | sed -n 's/^package://p' | head -n 1)
if [ -z "$apk_path" ]; then
    echo "Shizuku is not installed" >&2
    exit 1
fi

case "$(getprop ro.product.cpu.abi)" in
    arm64-v8a) native_abi=arm64 ;;
    armeabi-v7a) native_abi=arm ;;
    x86_64) native_abi=x86_64 ;;
    x86) native_abi=x86 ;;
    *)
        echo "Unsupported device ABI: $(getprop ro.product.cpu.abi)" >&2
        exit 1
        ;;
esac

starter="$(dirname "$apk_path")/lib/$native_abi/libshizuku.so"
if ! su -c "test -x '$starter'"; then
    echo "Shizuku starter not found: $starter" >&2
    exit 1
fi

# `su 2000` changes only the numeric uid on Magisk. Shizuku must also inherit
# the real adb-shell SELinux domain and supplementary groups used by input,
# storage, networking, UHID, logs, and tracefs.
su -c 'kill -9 $(pidof shizuku_server) 2>/dev/null || true'

group_args=
for group in $SHELL_GROUPS; do
    group_args="$group_args -G $group"
done

# shellcheck disable=SC2086
su -Z u:r:shell:s0 -g 2000 $group_args 2000 -c "$starter"
sleep 2

pid=$(su -c 'pidof -s shizuku_server')
if [ -z "$pid" ]; then
    echo "Shizuku server did not start" >&2
    exit 1
fi

status=$(su -c "cat /proc/$pid/status")
context=$(su -c "cat /proc/$pid/attr/current" | tr -d '\000')
uid=$(printf '%s\n' "$status" | awk '/^Uid:/ { print $2 }')
groups=$(printf '%s\n' "$status" | awk '/^Groups:/ { $1=""; sub(/^ /, ""); print }')

if [ "$uid" != 2000 ] || [ "$context" != u:r:shell:s0 ]; then
    echo "Invalid Shizuku identity: uid=$uid context=$context" >&2
    exit 1
fi

for required in $SHELL_GROUPS; do
    case " $groups " in
        *" $required "*) ;;
        *)
            echo "Shizuku is missing adb-shell group $required" >&2
            exit 1
            ;;
    esac
done

echo "Shizuku ready: pid=$pid uid=$uid context=$context"
echo "Groups: $groups"
