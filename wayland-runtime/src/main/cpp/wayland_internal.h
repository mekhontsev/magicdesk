#ifndef MAGICDESK_WAYLAND_INTERNAL_H
#define MAGICDESK_WAYLAND_INTERNAL_H

#include "wayland_server.h"
#include <wayland-server-core.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/types/wlr_scene.h>

struct wlr_xdg_popup;

/* One rendered surface family, independent of its xdg or shell role. */
struct MdwView {
    struct wl_list link;
    MdwServer *server;
    uint64_t id;
    struct wlr_surface *surface;
    struct wlr_scene *scene;
    struct wl_list watches, popups;
    struct wl_event_source *geometry_idle;
    pixman_region32_t input, geometry_scratch;
    struct wlr_box paint, popup_bounds;
    uint64_t geometry_revision;
    bool geometry_mapped, geometry_complete, collecting_complete, finishing;
    bool transparent;
    bool keyboard_allowed;
    void (*configure)(struct MdwView *, int width, int height);
    void (*activate)(struct MdwView *, bool active);
    void (*close)(struct MdwView *);
};

struct MdwServer {
    struct wl_display *display;
    struct wlr_backend *backend;
    struct wlr_renderer *renderer;
    struct wlr_allocator *allocator;
    struct wlr_xdg_shell *shell;
    struct wlr_layer_shell_v1 *layer_shell;
    struct wlr_output *shell_output;
    struct wlr_foreign_toplevel_manager_v1 *foreign_manager;
    struct wl_list foreign_windows;
    struct wlr_seat *seat;
    struct wlr_keyboard keyboard;
    bool keyboard_initialized;
    struct wl_listener modifiers;
    struct wl_listener selection;
    MdwOutput *pointer_owner, *keyboard_owner;
    struct wl_listener new_toplevel, new_popup;
    struct wl_listener new_layer;
    struct wl_list windows, views, outputs, layers;
    uint64_t next_id;
    MdwEvents events;
    const char *socket;
};

bool mdw_view_init(MdwServer *server, struct MdwView *view, struct wlr_surface *surface);
void mdw_view_finish(struct MdwView *view);
void mdw_view_unmap(struct MdwView *view);
void mdw_view_keyboard(struct MdwView *view, bool allowed);
void mdw_view_observe(struct MdwView *view);
void mdw_view_geometry_finish(struct MdwView *view);
void mdw_popup_create(struct MdwView *view, struct wlr_xdg_popup *popup, struct wlr_scene_tree *parent);
void mdw_view_popup_bounds(struct MdwView *view, const struct wlr_box *bounds);
void mdw_view_popups_finish(struct MdwView *view);
void mdw_shell_finish(MdwServer *server);
void mdw_toplevels_clear(MdwServer *server);
bool mdw_toplevels_prepare(MdwServer *server);
bool mdw_scene_render_transparent(struct wlr_scene_output *output);

#endif
