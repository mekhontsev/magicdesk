#define _GNU_SOURCE
#include "wayland_server.h"
#include "xdg-shell-client-protocol.h"
#include <assert.h>
#include <drm_fourcc.h>
#include <fcntl.h>
#include "frame_fd.h"
#include <linux/input-event-codes.h>
#include <math.h>
#include <stdint.h>
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
    struct xdg_wm_base *shell;
    struct wl_surface *surface;
    struct xdg_surface *xdg_surface;
    struct xdg_toplevel *toplevel;
    struct wl_seat *seat;
    struct wl_pointer *pointer;
    struct wl_keyboard *keyboard;
    int key_down, key_up, button_down, button_up;
    int width, height, frames;
    bool closed;
};

static void ping(void *data, struct xdg_wm_base *shell, uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(shell, serial);
}
static const struct xdg_wm_base_listener shell_listener = { .ping = ping };

static void pointer_enter(void *data, struct wl_pointer *pointer, uint32_t serial,
        struct wl_surface *surface, wl_fixed_t x, wl_fixed_t y) {
    (void)pointer; (void)serial;
    struct Client *client = data;
    assert(surface == client->surface);
    assert(wl_fixed_to_double(x) >= 0 && wl_fixed_to_double(y) >= 0);
}
static void pointer_leave(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface) {
    (void)data; (void)pointer; (void)serial; (void)surface;
}
static void pointer_motion(void *data, struct wl_pointer *pointer, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    (void)data; (void)pointer; (void)time; (void)x; (void)y;
}
static void pointer_button(void *data, struct wl_pointer *pointer, uint32_t serial,
        uint32_t time, uint32_t button, uint32_t state) {
    (void)pointer; (void)serial; (void)time;
    struct Client *client = data;
    assert(button == BTN_LEFT);
    if (state == WL_POINTER_BUTTON_STATE_PRESSED) client->button_down++;
    else client->button_up++;
}
static void pointer_axis(void *data, struct wl_pointer *pointer, uint32_t time,
        uint32_t axis, wl_fixed_t value) {
    (void)data; (void)pointer; (void)time; (void)axis; (void)value;
}
static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_enter, .leave = pointer_leave, .motion = pointer_motion,
    .button = pointer_button, .axis = pointer_axis,
};

static void keyboard_keymap(void *data, struct wl_keyboard *keyboard, uint32_t format,
        int32_t descriptor, uint32_t size) {
    (void)data; (void)keyboard;
    assert(format == WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 && size > 0);
    close(descriptor);
}
static void keyboard_enter(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        struct wl_surface *surface, struct wl_array *keys) {
    (void)keyboard; (void)serial; (void)keys;
    struct Client *client = data;
    assert(surface == client->surface);
}
static void keyboard_leave(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        struct wl_surface *surface) {
    (void)data; (void)keyboard; (void)serial; (void)surface;
}
static void keyboard_key(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        uint32_t time, uint32_t key, uint32_t state) {
    (void)keyboard; (void)serial; (void)time;
    struct Client *client = data;
    assert(key == KEY_A);
    if (state == WL_KEYBOARD_KEY_STATE_PRESSED) client->key_down++;
    else client->key_up++;
}
static void keyboard_modifiers(void *data, struct wl_keyboard *keyboard, uint32_t serial,
        uint32_t depressed, uint32_t latched, uint32_t locked, uint32_t group) {
    (void)data; (void)keyboard; (void)serial; (void)depressed; (void)latched; (void)locked; (void)group;
}
static const struct wl_keyboard_listener keyboard_listener = {
    .keymap = keyboard_keymap, .enter = keyboard_enter, .leave = keyboard_leave,
    .key = keyboard_key, .modifiers = keyboard_modifiers,
};
static void seat_capabilities(void *data, struct wl_seat *seat, uint32_t capabilities) {
    struct Client *client = data;
    if ((capabilities & WL_SEAT_CAPABILITY_POINTER) && !client->pointer) {
        client->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(client->pointer, &pointer_listener, client);
    }
    if ((capabilities & WL_SEAT_CAPABILITY_KEYBOARD) && !client->keyboard) {
        client->keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(client->keyboard, &keyboard_listener, client);
    }
}
static const struct wl_seat_listener seat_listener = {.capabilities = seat_capabilities};

static void global(void *data, struct wl_registry *registry, uint32_t name,
        const char *interface, uint32_t version) {
    (void)version;
    struct Client *client = data;
    if (!strcmp(interface, "wl_compositor"))
        client->compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 4);
    else if (!strcmp(interface, "wl_shm"))
        client->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    else if (!strcmp(interface, "wl_seat")) {
        client->seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
        wl_seat_add_listener(client->seat, &seat_listener, client);
    }
    else if (!strcmp(interface, "xdg_wm_base")) {
        client->shell = wl_registry_bind(registry, name, &xdg_wm_base_interface, 2);
        xdg_wm_base_add_listener(client->shell, &shell_listener, client);
    }
}
static void global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}
static const struct wl_registry_listener registry_listener = {global, global_remove};

static void frame_done(void *data, struct wl_callback *callback, uint32_t time) {
    (void)time;
    struct Client *client = data;
    client->frames++;
    wl_callback_destroy(callback);
}
static const struct wl_callback_listener frame_listener = {frame_done};

static void buffer_release(void *data, struct wl_buffer *buffer) {
    (void)data;
    wl_buffer_destroy(buffer);
}
static const struct wl_buffer_listener buffer_listener = {buffer_release};

static void configure(void *data, struct xdg_surface *surface, uint32_t serial) {
    struct Client *client = data;
    xdg_surface_ack_configure(surface, serial);
    int stride = client->width * 4;
    size_t size = (size_t)stride * client->height;
    int descriptor = syscall(SYS_memfd_create, "mdw-client", MFD_CLOEXEC);
    assert(descriptor >= 0 && ftruncate(descriptor, size) == 0);
    uint32_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
    assert(pixels != MAP_FAILED);
    for (size_t index = 0; index < size / 4; ++index) pixels[index] = 0xff1267ab;
    struct wl_shm_pool *pool = wl_shm_create_pool(client->shm, descriptor, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, client->width,
        client->height, stride, WL_SHM_FORMAT_ARGB8888);
    wl_buffer_add_listener(buffer, &buffer_listener, NULL);
    wl_shm_pool_destroy(pool);
    close(descriptor);
    munmap(pixels, size);
    wl_surface_attach(client->surface, buffer, 0, 0);
    wl_surface_damage_buffer(client->surface, 0, 0, client->width, client->height);
    wl_callback_add_listener(wl_surface_frame(client->surface), &frame_listener, client);
    wl_surface_commit(client->surface);
}
static const struct xdg_surface_listener surface_listener = {configure};

static void toplevel_configure(void *data, struct xdg_toplevel *toplevel,
        int32_t width, int32_t height, struct wl_array *states) {
    (void)toplevel; (void)states;
    struct Client *client = data;
    client->width = width > 0 ? width : 80;
    client->height = height > 0 ? height : 60;
}
static void toplevel_close(void *data, struct xdg_toplevel *toplevel) {
    (void)toplevel;
    struct Client *client = data;
    assert(client->frames > 0);
    assert(client->key_down == 1 && client->key_up == 1);
    assert(client->button_down == 1 && client->button_up == 1);
    client->closed = true;
}
static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = toplevel_configure, .close = toplevel_close,
};

static void run_client(const char *socket) {
    struct Client client = {0};
    client.display = wl_display_connect(socket);
    assert(client.display);
    struct wl_registry *registry = wl_display_get_registry(client.display);
    wl_registry_add_listener(registry, &registry_listener, &client);
    assert(wl_display_roundtrip(client.display) >= 0);
    assert(wl_display_roundtrip(client.display) >= 0);
    assert(client.compositor && client.shm && client.shell && client.keyboard && client.pointer);
    client.surface = wl_compositor_create_surface(client.compositor);
    client.xdg_surface = xdg_wm_base_get_xdg_surface(client.shell, client.surface);
    xdg_surface_add_listener(client.xdg_surface, &surface_listener, &client);
    client.toplevel = xdg_surface_get_toplevel(client.xdg_surface);
    xdg_toplevel_add_listener(client.toplevel, &toplevel_listener, &client);
    xdg_toplevel_set_title(client.toplevel, "MagicDesk Wayland fixture");
    xdg_toplevel_set_app_id(client.toplevel, "io.magicdesk.fixture");
    wl_surface_commit(client.surface);
    while (!client.closed) assert(wl_display_dispatch(client.display) >= 0);
    xdg_toplevel_destroy(client.toplevel);
    xdg_surface_destroy(client.xdg_surface);
    wl_surface_destroy(client.surface);
    wl_pointer_destroy(client.pointer);
    wl_keyboard_destroy(client.keyboard);
    wl_seat_destroy(client.seat);
    xdg_wm_base_destroy(client.shell);
    wl_shm_destroy(client.shm);
    wl_compositor_destroy(client.compositor);
    wl_registry_destroy(registry);
    assert(wl_display_flush(client.display) >= 0);
    wl_display_disconnect(client.display);
}

struct Host {
    uint64_t window;
    bool mapped, destroyed;
    int frames;
};

static void window_event(void *data, uint64_t id, const MdwWindow *window) {
    struct Host *host = data;
    if (!window) { host->destroyed = true; return; }
    host->window = id;
    host->mapped = window->mapped;
    if (window->mapped) {
        assert(!strcmp(window->title, "MagicDesk Wayland fixture"));
        assert(!strcmp(window->app_id, "io.magicdesk.fixture"));
    }
}

static void frame_event(void *data, MdwOutput *output, const MdwFrame *frame) {
    (void)output;
    struct Host *host = data;
    if (!frame) return;
    assert(frame->width == 80 && frame->height == 60);
    uint32_t pixel;
    memcpy(&pixel, (const char *)frame->pixels + 20 * frame->stride + 20 * 4, 4);
    uint32_t expected;
    switch (frame->format) {
        case DRM_FORMAT_ARGB8888:
        case DRM_FORMAT_XRGB8888: expected = 0x001267ab; break;
        case DRM_FORMAT_ABGR8888:
        case DRM_FORMAT_XBGR8888: expected = 0x00ab6712; break;
        default: fprintf(stderr, "Unexpected frame format %08x\n", frame->format); abort();
    }
    if ((pixel & 0x00ffffff) != expected)
        fprintf(stderr, "format=%08x pixel=%08x expected=%08x\n", frame->format, pixel, expected);
    assert((pixel & 0x00ffffff) == expected);
    int descriptor = mdw_frame_export(frame);
    assert(descriptor >= 0);
    int seals = fcntl(descriptor, F_GET_SEALS);
    assert((seals & (F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK)) ==
        (F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK));
    uint8_t rgba[4];
    assert(pread(descriptor, rgba, 4, (20 * 80 + 20) * 4) == 4);
    assert(rgba[0] == 0x12 && rgba[1] == 0x67 && rgba[2] == 0xab && rgba[3] == 255);
    assert(pwrite(descriptor, rgba, 4, 0) < 0);
    close(descriptor);
    host->frames++;
}

static void error_event(void *data, const char *message) {
    (void)data;
    fprintf(stderr, "%s\n", message);
    abort();
}

int main(int argc, char **argv) {
    const char *temporary = getenv("TMPDIR");
    char directory[4096];
    snprintf(directory, sizeof(directory), "%s/mdw-window-XXXXXX", temporary ? temporary : "/tmp");
    assert(mkdtemp(directory));
    assert(setenv("XDG_RUNTIME_DIR", directory, 1) == 0);
    MdwServer *server = mdw_server_create();
    assert(server);
    assert(mdw_server_fd(server) >= 0);
    struct Host host = {0};
    MdwEvents events = {.window = window_event, .frame = frame_event, .error = error_event, .context = &host};
    mdw_server_set_events(server, &events);
    int connection = argc == 2 && !strcmp(argv[1], "--fd") ? mdw_server_connect(server) : -1;
    assert(argc == 1 || connection >= 0);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        assert(unsetenv("WAYLAND_SOCKET") == 0);
        if (connection >= 0) {
            assert((fcntl(connection, F_GETFD) & FD_CLOEXEC) != 0);
            char descriptor[32];
            snprintf(descriptor, sizeof(descriptor), "%d", connection);
            assert(setenv("WAYLAND_SOCKET", descriptor, 1) == 0);
            run_client(NULL);
        } else run_client(mdw_server_socket(server));
        _exit(0);
    }
    if (connection >= 0) close(connection);
    MdwOutput *output = NULL;
    bool detached = false, closing = false;
    while (!host.destroyed) {
        assert(mdw_server_dispatch(server, -1) >= 0);
        if (host.mapped && !output) {
            output = mdw_output_create(server, host.window, 80, 60);
            assert(output);
            assert(!mdw_output_resize(output, 0, 60));
        }
        if (host.frames >= 1 && !detached) {
            mdw_output_destroy(output);
            output = mdw_output_create(server, host.window, 80, 60);
            assert(output && !host.destroyed);
            detached = true;
        } else if (host.frames >= 2 && !closing) {
            assert(!mdw_output_key(output, KEY_A, true));
            assert(mdw_output_focus(output, true));
            assert(!mdw_output_pointer(output, NAN, .5));
            assert(mdw_output_pointer(output, .25, .25));
            assert(mdw_output_button(output, MDW_PRIMARY, true));
            assert(mdw_output_key(output, KEY_A, true));
            assert(mdw_output_focus(output, false));
            assert(!mdw_output_key(output, KEY_A, false));
            assert(mdw_window_close(server, host.window));
            closing = true;
        }
    }
    assert(!mdw_window_close(server, host.window));
    assert(!mdw_output_resize(output, 80, 60));
    mdw_output_destroy(output);
    mdw_server_destroy(server);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(rmdir(directory) == 0);
    puts("xdg-shell, software pixels, frame callbacks, detach/reattach and graceful close passed");
    return 0;
}