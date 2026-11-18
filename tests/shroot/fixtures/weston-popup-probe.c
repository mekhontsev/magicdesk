// SPDX-License-Identifier: MIT
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <wayland-client.h>
#include "xdg-shell-client-protocol.h"

static struct wl_display *display;
static struct wl_compositor *compositor;
static struct wl_shm *shm;
static struct xdg_wm_base *wm;
static unsigned activated;
struct window {
    struct wl_surface *surface;
    struct xdg_surface *xdg;
    struct xdg_toplevel *toplevel;
    struct xdg_popup *popup;
    struct wl_buffer *buffer;
    bool configured;
};

static void ping(void *data, struct xdg_wm_base *base, uint32_t serial)
{
    (void)data;
    xdg_wm_base_pong(base, serial);
}
static const struct xdg_wm_base_listener wm_listener = {.ping = ping};
static void global(void *data, struct wl_registry *registry, uint32_t name,
                   const char *interface, uint32_t version)
{
    (void)data;
    if (strcmp(interface, wl_compositor_interface.name) == 0)
        compositor = wl_registry_bind(registry, name, &wl_compositor_interface, version < 4 ? version : 4);
    else if (strcmp(interface, wl_shm_interface.name) == 0)
        shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        wm = wl_registry_bind(registry, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(wm, &wm_listener, NULL);
    }
}
static void global_remove(void *data, struct wl_registry *registry, uint32_t name)
{
    (void)data; (void)registry; (void)name;
}
static const struct wl_registry_listener registry_listener = {global, global_remove};
static void configure(void *data, struct xdg_surface *xdg, uint32_t serial)
{
    struct window *w = data;
    xdg_surface_ack_configure(xdg, serial);
    wl_surface_attach(w->surface, w->buffer, 0, 0);
    wl_surface_damage(w->surface, 0, 0, 160, 120);
    wl_surface_commit(w->surface);
    w->configured = true;
}
static const struct xdg_surface_listener surface_listener = {configure};
static void top_configure(void *data, struct xdg_toplevel *top, int32_t width,
                          int32_t height, struct wl_array *states)
{
    (void)data; (void)top; (void)width; (void)height;
    uint32_t *state;
    wl_array_for_each(state, states)
        if (*state == XDG_TOPLEVEL_STATE_ACTIVATED)
            activated++;
}
static void top_close(void *data, struct xdg_toplevel *top) { (void)data; (void)top; }
static const struct xdg_toplevel_listener top_listener = {
    .configure = top_configure, .close = top_close,
};
static void popup_configure(void *data, struct xdg_popup *popup, int32_t x, int32_t y,
                            int32_t width, int32_t height)
{
    (void)data; (void)popup; (void)x; (void)y; (void)width; (void)height;
}
static void popup_done(void *data, struct xdg_popup *popup) { (void)data; (void)popup; }
static const struct xdg_popup_listener popup_listener = {
    .configure = popup_configure, .popup_done = popup_done,
};
static void create_window(struct window *w, struct window *parent, uint32_t color)
{
    int fd = memfd_create("popup-probe", MFD_CLOEXEC);
    const size_t size = 160 * 120 * 4;
    assert(fd >= 0 && ftruncate(fd, size) == 0);
    uint32_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(pixels != MAP_FAILED);
    for (size_t i = 0; i < size / 4; i++) pixels[i] = color;
    struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, size);
    w->buffer = wl_shm_pool_create_buffer(pool, 0, 160, 120, 640, WL_SHM_FORMAT_XRGB8888);
    wl_shm_pool_destroy(pool);
    munmap(pixels, size);
    close(fd);
    w->surface = wl_compositor_create_surface(compositor);
    w->xdg = xdg_wm_base_get_xdg_surface(wm, w->surface);
    xdg_surface_add_listener(w->xdg, &surface_listener, w);
    if (parent) {
        struct xdg_positioner *p = xdg_wm_base_create_positioner(wm);
        xdg_positioner_set_size(p, 160, 120);
        xdg_positioner_set_anchor_rect(p, 0, 0, 160, 120);
        w->popup = xdg_surface_get_popup(w->xdg, parent->xdg, p);
        xdg_popup_add_listener(w->popup, &popup_listener, w);
        xdg_positioner_destroy(p);
    } else {
        w->toplevel = xdg_surface_get_toplevel(w->xdg);
        xdg_toplevel_add_listener(w->toplevel, &top_listener, w);
        xdg_toplevel_set_title(w->toplevel, "Weston popup guard probe");
    }
    wl_surface_commit(w->surface);
    // EVENT_WAIT: compositor configure; the host enforces the failure deadline.
    while (!w->configured) assert(wl_display_dispatch(display) >= 0);
    assert(wl_display_roundtrip(display) >= 0);
}
int main(void)
{
    display = wl_display_connect(NULL);
    assert(display);
    struct wl_registry *registry = wl_display_get_registry(display);
    wl_registry_add_listener(registry, &registry_listener, NULL);
    assert(wl_display_roundtrip(display) >= 0 && compositor && shm && wm);
    struct window windows[3] = {0};
    create_window(&windows[0], NULL, 0xffd04040);
    create_window(&windows[1], &windows[0], 0xff40d040);
    create_window(&windows[2], NULL, 0xff4040d0);
    assert(wl_display_roundtrip(display) >= 0);
    printf("popup and focus handoff ready; activated=%u\n", activated);
    fflush(stdout);
    assert(activated >= 2);
    const char *ready = getenv("MD_POPUP_READY");
    assert(ready);
    int marker = open(ready, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    assert(marker >= 0);
    close(marker);
    struct pollfd fds[2] = {{wl_display_get_fd(display), POLLIN, 0}, {STDIN_FILENO, POLLIN, 0}};
    for (;;) {
        assert(wl_display_dispatch_pending(display) >= 0);
        wl_display_flush(display);
        // EVENT_WAIT: compositor or host control command; timeout is test failure.
        assert(poll(fds, 2, 60000) > 0);
        if (fds[1].revents) break;
        assert(wl_display_dispatch(display) >= 0);
    }
    for (int i = 2; i >= 0; i--) {
        if (windows[i].popup) xdg_popup_destroy(windows[i].popup);
        if (windows[i].toplevel) xdg_toplevel_destroy(windows[i].toplevel);
        xdg_surface_destroy(windows[i].xdg);
        wl_surface_destroy(windows[i].surface);
        wl_buffer_destroy(windows[i].buffer);
    }
    assert(wl_display_roundtrip(display) >= 0);
    wl_display_disconnect(display);
    return 0;
}
