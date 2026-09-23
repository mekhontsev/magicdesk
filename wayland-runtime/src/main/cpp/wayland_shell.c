#include "wayland_internal.h"
#include <stdlib.h>
#include <wlr/backend/headless.h>
#include <wlr/types/wlr_layer_shell_v1.h>
#include <wlr/types/wlr_xdg_shell.h>

struct MdwLayerSurface {
    struct wl_list link;
    struct MdwView view;
    struct wlr_layer_surface_v1 *layer;
    struct wlr_scene_tree *tree;
    struct wl_listener commit, map, unmap, destroy, popup;
    MdwShellSurface published;
    struct wlr_box configured;
    bool configured_once;
};

static void listen(struct wl_signal *signal, struct wl_listener *listener,
        void (*notify)(struct wl_listener *, void *)) {
    listener->notify = notify;
    wl_signal_add(signal, listener);
}

static bool same_state(const MdwShellSurface *a, const MdwShellSurface *b) {
    return a->id == b->id && a->mapped == b->mapped &&
        a->configure_needed == b->configure_needed && a->layer == b->layer &&
        a->keyboard == b->keyboard && a->anchors == b->anchors &&
        a->width == b->width && a->height == b->height &&
        a->margin_left == b->margin_left && a->margin_top == b->margin_top &&
        a->margin_right == b->margin_right && a->margin_bottom == b->margin_bottom &&
        a->exclusive_zone == b->exclusive_zone;
}

static void publish(struct MdwLayerSurface *layer, bool force) {
    const struct wlr_layer_surface_v1_state *state = &layer->layer->current;
    MdwShellSurface info = {
        .id = layer->view.id, .name = layer->layer->namespace,
        .mapped = layer->view.surface->mapped,
        .configure_needed = layer->layer->initialized && !layer->configured_once,
        .layer = (MdwLayer)state->layer,
        .keyboard = state->keyboard_interactive == ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_EXCLUSIVE
            ? MDW_KEYBOARD_EXCLUSIVE
            : state->keyboard_interactive == ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_ON_DEMAND
                ? MDW_KEYBOARD_ON_DEMAND : MDW_KEYBOARD_NONE,
        .anchors = ((state->anchor & ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT) ? MDW_ANCHOR_LEFT : 0) |
            ((state->anchor & ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP) ? MDW_ANCHOR_TOP : 0) |
            ((state->anchor & ZWLR_LAYER_SURFACE_V1_ANCHOR_RIGHT) ? MDW_ANCHOR_RIGHT : 0) |
            ((state->anchor & ZWLR_LAYER_SURFACE_V1_ANCHOR_BOTTOM) ? MDW_ANCHOR_BOTTOM : 0),
        .width = state->desired_width, .height = state->desired_height,
        .margin_left = state->margin.left, .margin_top = state->margin.top,
        .margin_right = state->margin.right, .margin_bottom = state->margin.bottom,
        .exclusive_zone = state->exclusive_zone,
    };
    mdw_view_keyboard(&layer->view, info.keyboard != MDW_KEYBOARD_NONE);
    if (!force && same_state(&info, &layer->published)) return;
    layer->published = info;
    MdwServer *server = layer->view.server;
    if (server->events.shell) server->events.shell(server->events.context, info.id, &info);
}

static void layer_commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwLayerSurface *layer = wl_container_of(listener, layer, commit);
    if (!layer->layer->initialized) return;
    if (layer->layer->initial_commit) layer->configured_once = false;
    publish(layer, layer->layer->initial_commit);
}

static void layer_map(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwLayerSurface *layer = wl_container_of(listener, layer, map);
    publish(layer, false);
}

static void layer_unmap(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwLayerSurface *layer = wl_container_of(listener, layer, unmap);
    mdw_view_unmap(&layer->view);
    publish(layer, false);
}

static void layer_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwLayerSurface *layer = wl_container_of(listener, layer, destroy);
    MdwServer *server = layer->view.server;
    mdw_view_finish(&layer->view);
    wl_list_remove(&layer->link);
    wl_list_remove(&layer->commit.link);
    wl_list_remove(&layer->map.link);
    wl_list_remove(&layer->unmap.link);
    wl_list_remove(&layer->destroy.link);
    wl_list_remove(&layer->popup.link);
    if (server->events.shell) server->events.shell(server->events.context, layer->view.id, NULL);
    free(layer);
}

static void layer_close(struct MdwView *view) {
    struct MdwLayerSurface *layer = wl_container_of(view, layer, view);
    wlr_layer_surface_v1_destroy(layer->layer);
}

static void layer_popup(struct wl_listener *listener, void *data) {
    struct MdwLayerSurface *layer = wl_container_of(listener, layer, popup);
    mdw_popup_create(&layer->view, data, layer->tree);
}

static void popup_bounds(struct MdwLayerSurface *layer) {
    if (!layer->configured_once) return;
    const struct wlr_output *output = layer->view.server->shell_output;
    mdw_view_popup_bounds(&layer->view, &(struct wlr_box) {
        -layer->configured.x, -layer->configured.y, output->width, output->height,
    });
}

static void new_layer(struct wl_listener *listener, void *data) {
    MdwServer *server = wl_container_of(listener, server, new_layer);
    struct wlr_layer_surface_v1 *surface = data;
    if (!server->shell_output || !server->events.shell ||
            (surface->output && surface->output != server->shell_output) ||
            wl_list_length(&server->layers) >= 32) {
        wlr_layer_surface_v1_destroy(surface);
        return;
    }
    surface->output = server->shell_output;
    struct MdwLayerSurface *layer = calloc(1, sizeof(*layer));
    if (!layer || !mdw_view_init(server, &layer->view, surface->surface)) {
        free(layer);
        wl_client_post_no_memory(wl_resource_get_client(surface->resource));
        return;
    }
    layer->layer = surface;
    layer->view.transparent = true;
    layer->view.close = layer_close;
    layer->tree = wlr_scene_tree_create(&layer->view.scene->tree);
    if (!layer->tree || !wlr_scene_subsurface_tree_create(layer->tree, surface->surface)) {
        mdw_view_finish(&layer->view);
        free(layer);
        wl_client_post_no_memory(wl_resource_get_client(surface->resource));
        return;
    }
    wl_list_insert(server->layers.prev, &layer->link);
    listen(&surface->surface->events.commit, &layer->commit, layer_commit);
    listen(&surface->surface->events.map, &layer->map, layer_map);
    listen(&surface->surface->events.unmap, &layer->unmap, layer_unmap);
    listen(&surface->events.destroy, &layer->destroy, layer_destroy);
    listen(&surface->events.new_popup, &layer->popup, layer_popup);
    mdw_view_observe(&layer->view);
}

bool mdw_server_shell_output(MdwServer *server, int width, int height) {
    if (width == 0 && height == 0) {
        struct MdwLayerSurface *layer, *next;
        wl_list_for_each_safe(layer, next, &server->layers, link)
            wlr_layer_surface_v1_destroy(layer->layer);
        if (server->shell_output) wlr_output_destroy(server->shell_output);
        server->shell_output = NULL;
        return true;
    }
    if (width < 1 || height < 1 || width > 16384 || height > 16384 || !server->events.shell)
        return false;
    const bool creating = !server->shell_output;
    if (creating) {
        server->shell_output = wlr_headless_add_output(server->backend, width, height);
        if (!server->shell_output) return false;
        wlr_output_set_name(server->shell_output, "MagicDesk");
        wlr_output_set_description(server->shell_output, "MagicDesk workspace");
    }
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_enabled(&state, true);
    wlr_output_state_set_custom_mode(&state, width, height, 60000);
    bool committed = wlr_output_commit_state(server->shell_output, &state);
    wlr_output_state_finish(&state);
    if (!committed) {
        if (creating) {
            wlr_output_destroy(server->shell_output);
            server->shell_output = NULL;
        }
        return false;
    }
    wlr_output_create_global(server->shell_output, server->display);
    struct MdwLayerSurface *layer;
    wl_list_for_each(layer, &server->layers, link) popup_bounds(layer);
    if (!server->layer_shell) {
        server->layer_shell = wlr_layer_shell_v1_create(server->display, 4);
        if (!server->layer_shell) {
            mdw_server_shell_output(server, 0, 0);
            return false;
        }
        listen(&server->layer_shell->events.new_surface, &server->new_layer, new_layer);
    }
    return true;
}

bool mdw_shell_surface_configure(MdwServer *server, uint64_t id,
        int x, int y, int width, int height) {
    if (!server->shell_output || width < 1 || height < 1 || width > 4096 || height > 4096 ||
            x < 0 || y < 0 || (int64_t)x + width > server->shell_output->width ||
            (int64_t)y + height > server->shell_output->height) return false;
    struct MdwLayerSurface *layer;
    wl_list_for_each(layer, &server->layers, link) {
        if (layer->view.id != id) continue;
        if (!layer->layer->initialized) return false;
        bool resize = !layer->configured_once || layer->configured.width != width ||
            layer->configured.height != height;
        layer->configured = (struct wlr_box){x, y, width, height};
        layer->configured_once = true;
        popup_bounds(layer);
        if (resize) wlr_layer_surface_v1_configure(layer->layer, width, height);
        return true;
    }
    return false;
}

void mdw_shell_finish(MdwServer *server) {
    mdw_server_shell_output(server, 0, 0);
    if (server->layer_shell) wl_list_remove(&server->new_layer.link);
}
