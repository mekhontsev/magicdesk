#!/system/bin/sh
# User-owned entry script for a prepared rootfs. Adjust this path before installing the script.
set -eu
root=${MAGICDESK_CHROOT_ROOTFS:-/data/local/linux/alpine}
root=$(realpath "$root")
[ "$(id -u)" = 0 ] || { printf '%s\n' 'This launcher requires an explicitly authorized root executor' >&2; exit 1; }
[ "$root" != / ] && { [ -x "$root/bin/sh" ] || [ -L "$root/bin/sh" ]; } || { printf '%s\n' 'Invalid prepared rootfs' >&2; exit 1; }

# All mounts belong to this launch namespace. Other users of this rootfs are untouched.
if [ "${1:-}" != --private-mounts ]; then
    exec /system/bin/unshare -m /system/bin/sh "$0" --private-mounts "$@"
fi
shift
/system/bin/mount -o rprivate none /
user=root
work=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --user) user=$2; shift 2 ;;
        --work-dir) work=$2; shift 2 ;;
        --) shift; break ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
mkdir -p "$root/proc" "$root/dev" "$root/tmp"
mount -t proc proc "$root/proc"
mount --rbind /dev "$root/dev"
guest_display=
guest_auth=
guest_wayland=
guest_session=
guest_file_socket=
guest_file_token=
if [ -n "${DISPLAY:-}" ]; then
    : "${MAGICDESK_X11_RUNTIME:?Missing X11 runtime}" "${MAGICDESK_X11_TMPDIR:?Missing X socket directory}"
    mkdir -p "$root/tmp/magicdesk-x11" "$root/tmp/.X11-unix"
    mount --bind "$MAGICDESK_X11_RUNTIME" "$root/tmp/magicdesk-x11"
    mount --bind "$MAGICDESK_X11_TMPDIR/.X11-unix" "$root/tmp/.X11-unix"
    guest_display=$DISPLAY
    guest_auth=/tmp/magicdesk-x11/Xauthority
    guest_session=x11
elif [ -n "${WAYLAND_DISPLAY:-}" ]; then
    : "${MAGICDESK_WAYLAND_RUNTIME:?Missing Wayland runtime}"
    case "$WAYLAND_DISPLAY" in wayland-[0-9]*) ;; *) echo 'Invalid Wayland socket name' >&2; exit 2 ;; esac
    case "${WAYLAND_DISPLAY#wayland-}" in *[!0-9]*) echo 'Invalid Wayland socket name' >&2; exit 2 ;; esac
    mkdir -p "$root/tmp/magicdesk-wayland"
    mount --bind "$MAGICDESK_WAYLAND_RUNTIME" "$root/tmp/magicdesk-wayland"
    guest_wayland=/tmp/magicdesk-wayland/$WAYLAND_DISPLAY
    guest_session=wayland
fi
if [ -n "$guest_session" ] && [ -n "${MAGICDESK_GUEST_FILES_HELPER:-}" ]; then
    touch "$root/tmp/magicdesk-guest-files"
    mount --bind "$MAGICDESK_GUEST_FILES_HELPER" "$root/tmp/magicdesk-guest-files"
    guest_file_socket=$MAGICDESK_GUEST_FILES_SOCKET
    guest_file_token=$MAGICDESK_GUEST_FILES_TOKEN
fi

# The guest chooses its login shell and home. No passwords or host loader variables cross the boundary.
exec /system/bin/chroot "$root" /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin \
    TERM="${TERM:-xterm-256color}" LANG=C.UTF-8 DISPLAY="$guest_display" XAUTHORITY="$guest_auth" \
    WAYLAND_DISPLAY="$guest_wayland" XDG_SESSION_TYPE="$guest_session" \
    MAGICDESK_GUEST_FILES_SOCKET="$guest_file_socket" MAGICDESK_GUEST_FILES_TOKEN="$guest_file_token" \
    /bin/sh -c '
        user=$1; work=$2; shift 2
        entry=$(awk -F: -v name="$user" '\''$1 == name { print $0; exit }'\'' /etc/passwd)
        [ -n "$entry" ] || { echo "Unknown Linux user: $user" >&2; exit 1; }
        home=$(printf "%s\n" "$entry" | cut -d: -f6)
        export HOME="$home" USER="$user" LOGNAME="$user"
        [ -n "$work" ] || work=$home
        cd "$work"
        if [ "$#" -eq 0 ]; then set -- /bin/sh -l; fi
        if [ "$user" = root ]; then exec "$@"; fi
        # su accepts a shell command; quote each supplied argument without interpreting it.
        command=
        for arg do
            quoted=$(printf "%s" "$arg" | sed "s/'\''/'\''\\\\'\'''\''/g")
            command="$command '\''$quoted'\''"
        done
        exec su -m -s /bin/sh -c "$command" "$user"
    ' magicdesk-chroot "$user" "$work" "$@"
