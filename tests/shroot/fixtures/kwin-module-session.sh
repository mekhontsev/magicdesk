#!/bin/sh
set -eu
work=/tmp/md-kwin-module
. /etc/profile.d/magicdesk-graphics.sh
export LD_LIBRARY_PATH="$work/patched:$LD_LIBRARY_PATH"
export QT_PLUGIN_PATH="$work/patched/plugins:/run/current-system/sw/lib/qt-6/plugins:/usr/lib/aarch64-linux-gnu/qt6/plugins:/usr/lib/qt6/plugins${QT_PLUGIN_PATH:+:$QT_PLUGIN_PATH}"
export XDG_CONFIG_HOME="$work/config" XDG_CACHE_HOME="$work/cache"
export XDG_DATA_HOME="$work/data" XDG_CURRENT_DESKTOP=KDE
export QT_LOGGING_RULES='kwin_scene_opengl.debug=true;kwin_wayland_backend.debug=true'
export QT_FORCE_STDERR_LOGGING=1
unset DISPLAY QT_QPA_PLATFORM
set -- "$work/patched/kwin_wayland" --wayland-display "$WAYLAND_DISPLAY" \
    --width 960 --height 640 --socket kwin-module --no-lockscreen \
    --no-global-shortcuts --no-kactivities \
    --exit-with-session "/bin/sh $work/kwin-module-client.sh"
if [ -n "${MD_KWIN_GDB:-}" ]; then
    exec "$MD_KWIN_GDB" --batch -ex 'set pagination off' -ex run -ex 'thread apply all bt' --args "$@"
fi
exec "$@"
