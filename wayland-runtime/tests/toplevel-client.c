#include "wlr-foreign-toplevel-management-unstable-v1-client-protocol.h"
#include <wayland-client.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct Window {
    struct zwlr_foreign_toplevel_handle_v1 *handle;
    char title[1024], app[1024];
    bool active, maximized, fullscreen, minimized;
};
static struct Window windows[256];
static unsigned count;
static struct wl_seat *seat;
static struct zwlr_foreign_toplevel_manager_v1 *manager;
static void title(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, const char *value) {
    (void)handle; snprintf(((struct Window*)data)->title, 1024, "%s", value);
}
static void app(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, const char *value) {
    (void)handle; snprintf(((struct Window*)data)->app, 1024, "%s", value);
}
static void output(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, struct wl_output *out) {
    (void)data; (void)handle; (void)out;
}
static void state(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, struct wl_array *values) {
    (void)handle;
    struct Window *w = data; w->active = w->maximized = w->fullscreen = w->minimized = false;
    uint32_t *value;
    wl_array_for_each(value, values) {
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_ACTIVATED) w->active = true;
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_MAXIMIZED) w->maximized = true;
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_FULLSCREEN) w->fullscreen = true;
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_MINIMIZED) w->minimized = true;
    }
}
static void done(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle) { (void)data; (void)handle; }
static void closed(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle) {
    ((struct Window*)data)->handle = NULL; zwlr_foreign_toplevel_handle_v1_destroy(handle);
}
static void parent(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, struct zwlr_foreign_toplevel_handle_v1 *value) {
    (void)data; (void)handle; (void)value;
}
static const struct zwlr_foreign_toplevel_handle_v1_listener listener = {
    .title=title, .app_id=app, .output_enter=output, .output_leave=output, .state=state, .done=done, .closed=closed, .parent=parent,
};
static void toplevel(void *data, struct zwlr_foreign_toplevel_manager_v1 *m, struct zwlr_foreign_toplevel_handle_v1 *handle) {
    (void)data; (void)m;
    if (count == 256) abort();
    struct Window *window = &windows[count++]; window->handle = handle;
    zwlr_foreign_toplevel_handle_v1_add_listener(handle, &listener, window);
}
static void finished(void *data, struct zwlr_foreign_toplevel_manager_v1 *m) { (void)data; (void)m; }
static const struct zwlr_foreign_toplevel_manager_v1_listener management = {.toplevel=toplevel, .finished=finished};
static void global(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
    (void)data;
    if (!strcmp(interface, "wl_seat")) seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
    if (!strcmp(interface, "zwlr_foreign_toplevel_manager_v1")) {
        manager = wl_registry_bind(registry, name, &zwlr_foreign_toplevel_manager_v1_interface, version < 3 ? version : 3);
        zwlr_foreign_toplevel_manager_v1_add_listener(manager, &management, NULL);
    }
}
static void removed(void *data, struct wl_registry *registry, uint32_t name) { (void)data; (void)registry; (void)name; }
static const struct wl_registry_listener registry_listener = {.global=global, .global_remove=removed};
int main(int argc, char **argv) {
    struct wl_display *display = wl_display_connect(NULL);
    if (!display) return 2;
    wl_registry_add_listener(wl_display_get_registry(display), &registry_listener, NULL);
    // EVENT_WAIT: registry/handle roundtrips; the invoking test command must bound a missing server reply.
    if (wl_display_roundtrip(display) < 0 || !manager || wl_display_roundtrip(display) < 0 || wl_display_roundtrip(display) < 0) return 3;
    struct Window *target = NULL;
    for (unsigned i = 0; i < count; i++) {
        struct Window *w = &windows[i];
        if (!w->handle) continue;
        printf("%s | %s active=%d maximized=%d fullscreen=%d minimized=%d\n", w->app, w->title, w->active, w->maximized, w->fullscreen, w->minimized);
        if (argc == 3 && !strcmp(argv[1], w->title)) { if (target) return 4; target = w; }
    }
    if (argc == 3) {
        if (!target) return 5;
        if (!strcmp(argv[2], "activate")) zwlr_foreign_toplevel_handle_v1_activate(target->handle, seat);
        else if (!strcmp(argv[2], "maximize")) zwlr_foreign_toplevel_handle_v1_set_maximized(target->handle);
        else if (!strcmp(argv[2], "unmaximize")) zwlr_foreign_toplevel_handle_v1_unset_maximized(target->handle);
        else if (!strcmp(argv[2], "minimize")) zwlr_foreign_toplevel_handle_v1_set_minimized(target->handle);
        else if (!strcmp(argv[2], "unminimize")) zwlr_foreign_toplevel_handle_v1_unset_minimized(target->handle);
        else if (!strcmp(argv[2], "fullscreen")) zwlr_foreign_toplevel_handle_v1_set_fullscreen(target->handle, NULL);
        else if (!strcmp(argv[2], "unfullscreen")) zwlr_foreign_toplevel_handle_v1_unset_fullscreen(target->handle);
        else if (!strcmp(argv[2], "close")) zwlr_foreign_toplevel_handle_v1_close(target->handle);
        else return 6;
        if (wl_display_roundtrip(display) < 0) return 7;
    }
    wl_display_disconnect(display);
    return 0;
}
