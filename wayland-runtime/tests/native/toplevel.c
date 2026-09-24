#include "wayland_server.h"
#include "wlr-foreign-toplevel-management-unstable-v1-client-protocol.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#include <wayland-client.h>

struct Client {
    struct wl_display *display;
    struct wl_seat *seat;
    struct zwlr_foreign_toplevel_manager_v1 *manager;
    unsigned stage, created, closed;
    bool active, fullscreen, maximized, minimized;
    char title[64], app[64];
};
static void title(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, const char *value) {
    (void)handle; snprintf(((struct Client *)data)->title, 64, "%s", value);
}
static void app(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, const char *value) {
    (void)handle; snprintf(((struct Client *)data)->app, 64, "%s", value);
}
static void output(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, struct wl_output *value) {
    (void)data; (void)handle; (void)value;
}
static void state(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle, struct wl_array *states) {
    (void)handle;
    struct Client *c = data;
    c->active = c->fullscreen = c->maximized = c->minimized = false;
    uint32_t *value;
    wl_array_for_each(value, states) {
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_ACTIVATED) c->active = true;
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_FULLSCREEN) c->fullscreen = true;
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_MAXIMIZED) c->maximized = true;
        if (*value == ZWLR_FOREIGN_TOPLEVEL_HANDLE_V1_STATE_MINIMIZED) c->minimized = true;
    }
}
static void done(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle) {
    struct Client *c = data;
    assert(!strcmp(c->app, "test.application"));
    if (c->created == 2) {
        assert(!strcmp(c->title, "Replacement") && c->closed == 1);
        zwlr_foreign_toplevel_handle_v1_close(handle);
        return;
    }
    assert(!strcmp(c->title, "Workspace application"));
    switch (c->stage++) {
        case 0:
            assert(!c->active && !c->fullscreen && !c->maximized);
            zwlr_foreign_toplevel_handle_v1_activate(handle, c->seat); break;
        case 1:
            assert(c->active);
            zwlr_foreign_toplevel_handle_v1_set_fullscreen(handle, NULL); break;
        case 2:
            assert(c->fullscreen);
            zwlr_foreign_toplevel_handle_v1_unset_fullscreen(handle); break;
        case 3:
            assert(!c->fullscreen);
            zwlr_foreign_toplevel_handle_v1_set_maximized(handle); break;
        case 4:
            assert(c->maximized);
            zwlr_foreign_toplevel_handle_v1_unset_maximized(handle); break;
        case 5:
            assert(!c->maximized);
            zwlr_foreign_toplevel_handle_v1_set_minimized(handle); break;
        case 6:
            assert(c->minimized && !c->active);
            zwlr_foreign_toplevel_handle_v1_unset_minimized(handle); break;
        case 7:
            assert(!c->minimized && c->active);
            zwlr_foreign_toplevel_handle_v1_close(handle); break;
        default: abort();
    }
}
static void closed(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle) {
    struct Client *c = data;
    c->closed++;
    zwlr_foreign_toplevel_handle_v1_destroy(handle);
}
static void parent(void *data, struct zwlr_foreign_toplevel_handle_v1 *handle,
        struct zwlr_foreign_toplevel_handle_v1 *value) { (void)data; (void)handle; (void)value; }
static const struct zwlr_foreign_toplevel_handle_v1_listener handle_listener = {
    .title = title, .app_id = app, .output_enter = output, .output_leave = output,
    .state = state, .done = done, .closed = closed, .parent = parent,
};
static void toplevel(void *data, struct zwlr_foreign_toplevel_manager_v1 *manager,
        struct zwlr_foreign_toplevel_handle_v1 *handle) {
    (void)manager;
    struct Client *c = data;
    c->created++;
    zwlr_foreign_toplevel_handle_v1_add_listener(handle, &handle_listener, c);
}
static const struct zwlr_foreign_toplevel_manager_v1_listener manager_listener = {.toplevel = toplevel};
static void global(void *data, struct wl_registry *registry, uint32_t id, const char *interface, uint32_t version) {
    (void)version;
    struct Client *c = data;
    if (!strcmp(interface, "wl_seat")) c->seat = wl_registry_bind(registry, id, &wl_seat_interface, 1);
    if (!strcmp(interface, "zwlr_foreign_toplevel_manager_v1")) {
        c->manager = wl_registry_bind(registry, id, &zwlr_foreign_toplevel_manager_v1_interface, 3);
        zwlr_foreign_toplevel_manager_v1_add_listener(c->manager, &manager_listener, c);
    }
}
static const struct wl_registry_listener registry_listener = {.global = global};
static void client(const char *socket) {
    struct Client c = {.display = wl_display_connect(socket)};
    assert(c.display);
    wl_registry_add_listener(wl_display_get_registry(c.display), &registry_listener, &c);
    // EVENT_WAIT: protocol events drive each stage; CTest's deadline fails a missing event.
    while (c.closed < 2) assert(wl_display_dispatch(c.display) >= 0);
    assert(c.stage == 8 && c.created == 2);
    wl_display_disconnect(c.display);
}
struct Host { MdwServer *server; unsigned actions; bool finished, pending; uint64_t id; MdwToplevelAction action; };
static void action(void *data, uint64_t id, MdwToplevelAction action) {
    struct Host *h = data;
    assert(!h->pending);
    h->pending = true; h->id = id; h->action = action;
}
static void apply(struct Host *h) {
    if (!h->pending) return;
    h->pending = false;
    uint64_t id = h->id;
    MdwToplevelAction action = h->action;
    const MdwToplevelAction expected[] = {MDW_TOPLEVEL_ACTIVATE, MDW_TOPLEVEL_FULLSCREEN,
        MDW_TOPLEVEL_UNFULLSCREEN, MDW_TOPLEVEL_MAXIMIZE, MDW_TOPLEVEL_UNMAXIMIZE,
        MDW_TOPLEVEL_MINIMIZE, MDW_TOPLEVEL_UNMINIMIZE, MDW_TOPLEVEL_CLOSE, MDW_TOPLEVEL_CLOSE};
    assert(h->actions < 9 && action == expected[h->actions]);
    assert(id == (h->actions == 8 ? 42 : 41));
    h->actions++;
    if (action == MDW_TOPLEVEL_CLOSE) {
        assert(mdw_server_toplevel(h->server, id, NULL, NULL, false, false, false, false, true));
        if (id == 41) assert(mdw_server_toplevel(h->server, 42, "Replacement", "test.application", false, false, false, false, false));
        else h->finished = true;
    } else assert(mdw_server_toplevel(h->server, id, "Workspace application", "test.application", action != MDW_TOPLEVEL_MINIMIZE,
            action == MDW_TOPLEVEL_MAXIMIZE, action == MDW_TOPLEVEL_FULLSCREEN, action == MDW_TOPLEVEL_MINIMIZE, false));
}
static void shell(void *data, uint64_t id, const MdwShellSurface *surface) { (void)data; (void)id; (void)surface; }
int main(void) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/mdw-toplevel-XXXXXX", getenv("TMPDIR") ? getenv("TMPDIR") : "/tmp");
    assert(mkdtemp(path) && setenv("XDG_RUNTIME_DIR", path, 1) == 0);
    struct Host h = {.server = mdw_server_create()};
    assert(h.server);
    MdwEvents events = {.context = &h, .shell = shell, .toplevel_action = action};
    mdw_server_set_events(h.server, &events);
    assert(!mdw_server_toplevel(h.server, 41, "Absent", "test.application", false, false, false, false, false));
    assert(mdw_server_shell_output(h.server, 800, 600));
    assert(mdw_server_toplevel(h.server, 41, "Workspace application", "test.application", false, false, false, false, false));
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) { client(mdw_server_socket(h.server)); _exit(0); }
    // EVENT_WAIT: client requests wake dispatch; CTest bounds a stalled protocol exchange.
    while (!h.finished) { assert(mdw_server_dispatch(h.server, -1) >= 0); apply(&h); }
    assert(mdw_server_dispatch(h.server, 0) >= 0);
    int status;
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(h.actions == 9 && mdw_server_shell_output(h.server, 0, 0));
    assert(!mdw_server_toplevel(h.server, 42, "Released", "test.application", false, false, false, false, false));
    mdw_server_destroy(h.server);
    assert(rmdir(path) == 0);
    puts("foreign task metadata, semantic actions, removal and workspace release passed");
    return 0;
}
