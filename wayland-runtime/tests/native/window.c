#define _GNU_SOURCE
#define WLR_USE_UNSTABLE
#include "wayland_internal.h"
#include <wlr/types/wlr_output.h>
#include "xdg-shell-client-protocol.h"
#include "text-input-unstable-v3-client-protocol.h"
#include "linux-dmabuf-v1-client-protocol.h"
#include "pointer-constraints-unstable-v1-client-protocol.h"
#include "relative-pointer-unstable-v1-client-protocol.h"
#include "single-pixel-buffer-v1-client-protocol.h"
#include "viewporter-client-protocol.h"
#include "presentation-time-client-protocol.h"
#ifdef __ANDROID__
#include "dmabuf_producer.h"
#endif
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
#include <time.h>
#include <unistd.h>
#include <wayland-client.h>

static bool fixed_client_size;
static bool minimum_client_size, bounded_client_size;
static bool interaction;
static bool dma_client;
static bool dma_cursor;
static bool pointer_lock_test;
static bool single_pixel;
static bool pacing;
enum { PACING_FRAMES = 120 };
static int64_t pacing_times[PACING_FRAMES];

static int64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000 + now.tv_nsec;
}

struct Client {
    struct wl_display *display;
    struct wl_compositor *compositor;
    struct wl_shm *shm;
    struct wp_single_pixel_buffer_manager_v1 *single_pixel_manager;
    struct wp_viewporter *viewporter;
    struct wp_viewport *viewport;
    struct wp_presentation *presentation;
    bool presentation_clock;
    int presented;
    struct zwp_linux_dmabuf_v1 *dma;
    struct xdg_wm_base *shell;
    struct wl_surface *surface;
    struct xdg_surface *xdg_surface;
    struct xdg_toplevel *toplevel;
    struct wl_seat *seat;
    struct wl_pointer *pointer;
    struct zwp_pointer_constraints_v1 *constraints;
    struct zwp_relative_pointer_manager_v1 *relative_manager;
    struct zwp_relative_pointer_v1 *relative;
    struct zwp_locked_pointer_v1 *lock;
    int locks, unlocks, relative_events;
    bool pointer_locked, relative_pending;
    struct wl_keyboard *keyboard;
    struct zwp_text_input_manager_v3 *text_manager;
    struct zwp_text_input_v3 *text;
    struct wl_surface *cursor;
    struct wl_buffer *cursor_buffer;
    int cursor_stage;
    bool preedit, committed, fullscreen, maximized, deleted;
    bool data_device_manager;
    int key_down, key_up, button_down, button_up;
    int width, height, frames;
    bool pacing_started;
    bool closed;
    uint32_t initial_monitor;
    int monitors;
};

static void ping(void *data, struct xdg_wm_base *shell, uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(shell, serial);
}
static const struct xdg_wm_base_listener shell_listener = { .ping = ping };

static void text_enter(void *data, struct zwp_text_input_v3 *text, struct wl_surface *surface) {
    struct Client *client = data;
    assert(surface == client->surface);
    zwp_text_input_v3_enable(text);
    zwp_text_input_v3_commit(text);
    // Optional context can arrive after the initial empty editor was enabled.
    zwp_text_input_v3_set_text_change_cause(text, ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_OTHER);
    zwp_text_input_v3_set_surrounding_text(text, "a\xd0\x96\xf0\x9f\x98\x80z", 7, 3);
    zwp_text_input_v3_set_content_type(text, ZWP_TEXT_INPUT_V3_CONTENT_HINT_COMPLETION,
            ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_EMAIL);
    zwp_text_input_v3_set_cursor_rectangle(text, 5, 6, 2, 4);
    zwp_text_input_v3_commit(text);
    zwp_text_input_v3_set_text_change_cause(text, ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_INPUT_METHOD);
    zwp_text_input_v3_commit(text);
}
static void text_leave(void *data, struct zwp_text_input_v3 *text, struct wl_surface *surface) {
    (void)data; (void)text; (void)surface;
}
static void text_preedit(void *data, struct zwp_text_input_v3 *text, const char *value, int32_t begin, int32_t end) {
    (void)text;
    struct Client *client = data;
    if (value && *value) {
        assert(!strcmp(value, "compose") && begin == 7 && end == 7);
        client->preedit = true;
    }
}
static void text_commit(void *data, struct zwp_text_input_v3 *text, const char *value) {
    (void)text;
    struct Client *client = data;
    assert(client->deleted && client->preedit && !strcmp(value, "Unicode \xd0\x96 \xf0\x9f\x98\x80"));
    client->committed = true;
    xdg_toplevel_set_app_id(client->toplevel, "io.magicdesk.committed");
}
static void text_delete(void *data, struct zwp_text_input_v3 *text, uint32_t before, uint32_t after) {
    (void)text;
    assert(before == 2 && after == 1);
    ((struct Client *)data)->deleted = true;
}
static void text_done(void *data, struct zwp_text_input_v3 *text, uint32_t serial) {
    (void)data; (void)text; (void)serial;
}
static const struct zwp_text_input_v3_listener text_listener = {
    .enter = text_enter, .leave = text_leave, .preedit_string = text_preedit,
    .commit_string = text_commit, .delete_surrounding_text = text_delete, .done = text_done,
};

static void locked(void *data, struct zwp_locked_pointer_v1 *lock) {
    (void)lock;
    struct Client *client = data;
    client->pointer_locked = true;
    ++client->locks;
}
static void unlocked(void *data, struct zwp_locked_pointer_v1 *lock) {
    (void)lock;
    struct Client *client = data;
    client->pointer_locked = false;
    ++client->unlocks;
    xdg_toplevel_set_app_id(client->toplevel, "pointer-unlocked");
}
static const struct zwp_locked_pointer_v1_listener lock_listener = {.locked = locked, .unlocked = unlocked};

static void relative_motion(void *data, struct zwp_relative_pointer_v1 *relative, uint32_t hi, uint32_t lo,
        wl_fixed_t dx, wl_fixed_t dy, wl_fixed_t raw_dx, wl_fixed_t raw_dy) {
    (void)relative;
    struct Client *client = data;
    assert(hi || lo);
    assert(client->pointer_locked && dx == raw_dx && dy == raw_dy);
    assert(fabs(wl_fixed_to_double(dx) - 10) < .001 && fabs(wl_fixed_to_double(dy) + 15) < .001);
    ++client->relative_events;
    assert(!client->relative_pending);
    client->relative_pending = true;
}
static const struct zwp_relative_pointer_v1_listener relative_listener = {.relative_motion = relative_motion};

static void pointer_frame(void *data, struct wl_pointer *pointer) {
    (void)pointer;
    struct Client *client = data;
    if (!client->relative_pending) return;
    client->relative_pending = false;
    if (client->relative_events == 2) {
        zwp_locked_pointer_v1_destroy(client->lock);
        client->pointer_locked = false;
        client->lock = zwp_pointer_constraints_v1_lock_pointer(client->constraints, client->surface,
            client->pointer, NULL, ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT);
        zwp_locked_pointer_v1_add_listener(client->lock, &lock_listener, client);
    } else xdg_toplevel_set_app_id(client->toplevel, "pointer-moved");
}

static void cursor_step(void *data, struct wl_callback *callback, uint32_t serial);
static const struct wl_callback_listener cursor_step_listener = {.done = cursor_step};

static void cursor_step(void *data, struct wl_callback *callback, uint32_t serial) {
    (void)serial;
    struct Client *client = data;
    wl_callback_destroy(callback);
    // A nested compositor hides its cursor by committing an empty surface.
    wl_surface_attach(client->cursor, client->cursor_stage++ == 0 ? NULL : client->cursor_buffer, 0, 0);
    wl_surface_commit(client->cursor);
    if (client->cursor_stage == 1)
        wl_callback_add_listener(wl_display_sync(client->display), &cursor_step_listener, client);
}

static void pointer_enter(void *data, struct wl_pointer *pointer, uint32_t serial,
        struct wl_surface *surface, wl_fixed_t x, wl_fixed_t y) {
    (void)pointer; (void)serial;
    struct Client *client = data;
    assert(surface == client->surface);
    if (pointer_lock_test && !client->lock) {
        assert(client->constraints && client->relative_manager);
        client->relative = zwp_relative_pointer_manager_v1_get_relative_pointer(client->relative_manager, pointer);
        zwp_relative_pointer_v1_add_listener(client->relative, &relative_listener, client);
        client->lock = zwp_pointer_constraints_v1_lock_pointer(client->constraints, surface, pointer,
            NULL, ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT);
        zwp_locked_pointer_v1_add_listener(client->lock, &lock_listener, client);
    }
    assert(fabs(wl_fixed_to_double(x) - (pacing ? client->width * .25 : bounded_client_size ? 300 : minimum_client_size ? 40 : interaction ? 10 : fixed_client_size ? 40 : 20)) < .01
        && fabs(wl_fixed_to_double(y) - (pacing ? client->height * .25 : bounded_client_size ? 75 : minimum_client_size ? 60 : interaction ? 7.5 : fixed_client_size ? 30 : 15)) < .01);
    if (interaction) {
        client->cursor = wl_compositor_create_surface(client->compositor);
        wl_pointer_set_cursor(pointer, serial, client->cursor, 1, 2);
        struct wl_buffer *buffer;
#ifdef __ANDROID__
        if (dma_cursor) {
            assert(client->dma);
            struct Producer producer = {.fd = -1, .width = 4, .height = 4, .stride = 16};
            assert(producer_create(&producer));
            producer_write(&producer, 0xff0000ff, 0xff0000ff);
            struct zwp_linux_buffer_params_v1 *params = zwp_linux_dmabuf_v1_create_params(client->dma);
            zwp_linux_buffer_params_v1_add(params, producer.fd, 0, 0, 16, 0, DRM_FORMAT_MOD_LINEAR);
            buffer = zwp_linux_buffer_params_v1_create_immed(params, 4, 4, DRM_FORMAT_ABGR8888, 0);
            zwp_linux_buffer_params_v1_destroy(params);
            producer_destroy(&producer);
        } else
#endif
        {
        int fd = syscall(SYS_memfd_create, "mdw-cursor", MFD_CLOEXEC);
        assert(fd >= 0 && ftruncate(fd, 4 * 4 * 4) == 0);
        uint32_t image[16];
        for (int i = 0; i < 16; ++i) image[i] = 0xffff0000;
        assert(write(fd, image, sizeof(image)) == sizeof(image));
        struct wl_shm_pool *pool = wl_shm_create_pool(client->shm, fd, sizeof(image));
        buffer = wl_shm_pool_create_buffer(pool, 0, 4, 4, 16, WL_SHM_FORMAT_ARGB8888);
        wl_shm_pool_destroy(pool); close(fd);
        }
        wl_surface_attach(client->cursor, buffer, 0, 0);
        wl_surface_commit(client->cursor);
        client->cursor_buffer = buffer;
        if (!dma_cursor)
            wl_callback_add_listener(wl_display_sync(client->display), &cursor_step_listener, client);
        xdg_toplevel_set_fullscreen(client->toplevel, NULL);
        xdg_toplevel_set_maximized(client->toplevel);
        xdg_toplevel_set_minimized(client->toplevel);
    }
}
static void pointer_leave(void *data, struct wl_pointer *pointer, uint32_t serial, struct wl_surface *surface) {
    (void)data; (void)pointer; (void)serial; (void)surface;
}
static void pointer_motion(void *data, struct wl_pointer *pointer, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    (void)data; (void)pointer; (void)time; (void)x; (void)y;
    assert(!((struct Client *)data)->pointer_locked);
}
static void pointer_button(void *data, struct wl_pointer *pointer, uint32_t serial,
        uint32_t time, uint32_t button, uint32_t state) {
    (void)pointer; (void)serial; (void)time;
    struct Client *client = data;
    assert(button == BTN_LEFT);
    if (state == WL_POINTER_BUTTON_STATE_PRESSED) {
        client->button_down++;
        if (interaction) {
            xdg_toplevel_move(client->toplevel, client->seat, UINT32_MAX);
            xdg_toplevel_move(client->toplevel, client->seat, serial);
            xdg_toplevel_resize(client->toplevel, client->seat, serial, XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT);
        }
    }
    else client->button_up++;
}
static void pointer_axis(void *data, struct wl_pointer *pointer, uint32_t time,
        uint32_t axis, wl_fixed_t value) {
    (void)data; (void)pointer; (void)time; (void)axis; (void)value;
}
static const struct wl_pointer_listener pointer_listener = {
    .enter = pointer_enter, .leave = pointer_leave, .motion = pointer_motion,
    .button = pointer_button, .axis = pointer_axis, .frame = pointer_frame,
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
static void keyboard_repeat(void *data, struct wl_keyboard *keyboard, int32_t rate, int32_t delay) {
    (void)data; (void)keyboard; (void)rate; (void)delay;
}
static const struct wl_keyboard_listener keyboard_listener = {
    .keymap = keyboard_keymap, .enter = keyboard_enter, .leave = keyboard_leave,
    .key = keyboard_key, .modifiers = keyboard_modifiers, .repeat_info = keyboard_repeat,
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
static void seat_name(void *data, struct wl_seat *seat, const char *name) {
    (void)data; (void)seat; (void)name;
}
static const struct wl_seat_listener seat_listener = {.capabilities = seat_capabilities, .name = seat_name};

static void presentation_clock(void *data, struct wp_presentation *presentation, uint32_t clock) {
    (void)presentation;
    assert(clock == CLOCK_MONOTONIC);
    ((struct Client *)data)->presentation_clock = true;
}
static const struct wp_presentation_listener presentation_listener = {.clock_id = presentation_clock};

static void feedback_output(void *data, struct wp_presentation_feedback *feedback, struct wl_output *output) {
    (void)data; (void)feedback; (void)output;
}
static void feedback_presented(void *data, struct wp_presentation_feedback *feedback,
        uint32_t sec_hi, uint32_t sec_lo, uint32_t nsec, uint32_t refresh,
        uint32_t seq_hi, uint32_t seq_lo, uint32_t flags) {
    (void)refresh; (void)seq_hi; (void)seq_lo;
    assert(sec_hi || sec_lo);
    assert(nsec < 1000000000);
    // The headless output observes software completion, not hardware scanout.
    assert(!(flags & (WP_PRESENTATION_FEEDBACK_KIND_HW_CLOCK |
        WP_PRESENTATION_FEEDBACK_KIND_HW_COMPLETION | WP_PRESENTATION_FEEDBACK_KIND_VSYNC)));
    ((struct Client *)data)->presented++;
    wp_presentation_feedback_destroy(feedback);
}
static void feedback_discarded(void *data, struct wp_presentation_feedback *feedback) {
    (void)data;
    wp_presentation_feedback_destroy(feedback);
}
static const struct wp_presentation_feedback_listener feedback_listener = {
    .sync_output = feedback_output, .presented = feedback_presented, .discarded = feedback_discarded,
};

static void global(void *data, struct wl_registry *registry, uint32_t name,
        const char *interface, uint32_t version) {
    (void)version;
    struct Client *client = data;
    if (!strcmp(interface, "zwp_pointer_constraints_v1"))
        client->constraints = wl_registry_bind(registry, name, &zwp_pointer_constraints_v1_interface, 1);
    if (!strcmp(interface, "zwp_relative_pointer_manager_v1"))
        client->relative_manager = wl_registry_bind(registry, name, &zwp_relative_pointer_manager_v1_interface, 1);
    if (!strcmp(interface, "wl_output")) {
        if (!client->initial_monitor) client->initial_monitor = name;
        client->monitors++;
    }
    if (!strcmp(interface, "wl_compositor"))
        client->compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 4);
    else if (!strcmp(interface, "wl_shm"))
        client->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    else if (!strcmp(interface, "wp_presentation")) {
        client->presentation = wl_registry_bind(registry, name, &wp_presentation_interface, 1);
        wp_presentation_add_listener(client->presentation, &presentation_listener, client);
    }
    else if (single_pixel && !strcmp(interface, "wp_single_pixel_buffer_manager_v1"))
        client->single_pixel_manager = wl_registry_bind(registry, name, &wp_single_pixel_buffer_manager_v1_interface, 1);
    else if (single_pixel && !strcmp(interface, "wp_viewporter"))
        client->viewporter = wl_registry_bind(registry, name, &wp_viewporter_interface, 1);
    else if ((dma_client || dma_cursor) && !strcmp(interface, "zwp_linux_dmabuf_v1")) {
        assert(version >= 3);
        client->dma = wl_registry_bind(registry, name, &zwp_linux_dmabuf_v1_interface, 3);
    }
    else if (!strcmp(interface, "wl_data_device_manager")) client->data_device_manager = true;
    else if (!strcmp(interface, "zwp_text_input_manager_v3"))
        client->text_manager = wl_registry_bind(registry, name, &zwp_text_input_manager_v3_interface, 1);
    else if (!strcmp(interface, "wl_seat")) {
        client->seat = wl_registry_bind(registry, name, &wl_seat_interface, pointer_lock_test ? 5 : 1);
        wl_seat_add_listener(client->seat, &seat_listener, client);
    }
    else if (!strcmp(interface, "xdg_wm_base")) {
        client->shell = wl_registry_bind(registry, name, &xdg_wm_base_interface, 2);
        xdg_wm_base_add_listener(client->shell, &shell_listener, client);
    }
}
static void global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)registry;
    struct Client *client = data;
    // The primary monitor outlives host detach/recreate and window destruction.
    assert(name != client->initial_monitor);
}
static const struct wl_registry_listener registry_listener = {global, global_remove};

static void animate(struct Client *client);
static int compare_time(const void *a, const void *b) {
    int64_t x = *(const int64_t *)a, y = *(const int64_t *)b;
    return (x > y) - (x < y);
}

static void frame_done(void *data, struct wl_callback *callback, uint32_t time) {
    (void)time;
    struct Client *client = data;
    client->frames++;
    wl_callback_destroy(callback);
    if (pacing && client->frames <= PACING_FRAMES) {
        pacing_times[client->frames - 1] = monotonic_ns();
        if (client->frames < PACING_FRAMES) animate(client);
        else {
            int64_t intervals[PACING_FRAMES - 1];
            for (int i = 1; i < PACING_FRAMES; ++i) intervals[i - 1] = pacing_times[i] - pacing_times[i - 1];
            qsort(intervals, PACING_FRAMES - 1, sizeof(*intervals), compare_time);
            printf("{\"frames\":%d,\"elapsedMs\":%.3f,\"intervalP50Ms\":%.3f,\"intervalP95Ms\":%.3f,\"intervalMaxMs\":%.3f}\n",
                PACING_FRAMES, (pacing_times[PACING_FRAMES - 1] - pacing_times[0]) / 1e6,
                intervals[(PACING_FRAMES - 1) / 2] / 1e6, intervals[(PACING_FRAMES - 1) * 95 / 100] / 1e6,
                intervals[PACING_FRAMES - 2] / 1e6);
            fflush(stdout);
            xdg_toplevel_set_app_id(client->toplevel, "io.magicdesk.pacing.done");
        }
    }
}
static const struct wl_callback_listener frame_listener = {frame_done};

static void buffer_release(void *data, struct wl_buffer *buffer) {
    (void)data;
    wl_buffer_destroy(buffer);
}
static const struct wl_buffer_listener buffer_listener = {buffer_release};

static struct wl_buffer *client_buffer(struct Client *client) {
    if (single_pixel) {
        assert(client->single_pixel_manager && client->viewporter);
        if (!client->viewport) client->viewport = wp_viewporter_get_viewport(client->viewporter, client->surface);
        wp_viewport_set_destination(client->viewport, client->width, client->height);
        return wp_single_pixel_buffer_manager_v1_create_u32_rgba_buffer(client->single_pixel_manager,
            0x12121212, 0x67676767, 0xabababab, UINT32_MAX);
    }
#ifdef __ANDROID__
    if (dma_client) {
        assert(client->dma);
        struct Producer producer = {.fd = -1, .width = client->width, .height = client->height, .stride = client->width * 4};
        assert(producer_create(&producer));
        producer_write(&producer, 0xffab6712, 0xffab6712);
        struct zwp_linux_buffer_params_v1 *params = zwp_linux_dmabuf_v1_create_params(client->dma);
        zwp_linux_buffer_params_v1_add(params, producer.fd, 0, 0, producer.stride, 0, DRM_FORMAT_MOD_LINEAR);
        struct wl_buffer *buffer = zwp_linux_buffer_params_v1_create_immed(params, client->width, client->height, DRM_FORMAT_ABGR8888, 0);
        zwp_linux_buffer_params_v1_destroy(params);
        /* Wayland's queued FD retains the allocation after the producer completes. */
        producer_destroy(&producer);
        return buffer;
    }
#endif
    int stride = client->width * 4;
    size_t size = (size_t)stride * client->height;
    int descriptor = syscall(SYS_memfd_create, "mdw-client", MFD_CLOEXEC);
    assert(descriptor >= 0 && ftruncate(descriptor, size) == 0);
    uint32_t *pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
    assert(pixels != MAP_FAILED);
    for (size_t index = 0; index < size / 4; ++index) pixels[index] = 0xff1267ab;
    if (pacing) {
        // Encode commit time/sequence in opaque RGB pixels for Android ImageReader.
        uint64_t time = monotonic_ns();
        uint8_t payload[12];
        for (int i = 0; i < 8; ++i) payload[i] = time >> (8 * i);
        uint32_t sequence = client->frames;
        for (int i = 0; i < 4; ++i) payload[8 + i] = sequence >> (8 * i);
        for (int i = 0; i < 4; ++i)
            for (int y = 40; y < 44; ++y) for (int x = 8 + i * 4; x < 12 + i * 4; ++x)
                pixels[y * client->width + x] = 0xff000000u | ((uint32_t)payload[i * 3] << 16) |
                    ((uint32_t)payload[i * 3 + 1] << 8) | payload[i * 3 + 2];
    }
    struct wl_shm_pool *pool = wl_shm_create_pool(client->shm, descriptor, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(pool, 0, client->width,
        client->height, stride, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);
    close(descriptor);
    munmap(pixels, size);
    return buffer;
}
static void animate(struct Client *client) {
    struct wl_buffer *buffer = client_buffer(client);
    wl_buffer_add_listener(buffer, &buffer_listener, NULL);
    wl_surface_attach(client->surface, buffer, 0, 0);
    wl_surface_damage_buffer(client->surface, 0, 0, client->width, client->height);
    wl_callback_add_listener(wl_surface_frame(client->surface), &frame_listener, client);
    wl_surface_commit(client->surface);
}
static void configure(void *data, struct xdg_surface *surface, uint32_t serial) {
    struct Client *client = data;
    xdg_surface_ack_configure(surface, serial);
    struct wl_buffer *buffer = client_buffer(client);
    wl_buffer_add_listener(buffer, &buffer_listener, NULL);
    wl_surface_attach(client->surface, buffer, 0, 0);
    wl_surface_damage_buffer(client->surface, 0, 0, client->width, client->height);
    if (!pacing || !client->pacing_started) {
        wl_callback_add_listener(wl_surface_frame(client->surface), &frame_listener, client);
        client->pacing_started = true;
    }
    wp_presentation_feedback_add_listener(wp_presentation_feedback(client->presentation, client->surface),
        &feedback_listener, client);
    wl_surface_commit(client->surface);
}
static const struct xdg_surface_listener surface_listener = {configure};

static void toplevel_configure(void *data, struct xdg_toplevel *toplevel,
        int32_t width, int32_t height, struct wl_array *states) {
    (void)toplevel; (void)states;
    struct Client *client = data;
    client->width = fixed_client_size ? 160 : width > 0 ? width : 80;
    client->height = fixed_client_size ? 120 : height > 0 ? height : 60;
    client->fullscreen = false;
    client->maximized = false;
    uint32_t *state;
    wl_array_for_each(state, states) {
        if (*state == XDG_TOPLEVEL_STATE_FULLSCREEN) client->fullscreen = true;
        if (*state == XDG_TOPLEVEL_STATE_MAXIMIZED) client->maximized = true;
    }
}
static void toplevel_close(void *data, struct xdg_toplevel *toplevel) {
    (void)toplevel;
    struct Client *client = data;
    assert(client->frames > 0);
    assert(client->presented > 0);
    assert(client->key_down == 1 && client->key_up == 1);
    assert(client->button_down == 1 && client->button_up == 1);
    if (interaction) assert(client->committed && client->fullscreen && client->maximized && client->width == 40 && client->height == 30);
    if (pointer_lock_test) assert(client->locks == 3 && client->unlocks == 2 && client->relative_events == 3 && !client->pointer_locked);
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
    assert(client.data_device_manager);
    assert(client.presentation && client.presentation_clock);
    assert(client.initial_monitor && client.monitors == 1);
#ifdef __ANDROID__
    if (dma_client) assert(client.dma);
#else
    assert(!dma_client);
#endif
    if (interaction) {
        assert(client.text_manager);
        client.text = zwp_text_input_manager_v3_get_text_input(client.text_manager, client.seat);
        zwp_text_input_v3_add_listener(client.text, &text_listener, &client);
    }
    client.surface = wl_compositor_create_surface(client.compositor);
    client.xdg_surface = xdg_wm_base_get_xdg_surface(client.shell, client.surface);
    xdg_surface_add_listener(client.xdg_surface, &surface_listener, &client);
    client.toplevel = xdg_surface_get_toplevel(client.xdg_surface);
    xdg_toplevel_add_listener(client.toplevel, &toplevel_listener, &client);
    if (fixed_client_size) {
        xdg_toplevel_set_min_size(client.toplevel, 160, 120);
        xdg_toplevel_set_max_size(client.toplevel, 160, 120);
    }
    if (minimum_client_size || bounded_client_size)
        xdg_toplevel_set_min_size(client.toplevel, bounded_client_size ? 1200 : 160, 60);
    xdg_toplevel_set_title(client.toplevel, "MagicDesk Wayland fixture");
    xdg_toplevel_set_app_id(client.toplevel, "io.magicdesk.fixture");
    wl_surface_commit(client.surface);
    while (!client.closed) assert(wl_display_dispatch(client.display) >= 0);
    if (client.cursor) wl_surface_destroy(client.cursor);
    if (client.cursor_buffer) wl_buffer_destroy(client.cursor_buffer);
    if (client.text) zwp_text_input_v3_destroy(client.text);
    if (client.text_manager) zwp_text_input_manager_v3_destroy(client.text_manager);
    xdg_toplevel_destroy(client.toplevel);
    xdg_surface_destroy(client.xdg_surface);
    wl_surface_destroy(client.surface);
    wl_pointer_destroy(client.pointer);
    wl_keyboard_destroy(client.keyboard);
    wl_seat_destroy(client.seat);
    xdg_wm_base_destroy(client.shell);
    wl_shm_destroy(client.shm);
    wp_presentation_destroy(client.presentation);
    if (client.viewport) wp_viewport_destroy(client.viewport);
    if (client.viewporter) wp_viewporter_destroy(client.viewporter);
    if (client.single_pixel_manager) wp_single_pixel_buffer_manager_v1_destroy(client.single_pixel_manager);
    wl_compositor_destroy(client.compositor);
    assert(wl_display_roundtrip(client.display) >= 0);
    assert(client.monitors == 1);
    wl_registry_destroy(registry);
    wl_display_disconnect(client.display);
}

struct Host {
    uint64_t window;
    bool mapped, destroyed;
    bool text_enabled, cursor_seen, cursor_hidden, cursor_restored;
    uint64_t editor;
    uint64_t capture_serial;
    int capture_requests, capture_releases;
    uint32_t text_revision;
    int gestures;
    int frames;
    bool allow_render;
    int deferred_frames;
    MdwWindow previous;
    char title[256], app_id[256];
};

static void window_event(void *data, uint64_t id, const MdwWindow *window) {
    struct Host *host = data;
    if (!window) { host->destroyed = true; return; }
    assert(!host->previous.id || host->previous.parent != window->parent ||
        host->previous.mapped != window->mapped || host->previous.width != window->width ||
        host->previous.height != window->height || strcmp(host->title, window->title) ||
        strcmp(host->app_id, window->app_id) || host->previous.request_serial != window->request_serial ||
        host->previous.fullscreen != window->fullscreen || host->previous.maximize_serial != window->maximize_serial
        || host->previous.min_width != window->min_width || host->previous.max_width != window->max_width
        || host->previous.minimize_serial != window->minimize_serial || host->previous.minimize_requested != window->minimize_requested);
    host->previous = *window;
    snprintf(host->title, sizeof(host->title), "%s", window->title);
    snprintf(host->app_id, sizeof(host->app_id), "%s", window->app_id);
    host->window = id;
    host->mapped = window->mapped;
    if (window->mapped) {
        assert(!strcmp(window->title, "MagicDesk Wayland fixture"));
        assert(!strcmp(window->app_id, "io.magicdesk.fixture") || (interaction && !strcmp(window->app_id, "io.magicdesk.committed")) ||
            (pointer_lock_test && (!strcmp(window->app_id, "pointer-moved") || !strcmp(window->app_id, "pointer-unlocked"))));
    }
}

static void frame_event(void *data, MdwOutput *output, const MdwFrame *frame) {
    (void)output;
    struct Host *host = data;
    if (!frame) return;
    int width = bounded_client_size ? 4096 : minimum_client_size || fixed_client_size ? 160 : 80;
    int height = bounded_client_size ? 1024 : minimum_client_size ? 240 : fixed_client_size ? 120 : 60;
    assert(frame->width == width && frame->height == height);
    uint32_t *pixels = malloc((size_t)width * height * 4);
    assert(pixels);
    assert(mdg_image_read(frame->image, pixels, width * 4));
    assert((pixels[20 * width + 20] & 0x00ffffff) == 0x00ab6712);
    free(pixels);
    int descriptor = mdw_frame_export(frame);
    assert(descriptor >= 0);
    int seals = fcntl(descriptor, F_GET_SEALS);
    assert((seals & (F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK)) ==
        (F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK));
    uint8_t rgba[4];
    assert(pread(descriptor, rgba, 4, (20 * width + 20) * 4) == 4);
    assert(rgba[0] == 0x12 && rgba[1] == 0x67 && rgba[2] == 0xab && rgba[3] == 255);
    assert(pwrite(descriptor, rgba, 4, 0) < 0);
    close(descriptor);
    host->frames++;
}

static bool can_render(void *data, MdwOutput *output) {
    (void)output;
    struct Host *host = data;
    if (!host->allow_render) ++host->deferred_frames;
    return host->allow_render;
}

static void error_event(void *data, const char *message) {
    (void)data;
    fprintf(stderr, "%s\n", message);
    abort();
}

static void text_input_event(void *data, MdwOutput *output, const MdwTextState *state) {
    (void)output;
    struct Host *host = data;
    host->text_enabled = state->editor != 0 && state->surrounding != NULL;
    host->editor = state->editor;
    host->text_revision = state->revision;
    if (state->editor) {
        if (state->revision == 1) {
            assert(!state->surrounding && !state->caret_valid);
            assert(state->input_method_change);
            return;
        }
        assert(state->surrounding && !strcmp(state->surrounding, "a\xd0\x96\xf0\x9f\x98\x80z"));
        assert(state->cursor == 7 && state->anchor == 3);
        assert(state->purpose == ZWP_TEXT_INPUT_V3_CONTENT_PURPOSE_EMAIL);
        assert(state->hints == ZWP_TEXT_INPUT_V3_CONTENT_HINT_COMPLETION);
        assert(state->caret_valid && fabs(state->caret[0] - .125f) < .0001f
            && fabs(state->caret[1] - .2f) < .0001f && fabs(state->caret[2] - .175f) < .0001f
            && fabs(state->caret[3] - (1.f / 3)) < .0001f);
    }
}
static void window_gesture(void *data, uint64_t window, uint32_t edges) {
    struct Host *host = data;
    assert(window == host->window && host->gestures < 2);
    assert(edges == (host->gestures == 0 ? 0 : XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT));
    ++host->gestures;
}
static void capture_event(void *data, MdwOutput *output, uint64_t serial) {
    (void)output;
    struct Host *host = data;
    host->capture_serial = serial;
    if (serial) ++host->capture_requests;
    else ++host->capture_releases;
}

static void cursor_event(void *data, MdwOutput *output, const uint32_t *pixels,
        int width, int height, int x, int y, bool hidden) {
    (void)output;
    struct Host *host = data;
    if (!pixels) {
        if (hidden && host->cursor_seen) host->cursor_hidden = true;
        return;
    }
    assert(width == 4 && height == 4 && x == 1 && y == 2 && !hidden);
    for (int i = 0; i < 16; ++i) assert(pixels[i] == 0xffff0000);
    host->cursor_seen = true;
    if (host->cursor_hidden) host->cursor_restored = true;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--pacing-client")) { pacing = true; run_client(NULL); return 0; }
    if (argc == 2 && !strcmp(argv[1], "--dma-client")) { dma_client = true; run_client(NULL); return 0; }
    if (argc == 2 && !strcmp(argv[1], "--client")) { run_client(NULL); return 0; }
    fixed_client_size = argc == 2 && !strcmp(argv[1], "--fixed-size");
    single_pixel = argc == 2 && !strcmp(argv[1], "--single-pixel");
    pointer_lock_test = argc == 2 && !strcmp(argv[1], "--pointer-lock");
    minimum_client_size = argc == 2 && !strcmp(argv[1], "--minimum-size");
    bounded_client_size = argc == 2 && !strcmp(argv[1], "--bounded-size");
    int offered_height = bounded_client_size ? 20 : minimum_client_size ? 120 : 60;
    dma_cursor = argc == 2 && !strcmp(argv[1], "--dma-cursor");
    interaction = dma_cursor || (argc == 2 && !strcmp(argv[1], "--interaction"));
    const char *temporary = getenv("TMPDIR");
    char directory[4096];
    snprintf(directory, sizeof(directory), "%s/mdw-window-XXXXXX", temporary ? temporary : "/tmp");
    assert(mkdtemp(directory));
    assert(setenv("XDG_RUNTIME_DIR", directory, 1) == 0);
    MdwServer *server = mdw_server_create();
    assert(server);
    assert(mdw_server_initial_output(server, 900, 700, 1));
    assert(mdw_server_fd(server) >= 0);
    struct Host host = {.allow_render = true};
    MdwEvents events = {.window = window_event, .window_gesture = window_gesture, .frame = frame_event, .can_render = can_render,
        .error = error_event, .text_input = text_input_event, .cursor = cursor_event,
        .pointer_capture = capture_event, .context = &host};
    mdw_server_set_events(server, &events);
    int connection = argc == 2 && !strcmp(argv[1], "--fd") ? mdw_server_connect(server) : -1;
    assert(argc == 1 || connection >= 0 || fixed_client_size || minimum_client_size || bounded_client_size || interaction || pointer_lock_test || single_pixel);
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
    bool detached = false, closing = false, deferred = false, resumed = false;
    bool input_sent = false, text_sent = false, minimize_seen = false;
    int capture_stage = 0;
    uint64_t old_capture = 0;
    while (!host.destroyed) {
        // Process a ready host capture receipt before blocking on the next client event.
        assert(mdw_server_dispatch(server,
            pointer_lock_test && host.capture_serial && host.capture_serial != old_capture ? 0 : -1) >= 0);
        if (host.mapped && !output) {
            output = mdw_output_create(server, host.window, 80, offered_height);
            assert(output);
            if (interaction) assert(mdw_output_scale(output, 2));
            if (bounded_client_size) assert(mdw_output_scale(output, 4));
            assert(!mdw_output_resize(output, 0, 60));
        }
        if (host.frames >= 1 && !detached) {
            MdwOutput *dependents = mdw_output_borrow_dependents(output);
            assert(dependents && !mdw_output_borrow_dependents(output));
            assert(!mdw_output_resize(dependents, 30, 20));
            assert(mdw_output_set_visible(dependents, false));
            assert(mdw_output_viewport(dependents, 10, 10, 30, 20));
            assert(mdw_output_set_visible(dependents, true));
            mdw_output_destroy(dependents);
            mdw_output_destroy(output);
            output = mdw_output_create(server, host.window, 80, offered_height);
            assert(output && !host.destroyed);
            if (interaction) assert(mdw_output_scale(output, 2));
            if (bounded_client_size) assert(mdw_output_scale(output, 4));
            detached = true;
        } else if (host.frames >= 2 && !deferred) {
            host.allow_render = false;
            assert(mdw_output_refresh(output));
            deferred = true;
        } else if (host.deferred_frames && !resumed) {
            int frames = host.frames;
            assert(mdw_output_set_visible(output, false));
            assert(!mdw_output_focus(output, true));
            assert(mdw_output_resize(output, 80, offered_height));
            if (bounded_client_size) {
                assert(mdw_output_scale(output, 2));
                assert(mdw_output_scale(output, 4));
            }
            assert(mdw_output_refresh(output));
            assert(mdw_server_dispatch(server, 0) >= 0);
            assert(host.frames == frames);
            host.allow_render = true;
            assert(mdw_output_set_visible(output, true));
            resumed = true;
        } else if (resumed && host.frames >= 3 && !input_sent) {
            assert(!mdw_output_key(output, KEY_A, true));
            assert(mdw_output_focus(output, true));
            assert(!mdw_output_pointer(output, NAN, .5));
            assert(mdw_output_pointer(output, .25, .25));
            assert(mdw_output_button(output, MDW_PRIMARY, true));
            assert(mdw_output_key(output, KEY_A, true));
            MdwOutput *dependents = mdw_output_borrow_dependents(output);
            assert(dependents && mdw_output_focus(dependents, true));
            assert(mdw_output_focus(output, true));
            mdw_output_destroy(dependents);
            input_sent = true;
        }
        if (interaction && !host.destroyed && !closing && host.previous.fullscreen) {
            if (host.previous.minimize_requested && !minimize_seen) {
                assert(!mdw_window_confirm_minimize(server,host.window,host.previous.minimize_serial+1));
                assert(mdw_window_confirm_minimize(server,host.window,host.previous.minimize_serial));
                assert(!host.previous.minimize_requested);
                minimize_seen=true;
            }
            assert(!mdw_window_confirm_fullscreen(server, host.window, host.previous.request_serial + 1, true));
            assert(mdw_window_confirm_fullscreen(server, host.window, host.previous.request_serial, true));
            assert(!mdw_window_confirm_maximized(server, host.window, host.previous.maximize_serial + 1, true));
            assert(mdw_window_confirm_maximized(server, host.window, host.previous.maximize_serial, true));
            if (host.text_enabled && !text_sent) {
                assert(!mdw_output_text(output, host.editor + 1, "stale", false, 5));
                assert(!mdw_output_delete_text(output, host.editor, host.text_revision + 1, 2, 1, "", 0));
                assert(!mdw_output_delete_text(output, host.editor, host.text_revision, 1, 1, "", 0));
                assert(mdw_output_text(output, host.editor, "compose", true, 7));
                assert(mdw_output_delete_text(output, host.editor, host.text_revision, 2, 1, "compose", 7));
                assert(mdw_output_text(output, host.editor, "Unicode \xd0\x96 \xf0\x9f\x98\x80", false, 15));
                text_sent = true;
            }
        }
        if (pointer_lock_test && input_sent && !closing) {
            if ((capture_stage == 0 || capture_stage == 2 || (capture_stage == 3 && host.capture_requests == 3)) && host.capture_serial
                    && host.capture_serial != old_capture) {
                uint64_t serial = host.capture_serial;
                assert(!mdw_output_relative(output, serial, .125, -.25));
                assert(!mdw_output_pointer_capture(output, old_capture, true));
                assert(mdw_output_pointer_capture(output, serial, true));
                assert(mdw_output_relative(output, serial, .125, -.25));
                assert(!mdw_output_relative(output, serial + 1, .125, -.25));
                assert(!mdw_output_relative(output, serial, NAN, 0));
                // A delayed absolute sample (including button positioning) cannot unlock or warp.
                assert(mdw_output_pointer(output, .9, .9));
                old_capture = serial;
                capture_stage = capture_stage == 3 ? 5 : capture_stage + 1;
            } else if ((capture_stage == 1 || capture_stage == 5) && !strcmp(host.app_id, "pointer-moved")) {
                assert(mdw_output_focus(output, false));
                assert(host.capture_serial == 0);
                assert(!mdw_output_relative(output, old_capture, 1, 1));
                assert(!mdw_output_pointer_capture(output, old_capture, true));
                if (capture_stage == 1) capture_stage = 7;
                else capture_stage = 6;
            } else if (capture_stage == 7 && !strcmp(host.app_id, "pointer-unlocked")) {
                assert(mdw_output_focus(output, true));
                assert(mdw_output_pointer(output, .25, .25));
                capture_stage = 2;
            } else if (capture_stage == 6 && !strcmp(host.app_id, "pointer-unlocked")) {
                assert(host.capture_requests == 3 && host.capture_releases == 3);
                assert(mdw_window_close(server, host.window));
                closing = true;
            }
        }
        if (input_sent && !pointer_lock_test && !closing && (!interaction ||
                (host.cursor_seen && (dma_cursor || host.cursor_restored) && minimize_seen && host.gestures == 2 && !strcmp(host.app_id, "io.magicdesk.committed")))) {
            assert(mdw_output_focus(output, false));
            assert(!mdw_output_key(output, KEY_A, false));
            assert(mdw_window_close(server, host.window));
            closing = true;
        }
    }
    assert(!mdw_window_close(server, host.window));
    assert(!mdw_output_resize(output, 80, 60));
    mdw_output_destroy(output);
    assert(!server->initial_output_borrowed);
    assert(!server->initial_output->swapchain && !server->initial_output->renderer);
    // EVENT_WAIT: let the client complete its final registry roundtrip and disconnect.
    // Window destruction alone does not mean the client has finished using the display.
    while (!wl_list_empty(wl_display_get_client_list(server->display)))
        assert(mdw_server_dispatch(server, -1) >= 0);
    mdw_server_destroy(server);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(rmdir(directory) == 0);
    puts("xdg-shell, software pixels, frame callbacks, detach/reattach and graceful close passed");
    return 0;
}
