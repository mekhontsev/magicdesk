#define _GNU_SOURCE
#include "wayland_server.h"
#include "frame_fd.h"
#include "xdg-shell-client-protocol.h"
#include "wlr-layer-shell-unstable-v1-client-protocol.h"
#include <assert.h>
#include <fcntl.h>
#include <linux/input-event-codes.h>
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
    struct wl_shm *shm;
    struct xdg_wm_base *xdg;
    struct zwlr_layer_shell_v1 *shell;
    struct wl_seat *seat;
    struct wl_keyboard *keyboard;
    struct wl_pointer *pointer;
    struct wl_surface *app, *panel, *keyboard_surface;
    struct xdg_surface *app_surface;
    struct xdg_toplevel *toplevel;
    struct zwlr_layer_surface_v1 *layer;
    int app_width, app_height;
    int app_keys, panel_keys, buttons, secondary_buttons, frames, configures, outputs;
    bool closed, workspace, home, app_closed;
};

static void buffer_release(void *data, struct wl_buffer *buffer) {
    (void)data;
    wl_buffer_destroy(buffer);
}
static const struct wl_buffer_listener buffer_listener = {buffer_release};

static void paint(struct Client *client, struct wl_surface *surface, int width, int height,
        uint32_t color) {
    size_t size = (size_t)width * height * 4;
    int fd = syscall(SYS_memfd_create, "mdw-shell-fixture", MFD_CLOEXEC);
    assert(fd >= 0 && ftruncate(fd, size) == 0);
    uint32_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(pixels != MAP_FAILED);
    for (size_t i = 0; i < size / 4; i++) pixels[i] = color;
    struct wl_shm_pool *pool = wl_shm_create_pool(client->shm, fd, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, width, height, width * 4,
        WL_SHM_FORMAT_ARGB8888);
    wl_buffer_add_listener(buffer, &buffer_listener, NULL);
    wl_shm_pool_destroy(pool);
    munmap(pixels, size);
    close(fd);
    wl_surface_attach(surface, buffer, 0, 0);
    wl_surface_damage_buffer(surface, 0, 0, width, height);
    wl_surface_commit(surface);
}

static void frame_done(void *data, struct wl_callback *callback, uint32_t time) {
    (void)time;
    struct Client *client = data;
    client->frames++;
    wl_callback_destroy(callback);
}
static const struct wl_callback_listener frame_listener = {frame_done};

static void configure_panel(struct Client *client) {
    if (client->home && client->configures > 0)
        zwlr_layer_surface_v1_set_layer(client->layer, ZWLR_LAYER_SHELL_V1_LAYER_BOTTOM);
    zwlr_layer_surface_v1_set_size(client->layer, 0, 24);
    zwlr_layer_surface_v1_set_anchor(client->layer, ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP |
        ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT | ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT);
    zwlr_layer_surface_v1_set_exclusive_zone(client->layer, 24);
    zwlr_layer_surface_v1_set_margin(client->layer, 3, 0, 0, 0);
    wl_surface_commit(client->panel);
}
static void remap_done(void *data, struct wl_callback *callback, uint32_t serial) {
    (void)serial;
    wl_callback_destroy(callback);
    configure_panel(data);
}
static const struct wl_callback_listener remap_listener = {remap_done};

static void layer_configure(void *data, struct zwlr_layer_surface_v1 *surface,
        uint32_t serial, uint32_t width, uint32_t height) {
    struct Client *client = data;
    assert((client->workspace ? width >= 64 : width == 64) && height == 24);
    client->configures++;
    zwlr_layer_surface_v1_ack_configure(surface, serial);
    struct wl_region *input = wl_compositor_create_region(client->compositor);
    wl_region_add(input, 0, 0, 16, 24);
    wl_region_add(input, 48, 0, 16, 24);
    wl_surface_set_input_region(client->panel, input);
    wl_region_destroy(input);
    wl_callback_add_listener(wl_surface_frame(client->panel), &frame_listener, client);
    paint(client, client->panel, width, height, 0x80402010);
}
static void layer_closed(void *data, struct zwlr_layer_surface_v1 *surface) {
    (void)surface;
    struct Client *client = data;
    if (client->workspace) {
        fprintf(stderr, "workspace client: keys=%d/%d buttons=%d frames=%d configures=%d\n",
            client->app_keys, client->panel_keys, client->buttons, client->frames, client->configures);
        assert(client->app_keys == 2 && client->panel_keys == 0 && client->buttons == 6);
        assert(client->secondary_buttons == (client->home ? 6 : 0));
        // Moving a layer surface does not reconfigure its unchanged buffer size.
        assert(client->frames > 0 && client->configures == 2);
        client->closed = true;
        return;
    }
    assert(client->app_keys == 2 && client->panel_keys == 2 && client->buttons == 2);
    assert(client->frames > 0 && client->configures == 2);
    client->closed = true;
}
static const struct zwlr_layer_surface_v1_listener layer_listener = {layer_configure, layer_closed};

static void ping(void *data, struct xdg_wm_base *xdg, uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(xdg, serial);
}
static const struct xdg_wm_base_listener xdg_listener = {ping};
static void app_configure(void *data, struct xdg_surface *surface, uint32_t serial) {
    struct Client *client = data;
    xdg_surface_ack_configure(surface, serial);
    paint(client, client->app, client->app_width, client->app_height, 0xff204060);
}
static const struct xdg_surface_listener app_listener = {app_configure};
static void toplevel_configure(void *data, struct xdg_toplevel *toplevel,
        int32_t width, int32_t height, struct wl_array *states) {
    (void)toplevel; (void)states;
    struct Client *client = data;
    client->app_width = width > 0 ? width : 80;
    client->app_height = height > 0 ? height : 60;
}
static void toplevel_close(void *data, struct xdg_toplevel *toplevel) {
    (void)toplevel;
    struct Client *client = data;
    assert(client->workspace);
    client->app_closed = true;
}
static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = toplevel_configure, .close = toplevel_close,
};

static void pointer_enter(void *data, struct wl_pointer *pointer, uint32_t serial,
        struct wl_surface *surface, wl_fixed_t x, wl_fixed_t y) {
    (void)pointer; (void)serial; (void)x; (void)y;
    assert(surface == ((struct Client *)data)->panel);
}
static void pointer_leave(void *data, struct wl_pointer *pointer, uint32_t serial,
        struct wl_surface *surface) {
    (void)data; (void)pointer; (void)serial; (void)surface;
}
static void pointer_motion(void *data, struct wl_pointer *pointer, uint32_t time,
        wl_fixed_t x, wl_fixed_t y) {
    (void)data; (void)pointer; (void)time; (void)x; (void)y;
}
static void pointer_button(void *data, struct wl_pointer *pointer, uint32_t serial,
        uint32_t time, uint32_t button, uint32_t state) {
    (void)pointer; (void)serial; (void)time;
    struct Client *client = data;
    if (client->home && button == BTN_RIGHT) { client->secondary_buttons++; return; }
    assert(button == BTN_LEFT);
    client->buttons++;
    if (state == WL_POINTER_BUTTON_STATE_RELEASED) {
        if (client->workspace && client->buttons == 2) {
            zwlr_layer_surface_v1_set_margin(client->layer, 13, 0, 0, 0);
            wl_surface_commit(client->panel);
            return;
        }
        if (client->workspace && client->buttons == 4) {
            wl_surface_attach(client->panel, NULL, 0, 0);
            wl_surface_commit(client->panel);
            wl_callback_add_listener(wl_display_sync(client->display), &remap_listener, client);
            return;
        }
        zwlr_layer_surface_v1_set_keyboard_interactivity(client->layer,
            client->workspace ? ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_EXCLUSIVE :
                ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ON_DEMAND);
        wl_surface_commit(client->panel);
    }
}
static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_enter, .leave = pointer_leave, .motion = pointer_motion,
    .button = pointer_button,
};
static void keyboard_keymap(void *data, struct wl_keyboard *keyboard, uint32_t format,
        int32_t fd, uint32_t size) {
    (void)data; (void)keyboard; (void)format; (void)size;
    close(fd);
}
static void keyboard_enter(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        struct wl_surface *surface, struct wl_array *keys) {
    (void)keyboard; (void)serial; (void)keys;
    ((struct Client *)data)->keyboard_surface = surface;
}
static void keyboard_leave(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        struct wl_surface *surface) {
    (void)keyboard; (void)serial; (void)surface;
    ((struct Client *)data)->keyboard_surface = NULL;
}
static void keyboard_modifiers(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group) {
    (void)data; (void)keyboard; (void)serial; (void)depressed; (void)latched; (void)locked; (void)group;
}
static void keyboard_key(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        uint32_t time, uint32_t key, uint32_t state) {
    (void)keyboard; (void)serial; (void)time;
    struct Client *client = data;
    if (key == KEY_A) {
        assert(client->keyboard_surface == client->app);
        client->app_keys++;
    } else {
        assert(key == KEY_B && client->keyboard_surface == client->panel);
        client->panel_keys++;
        if (state == WL_KEYBOARD_KEY_STATE_PRESSED) {
            zwlr_layer_surface_v1_set_keyboard_interactivity(client->layer,
                ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_NONE);
            wl_surface_commit(client->panel);
        } else {
            wl_surface_attach(client->panel, NULL, 0, 0);
            wl_surface_commit(client->panel);
            wl_callback_add_listener(wl_display_sync(client->display), &remap_listener, client);
        }
    }
}
static const struct wl_keyboard_listener keyboard_listener = {
    .keymap = keyboard_keymap, .enter = keyboard_enter, .leave = keyboard_leave,
    .key = keyboard_key, .modifiers = keyboard_modifiers,
};
static void capabilities(void *data, struct wl_seat *seat, uint32_t caps) {
    struct Client *client = data;
    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !client->pointer) {
        client->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(client->pointer, &pointer_listener, client);
    }
    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !client->keyboard) {
        client->keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(client->keyboard, &keyboard_listener, client);
    }
}
static const struct wl_seat_listener seat_listener = {.capabilities = capabilities};
static void global(void *data, struct wl_registry *registry, uint32_t name,
        const char *interface, uint32_t version) {
    (void)version;
    struct Client *client = data;
    if (!strcmp(interface, "wl_compositor"))
        client->compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 4);
    else if (!strcmp(interface, "wl_shm"))
        client->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    else if (!strcmp(interface, "zwlr_layer_shell_v1"))
        client->shell = wl_registry_bind(registry, name, &zwlr_layer_shell_v1_interface, 4);
    else if (!strcmp(interface, "xdg_wm_base")) {
        client->xdg = wl_registry_bind(registry, name, &xdg_wm_base_interface, 2);
        xdg_wm_base_add_listener(client->xdg, &xdg_listener, client);
    } else if (!strcmp(interface, "wl_seat")) {
        client->seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
        wl_seat_add_listener(client->seat, &seat_listener, client);
    } else if (!strcmp(interface, "wl_output")) client->outputs++;
}
static void global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}
static const struct wl_registry_listener registry_listener = {global, global_remove};

static void run_client(const char *socket, bool workspace, bool home) {
    struct Client client = {.workspace = workspace, .home = home};
    client.display = wl_display_connect(socket);
    assert(client.display);
    struct wl_registry *registry = wl_display_get_registry(client.display);
    wl_registry_add_listener(registry, &registry_listener, &client);
    assert(wl_display_roundtrip(client.display) >= 0);
    assert(wl_display_roundtrip(client.display) >= 0);
    assert(client.compositor && client.shm && client.shell && client.xdg && client.outputs == 1);
    client.app = wl_compositor_create_surface(client.compositor);
    client.app_surface = xdg_wm_base_get_xdg_surface(client.xdg, client.app);
    xdg_surface_add_listener(client.app_surface, &app_listener, &client);
    client.toplevel = xdg_surface_get_toplevel(client.app_surface);
    xdg_toplevel_add_listener(client.toplevel, &toplevel_listener, &client);
    wl_surface_commit(client.app);
    client.panel = wl_compositor_create_surface(client.compositor);
    client.layer = zwlr_layer_shell_v1_get_layer_surface(client.shell, client.panel, NULL,
        home ? ZWLR_LAYER_SHELL_V1_LAYER_BACKGROUND : ZWLR_LAYER_SHELL_V1_LAYER_TOP, "fixture-panel");
    zwlr_layer_surface_v1_add_listener(client.layer, &layer_listener, &client);
    configure_panel(&client);
    while (!client.closed) assert(wl_display_dispatch(client.display) >= 0);
    zwlr_layer_surface_v1_destroy(client.layer);
    wl_surface_destroy(client.panel);
    if (workspace) while (!client.app_closed) assert(wl_display_dispatch(client.display) >= 0);
    xdg_toplevel_destroy(client.toplevel);
    xdg_surface_destroy(client.app_surface);
    wl_surface_destroy(client.app);
    assert(wl_display_roundtrip(client.display) >= 0);
    wl_display_disconnect(client.display);
}

struct Host {
    MdwServer *server;
    uint64_t window, panel;
    bool window_mapped, panel_mapped, window_destroyed, panel_destroyed;
    MdwKeyboard keyboard;
    MdwOutput *app_output, *panel_output;
    int frames, mappings, render_attempts;
};
static void window_event(void *data, uint64_t id, const MdwWindow *window) {
    struct Host *host = data;
    assert(!host->window || id == host->window);
    host->window = id;
    host->window_mapped = window && window->mapped;
    if (!window) host->window_destroyed = true;
}
static void shell_event(void *data, uint64_t id, const MdwShellSurface *surface) {
    struct Host *host = data;
    assert(!host->panel || host->panel == id);
    host->panel = id;
    if (surface && surface->mapped && !host->panel_mapped) host->mappings++;
    host->panel_mapped = surface && surface->mapped;
    if (!surface) { host->panel_destroyed = true; return; }
    assert(!strcmp(surface->name, "fixture-panel"));
    assert(surface->layer == MDW_TOP && surface->width == 0 && surface->height == 24);
    assert(surface->anchors == (MDW_ANCHOR_LEFT | MDW_ANCHOR_TOP | MDW_ANCHOR_RIGHT));
    assert(surface->exclusive_zone == 24 && surface->margin_top == 3);
    host->keyboard = surface->keyboard;
    assert(mdw_shell_surface_configure(host->server, id, 0, 3, 64, 24));
    assert(mdw_shell_surface_configure(host->server, id, 0, 3, 64, 24));
}
static void frame_event(void *data, MdwOutput *output, const MdwFrame *frame) {
    struct Host *host = data;
    if (!frame || output != host->panel_output) return;
    assert(frame->width == 80 && frame->height == 40);
    int fd = mdw_frame_export(frame);
    assert(fd >= 0);
    uint8_t pixel[4];
    assert(pread(fd, pixel, 4, 0) == 4);
    assert(pixel[0] == 0x40 && pixel[1] == 0x20 && pixel[2] == 0x10 && pixel[3] == 0x80);
    assert(pread(fd, pixel, 4, (35 * 80 + 70) * 4) == 4);
    assert(pixel[0] == 0 && pixel[1] == 0 && pixel[2] == 0 && pixel[3] == 0);
    close(fd);
    host->frames++;
}
static void error_event(void *data, const char *message) {
    (void)data;
    fprintf(stderr, "%s\n", message);
    abort();
}

static bool can_render(void *data, MdwOutput *output) {
    struct Host *host = data;
    if (output == host->panel_output) host->render_attempts++;
    return true;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--client")) { run_client(NULL, false, false); return 0; }
    if (argc == 2 && !strcmp(argv[1], "--workspace-client")) { run_client(NULL, true, false); return 0; }
    if (argc == 2 && !strcmp(argv[1], "--home-client")) { run_client(NULL, true, true); return 0; }
    char directory[4096];
    snprintf(directory, sizeof(directory), "%s/mdw-shell-XXXXXX", getenv("TMPDIR") ? getenv("TMPDIR") : "/tmp");
    assert(mkdtemp(directory) && setenv("XDG_RUNTIME_DIR", directory, 1) == 0);
    struct Host host = {.server = mdw_server_create()};
    assert(host.server);
    assert(!mdw_server_shell_output(host.server, 800, 600));
    MdwEvents events = {.window = window_event, .shell = shell_event, .frame = frame_event,
        .can_render = can_render, .error = error_event, .context = &host};
    mdw_server_set_events(host.server, &events);
    assert(mdw_server_shell_output(host.server, 800, 600));
    assert(mdw_server_shell_output(host.server, 900, 700));
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) { run_client(mdw_server_socket(host.server), false, false); _exit(0); }
    int stage = 0;
    while (!host.window_destroyed) {
        assert(mdw_server_dispatch(host.server, -1) >= 0);
        if (host.window_mapped && !host.app_output)
            assert((host.app_output = mdw_output_create(host.server, host.window, 80, 60)));
        if (host.panel_mapped && !host.panel_output)
            assert((host.panel_output = mdw_output_create(host.server, host.panel, 80, 40)));
        if (stage == 0 && host.app_output && host.panel_output && host.render_attempts >= 2) {
            assert(host.frames == 1);
            assert(mdw_output_focus(host.app_output, true));
            assert(mdw_output_key(host.app_output, KEY_A, true));
            assert(mdw_output_pointer(host.panel_output, .1, .1));
            assert(!mdw_output_key(host.panel_output, KEY_B, true));
            assert(mdw_output_key(host.app_output, KEY_A, false));
            assert(mdw_output_pointer(host.panel_output, .1, .1));
            assert(mdw_output_button(host.panel_output, MDW_PRIMARY, true));
            assert(mdw_output_button(host.panel_output, MDW_PRIMARY, false));
            stage = 1;
        } else if (stage == 1 && host.keyboard == MDW_KEYBOARD_ON_DEMAND) {
            assert(mdw_output_focus(host.panel_output, true));
            assert(mdw_output_key(host.panel_output, KEY_B, true));
            stage = 2;
        } else if (stage == 2 && host.keyboard == MDW_KEYBOARD_NONE && host.mappings == 2 && host.frames >= 2) {
            assert(!mdw_output_key(host.panel_output, KEY_B, false));
            assert(mdw_server_shell_output(host.server, 0, 0));
            assert(host.panel_destroyed && !host.window_destroyed);
            assert(!mdw_output_resize(host.panel_output, 80, 40));
            stage = 3;
        }
    }
    assert(stage == 3);
    mdw_output_destroy(host.app_output);
    mdw_output_destroy(host.panel_output);
    mdw_server_destroy(host.server);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(rmdir(directory) == 0);
    puts("layer-shell configure, transparent frames, independent pointer/keyboard and scope revocation passed");
    return 0;
}
