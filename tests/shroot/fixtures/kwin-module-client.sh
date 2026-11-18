#!/bin/sh
set -eu
work=/tmp/md-kwin-module
printf '%s\n' "$PPID" > "$work/compositor.pid"
cat /proc/"$PPID"/maps > "$work/compositor.maps"
env > "$work/client.env"
qdbus=$(command -v qdbus6 || command -v qdbus)
"$qdbus" org.kde.KWin /KWin org.kde.KWin.supportInformation > "$work/support.txt"
case "$MD_KWIN_CASE" in
    qpa) "$qdbus" org.kde.KWin /KWin org.kde.KWin.showDebugConsole ;;
    egl|vulkan)
        if [ "$MD_KWIN_CASE" = egl ]; then set -- weston-simple-egl -f; else set -- vkcube --wsi wayland; fi
        WAYLAND_DEBUG=client stdbuf -oL "$@" > "$work/client.log" 2>&1 &
        client=$!
        trap 'kill "$client" 2>/dev/null || :; wait "$client" 2>/dev/null || :' EXIT ;;
    *) exit 2 ;;
esac
mkfifo "$XDG_RUNTIME_DIR/module-control"
printf '%s\n' "$XDG_RUNTIME_DIR/module-control" > "$work/control-path"
printf ready > "$work/client.ready"
# EVENT_WAIT: fixture command pipe; the host owns the bounded test/cleanup deadline.
read -r command < "$XDG_RUNTIME_DIR/module-control"
test "$command" = quit
