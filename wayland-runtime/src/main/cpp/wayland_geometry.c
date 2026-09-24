#include "wayland_internal.h"
#include <stdlib.h>
#include <limits.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/util/addon.h>

struct Watch {
    struct wl_list link;
    struct wlr_addon addon;
    struct MdwView *view;
    struct wlr_scene_surface *scene;
    struct wl_listener commit, map, unmap, subsurface;
    pixman_region32_t input;
    int x, y, width, height;
    bool enabled, oversized;
};

static void changed(struct MdwView *view);

struct Geometry {
    struct MdwView *view;
    struct wlr_box paint;
    bool complete, dependents;
};

static void collect(struct wlr_scene_buffer *buffer, int x, int y, void *data) {
    struct Geometry *geometry = data;
    struct MdwView *view = geometry->view;
    struct wlr_scene_surface *scene = wlr_scene_surface_try_from_buffer(buffer);
    if (!scene || !buffer->buffer) return;
    struct wlr_surface *surface = scene->surface;
    if (geometry->dependents && wlr_surface_get_root_surface(surface) == view->surface) return;
    struct wlr_box box = {x, y, surface->current.width, surface->current.height};
    if (wlr_box_empty(&box)) return;
    int64_t right = (int64_t)x + box.width, bottom = (int64_t)y + box.height;
    if (right > INT_MAX || bottom > INT_MAX) { geometry->complete = false; return; }
    if (wlr_box_empty(&geometry->paint)) geometry->paint = box;
    else {
        if (right < (int64_t)geometry->paint.x + geometry->paint.width) right = (int64_t)geometry->paint.x + geometry->paint.width;
        if (bottom < (int64_t)geometry->paint.y + geometry->paint.height) bottom = (int64_t)geometry->paint.y + geometry->paint.height;
        int left = geometry->paint.x < x ? geometry->paint.x : x;
        int top = geometry->paint.y < y ? geometry->paint.y : y;
        if (right - left > INT_MAX || bottom - top > INT_MAX) { geometry->complete = false; return; }
        geometry->paint = (struct wlr_box){left, top, right - left, bottom - top};
    }
    int count;
    const pixman_box32_t *rects = pixman_region32_rectangles(&surface->input_region, &count);
    if (count > MDW_MAX_INPUT_RECTS || !geometry->complete) {
        geometry->complete = false;
        return;
    }
    for (int i = 0; i < count; ++i) {
        if (!pixman_region32_union_rect(&view->geometry_scratch, &view->geometry_scratch,
                x + rects[i].x1, y + rects[i].y1, rects[i].x2 - rects[i].x1, rects[i].y2 - rects[i].y1))
            geometry->complete = false;
        if (pixman_region32_n_rects(&view->geometry_scratch) > MDW_MAX_INPUT_RECTS) {
            geometry->complete = false;
            break;
        }
    }
}

static void publish_geometry(struct MdwView *view, bool dependents) {
    struct wlr_box *paint = dependents ? &view->dependent_paint : &view->paint;
    pixman_region32_t *input = dependents ? &view->dependent_input : &view->input;
    bool *complete_state = dependents ? &view->dependent_complete : &view->geometry_complete;
    struct Geometry collecting = {.view = view, .complete = true, .dependents = dependents};
    pixman_region32_clear(&view->geometry_scratch);
    bool mapped = view->surface->mapped;
    if (mapped) wlr_scene_node_for_each_buffer(&view->scene->tree.node, collect, &collecting);
    if (view->geometry_revision && mapped == view->geometry_mapped &&
            *complete_state == collecting.complete && wlr_box_equal(paint, &collecting.paint) &&
            pixman_region32_equal(input, &view->geometry_scratch)) return;
    *paint = collecting.paint;
    pixman_region32_t swap = *input;
    *input = view->geometry_scratch;
    view->geometry_scratch = swap;
    *complete_state = collecting.complete;
    ++view->geometry_revision;
    if (!view->server->events.geometry) return;
    int count;
    const pixman_box32_t *boxes = pixman_region32_rectangles(input, &count);
    MdwRect rects[MDW_MAX_INPUT_RECTS];
    bool complete = *complete_state && count <= MDW_MAX_INPUT_RECTS;
    if (complete) for (int i = 0; i < count; ++i)
        rects[i] = (MdwRect){boxes[i].x1, boxes[i].y1, boxes[i].x2, boxes[i].y2};
    MdwViewGeometry geometry = {
        .id = view->id, .revision = view->geometry_revision, .mapped = mapped,
        .paint = {paint->x, paint->y, paint->x + paint->width, paint->y + paint->height},
        .input_complete = complete, .input = rects, .input_count = complete ? (size_t)count : 0,
        .dependents = dependents,
    };
    view->server->events.geometry(view->server->events.context, &geometry);
}

static void publish(void *data) {
    struct MdwView *view = data;
    view->geometry_idle = NULL;
    publish_geometry(view, false);
    if (!view->transparent) publish_geometry(view, true);
    view->geometry_mapped = view->surface->mapped;
}

static void changed(struct MdwView *view) {
    if (!view->finishing && !view->geometry_idle) {
        view->geometry_idle = wl_event_loop_add_idle(wl_display_get_event_loop(view->server->display), publish, view);
        if (!view->geometry_idle) wl_client_post_no_memory(wl_resource_get_client(view->surface->resource));
    }
}

static void scan(struct MdwView *view) {
    struct Watch *watch;
    wl_list_for_each(watch, &view->watches, link) {
        struct wlr_surface *surface = watch->scene->surface;
        int x, y;
        bool enabled = wlr_scene_node_coords(&watch->scene->buffer->node, &x, &y) && surface->mapped;
        bool oversized = pixman_region32_n_rects(&surface->input_region) > MDW_MAX_INPUT_RECTS;
        if (watch->x == x && watch->y == y && watch->enabled == enabled &&
                watch->width == surface->current.width && watch->height == surface->current.height &&
                watch->oversized == oversized && (oversized ||
                    pixman_region32_equal(&watch->input, &surface->input_region))) continue;
        watch->x = x; watch->y = y; watch->enabled = enabled;
        watch->oversized = oversized;
        watch->width = surface->current.width; watch->height = surface->current.height;
        if (oversized) pixman_region32_clear(&watch->input);
        else if (!pixman_region32_copy(&watch->input, &surface->input_region))
            wl_client_post_no_memory(wl_resource_get_client(surface->resource));
        changed(view);
    }
}

static void commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct Watch *watch = wl_container_of(listener, watch, commit);
    // A parent commit can also move synchronized subsurfaces. Compare cached scene
    // geometry, so pixel-only animation neither allocates idle work nor publishes metadata.
    scan(watch->view);
}

static void map(struct wl_listener *listener, void *data) {
    (void)data;
    struct Watch *watch = wl_container_of(listener, watch, map);
    changed(watch->view);
}

static void unmap(struct wl_listener *listener, void *data) {
    (void)data;
    struct Watch *watch = wl_container_of(listener, watch, unmap);
    changed(watch->view);
}

static void subsurface(struct wl_listener *listener, void *data) {
    (void)data;
    struct Watch *watch = wl_container_of(listener, watch, subsurface);
    mdw_view_observe(watch->view);
}

static void destroy(struct wlr_addon *addon) {
    struct Watch *watch = wl_container_of(addon, watch, addon);
    changed(watch->view);
    wl_list_remove(&watch->commit.link);
    wl_list_remove(&watch->map.link);
    wl_list_remove(&watch->unmap.link);
    wl_list_remove(&watch->subsurface.link);
    wl_list_remove(&watch->link);
    wlr_addon_finish(addon);
    pixman_region32_fini(&watch->input);
    free(watch);
}

static const struct wlr_addon_interface watch_impl = {.name = "magicdesk-view-geometry", .destroy = destroy};

static void observe(struct MdwView *view, struct wlr_scene_node *node) {
    if (node->type == WLR_SCENE_NODE_TREE) {
        struct wlr_scene_node *child;
        wl_list_for_each(child, &wlr_scene_tree_from_node(node)->children, link) observe(view, child);
    } else if (node->type == WLR_SCENE_NODE_BUFFER) {
        struct wlr_scene_surface *scene = wlr_scene_surface_try_from_buffer(wlr_scene_buffer_from_node(node));
        if (!scene || wlr_addon_find(&node->addons, view, &watch_impl)) return;
        struct Watch *watch = calloc(1, sizeof(*watch));
        if (!watch) { wl_client_post_no_memory(wl_resource_get_client(scene->surface->resource)); return; }
        watch->view = view;
        watch->scene = scene;
        pixman_region32_init(&watch->input);
        wlr_addon_init(&watch->addon, &node->addons, view, &watch_impl);
        wl_list_insert(view->watches.prev, &watch->link);
        watch->commit.notify = commit;
        wl_signal_add(&scene->surface->events.commit, &watch->commit);
        watch->map.notify = map;
        wl_signal_add(&scene->surface->events.map, &watch->map);
        watch->unmap.notify = unmap;
        wl_signal_add(&scene->surface->events.unmap, &watch->unmap);
        watch->subsurface.notify = subsurface;
        wl_signal_add(&scene->surface->events.new_subsurface, &watch->subsurface);
        changed(view);
    }
}

void mdw_view_observe(struct MdwView *view) {
    if (!view->server->events.geometry) return;
    observe(view, &view->scene->tree.node);
    scan(view);
}

void mdw_view_geometry_finish(struct MdwView *view) {
    view->finishing = true;
    if (view->geometry_idle) wl_event_source_remove(view->geometry_idle);
    view->geometry_idle = NULL;
    struct Watch *watch, *next;
    wl_list_for_each_safe(watch, next, &view->watches, link) destroy(&watch->addon);
    pixman_region32_fini(&view->input);
    pixman_region32_fini(&view->geometry_scratch);
    pixman_region32_fini(&view->dependent_input);
}
