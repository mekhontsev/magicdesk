#define _GNU_SOURCE
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <assert.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Event-controlled startup fixture: no process-start or splash-duration heuristic. */
static Display *display;
static Window splash, main_window, dialog;
static Atom atom(const char *name) { return XInternAtom(display, name, False); }
static Window create(const char *title, const char *role, int width, int height, unsigned long color) {
    Window window = XCreateSimpleWindow(display, DefaultRootWindow(display), 0, 0, width, height, 0, 0, color);
    XStoreName(display, window, title);
    XClassHint hint = {"startup-check", "MagicDeskStartupCheck"};
    XSetClassHint(display, window, &hint);
    if (role) {
        Atom type = atom(role);
        XChangeProperty(display, window, atom("_NET_WM_WINDOW_TYPE"), XA_ATOM, 32, PropModeReplace,
                (unsigned char *)&type, 1);
    }
    XSelectInput(display, window, ExposureMask | StructureNotifyMask | ButtonPressMask);
    Atom close = atom("WM_DELETE_WINDOW");
    XSetWMProtocols(display, window, &close, 1);
    return window;
}
static void command(const char *text) {
    if (!strcmp(text, "splash")) XMapWindow(display, splash);
    else if (!strcmp(text, "gap")) XUnmapWindow(display, splash);
    else if (!strcmp(text, "dialog")) XMapWindow(display, dialog);
    else if (!strcmp(text, "main")) { XMapWindow(display, main_window); XUnmapWindow(display, splash); }
    else if (!strcmp(text, "promote")) {
        Atom type = atom("_NET_WM_WINDOW_TYPE_NORMAL");
        XChangeProperty(display, splash, atom("_NET_WM_WINDOW_TYPE"), XA_ATOM, 32, PropModeReplace,
                (unsigned char *)&type, 1);
    } else if (!strcmp(text, "quit")) { XCloseDisplay(display); exit(0); }
    printf("command %s\n", text);
    XFlush(display);
}
int main(int argc, char **argv) {
    assert(argc == 2 || argc == 3);
    setvbuf(stdout, NULL, _IOLBF, 0);
    display = XOpenDisplay(NULL); assert(display);
    /* The first client must discover closure without creating the protocol atoms itself. */
    assert(XInternAtom(display, "WM_PROTOCOLS", True) != None);
    assert(XInternAtom(display, "WM_DELETE_WINDOW", True) != None);
    assert(XInternAtom(display, "WM_TAKE_FOCUS", True) != None);
    splash = create("Startup splash", "_NET_WM_WINDOW_TYPE_SPLASH", 420, 220, 0x246454);
    main_window = create("Startup main", "_NET_WM_WINDOW_TYPE_NORMAL", 800, 600, 0x245488);
    dialog = create("Startup recovery", "_NET_WM_WINDOW_TYPE_DIALOG", 360, 240, 0x885424);
    assert(mkfifo(argv[1], 0600) == 0);
    int input = open(argv[1], O_RDWR | O_NONBLOCK | O_CLOEXEC); assert(input >= 0);
    command(argc == 3 ? argv[2] : "splash");
    printf("ready splash=%lu main=%lu dialog=%lu\n", splash, main_window, dialog);
    for (;;) {
        while (XPending(display)) {
            XEvent event; XNextEvent(display, &event);
            if (event.type == Expose) {
                GC gc = XCreateGC(display, event.xexpose.window, 0, NULL);
                XSetForeground(display, gc, 0xffffff);
                const char *label = event.xexpose.window == splash ? "Splash: 420 x 220" : "Application startup check";
                XDrawString(display, event.xexpose.window, gc, 25, 50, label, strlen(label));
                XFreeGC(display, gc);
            } else if (event.type == ConfigureNotify) printf("size %lu %d %d\n", event.xconfigure.window,
                    event.xconfigure.width, event.xconfigure.height);
            else if (event.type == ButtonPress) printf("click %lu\n", event.xbutton.window);
            else if (event.type == ClientMessage && (Atom)event.xclient.data.l[0] == atom("WM_DELETE_WINDOW")) {
                printf("close %lu\n", event.xclient.window);
                XDestroyWindow(display, event.xclient.window);
            }
        }
        XFlush(display);
        struct pollfd fds[] = {{ConnectionNumber(display), POLLIN, 0}, {input, POLLIN, 0}};
        // EVENT_WAIT: X events or test commands; the test owner terminates this fixture explicitly.
        assert(poll(fds, 2, -1) >= 0);
        if (fds[1].revents & POLLIN) {
            char buffer[512]; ssize_t count = read(input, buffer, sizeof(buffer) - 1); assert(count > 0);
            buffer[count] = 0;
            char *save = NULL;
            for (char *line = strtok_r(buffer, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) command(line);
        }
    }
}
