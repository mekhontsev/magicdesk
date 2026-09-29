#define _GNU_SOURCE
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned long *property(Display *d, Window w, const char *name, unsigned long *size) {
    Atom type; int format; unsigned long rest; unsigned char *bytes = NULL;
    *size = 0;
    if (XGetWindowProperty(d, w, XInternAtom(d, name, False), 0, 4096, False,
            AnyPropertyType, &type, &format, size, &rest, &bytes) != Success || format != 32) {
        if (bytes) XFree(bytes);
        *size = 0; return NULL;
    }
    return (unsigned long *)bytes;
}
static int shell_ready(Display *d, Window root) {
    unsigned long count;
    unsigned long *wm = property(d, root, "_NET_SUPPORTING_WM_CHECK", &count);
    int manager = count && wm[0]; if (wm) XFree(wm);
    unsigned long *clients = property(d, root, "_NET_CLIENT_LIST", &count);
    int dock = 0, desktop = 0;
    Atom dock_type = XInternAtom(d, "_NET_WM_WINDOW_TYPE_DOCK", False);
    Atom desktop_type = XInternAtom(d, "_NET_WM_WINDOW_TYPE_DESKTOP", False);
    for (unsigned long i = 0; i < count; ++i) {
        unsigned long n;
        unsigned long *types = property(d, clients[i], "_NET_WM_WINDOW_TYPE", &n);
        for (unsigned long j = 0; j < n; ++j) {
            dock |= types[j] == dock_type;
            desktop |= types[j] == desktop_type;
        }
        if (types) XFree(types);
    }
    if (clients) XFree(clients);
    return manager && dock && desktop;
}
static int ignore_destroyed(Display *d, XErrorEvent *e) {
    (void)d;
    if (e->error_code != BadWindow) {
        fprintf(stderr, "X11 error %u request %u\n", e->error_code, e->request_code);
        exit(1);
    }
    return 0;
}
int main(int argc, char **argv) {
    Display *d = XOpenDisplay(NULL);
    if (!d) { fputs("No X11 connection\n", stderr); return 1; }
    XSetErrorHandler(ignore_destroyed);
    Window root = DefaultRootWindow(d);
    XSelectInput(d, root, PropertyChangeMask | SubstructureNotifyMask);
    if (argc == 2 && !strcmp(argv[1], "manager")) {
        for (;;) {
            unsigned long count;
            unsigned long *wm = property(d, root, "_NET_SUPPORTING_WM_CHECK", &count);
            int ready = count && wm[0];
            if (wm) XFree(wm);
            if (ready) break;
            // EVENT_WAIT: WM publishes its root property; the enclosing timeout fails the test.
            XEvent event; XNextEvent(d, &event);
        }
        puts("PASS X11 window manager is ready");
        XCloseDisplay(d); return 0;
    }
    if (argc == 5 && !strcmp(argv[1], "resize")) {
        Window target = strtoul(argv[2], NULL, 0);
        int width = atoi(argv[3]), height = atoi(argv[4]);
        if (width <= 0 || height <= 0) return 2;
        XSelectInput(d, target, StructureNotifyMask);
        XResizeWindow(d, target, (unsigned)width, (unsigned)height);
        for (;;) {
            XWindowAttributes attributes;
            if (!XGetWindowAttributes(d, target, &attributes)) return 1;
            if (attributes.width == width && attributes.height == height) break;
            // EVENT_WAIT: WM ConfigureNotify after resize; the enclosing timeout fails the test.
            XEvent event; XNextEvent(d, &event);
        }
        puts("PASS Xfce window resize acknowledged");
        XCloseDisplay(d); return 0;
    }
    if (argc == 3 && !strcmp(argv[1], "activate")) {
        Window target = strtoul(argv[2], NULL, 0);
        XEvent request = {.xclient = {.type = ClientMessage, .display = d,
            .window = target, .message_type = XInternAtom(d, "_NET_ACTIVE_WINDOW", False),
            .format = 32, .data.l = {2, CurrentTime, 0, 0, 0}}};
        XSendEvent(d, root, False, SubstructureRedirectMask | SubstructureNotifyMask, &request);
        for (;;) {
            unsigned long count;
            unsigned long *active = property(d, root, "_NET_ACTIVE_WINDOW", &count);
            int selected = count && active[0] == target;
            if (active) XFree(active);
            if (selected) break;
            // EVENT_WAIT: WM acknowledges activation by publishing its active window.
            XEvent event; XNextEvent(d, &event);
        }
        puts("PASS Xfce activation acknowledged");
        XCloseDisplay(d); return 0;
    }
    if (argc != 1) return 2;
    while (!shell_ready(d, root)) {
        // EVENT_WAIT: root properties/maps; the enclosing command timeout fails this test.
        XEvent event; XNextEvent(d, &event);
    }
    puts("PASS Xfce shell: window manager, panel and desktop are mapped");
    XCloseDisplay(d);
    return 0;
}
