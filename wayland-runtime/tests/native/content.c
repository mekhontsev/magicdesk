#define _GNU_SOURCE
#include "wayland_server.h"
#include "xdg-shell-client-protocol.h"
#include <assert.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <wayland-client.h>

struct Client {
    struct wl_display *display;
    struct wl_compositor *compositor;
    struct wl_shm *shm;
    struct wl_seat *seat;
    struct wl_pointer *pointer;
    struct wl_keyboard *keyboard;
    struct wl_data_device_manager *manager;
    struct wl_data_device *device;
    struct wl_data_offer *drag_offer;
    struct xdg_wm_base *shell;
    struct wl_surface *surface;
    struct xdg_surface *xdg;
    struct xdg_toplevel *top;
    bool selection, closed;
    int drops;
};
static const char *mime = "text/plain;charset=utf-8";
static int memory(const char *value) {
    int fd = syscall(SYS_memfd_create, "test-content", MFD_CLOEXEC);
    assert(fd >= 0 && write(fd, value, strlen(value)) == (ssize_t)strlen(value));
    return fd;
}
static void source_target(void *data, struct wl_data_source *source, const char *type) { (void)data; (void)source; (void)type; }
static void source_send(void *data, struct wl_data_source *source, const char *type, int fd) {
    (void)source; assert(!strcmp(type, mime));
    const char *text = data;
    assert(write(fd, text, strlen(text)) == (ssize_t)strlen(text)); close(fd);
}
static void source_cancel(void *data, struct wl_data_source *source) { (void)data; wl_data_source_destroy(source); }
static void source_drop(void *data, struct wl_data_source *source) { (void)data; (void)source; }
static void source_finish(void *data, struct wl_data_source *source) { (void)data; (void)source; }
static void source_action(void *data, struct wl_data_source *source, uint32_t action) { (void)data; (void)source; assert(action == WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY || action == 0); }
static const struct wl_data_source_listener source_listener = {
    source_target, source_send, source_cancel, source_drop, source_finish, source_action,
};
static struct wl_data_source *source(struct Client *c, const char *text) {
    struct wl_data_source *s = wl_data_device_manager_create_data_source(c->manager);
    wl_data_source_add_listener(s, &source_listener, (void *)text);
    wl_data_source_offer(s, mime); return s;
}
static void read_offer(struct Client *c, struct wl_data_offer *offer, const char *expected) {
    int fds[2]; assert(pipe2(fds, O_CLOEXEC) == 0);
    wl_data_offer_receive(offer, mime, fds[1]); close(fds[1]);
    // EVENT_WAIT: dispatch the producer's send request before consuming this test pipe.
    assert(wl_display_roundtrip(c->display) >= 0);
    char text[128] = {0}; size_t size = 0;
    for (;;) {
        ssize_t n = read(fds[0], text + size, sizeof(text) - 1 - size);
        assert(n >= 0); if (!n) break; size += n; assert(size < sizeof(text) - 1);
    }
    close(fds[0]); assert(!strcmp(text, expected));
}
static void offer_mime(void *data, struct wl_data_offer *offer, const char *type) { (void)data; (void)offer; assert(!strcmp(type, mime)); }
static void offer_source_actions(void *data, struct wl_data_offer *offer, uint32_t actions) { (void)data; (void)offer; assert(actions & WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY); }
static void offer_action(void *data, struct wl_data_offer *offer, uint32_t action) { (void)data; (void)offer; (void)action; }
static const struct wl_data_offer_listener offer_listener = {offer_mime, offer_source_actions, offer_action};
static void data_offer(void *data, struct wl_data_device *device, struct wl_data_offer *offer) {
    (void)device; wl_data_offer_add_listener(offer, &offer_listener, data);
}
static void data_selection(void *data, struct wl_data_device *device, struct wl_data_offer *offer) {
    (void)device; struct Client *c = data;
    if (!offer || !c->selection) return;
    // The first echoed selection is the guest's own; host selection follows it.
    static int selections;
    if (++selections == 1) { wl_data_offer_destroy(offer); return; }
    read_offer(c, offer, "host clipboard"); wl_data_offer_destroy(offer);
    xdg_toplevel_set_title(c->top, "clipboard");
}
static void data_enter(void *data, struct wl_data_device *device, uint32_t serial,
        struct wl_surface *surface, wl_fixed_t x, wl_fixed_t y, struct wl_data_offer *offer) {
    (void)device; (void)x; (void)y; struct Client *c = data; assert(surface == c->surface);
    c->drag_offer = offer;
    wl_data_offer_accept(offer, serial, mime);
    wl_data_offer_set_actions(offer, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);
}
static void data_leave(void *data, struct wl_data_device *device) { (void)data; (void)device; }
static void data_motion(void *data, struct wl_data_device *device, uint32_t time, wl_fixed_t x, wl_fixed_t y) { (void)data; (void)device; (void)time; (void)x; (void)y; }
static void data_drop(void *data, struct wl_data_device *device) {
    (void)device; struct Client *c = data;
    read_offer(c, c->drag_offer, c->drops == 0 ? "host drag" : "guest drag");
    wl_data_offer_finish(c->drag_offer); wl_data_offer_destroy(c->drag_offer); c->drag_offer = NULL;
    ++c->drops; assert(c->drops <= 2);
    xdg_toplevel_set_title(c->top, c->drops == 1 ? "external-drop" : "local-drop");
}
static const struct wl_data_device_listener device_listener = {data_offer, data_enter, data_leave, data_motion, data_drop, data_selection};
static void pointer_enter(void *d, struct wl_pointer *p, uint32_t s, struct wl_surface *w, wl_fixed_t x, wl_fixed_t y) { (void)d;(void)p;(void)s;(void)w;(void)x;(void)y; }
static void pointer_leave(void *d, struct wl_pointer *p, uint32_t s, struct wl_surface *w) { (void)d;(void)p;(void)s;(void)w; }
static void pointer_motion(void *d, struct wl_pointer *p, uint32_t t, wl_fixed_t x, wl_fixed_t y) { (void)d;(void)p;(void)t;(void)x;(void)y; }
static void pointer_axis(void *d, struct wl_pointer *p, uint32_t t, uint32_t a, wl_fixed_t v) { (void)d;(void)p;(void)t;(void)a;(void)v; }
static void pointer_button(void *data, struct wl_pointer *p, uint32_t serial, uint32_t t, uint32_t button, uint32_t state) {
    (void)p;(void)t;(void)button; struct Client *c = data;
    if (state != WL_POINTER_BUTTON_STATE_PRESSED) return;
    struct wl_data_source *s = source(c, "guest drag");
    wl_data_source_set_actions(s, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);
    wl_data_device_start_drag(c->device, s, c->surface, NULL, serial);
}
static const struct wl_pointer_listener pointer_listener = {pointer_enter, pointer_leave, pointer_motion, pointer_button, pointer_axis};
static void keyboard_map(void *d, struct wl_keyboard *k, uint32_t f, int fd, uint32_t size) { (void)d;(void)k;(void)f;(void)size; close(fd); }
static void keyboard_enter(void *data, struct wl_keyboard *k, uint32_t serial, struct wl_surface *s, struct wl_array *keys) {
    (void)k;(void)s;(void)keys; struct Client *c = data;
    if (!c->selection) { c->selection = true; wl_data_device_set_selection(c->device, source(c, "guest clipboard"), serial); }
}
static void keyboard_leave(void *d, struct wl_keyboard *k, uint32_t serial, struct wl_surface *s) { (void)d;(void)k;(void)serial;(void)s; }
static void keyboard_key(void *d, struct wl_keyboard *k, uint32_t s, uint32_t t, uint32_t key, uint32_t state) { (void)d;(void)k;(void)s;(void)t;(void)key;(void)state; }
static void keyboard_modifiers(void *d, struct wl_keyboard *k, uint32_t s, uint32_t a, uint32_t b, uint32_t c, uint32_t g) { (void)d;(void)k;(void)s;(void)a;(void)b;(void)c;(void)g; }
static const struct wl_keyboard_listener keyboard_listener = {keyboard_map, keyboard_enter, keyboard_leave, keyboard_key, keyboard_modifiers};
static void capabilities(void *d, struct wl_seat *seat, uint32_t caps) { (void)d; (void)seat; (void)caps; }
static const struct wl_seat_listener seat_listener = {.capabilities = capabilities};
static void global(void *data, struct wl_registry *r, uint32_t id, const char *name, uint32_t version) {
    (void)version; struct Client *c = data;
    if (!strcmp(name, "wl_compositor")) c->compositor = wl_registry_bind(r, id, &wl_compositor_interface, 4);
    else if (!strcmp(name, "wl_shm")) c->shm = wl_registry_bind(r, id, &wl_shm_interface, 1);
    else if (!strcmp(name, "wl_seat")) { c->seat = wl_registry_bind(r, id, &wl_seat_interface, 1); wl_seat_add_listener(c->seat, &seat_listener, c); }
    else if (!strcmp(name, "wl_data_device_manager")) c->manager = wl_registry_bind(r, id, &wl_data_device_manager_interface, 3);
    else if (!strcmp(name, "xdg_wm_base")) c->shell = wl_registry_bind(r, id, &xdg_wm_base_interface, 2);
}
static void removed(void *d, struct wl_registry *r, uint32_t id) { (void)d;(void)r;(void)id; }
static const struct wl_registry_listener registry_listener = {global, removed};
static void configure(void *data, struct xdg_surface *xdg, uint32_t serial) {
    struct Client *c = data; xdg_surface_ack_configure(xdg, serial);
    int fd = syscall(SYS_memfd_create, "test-surface", MFD_CLOEXEC); assert(fd >= 0 && ftruncate(fd, 32 * 32 * 4) == 0);
    struct wl_shm_pool *pool = wl_shm_create_pool(c->shm, fd, 32 * 32 * 4);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, 32, 32, 128, WL_SHM_FORMAT_ARGB8888);
    wl_surface_attach(c->surface, buffer, 0, 0); wl_surface_commit(c->surface); wl_shm_pool_destroy(pool); close(fd);
}
static const struct xdg_surface_listener surface_listener = {configure};
static void configured(void *d, struct xdg_toplevel *t, int32_t w, int32_t h, struct wl_array *s) { (void)d;(void)t;(void)w;(void)h;(void)s; }
static void closed(void *data, struct xdg_toplevel *top) { (void)top; ((struct Client *)data)->closed = true; }
static const struct xdg_toplevel_listener top_listener = {configured, closed};
static void client(const char *socket) {
    struct Client c = {0}; c.display = wl_display_connect(socket); assert(c.display);
    struct wl_registry *r = wl_display_get_registry(c.display); wl_registry_add_listener(r, &registry_listener, &c);
    assert(wl_display_roundtrip(c.display) >= 0);
    assert(c.compositor && c.shm && c.seat && c.manager && c.shell);
    c.device = wl_data_device_manager_get_data_device(c.manager, c.seat); wl_data_device_add_listener(c.device, &device_listener, &c);
    c.pointer = wl_seat_get_pointer(c.seat); wl_pointer_add_listener(c.pointer, &pointer_listener, &c);
    c.keyboard = wl_seat_get_keyboard(c.seat); wl_keyboard_add_listener(c.keyboard, &keyboard_listener, &c);
    c.surface = wl_compositor_create_surface(c.compositor); c.xdg = xdg_wm_base_get_xdg_surface(c.shell, c.surface);
    xdg_surface_add_listener(c.xdg, &surface_listener, &c); c.top = xdg_surface_get_toplevel(c.xdg);
    xdg_toplevel_add_listener(c.top, &top_listener, &c); wl_surface_commit(c.surface);
    while (!c.closed) assert(wl_display_dispatch(c.display) >= 0);
    assert(c.drops == 2);
    wl_display_disconnect(c.display);
}
struct Host {
    MdwServer *server; MdwOutput *output;
    uint64_t window, offer;
    bool mapped, done, clipboard, external, dragging, local, destroyed, closing;
    int finishes;
};
static void window(void *data, uint64_t id, const MdwWindow *window) {
    struct Host *h = data; h->window = id;
    if (!window) { h->destroyed = true; return; }
    h->mapped = window->mapped;
    if (!strcmp(window->title, "clipboard")) h->clipboard = true;
    if (!strcmp(window->title, "external-drop")) h->external = true;
    if (!strcmp(window->title, "local-drop")) h->local = true;
}
static void offer(void *data, int channel, uint64_t id, MdwOutput *output, const char *types) {
    struct Host *h = data;
    if (!*types) return;
    assert(!strcmp(types, mime));
    if (channel == MDW_CONTENT_CLIPBOARD) assert(mdw_content_read(h->server, channel, id, 1, mime));
    else {
        assert(output == h->output); h->offer = id;
        mdw_content_drag(h->server, output, MDW_DRAG_BEGIN, id, 0, 0, false);
        assert(mdw_content_read(h->server, channel, id, 2, mime));
    }
}
static void reply(void *data, uint64_t request, int fd) {
    struct Host *h = data; char text[64] = {0}; assert(fd >= 0);
    assert(pread(fd, text, sizeof(text) - 1, 0) > 0);
    if (request == 1) { assert(!strcmp(text, "guest clipboard")); assert(mdw_content_publish(h->server, MDW_CONTENT_CLIPBOARD, 10, mime)); }
    else {
        assert(request == 2 && !strcmp(text, "guest drag"));
        mdw_content_drag(h->server, h->output, MDW_DRAG_ENTER, h->offer, 0, 0, false);
        mdw_content_drag(h->server, h->output, MDW_DRAG_MOVE, h->offer, .5, .5, false);
        mdw_content_drag(h->server, h->output, MDW_DRAG_DROP, h->offer, 0, 0, false);
    }
}
static void request(void *data, int channel, uint64_t id, uint64_t request, const char *type) {
    struct Host *h = data; assert(!strcmp(type, mime)); assert(id == (channel == MDW_CONTENT_CLIPBOARD ? 10 : 11));
    int fd = memory(channel == MDW_CONTENT_CLIPBOARD ? "host clipboard" : "host drag");
    mdw_content_reply(h->server, request, fd); close(fd);
}
static void drag(void *data, MdwOutput *output, uint64_t offer, bool finished, bool accepted) {
    struct Host *h = data;
    assert(output == h->output && finished && accepted);
    assert(offer == (h->finishes == 0 ? 11 : h->offer));
    ++h->finishes;
}
int main(void) {
    char path[4096]; snprintf(path, sizeof(path), "%s/mdw-content-XXXXXX", getenv("TMPDIR") ? getenv("TMPDIR") : "/tmp");
    assert(mkdtemp(path) && setenv("XDG_RUNTIME_DIR", path, 1) == 0);
    struct Host h = {.server = mdw_server_create()}; assert(h.server);
    MdwEvents events = {.window = window, .content_offer = offer, .content_reply = reply,
        .content_request = request, .drag_event = drag, .context = &h};
    mdw_server_set_events(h.server, &events); mdw_content_enable(h.server, true);
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) { client(mdw_server_socket(h.server)); _exit(0); }
    while (!h.destroyed) {
        // EVENT_WAIT: Wayland requests/native callbacks; CTest deadline fails a stalled protocol.
        assert(mdw_server_dispatch(h.server, -1) >= 0);
        if (h.mapped && !h.output) { h.output = mdw_output_create(h.server, h.window, 32, 32); assert(h.output); assert(mdw_output_focus(h.output, true)); }
        if (h.clipboard && !h.done) {
            h.done = true; assert(mdw_content_publish(h.server, MDW_CONTENT_DRAG, 11, mime));
            mdw_content_drag(h.server, h.output, MDW_DRAG_ENTER, 11, 0, 0, false);
            mdw_content_drag(h.server, h.output, MDW_DRAG_MOVE, 11, .5, .5, false);
            mdw_content_drag(h.server, h.output, MDW_DRAG_DROP, 11, 0, 0, false);
        }
        if (h.external && !h.dragging) {
            assert(h.finishes == 1); h.dragging = true;
            // Clipboard admission must not suppress a pointer-owned drag offer.
            mdw_content_enable(h.server, false);
            assert(mdw_output_focus(h.output, true)); assert(mdw_output_pointer(h.output, .5, .5));
            assert(mdw_output_button(h.output, MDW_PRIMARY, true));
        }
        if (h.local && !h.closing && !h.destroyed) { assert(h.finishes == 2); h.closing = true; assert(mdw_window_close(h.server, h.window)); }
    }
    mdw_output_destroy(h.output); mdw_server_destroy(h.server);
    int status; assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(rmdir(path) == 0);
    puts("bidirectional clipboard, external and same-server single-delivery drag passed");
}
