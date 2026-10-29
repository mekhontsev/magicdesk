set -eu
md_protocol=$1
shift
case "$md_protocol" in
    x11|x11-routed) XDG_SESSION_TYPE=x11 ;;
    wayland) XDG_SESSION_TYPE=wayland ;;
    *) printf 'Unknown graphical protocol: %s\n' "$md_protocol" >&2; exit 2 ;;
esac
[ "$#" -gt 0 ] || exit 2
XDG_RUNTIME_DIR=$(umask 077; mktemp -d /tmp/magicdesk-runtime.XXXXXX)
export XDG_RUNTIME_DIR XDG_SESSION_TYPE

# /proc encodes whitespace and backslashes as octal escapes. Keep the encoded
# records until each helper invocation; never evaluate mount-table content.
md_session_mounts() {
    while IFS=' ' read -r md_source md_path md_type md_options; do
        md_target=$md_path
        case "$md_path" in *'\'*)
            md_target=$(printf '%b.' "$md_path") || return
            md_target=${md_target%.} ;;
        esac
        case "$md_target" in
            "$XDG_RUNTIME_DIR"|"$XDG_RUNTIME_DIR"/*)
                printf '%s %s\n' "$md_path" "$md_type" ;;
        esac
    done < /proc/self/mounts
}
md_session_cleanup() {
    md_mounts=$(md_session_mounts) || return
    # Reverse path order puts nested mounts before their parents.
    printf '%s\n' "$md_mounts" | LC_ALL=C sort -r |
    while IFS=' ' read -r md_path md_type; do
        [ -n "$md_path" ] || continue
        md_target=$(printf '%b.' "$md_path") || exit
        md_target=${md_target%.}
        case "$md_type" in
            fuse|fuse.*|fuseblk)
                if command -v fusermount3 >/dev/null 2>&1; then
                    fusermount3 -u -z -- "$md_target" 2>/dev/null || :
                elif command -v fusermount >/dev/null 2>&1; then
                    fusermount -u -z -- "$md_target" 2>/dev/null || :
                else
                    umount -l -- "$md_target" 2>/dev/null || :
                fi ;;
            *) umount -l -- "$md_target" 2>/dev/null || : ;;
        esac
    done
    # A daemon may have unmounted concurrently. Check the outcome,
    # not a helper's error for a mount that has already disappeared.
    md_mounts=$(md_session_mounts) || return
    if [ -n "$md_mounts" ]; then
        printf 'MagicDesk: runtime mounts remain; retaining %s\n' "$XDG_RUNTIME_DIR" >&2
        return 1
    fi
    if [ -e "$XDG_RUNTIME_DIR" ] || [ -L "$XDG_RUNTIME_DIR" ]; then
        find "$XDG_RUNTIME_DIR" -xdev -depth -delete
    fi
}
md_session_exit() {
    md_status=$?
    trap - EXIT
    if ! md_session_cleanup; then
        printf 'MagicDesk: runtime cleanup failed: %s\n' "$XDG_RUNTIME_DIR" >&2
        [ "$md_status" -ne 0 ] || md_status=1
    fi
    # Login-shell logout hooks belong to the distribution, not our strict script.
    set +eu
    exit "$md_status"
}
trap md_session_exit EXIT

# Activated D-Bus services must inherit the same authority as the client.
if [ "$md_protocol" = x11-routed ]; then
    : "${MAGICDESK_X11_AUTHORITY:?Missing X11 authority}"
    export XAUTHORITY="$XDG_RUNTIME_DIR/Xauthority"
    printf '%s' "$MAGICDESK_X11_AUTHORITY" | base64 -d > "$XAUTHORITY"
    unset MAGICDESK_X11_AUTHORITY
fi
if [ -n "${MAGICDESK_AUTOMATION_HELPER:-}" ] && [ -n "${MAGICDESK_AUTOMATION_SOCKET:-}" ] && [ -n "${MAGICDESK_AUTOMATION_TOKEN:-}" ]; then
    dbus-run-session -- "$MAGICDESK_AUTOMATION_HELPER" --automation "$@"
else
    dbus-run-session -- "$@"
fi
