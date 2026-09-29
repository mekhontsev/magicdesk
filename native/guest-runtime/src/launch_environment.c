#include "launch_environment.h"
#include "raw.h"
#include <errno.h>

static int retain(const char *entry) {
    const char *names[] = {"TERM=", "COLORTERM=", "LANG=", "LC_", "WAYLAND_SOCKET=", "WAYLAND_DISPLAY=",
        "DISPLAY=", "XAUTHORITY=", "XDG_SESSION_TYPE=", "GDK_", "GTK_", "QT_", "SDL_",
        "GSETTINGS_BACKEND=", "LIBGL_", "MESA_", "VK_", "MAGICDESK_X11_AUTHORITY="};
    for (unsigned i = 0; i < sizeof(names) / sizeof(*names); ++i)
        if (md_prefix(entry, names[i])) return 1;
    return 0;
}

long md_launch_environment(struct md_launch_environment *out, const char *home, char **inherited) {
    if (md_copy(out->home, sizeof(out->home), "HOME=") || md_append(out->home, sizeof(out->home), home))
        return -ENAMETOOLONG;
    unsigned n = 0;
    out->values[n++] = out->home;
    out->values[n++] = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    out->values[n++] = "SHELL=/bin/sh";
    out->values[n++] = RAW0(getuid) == 0 ? "USER=root" : "USER=shell";
    out->values[n++] = RAW0(getuid) == 0 ? "LOGNAME=root" : "LOGNAME=shell";
    out->values[n++] = "TMPDIR=/tmp";
    /* The guest owns its runtime-directory policy; Android's private XDG paths never cross this boundary. */
    for (unsigned i = 0; inherited[i]; ++i) {
        if (i >= 4096) return -E2BIG;
        if (retain(inherited[i])) {
            if (n + 1 == sizeof(out->values) / sizeof(*out->values)) return -E2BIG;
            out->values[n++] = inherited[i];
        }
    }
    out->values[n] = NULL;
    return 0;
}
