#!/system/bin/sh

set -eu

SHIZUKU_PACKAGE=moe.shizuku.privileged.api
SHELL_GROUPS="1004 1007 1011 1015 1028 1078 1079 3001 3002 3003 3006 3009 3011 3012"
caller_uid=$(id -u)

system_command() {
    if [ "$caller_uid" = 2000 ]; then
        sh -c "$1"
    else
        su -c "$1"
    fi
}

apk_path=$(system_command "pm path $SHIZUKU_PACKAGE" | sed -n 's/^package://p' | head -n 1)
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
if ! system_command "test -x '$starter'"; then
    echo "Shizuku starter not found: $starter" >&2
    exit 1
fi

pid=$(system_command 'pidof -s shizuku_server' || true)
if [ -z "$pid" ]; then
    if [ "$caller_uid" = 2000 ]; then
        # A real ADB shell already owns the required identity and groups.
        "$starter"
    else
        # Magisk's `su 2000` alone does not provide the adb-shell SELinux
        # domain or its supplementary input, storage, network and log groups.
        group_args=
        for group in $SHELL_GROUPS; do
            group_args="$group_args -G $group"
        done
        # shellcheck disable=SC2086
        su -Z u:r:shell:s0 -g 2000 $group_args 2000 -c "$starter"
    fi
    sleep 2
fi

pid=$(system_command 'pidof -s shizuku_server' || true)
if [ -z "$pid" ]; then
    echo "Shizuku server did not start" >&2
    exit 1
fi

status=$(system_command "cat /proc/$pid/status")
context=$(system_command "cat /proc/$pid/attr/current" | tr -d '\000')
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
