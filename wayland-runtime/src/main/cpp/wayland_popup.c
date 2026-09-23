#include "wayland_internal.h"
#include <stdlib.h>
#include <wlr/types/wlr_xdg_shell.h>

struct MdwPopup {
    struct wl_list link;
    struct MdwView *view;
    struct wlr_xdg_popup *xdg;
    struct wl_listener commit, destroy, reposition;
};

static void constrain(struct MdwPopup *popup) {
    if (!wlr_box_empty(&popup->view->popup_bounds))
        wlr_xdg_popup_unconstrain_from_box(popup->xdg, &popup->view->popup_bounds);
    else wlr_xdg_surface_schedule_configure(popup->xdg->base);
}

static void commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwPopup *popup = wl_container_of(listener, popup, commit);
    if (popup->xdg->base->initial_commit) constrain(popup);
}

static void reposition(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwPopup *popup = wl_container_of(listener, popup, reposition);
    constrain(popup);
}

static void destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwPopup *popup = wl_container_of(listener, popup, destroy);
    wl_list_remove(&popup->link);
    wl_list_remove(&popup->commit.link);
    wl_list_remove(&popup->destroy.link);
    wl_list_remove(&popup->reposition.link);
    free(popup);
}

void mdw_popup_create(struct MdwView *view, struct wlr_xdg_popup *xdg, struct wlr_scene_tree *parent) {
    struct MdwPopup *popup = calloc(1, sizeof(*popup));
    if (!popup) { wl_client_post_no_memory(wl_resource_get_client(xdg->resource)); return; }
    xdg->base->data = wlr_scene_xdg_surface_create(parent, xdg->base);
    if (!xdg->base->data) {
        free(popup);
        wl_client_post_no_memory(wl_resource_get_client(xdg->resource));
        return;
    }
    popup->view = view;
    popup->xdg = xdg;
    wl_list_insert(view->popups.prev, &popup->link);
    popup->commit.notify = commit;
    wl_signal_add(&xdg->base->surface->events.commit, &popup->commit);
    popup->destroy.notify = destroy;
    wl_signal_add(&xdg->events.destroy, &popup->destroy);
    popup->reposition.notify = reposition;
    wl_signal_add(&xdg->events.reposition, &popup->reposition);
    mdw_view_observe(view);
}

void mdw_view_popup_bounds(struct MdwView *view, const struct wlr_box *bounds) {
    if (wlr_box_equal(&view->popup_bounds, bounds)) return;
    view->popup_bounds = *bounds;
    struct MdwPopup *popup;
    wl_list_for_each(popup, &view->popups, link) {
        if (popup->xdg->base->initialized) constrain(popup);
    }
}

void mdw_view_popups_finish(struct MdwView *view) {
    // Destroying an ancestor also destroys descendants; take a fresh list head each time.
    while (!wl_list_empty(&view->popups)) {
        struct MdwPopup *popup = wl_container_of(view->popups.next, popup, link);
        wlr_xdg_popup_destroy(popup->xdg);
    }
}
