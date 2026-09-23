#define _GNU_SOURCE
#include "wayland_server.h"
#include "xdg-shell-client-protocol.h"
#include "wlr-layer-shell-unstable-v1-client-protocol.h"
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include <wayland-client.h>

struct Client {
    struct wl_display *display;
    struct wl_compositor *compositor;
    struct wl_subcompositor *subcompositor;
    struct wl_shm *shm;
    struct xdg_wm_base *xdg;
    struct zwlr_layer_shell_v1 *shell;
    struct wl_seat *seat;
    struct wl_surface *panel, *child, *popup_surface, *entered;
    struct wl_subsurface *subsurface;
    struct xdg_surface *popup_base;
    struct xdg_popup *popup;
    struct zwlr_layer_surface_v1 *layer;
    int stage, repaints;
    bool closed;
};

static void release(void *data, struct wl_buffer *buffer) { (void)data; wl_buffer_destroy(buffer); }
static const struct wl_buffer_listener buffer_listener = {.release = release};
static void paint(struct Client *c, struct wl_surface *surface, int width, int height, uint32_t color) {
    int size = width * height * 4;
    int fd = syscall(SYS_memfd_create, "mdw-geometry", MFD_CLOEXEC);
    assert(fd >= 0 && ftruncate(fd, size) == 0);
    uint32_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(pixels != MAP_FAILED);
    for (int i = 0; i < width * height; ++i) pixels[i] = color;
    struct wl_shm_pool *pool = wl_shm_create_pool(c->shm, fd, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, width * 4, WL_SHM_FORMAT_ARGB8888);
    wl_buffer_add_listener(buffer, &buffer_listener, NULL);
    wl_shm_pool_destroy(pool);
    munmap(pixels, size);
    close(fd);
    wl_surface_attach(surface, buffer, 0, 0);
    wl_surface_damage_buffer(surface, 0, 0, width, height);
    wl_surface_commit(surface);
}

static void popup_configure(void *data, struct xdg_popup *popup, int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)data; (void)popup;
    // Requested x=-10 is constrained by the logical output, five units left of the panel.
    assert(x == -5 && y == 24 && width == 30 && height == 20);
}
static const struct xdg_popup_listener popup_listener = {.configure = popup_configure};
static void popup_surface_configure(void *data, struct xdg_surface *surface, uint32_t serial) {
    struct Client *c = data;
    xdg_surface_ack_configure(surface, serial);
    paint(c, c->popup_surface, 30, 20, 0xffe06020);
}
static const struct xdg_surface_listener popup_surface_listener = {.configure = popup_surface_configure};

static void panel_configure(void *data, struct zwlr_layer_surface_v1 *layer, uint32_t serial, uint32_t width, uint32_t height) {
    struct Client *c = data;
    assert(width == 64 && height == 24 && !c->popup);
    zwlr_layer_surface_v1_ack_configure(layer, serial);
    struct wl_region *input = wl_compositor_create_region(c->compositor);
    wl_region_add(input, 0, 0, 8, 24);
    wl_region_add(input, 56, 0, 8, 24);
    wl_surface_set_input_region(c->panel, input);
    wl_region_destroy(input);
    c->child = wl_compositor_create_surface(c->compositor);
    c->subsurface = wl_subcompositor_get_subsurface(c->subcompositor, c->child, c->panel);
    wl_subsurface_set_position(c->subsurface, -5, -6);
    paint(c, c->child, 10, 8, 0xff208040);
    paint(c, c->panel, 64, 24, 0xff123456);
    c->popup_surface = wl_compositor_create_surface(c->compositor);
    c->popup_base = xdg_wm_base_get_xdg_surface(c->xdg, c->popup_surface);
    xdg_surface_add_listener(c->popup_base, &popup_surface_listener, c);
    struct xdg_positioner *positioner = xdg_wm_base_create_positioner(c->xdg);
    xdg_positioner_set_size(positioner, 30, 20);
    xdg_positioner_set_anchor_rect(positioner, 0, 0, 10, 24);
    xdg_positioner_set_anchor(positioner, XDG_POSITIONER_ANCHOR_BOTTOM_LEFT);
    xdg_positioner_set_gravity(positioner, XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT);
    xdg_positioner_set_offset(positioner, -10, 0);
    xdg_positioner_set_constraint_adjustment(positioner, XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_X |
        XDG_POSITIONER_CONSTRAINT_ADJUSTMENT_SLIDE_Y);
    c->popup = xdg_surface_get_popup(c->popup_base, NULL, positioner);
    xdg_positioner_destroy(positioner);
    zwlr_layer_surface_v1_get_popup(c->layer, c->popup);
    xdg_popup_add_listener(c->popup, &popup_listener, c);
    wl_surface_commit(c->popup_surface);
}
static void panel_closed(void *data, struct zwlr_layer_surface_v1 *layer) {
    (void)layer;
    struct Client *c = data;
    assert(c->stage == 5 && c->repaints == 3);
    c->closed = true;
}
static const struct zwlr_layer_surface_v1_listener panel_listener = {panel_configure, panel_closed};

static void repaint(void *data, struct wl_callback *callback, uint32_t time);
static const struct wl_callback_listener frame_listener = {.done = repaint};
static void repaint(void *data, struct wl_callback *callback, uint32_t time) {
    (void)time;
    struct Client *c = data;
    if (callback) wl_callback_destroy(callback);
    if (++c->repaints < 3) wl_callback_add_listener(wl_surface_frame(c->panel), &frame_listener, c);
    paint(c, c->panel, 64, 24, c->repaints == 3 ? 0xff778899 : 0xff123456);
}
static void enter(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface, wl_fixed_t x, wl_fixed_t y) {
    (void)pointer; (void)serial; (void)x; (void)y;
    ((struct Client *)data)->entered = surface;
}
static void leave(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface) {
    (void)data; (void)pointer; (void)serial; (void)surface;
}
static void motion(void *data, struct wl_pointer *pointer, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    (void)data; (void)pointer; (void)time; (void)x; (void)y;
}
static void button(void *data, struct wl_pointer *pointer, uint32_t serial, uint32_t time, uint32_t key, uint32_t state) {
    (void)pointer; (void)serial; (void)time; (void)key;
    struct Client *c = data;
    if (state != WL_POINTER_BUTTON_STATE_RELEASED) return;
    if (c->stage++ == 0) {
        assert(c->entered == c->popup_surface);
        xdg_popup_destroy(c->popup);
        xdg_surface_destroy(c->popup_base);
        wl_surface_destroy(c->popup_surface);
        wl_subsurface_set_position(c->subsurface, 70, 0);
        wl_surface_commit(c->panel);
    } else if (c->stage == 2) {
        assert(c->entered == c->child);
        wl_surface_set_input_region(c->panel, NULL);
        wl_surface_commit(c->panel);
    } else if (c->stage == 3) {
        assert(c->entered == c->panel);
        struct wl_region *input = wl_compositor_create_region(c->compositor);
        for (int y = 0; y < 24; ++y)
            for (int x = y % 2; x < 64; x += 2) wl_region_add(input, x, y, 1, 1);
        wl_surface_set_input_region(c->panel, input);
        wl_region_destroy(input);
        wl_surface_commit(c->panel);
    } else if (c->stage == 4) {
        assert(c->entered == c->panel);
        wl_surface_set_input_region(c->panel, NULL);
        wl_surface_commit(c->panel);
    } else {
        assert(c->entered == c->panel && c->stage == 5);
        repaint(c, NULL, 0);
    }
}
static const struct wl_pointer_listener pointer_listener = {.enter = enter, .leave = leave, .motion = motion, .button = button};
static void caps(void *data, struct wl_seat *seat, uint32_t capabilities) {
    if (capabilities & WL_SEAT_CAPABILITY_POINTER)
        wl_pointer_add_listener(wl_seat_get_pointer(seat), &pointer_listener, data);
}
static const struct wl_seat_listener seat_listener = {.capabilities = caps};
static void ping(void *data, struct xdg_wm_base *xdg, uint32_t serial) { (void)data; xdg_wm_base_pong(xdg, serial); }
static const struct xdg_wm_base_listener xdg_listener = {.ping = ping};
static void global(void *data, struct wl_registry *registry, uint32_t id, const char *interface, uint32_t version) {
    (void)version;
    struct Client *c = data;
    if (!strcmp(interface, "wl_compositor")) c->compositor = wl_registry_bind(registry, id, &wl_compositor_interface, 4);
    else if (!strcmp(interface, "wl_subcompositor")) c->subcompositor = wl_registry_bind(registry, id, &wl_subcompositor_interface, 1);
    else if (!strcmp(interface, "wl_shm")) c->shm = wl_registry_bind(registry, id, &wl_shm_interface, 1);
    else if (!strcmp(interface, "zwlr_layer_shell_v1")) c->shell = wl_registry_bind(registry, id, &zwlr_layer_shell_v1_interface, 4);
    else if (!strcmp(interface, "xdg_wm_base")) {
        c->xdg = wl_registry_bind(registry, id, &xdg_wm_base_interface, 3);
        xdg_wm_base_add_listener(c->xdg, &xdg_listener, c);
    } else if (!strcmp(interface, "wl_seat")) {
        c->seat = wl_registry_bind(registry, id, &wl_seat_interface, 1);
        wl_seat_add_listener(c->seat, &seat_listener, c);
    }
}
static const struct wl_registry_listener registry_listener = {.global = global};
static void client(const char *socket) {
    struct Client c = {.display = wl_display_connect(socket)};
    assert(c.display);
    wl_registry_add_listener(wl_display_get_registry(c.display), &registry_listener, &c);
    assert(wl_display_roundtrip(c.display) >= 0 && wl_display_roundtrip(c.display) >= 0);
    assert(c.compositor && c.subcompositor && c.shm && c.xdg && c.shell && c.seat);
    c.panel = wl_compositor_create_surface(c.compositor);
    c.layer = zwlr_layer_shell_v1_get_layer_surface(c.shell, c.panel, NULL, ZWLR_LAYER_SHELL_V1_LAYER_TOP, "geometry");
    zwlr_layer_surface_v1_add_listener(c.layer, &panel_listener, &c);
    zwlr_layer_surface_v1_set_size(c.layer, 64, 24);
    wl_surface_commit(c.panel);
    while (!c.closed) assert(wl_display_dispatch(c.display) >= 0);
    wl_display_disconnect(c.display);
}

struct Host {
    MdwServer *server;
    uint64_t panel;
    MdwOutput *output;
    MdwViewGeometry geometry;
    MdwRect input[MDW_MAX_INPUT_RECTS];
    int stage, geometry_events, steady_events;
    bool destroyed, popup_pixels, popup_clicked, repainted;
};
static void shell(void *data, uint64_t id, const MdwShellSurface *surface) {
    struct Host *h = data;
    h->panel = id;
    if (!surface) { h->destroyed = true; return; }
    if (surface->configure_needed) assert(mdw_shell_surface_configure(h->server, id, 5, 100, 64, 24));
}
static void geometry(void *data, const MdwViewGeometry *geometry) {
    struct Host *h = data;
    assert(geometry->id == h->panel);
    h->geometry = *geometry;
    memcpy(h->input, geometry->input, geometry->input_count * sizeof(MdwRect));
    h->geometry.input = h->input;
    h->geometry_events++;
}
static bool contains(struct Host *h, int x, int y) {
    for (size_t i = 0; i < h->geometry.input_count; ++i) {
        MdwRect r = h->input[i];
        if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return true;
    }
    return false;
}
static void frame(void *data, MdwOutput *output, const MdwFrame *frame) {
    (void)output;
    struct Host *h = data;
    if (!frame) return;
    const uint32_t *pixel = (const void *)((const char *)frame->pixels + 35 * frame->stride + 5 * 4);
    if (h->stage == 1 && *pixel == 0xffe06020) h->popup_pixels = true;
    pixel = (const void *)((const char *)frame->pixels + 12 * frame->stride + 20 * 4);
    if (h->stage == 5 && *pixel == 0xff778899) h->repainted = true;
}
static void error(void *data, const char *message) { (void)data; fprintf(stderr, "%s\n", message); abort(); }
static void click(struct Host *h, double x, double y) {
    assert(mdw_output_pointer(h->output, x, y));
    assert(mdw_output_button(h->output, MDW_PRIMARY, true));
    assert(mdw_output_button(h->output, MDW_PRIMARY, false));
}

int main(void) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/mdw-geometry-XXXXXX", getenv("TMPDIR") ? getenv("TMPDIR") : "/tmp");
    assert(mkdtemp(path) && setenv("XDG_RUNTIME_DIR", path, 1) == 0);
    struct Host h = {.server = mdw_server_create()};
    assert(h.server);
    MdwEvents events = {.shell = shell, .geometry = geometry, .frame = frame, .error = error, .context = &h};
    mdw_server_set_events(h.server, &events);
    assert(mdw_server_shell_output(h.server, 800, 600));
    pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) { client(mdw_server_socket(h.server)); _exit(0); }
    while (!h.destroyed) {
        assert(mdw_server_dispatch(h.server, -1) >= 0);
        MdwRect p = h.geometry.paint;
        if (h.stage == 0 && p.left == -5 && p.top == -6 && p.right == 64 && p.bottom == 44) {
            assert(!contains(&h, 30, 12) && contains(&h, -3, 30) && contains(&h, -3, -3));
            h.output = mdw_output_create(h.server, h.panel, 74, 50);
            assert(h.output && mdw_output_viewport(h.output, -10, -6, 74, 50));
            assert(mdw_output_focus(h.output, true));
            assert(!mdw_output_pointer(h.output, 40.0 / 74, 18.0 / 50));
            h.stage = 1;
        } else if (h.stage == 1 && h.popup_pixels && !h.popup_clicked) {
            click(&h, 7.0 / 74, 36.0 / 50);
            h.popup_clicked = true;
        }
        if (h.stage == 1 && p.left == 0 && p.top == 0 && p.right == 80 && p.bottom == 24) {
            assert(!contains(&h, 30, 12) && contains(&h, 75, 3) && !contains(&h, -3, -3));
            assert(mdw_output_viewport(h.output, 0, 0, 80, 40));
            click(&h, 75.0 / 80, 3.0 / 40);
            h.stage = 2;
        } else if (h.stage == 2 && contains(&h, 30, 12)) {
            click(&h, 30.0 / 80, 12.0 / 40);
            h.stage = 3;
        } else if (h.stage == 3 && !h.geometry.input_complete) {
            assert(h.geometry.input_count == 0);
            click(&h, 0, 0);
            h.stage = 4;
        } else if (h.stage == 4 && h.geometry.input_complete && contains(&h, 30, 12)) {
            h.steady_events = h.geometry_events;
            click(&h, 30.0 / 80, 12.0 / 40);
            h.stage = 5;
        } else if (h.stage == 5 && h.repainted) {
            assert(h.geometry_events == h.steady_events);
            assert(mdw_window_close(h.server, h.panel));
            h.stage = 6;
        }
    }
    assert(h.stage == 6);
    mdw_output_destroy(h.output);
    mdw_server_destroy(h.server);
    int status;
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(rmdir(path) == 0);
    puts("popup constraints, subsurface movement, bounded input holes, viewport input and pixel-only metadata stability passed");
    return 0;
}
