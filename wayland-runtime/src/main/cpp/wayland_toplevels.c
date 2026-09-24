#include "wayland_internal.h"
#include <stdlib.h>
#include <string.h>
#include <wlr/types/wlr_foreign_toplevel_management_v1.h>

struct MdwForeign {
    struct wl_list link;
    MdwServer *server;
    uint64_t id;
    struct wlr_foreign_toplevel_handle_v1 *handle;
    struct wl_listener activate, maximize, fullscreen, minimize, close;
};

static void request(struct MdwForeign *item, MdwToplevelAction action) {
    if (item->server->shell_output && item->server->events.toplevel_action)
        item->server->events.toplevel_action(item->server->events.context, item->id, action);
}

static void activate(struct wl_listener *listener, void *data) {
    struct MdwForeign *item = wl_container_of(listener, item, activate);
    struct wlr_foreign_toplevel_handle_v1_activated_event *event = data;
    if (event->seat == item->server->seat) request(item, MDW_TOPLEVEL_ACTIVATE);
}
static void maximize(struct wl_listener *listener, void *data) {
    struct MdwForeign *item = wl_container_of(listener, item, maximize);
    struct wlr_foreign_toplevel_handle_v1_maximized_event *event = data;
    request(item, event->maximized ? MDW_TOPLEVEL_MAXIMIZE : MDW_TOPLEVEL_UNMAXIMIZE);
}
static void fullscreen(struct wl_listener *listener, void *data) {
    struct MdwForeign *item = wl_container_of(listener, item, fullscreen);
    struct wlr_foreign_toplevel_handle_v1_fullscreen_event *event = data;
    if (!event->output || event->output == item->server->shell_output)
        request(item, event->fullscreen ? MDW_TOPLEVEL_FULLSCREEN : MDW_TOPLEVEL_UNFULLSCREEN);
}
static void minimize(struct wl_listener *listener, void *data) {
    struct MdwForeign *item = wl_container_of(listener, item, minimize);
    struct wlr_foreign_toplevel_handle_v1_minimized_event *event = data;
    request(item, event->minimized ? MDW_TOPLEVEL_MINIMIZE : MDW_TOPLEVEL_UNMINIMIZE);
}
static void close_window(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwForeign *item = wl_container_of(listener, item, close);
    request(item, MDW_TOPLEVEL_CLOSE);
}

static void remove_window(struct MdwForeign *item) {
    wl_list_remove(&item->activate.link);
    wl_list_remove(&item->maximize.link);
    wl_list_remove(&item->fullscreen.link);
    wl_list_remove(&item->minimize.link);
    wl_list_remove(&item->close.link);
    wl_list_remove(&item->link);
    wlr_foreign_toplevel_handle_v1_destroy(item->handle);
    free(item);
}

void mdw_toplevels_clear(MdwServer *server) {
    struct MdwForeign *item, *next;
    wl_list_for_each_safe(item, next, &server->foreign_windows, link) remove_window(item);
}

bool mdw_toplevels_prepare(MdwServer *server) {
    if (!server->foreign_manager)
        server->foreign_manager = wlr_foreign_toplevel_manager_v1_create(server->display);
    return server->foreign_manager != NULL;
}

bool mdw_server_toplevel(MdwServer *server, uint64_t id, const char *title, const char *app_id,
        bool active, bool maximized, bool fullscreen_state, bool minimized, bool removed) {
    if (!id || !server->shell_output) return false;
    struct MdwForeign *item = NULL, *candidate;
    wl_list_for_each(candidate, &server->foreign_windows, link) if (candidate->id == id) { item = candidate; break; }
    if (removed) { if (item) remove_window(item); return true; }
    if (!title || !app_id || strlen(title) > 4096 || strlen(app_id) > 1024) return false;
    if (!item) {
        if (wl_list_length(&server->foreign_windows) >= 256) return false;
        if (!mdw_toplevels_prepare(server)) return false;
        item = calloc(1, sizeof(*item));
        if (!item) return false;
        item->handle = wlr_foreign_toplevel_handle_v1_create(server->foreign_manager);
        if (!item->handle) { free(item); return false; }
        item->server = server;
        item->id = id;
        item->activate.notify = activate;
        item->maximize.notify = maximize;
        item->fullscreen.notify = fullscreen;
        item->minimize.notify = minimize;
        item->close.notify = close_window;
        wl_signal_add(&item->handle->events.request_activate, &item->activate);
        wl_signal_add(&item->handle->events.request_maximize, &item->maximize);
        wl_signal_add(&item->handle->events.request_fullscreen, &item->fullscreen);
        wl_signal_add(&item->handle->events.request_minimize, &item->minimize);
        wl_signal_add(&item->handle->events.request_close, &item->close);
        wl_list_insert(server->foreign_windows.prev, &item->link);
        wlr_foreign_toplevel_handle_v1_output_enter(item->handle, server->shell_output);
    }
    wlr_foreign_toplevel_handle_v1_set_title(item->handle, title);
    wlr_foreign_toplevel_handle_v1_set_app_id(item->handle, app_id);
    wlr_foreign_toplevel_handle_v1_set_activated(item->handle, active);
    wlr_foreign_toplevel_handle_v1_set_maximized(item->handle, maximized);
    wlr_foreign_toplevel_handle_v1_set_fullscreen(item->handle, fullscreen_state);
    wlr_foreign_toplevel_handle_v1_set_minimized(item->handle, minimized);
    return true;
}
