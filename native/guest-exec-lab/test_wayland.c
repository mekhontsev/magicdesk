#define _GNU_SOURCE
#include "xdg-shell-client-protocol.h"
#include <wayland-client.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s errno=%d\n", __FILE__, __LINE__, #x, errno); exit(1); } } while (0)
static struct wl_display *display;
static struct wl_compositor *compositor;
static struct wl_shm *shm;
static struct wl_seat *seat;
static struct wl_pointer *pointer;
static struct wl_keyboard *keyboard;
static struct xdg_wm_base *wm;
static struct wl_surface *surface;
static struct wl_buffer *buffer;
static int configured, closed, framed, released, buttons, keys;
enum { WIDTH = 640, HEIGHT = 400, BYTES = WIDTH * HEIGHT * 4 };

static void release_buffer(void *data, struct wl_buffer *value) {
    (void)data; (void)value; released++;
    puts("EVENT buffer released");
}
static const struct wl_buffer_listener buffer_listener = {.release = release_buffer};
static void frame_done(void *data, struct wl_callback *callback, uint32_t time) {
    (void)data; (void)time; wl_callback_destroy(callback); framed++;
    puts("EVENT frame callback (not Android pixel verification)");
}
static const struct wl_callback_listener frame_listener = {.done = frame_done};
static void configure_surface(void *data, struct xdg_surface *xdg, uint32_t serial) {
    (void)data; xdg_surface_ack_configure(xdg, serial);
    if (!configured++) {
        wl_callback_add_listener(wl_surface_frame(surface), &frame_listener, NULL);
        wl_surface_attach(surface, buffer, 0, 0);
        wl_surface_damage(surface, 0, 0, WIDTH, HEIGHT);
        wl_surface_commit(surface);
        puts("EVENT initial configure acknowledged; SHM buffer committed");
    }
}
static const struct xdg_surface_listener surface_listener = {.configure = configure_surface};
static void configure_top(void *data, struct xdg_toplevel *top, int32_t w, int32_t h, struct wl_array *states) {
    (void)data; (void)top; (void)states;
    printf("EVENT toplevel configure %dx%d\n", w, h);
}
static void close_top(void *data, struct xdg_toplevel *top) { (void)data; (void)top; closed = 1; }
static const struct xdg_toplevel_listener top_listener = {.configure = configure_top, .close = close_top};
static void ping(void *data, struct xdg_wm_base *base, uint32_t serial) {
    (void)data; xdg_wm_base_pong(base, serial);
}
static const struct xdg_wm_base_listener wm_listener = {.ping = ping};
static void pointer_enter(void *data, struct wl_pointer *p, uint32_t serial, struct wl_surface *s, wl_fixed_t x, wl_fixed_t y) {
    (void)data; (void)p; (void)serial; (void)s;
    printf("EVENT pointer enter %.1f %.1f\n", wl_fixed_to_double(x), wl_fixed_to_double(y));
}
static void pointer_leave(void *data, struct wl_pointer *p, uint32_t serial, struct wl_surface *s) {
    (void)data; (void)p; (void)serial; (void)s;
}
static void pointer_motion(void *data, struct wl_pointer *p, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    (void)data; (void)p; (void)time; (void)x; (void)y;
}
static void pointer_button(void *data, struct wl_pointer *p, uint32_t serial, uint32_t time, uint32_t button, uint32_t state) {
    (void)data; (void)p; (void)serial; (void)time;
    printf("EVENT pointer button %u %u\n", button, state);
    if (button == 272) buttons |= state == WL_POINTER_BUTTON_STATE_PRESSED ? 1 : 2;
}
static void pointer_axis(void *data, struct wl_pointer *p, uint32_t time, uint32_t axis, wl_fixed_t value) {
    (void)data; (void)p; (void)time; (void)axis; (void)value;
}
static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_enter, .leave = pointer_leave, .motion = pointer_motion,
    .button = pointer_button, .axis = pointer_axis,
};
static void keymap(void *data, struct wl_keyboard *k, uint32_t format, int32_t fd, uint32_t size) {
    (void)data; (void)k;
    CHECK(format == WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 && size > 0);
    void *map = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0); CHECK(map != MAP_FAILED);
    CHECK(!memcmp(map, "xkb_keymap", size < 10 ? size : 10));
    munmap(map, size); close(fd); puts("EVENT server keymap FD readable");
}
static void key_enter(void *data, struct wl_keyboard *k, uint32_t serial, struct wl_surface *s, struct wl_array *keys) {
    (void)data; (void)k; (void)serial; (void)s; (void)keys;
}
static void key_leave(void *data, struct wl_keyboard *k, uint32_t serial, struct wl_surface *s) {
    (void)data; (void)k; (void)serial; (void)s;
}
static void key(void *data, struct wl_keyboard *k, uint32_t serial, uint32_t time, uint32_t code, uint32_t state) {
    (void)data; (void)k; (void)serial; (void)time;
    printf("EVENT key %u %u\n", code, state);
    if (code == 30) keys |= state == WL_KEYBOARD_KEY_STATE_PRESSED ? 1 : 2;
}
static void modifiers(void *data, struct wl_keyboard *k, uint32_t serial, uint32_t dep, uint32_t lat, uint32_t lock, uint32_t group) {
    (void)data; (void)k; (void)serial; (void)dep; (void)lat; (void)lock; (void)group;
}
static const struct wl_keyboard_listener key_listener = {
    .keymap = keymap, .enter = key_enter, .leave = key_leave, .key = key, .modifiers = modifiers,
};
static void capabilities(void *data, struct wl_seat *s, uint32_t caps) {
    (void)data;
    if ((caps & WL_SEAT_CAPABILITY_POINTER) && !pointer) {
        pointer = wl_seat_get_pointer(s); wl_pointer_add_listener(pointer, &pointer_listener, NULL);
    }
    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !keyboard) {
        keyboard = wl_seat_get_keyboard(s); wl_keyboard_add_listener(keyboard, &key_listener, NULL);
    }
}
static const struct wl_seat_listener seat_listener = {.capabilities = capabilities};
static void global(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
    (void)data; (void)version;
    if (!strcmp(interface, "wl_compositor")) compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 1);
    if (!strcmp(interface, "wl_shm")) shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    if (!strcmp(interface, "xdg_wm_base")) {
        wm = wl_registry_bind(registry, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(wm, &wm_listener, NULL);
    }
    if (!strcmp(interface, "wl_seat")) {
        seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
        wl_seat_add_listener(seat, &seat_listener, NULL);
    }
}
static void removed(void *data, struct wl_registry *registry, uint32_t name) { (void)data; (void)registry; (void)name; }
static const struct wl_registry_listener registry_listener = {.global = global, .global_remove = removed};

int main(int argc, char **argv) {
    CHECK(argc == 3 && (!strcmp(argv[2], "memfd") || !strcmp(argv[2], "file")));
    setvbuf(stdout, NULL, _IOLBF, 0);
    CHECK(getuid() == 2000 && getenv("WAYLAND_SOCKET"));
    display = wl_display_connect(NULL); CHECK(display);
    struct ucred peer; socklen_t size = sizeof(peer);
    CHECK(getsockopt(wl_display_get_fd(display), SOL_SOCKET, SO_PEERCRED, &peer, &size) == 0);
    CHECK(peer.uid != 0 && peer.uid != getuid());
    printf("CONNECTED clientUid=%u peerUid=%u pid=%d\n", getuid(), peer.uid, getpid());
    struct wl_registry *registry = wl_display_get_registry(display);
    wl_registry_add_listener(registry, &registry_listener, NULL);
    /* EVENT_WAIT: registry and seat announcements; the invoking timeout cancels a silent server. */
    CHECK(wl_display_roundtrip(display) >= 0 && compositor && shm && wm);
    CHECK(wl_display_roundtrip(display) >= 0);
    int fd;
    if (!strcmp(argv[2], "memfd")) fd = (int)syscall(SYS_memfd_create, "md-debian-wayland", MFD_CLOEXEC);
    else {
        char path[] = "/tmp/md-wayland-XXXXXX";
        fd = mkostemp(path, O_CLOEXEC);
        CHECK(fd >= 0 && unlink(path) == 0);
    }
    CHECK(fd >= 0);
    CHECK(ftruncate(fd, BYTES) == 0);
    uint32_t *pixels = mmap(NULL, BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); CHECK(pixels != MAP_FAILED);
    const uint32_t colors[] = {0xffe03040, 0xff30c060, 0xff3060d0, 0xffe0c030};
    for (int y = 0; y < HEIGHT; ++y) for (int x = 0; x < WIDTH; ++x)
        pixels[y * WIDTH + x] = colors[(y >= HEIGHT / 2) * 2 + (x >= WIDTH / 2)];
    char label[256]; ssize_t n = fgetxattr(fd, "security.selinux", label, sizeof(label) - 1);
    if (n >= 0) { label[n] = 0; printf("BUFFER label=%s\n", label); }
    else printf("BUFFER label unavailable errno=%d\n", errno);
    struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, BYTES);
    buffer = wl_shm_pool_create_buffer(pool, 0, WIDTH, HEIGHT, WIDTH * 4, WL_SHM_FORMAT_XRGB8888);
    wl_buffer_add_listener(buffer, &buffer_listener, NULL);
    wl_shm_pool_destroy(pool); close(fd);
    CHECK(wl_display_roundtrip(display) >= 0);
    puts("EVENT SHM creation roundtrip completed");
    surface = wl_compositor_create_surface(compositor);
    struct xdg_surface *xdg = xdg_wm_base_get_xdg_surface(wm, surface);
    xdg_surface_add_listener(xdg, &surface_listener, NULL);
    struct xdg_toplevel *top = xdg_surface_get_toplevel(xdg);
    xdg_toplevel_add_listener(top, &top_listener, NULL);
    xdg_toplevel_set_title(top, argv[1]);
    xdg_toplevel_set_app_id(top, "org.magicdesk.GuestExecutionLab");
    xdg_toplevel_set_min_size(top, WIDTH, HEIGHT);
    xdg_toplevel_set_max_size(top, WIDTH, HEIGHT);
    wl_surface_commit(surface);
    /* EVENT_WAIT: configure/frame/input/close; caller bounds the complete fixture, not a settling delay. */
    while (!closed) CHECK(wl_display_dispatch(display) >= 0);
    CHECK(configured && framed && buttons == 3 && keys == 3);
    printf("PASS Wayland guest: protocol close, configured=%d frames=%d releases=%d\n", configured, framed, released);
    xdg_toplevel_destroy(top); xdg_surface_destroy(xdg); wl_surface_destroy(surface);
    wl_buffer_destroy(buffer); munmap(pixels, BYTES);
    if (pointer) wl_pointer_destroy(pointer);
    if (keyboard) wl_keyboard_destroy(keyboard);
    if (seat) wl_seat_destroy(seat);
    wl_shm_destroy(shm); wl_compositor_destroy(compositor); xdg_wm_base_destroy(wm);
    wl_registry_destroy(registry); wl_display_disconnect(display);
    return 0;
}
