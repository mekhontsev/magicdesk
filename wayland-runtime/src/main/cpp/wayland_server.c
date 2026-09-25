#include "wayland_internal.h"
#include "hosted_window_size.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <linux/input-event-codes.h>
#include <time.h>
#include <sys/socket.h>
#include <unistd.h>
#include <wayland-server-core.h>
#include <wlr/backend/headless.h>
#include <wlr/interfaces/wlr_keyboard.h>
#include <wlr/render/allocator.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_data_device.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_subcompositor.h>
#include <wlr/types/wlr_xdg_shell.h>
#include <wlr/types/wlr_fractional_scale_v1.h>
#include <wlr/types/wlr_viewporter.h>
#include "wayland_renderer.h"
#include "wayland_dmabuf.h"
#include <drm_fourcc.h>

struct MdwToplevel {
    struct wl_list link;
    struct MdwView view;
    struct wlr_xdg_toplevel *xdg;
    struct wl_listener map, unmap, commit, destroy, title, app_id, parent;
    struct wl_listener fullscreen, maximize, move, resize;
    MdwWindow published;
    uint64_t request_serial;
    uint64_t maximize_serial;
    char *published_title, *published_app_id;
};

struct MdwOutput {
    struct wl_list link;
    MdwServer *server;
    struct MdwView *view;
    struct wlr_output *output;
    struct wlr_scene_output *scene_output;
    struct wlr_buffer *pending_frame;
    struct wl_event_source *source_ready;
    int source_ready_fd;
    struct wl_listener frame, commit;
    bool presenting;
    bool visible;
    int viewport_width, viewport_height;
    int requested_width, requested_height;
    double scale, render_scale;
    MdwOutput *parent, *dependents;
    bool keys[KEY_MAX + 1];
    bool buttons[3];
};



static void listen_signal(struct wl_signal *signal, struct wl_listener *listener,
        void (*notify)(struct wl_listener *, void *)) {
    listener->notify = notify;
    wl_signal_add(signal, listener);
}

static void report_error(MdwServer *server, const char *message) {
    if (server->events.error) server->events.error(server->events.context, message);
}

static struct MdwView *find_view(MdwServer *server, uint64_t id) {
    struct MdwView *view;
    wl_list_for_each(view, &server->views, link) {
        if (view->id == id) return view;
    }
    return NULL;
}

static void publish(struct MdwToplevel *window) {
    MdwServer *server = window->view.server;
    if (!server->events.window) return;
    uint64_t parent = 0;
    struct MdwToplevel *candidate;
    wl_list_for_each(candidate, &server->windows, link) {
        if (candidate->xdg == window->xdg->parent) parent = candidate->view.id;
    }
    struct wlr_box geometry;
    wlr_xdg_surface_get_geometry(window->xdg->base, &geometry);
    MdwWindow info = {
        .id = window->view.id, .parent = parent,
        .title = window->xdg->title ? window->xdg->title : "",
        .app_id = window->xdg->app_id ? window->xdg->app_id : "",
        .mapped = window->xdg->base->surface->mapped,
        .width = geometry.width, .height = geometry.height,
        .min_width = window->xdg->current.min_width, .min_height = window->xdg->current.min_height,
        .max_width = window->xdg->current.max_width, .max_height = window->xdg->current.max_height,
        .request_serial = window->request_serial,
        .fullscreen = window->xdg->requested.fullscreen,
        .maximize_serial = window->maximize_serial, .maximized = window->xdg->requested.maximized,
    };
    MdwWindow *previous = &window->published;
    if (previous->id && previous->parent == info.parent && previous->mapped == info.mapped &&
            previous->width == info.width && previous->height == info.height &&
            previous->min_width == info.min_width && previous->min_height == info.min_height &&
            previous->max_width == info.max_width && previous->max_height == info.max_height &&
            previous->request_serial == info.request_serial && previous->fullscreen == info.fullscreen &&
            previous->maximize_serial == info.maximize_serial && previous->maximized == info.maximized &&
            !strcmp(previous->title, info.title) && !strcmp(previous->app_id, info.app_id)) return;
    bool title_changed = !window->published_title || strcmp(window->published_title, info.title);
    bool app_changed = !window->published_app_id || strcmp(window->published_app_id, info.app_id);
    char *title = title_changed ? strdup(info.title) : window->published_title;
    char *app_id = app_changed ? strdup(info.app_id) : window->published_app_id;
    if (!title || !app_id) {
        if (title_changed) free(title);
        if (app_changed) free(app_id);
        report_error(server, "Cannot retain Wayland window metadata");
        return;
    }
    if (title_changed) free(window->published_title);
    if (app_changed) free(window->published_app_id);
    window->published_title = title;
    window->published_app_id = app_id;
    window->published = info;
    window->published.title = window->published_title;
    window->published.app_id = window->published_app_id;
    server->events.window(server->events.context, window->view.id, &info);
}

static void output_commit(struct wl_listener *listener, void *data) {
    MdwOutput *output = wl_container_of(listener, output, commit);
    const struct wlr_output_event_commit *event = data;
    if (!output->presenting || !(event->state->committed & WLR_OUTPUT_STATE_BUFFER) ||
            !event->state->buffer || !output->server->events.frame) return;
    struct wlr_buffer *buffer = event->state->buffer;
    MdgImage *image = mdw_buffer_image(buffer);
    if (!image) {
        report_error(output->server, "unknown renderer buffer");
        return;
    }
    mdw_output_frame_consumed(output);
    output->pending_frame = wlr_buffer_lock(buffer);
    MdwFrame frame = { .image = image,
        .width = buffer->width, .height = buffer->height };
    output->server->events.frame(output->server->events.context, output, &frame);
}

void mdw_output_frame_consumed(MdwOutput *output) {
    if (output->pending_frame) wlr_buffer_unlock(output->pending_frame);
    output->pending_frame = NULL;
}

static int gpu_ready(int fd, uint32_t mask, void *data) {
    (void)fd; (void)mask;
    MdwServer *server = data;
    wl_event_source_remove(server->gpu_ready);
    server->gpu_ready = NULL;
    close(server->gpu_ready_fd);
    mdg_device_collect(mdw_renderer_device(server->renderer));
    MdwOutput *output;
    wl_list_for_each(output, &server->outputs, link)
        if (output->output && output->visible) wlr_output_schedule_frame(output->output);
    return 0;
}

static void cancel_source_wait(MdwOutput *output) {
    if (!output->source_ready) return;
    wl_event_source_remove(output->source_ready);
    output->source_ready = NULL;
    close(output->source_ready_fd);
}

static int source_ready(int fd, uint32_t mask, void *data) {
    (void)fd;
    MdwOutput *output = data;
    cancel_source_wait(output);
    if (mask & (WL_EVENT_ERROR | WL_EVENT_HANGUP)) report_error(output->server, "Source fence failed");
    else if (output->output && output->visible) wlr_output_schedule_frame(output->output);
    return 0;
}

static void output_frame(struct wl_listener *listener, void *data) {
    (void)data;
    MdwOutput *output = wl_container_of(listener, output, frame);
    /* A new scene update can supersede a producer that has not completed. */
    cancel_source_wait(output);
    if (!output->visible || !output->view || !output->view->surface->mapped) return;
    MdwEvents *events = &output->server->events;
    if (events->can_render && !events->can_render(events->context, output)) return;
    MdgDevice *device = mdw_renderer_device(output->server->renderer);
    if (!mdg_device_available(device)) {
        if (!output->server->gpu_ready) {
            int fd = mdg_device_pending_fence(device);
            if (fd < 0) {
                report_error(output->server, "Cannot export pending GPU completion");
                return;
            }
            output->server->gpu_ready_fd = fd;
            output->server->gpu_ready = wl_event_loop_add_fd(wl_display_get_event_loop(output->server->display),
                fd, WL_EVENT_READABLE, gpu_ready, output->server);
            if (!output->server->gpu_ready) {
                close(fd);
                report_error(output->server, "Cannot observe GPU completion");
            }
        }
        return;
    }
    output->presenting = true;
    bool committed = output->parent || output->dependents
        ? mdw_scene_render_family(output->scene_output, output->view->surface, output->parent != NULL)
        : output->view->transparent
        ? mdw_scene_render_transparent(output->scene_output)
        : wlr_scene_output_commit(output->scene_output, NULL);
    output->presenting = false;
    if (!committed) {
        int fd = mdw_renderer_take_deferred_fence(output->server->renderer);
        if (fd >= 0) {
            // EVENT_WAIT: producer write fence; output teardown cancels the wait.
            // Other outputs and protocol dispatch retain their independent progress.
            output->source_ready_fd = fd;
            output->source_ready = wl_event_loop_add_fd(wl_display_get_event_loop(output->server->display),
                fd, WL_EVENT_READABLE, source_ready, output);
            if (!output->source_ready) { close(fd); report_error(output->server, "Cannot observe source fence"); }
            return;
        }
        report_error(output->server, "scene commit failed");
        return;
    }
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    wlr_scene_output_send_frame_done(output->scene_output, &now);
}

static void release_output(MdwOutput *output) {
    cancel_source_wait(output);
    mdw_output_frame_consumed(output);
    mdw_content_output_released(output->server, output);
    mdw_output_focus(output, false);
    if (!output->output) return;
    wl_list_remove(&output->frame.link);
    wl_list_remove(&output->commit.link);
    if (output->scene_output) wlr_scene_output_destroy(output->scene_output);
    wlr_output_destroy(output->output);
    output->output = NULL;
    output->scene_output = NULL;
}

bool mdw_view_init(MdwServer *server, struct MdwView *view, struct wlr_surface *surface) {
    view->scene = wlr_scene_create();
    if (!view->scene) return false;
    view->scene->direct_scanout = false;
    view->server = server;
    view->surface = surface;
    view->id = ++server->next_id;
    wl_list_init(&view->watches);
    wl_list_init(&view->popups);
    pixman_region32_init(&view->input);
    pixman_region32_init(&view->geometry_scratch);
    pixman_region32_init(&view->dependent_input);
    wl_list_insert(&server->views, &view->link);
    return true;
}

void mdw_view_unmap(struct MdwView *view) {
    MdwOutput *output;
    wl_list_for_each(output, &view->server->outputs, link) {
        if (output->view != view) continue;
        mdw_output_focus(output, false);
        if (view->server->events.frame)
            view->server->events.frame(view->server->events.context, output, NULL);
    }
}

void mdw_view_finish(struct MdwView *view) {
    mdw_view_geometry_finish(view);
    mdw_view_dismiss_popups(view);
    MdwOutput *output;
    wl_list_for_each(output, &view->server->outputs, link) {
        if (output->view != view) continue;
        release_output(output);
        output->view = NULL;
    }
    wl_list_remove(&view->link);
    wlr_scene_node_destroy(&view->scene->tree.node);
}

static void window_map(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, map);
    publish(window);
}

static void window_unmap(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, unmap);
    mdw_view_unmap(&window->view);
    publish(window);
}

static void window_commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, commit);
    if (window->xdg->base->initial_commit) {
        wlr_xdg_toplevel_set_wm_capabilities(window->xdg, WLR_XDG_TOPLEVEL_WM_CAPABILITIES_FULLSCREEN
            | WLR_XDG_TOPLEVEL_WM_CAPABILITIES_MAXIMIZE);
        wlr_xdg_toplevel_set_size(window->xdg, window->xdg->parent ? 0 : 640, window->xdg->parent ? 0 : 480);
    }
    MdwWindow *previous = &window->published;
    if (previous->min_width != window->xdg->current.min_width || previous->min_height != window->xdg->current.min_height ||
            previous->max_width != window->xdg->current.max_width || previous->max_height != window->xdg->current.max_height) {
        MdwOutput *output;
        wl_list_for_each(output, &window->view.server->outputs, link) {
            if (output->view == &window->view && !output->parent)
                mdw_output_resize(output, output->requested_width, output->requested_height);
        }
    }
    publish(window);
}

static void window_title(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, title);
    publish(window);
}

static void window_app_id(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, app_id);
    publish(window);
}

static void window_parent(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, parent);
    publish(window);
}

static void window_fullscreen(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, fullscreen);
    ++window->request_serial;
    publish(window);
    wlr_xdg_surface_schedule_configure(window->xdg->base);
}

bool mdw_window_confirm_fullscreen(MdwServer *server, uint64_t id, uint64_t serial, bool fullscreen) {
    struct MdwToplevel *window;
    wl_list_for_each(window, &server->windows, link) {
        if (window->view.id != id) continue;
        if (window->request_serial != serial) return false;
        wlr_xdg_toplevel_set_fullscreen(window->xdg, fullscreen);
        return true;
    }
    return false;
}

static void window_maximize(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, maximize);
    ++window->maximize_serial;
    publish(window);
    wlr_xdg_surface_schedule_configure(window->xdg->base);
}

bool mdw_window_confirm_maximized(MdwServer *server, uint64_t id, uint64_t serial, bool maximized) {
    struct MdwToplevel *window;
    wl_list_for_each(window, &server->windows, link) {
        if (window->view.id != id) continue;
        if (window->maximize_serial != serial) return false;
        wlr_xdg_toplevel_set_maximized(window->xdg, maximized);
        return true;
    }
    return false;
}

static void window_gesture(struct MdwToplevel *window, struct wlr_seat_client *seat, uint32_t serial, uint32_t edges) {
    MdwServer *server = window->view.server;
    if (seat->seat != server->seat || !server->pointer_owner
            || server->pointer_owner->view != &window->view
            || !wlr_seat_validate_pointer_grab_serial(server->seat, window->view.surface, serial)) return;
    if (server->events.window_gesture) server->events.window_gesture(server->events.context, window->view.id, edges);
}

static void window_move(struct wl_listener *listener, void *data) {
    struct MdwToplevel *window = wl_container_of(listener, window, move);
    struct wlr_xdg_toplevel_move_event *event = data;
    window_gesture(window, event->seat, event->serial, 0);
}

static void window_resize(struct wl_listener *listener, void *data) {
    struct MdwToplevel *window = wl_container_of(listener, window, resize);
    struct wlr_xdg_toplevel_resize_event *event = data;
    window_gesture(window, event->seat, event->serial, event->edges);
}

static void window_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwToplevel *window = wl_container_of(listener, window, destroy);
    MdwServer *server = window->view.server;
    mdw_view_finish(&window->view);
    wl_list_remove(&window->link);
    wl_list_remove(&window->map.link);
    wl_list_remove(&window->unmap.link);
    wl_list_remove(&window->commit.link);
    wl_list_remove(&window->destroy.link);
    wl_list_remove(&window->title.link);
    wl_list_remove(&window->app_id.link);
    wl_list_remove(&window->parent.link);
    wl_list_remove(&window->fullscreen.link);
    wl_list_remove(&window->maximize.link);
    wl_list_remove(&window->move.link);
    wl_list_remove(&window->resize.link);
    if (server->events.window) server->events.window(server->events.context, window->view.id, NULL);
    free(window->published_title);
    free(window->published_app_id);
    free(window);
}

static void toplevel_configure(struct MdwView *view, int width, int height) {
    struct MdwToplevel *window = wl_container_of(view, window, view);
    wlr_xdg_toplevel_set_size(window->xdg, width, height);
}

static int constrain_axis(int offered, uint32_t minimum, uint32_t maximum) {
    int min = minimum > 4096 ? 4096 : minimum ? (int)minimum : 1;
    int max = !maximum || maximum > 4096 ? 4096 : (int)maximum;
    if (max < min) max = min;
    return offered < min ? min : offered > max ? max : offered;
}

static void toplevel_constrain(struct MdwView *view, int *width, int *height) {
    struct MdwToplevel *window = wl_container_of(view, window, view);
    const struct wlr_xdg_toplevel_state *state = &window->xdg->current;
    hosted_window_size(constrain_axis(1, state->min_width, state->max_width),
        constrain_axis(1, state->min_height, state->max_height),
        constrain_axis(4096, state->min_width, state->max_width),
        constrain_axis(4096, state->min_height, state->max_height), width, height);
}

static void toplevel_activate(struct MdwView *view, bool active) {
    struct MdwToplevel *window = wl_container_of(view, window, view);
    wlr_xdg_toplevel_set_activated(window->xdg, active);
}

static void toplevel_close(struct MdwView *view) {
    struct MdwToplevel *window = wl_container_of(view, window, view);
    wlr_xdg_toplevel_send_close(window->xdg);
}

static void new_toplevel(struct wl_listener *listener, void *data) {
    MdwServer *server = wl_container_of(listener, server, new_toplevel);
    struct wlr_xdg_toplevel *xdg = data;
    struct MdwToplevel *window = calloc(1, sizeof(*window));
    if (!window) { wl_client_post_no_memory(wl_resource_get_client(xdg->resource)); return; }
    window->xdg = xdg;
    if (!mdw_view_init(server, &window->view, xdg->base->surface)) {
        free(window);
        wl_client_post_no_memory(wl_resource_get_client(xdg->resource));
        return;
    }
    window->view.configure = toplevel_configure;
    window->view.constrain = toplevel_constrain;
    window->view.keyboard_allowed = true;
    window->view.activate = toplevel_activate;
    window->view.close = toplevel_close;
    struct wlr_scene_tree *tree = wlr_scene_xdg_surface_create(&window->view.scene->tree, xdg->base);
    if (!tree) {
        mdw_view_finish(&window->view);
        free(window);
        wl_client_post_no_memory(wl_resource_get_client(xdg->resource));
        return;
    }
    xdg->base->data = tree;
    wl_list_insert(&server->windows, &window->link);
    listen_signal(&xdg->base->surface->events.map, &window->map, window_map);
    listen_signal(&xdg->base->surface->events.unmap, &window->unmap, window_unmap);
    listen_signal(&xdg->base->surface->events.commit, &window->commit, window_commit);
    listen_signal(&xdg->events.destroy, &window->destroy, window_destroy);
    listen_signal(&xdg->events.set_title, &window->title, window_title);
    listen_signal(&xdg->events.set_app_id, &window->app_id, window_app_id);
    listen_signal(&xdg->events.set_parent, &window->parent, window_parent);
    listen_signal(&xdg->events.request_fullscreen, &window->fullscreen, window_fullscreen);
    listen_signal(&xdg->events.request_maximize, &window->maximize, window_maximize);
    listen_signal(&xdg->events.request_move, &window->move, window_move);
    listen_signal(&xdg->events.request_resize, &window->resize, window_resize);
    mdw_view_observe(&window->view);
    publish(window);
}

static void new_popup(struct wl_listener *listener, void *data) {
    MdwServer *server = wl_container_of(listener, server, new_popup);
    struct wlr_xdg_popup *xdg = data;
    // A layer-shell parent is assigned by get_popup before the initial commit.
    if (!xdg->parent) return;
    struct wlr_xdg_surface *parent = wlr_xdg_surface_try_from_wlr_surface(xdg->parent);
    if (!parent || !parent->data) { wlr_xdg_popup_destroy(xdg); return; }
    struct wlr_scene_tree *tree = parent->data;
    struct wlr_scene_node *root = &tree->node;
    while (root->parent) root = &root->parent->node;
    struct MdwView *view;
    wl_list_for_each(view, &server->views, link) {
        if (&view->scene->tree.node == root) { mdw_popup_create(view, xdg, tree); return; }
    }
    wlr_xdg_popup_destroy(xdg);
}

static void keyboard_modifiers(struct wl_listener *listener, void *data) {
    (void)data;
    MdwServer *server = wl_container_of(listener, server, modifiers);
    wlr_seat_keyboard_notify_modifiers(server->seat, &server->keyboard.modifiers);
}

static const struct wlr_keyboard_impl keyboard_impl = {.name = "magicdesk-host"};

MdwServer *mdw_server_create(void) {
    MdwServer *server = calloc(1, sizeof(*server));
    if (!server) return NULL;
    wl_list_init(&server->windows);
    wl_list_init(&server->views);
    wl_list_init(&server->outputs);
    wl_list_init(&server->layers);
    wl_list_init(&server->foreign_windows);
    server->display = wl_display_create();
    if (!server->display) goto fail;
    server->backend = wlr_headless_backend_create(wl_display_get_event_loop(server->display));
    if (!server->backend) goto fail;
    server->renderer = mdw_renderer_create();
    if (!server->renderer || !wlr_renderer_init_wl_display(server->renderer, server->display)) goto fail;
    if (!mdw_dmabuf_init(server->display, server->renderer)) goto fail;
    server->allocator = mdw_allocator_create(server->renderer);
    if (!server->allocator) goto fail;
    if (!wlr_compositor_create(server->display, 6, server->renderer) ||
            !wlr_subcompositor_create(server->display) ||
            !wlr_data_device_manager_create(server->display) ||
            !wlr_fractional_scale_manager_v1_create(server->display, 1) ||
            !wlr_viewporter_create(server->display)) goto fail;
    server->seat = wlr_seat_create(server->display, "magicdesk");
    if (!server->seat) goto fail;
    if (!mdw_content_init(server)) goto fail;
    wlr_keyboard_init(&server->keyboard, &keyboard_impl, "magicdesk-host");
    server->keyboard_initialized = true;
    listen_signal(&server->keyboard.events.modifiers, &server->modifiers, keyboard_modifiers);
    struct xkb_context *context = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (!context) goto fail;
    struct xkb_keymap *keymap = xkb_keymap_new_from_names(context, NULL, XKB_KEYMAP_COMPILE_NO_FLAGS);
    xkb_context_unref(context);
    if (!keymap) goto fail;
    bool keymap_ready = wlr_keyboard_set_keymap(&server->keyboard, keymap);
    xkb_keymap_unref(keymap);
    if (!keymap_ready) goto fail;
    wlr_keyboard_set_repeat_info(&server->keyboard, 25, 600);
    wlr_seat_set_keyboard(server->seat, &server->keyboard);
    wlr_seat_set_capabilities(server->seat, WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_POINTER);
    if (!mdw_input_init(server) || !mdw_cursor_init(server)) goto fail;
    server->shell = wlr_xdg_shell_create(server->display, 6);
    if (!server->shell) goto fail;
    listen_signal(&server->shell->events.new_toplevel, &server->new_toplevel, new_toplevel);
    listen_signal(&server->shell->events.new_popup, &server->new_popup, new_popup);
    server->socket = wl_display_add_socket_auto(server->display);
    if (!server->socket || !wlr_backend_start(server->backend)) goto fail;
    return server;
fail:
    mdw_server_destroy(server);
    return NULL;
}

const char *mdw_server_socket(const MdwServer *server) {
    return server->socket;
}

int mdw_server_fd(MdwServer *server) {
    return wl_event_loop_get_fd(wl_display_get_event_loop(server->display));
}

int mdw_server_connect(MdwServer *server) {
    int descriptors[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, descriptors) < 0) return -1;
    if (!wl_client_create(server->display, descriptors[0])) {
        close(descriptors[0]);
        close(descriptors[1]);
        return -1;
    }
    return descriptors[1];
}

bool mdw_output_refresh(MdwOutput *output) {
    if (!output || !output->output || !output->scene_output) return false;
    wlr_damage_ring_add_whole(&output->scene_output->damage_ring);
    if (output->visible) wlr_output_schedule_frame(output->output);
    return true;
}

void mdw_server_set_events(MdwServer *server, const MdwEvents *events) {
    server->events = events ? *events : (MdwEvents){0};
}

static MdwOutput *create_output(MdwServer *server, uint64_t id, int width, int height, bool configure) {
    struct MdwView *view = find_view(server, id);
    if (!view || width < 1 || height < 1 || width > 4096 || height > 4096) return NULL;
    MdwOutput *output = calloc(1, sizeof(*output));
    if (!output) return NULL;
    output->server = server;
    output->view = view;
    output->visible = true;
    output->scale = output->render_scale = 1;
    output->output = wlr_headless_add_output(server->backend, width, height);
    if (!output->output) { free(output); return NULL; }
    // Only application hosts are monitors. Shell and dependent render targets
    // borrow their owner's coordinates; advertising them causes monitor churn.
    if (configure && view->configure)
        wlr_output_create_global(output->output, server->display);
    if (!wlr_output_init_render(output->output, server->allocator, server->renderer)) {
        wlr_output_destroy(output->output);
        free(output);
        return NULL;
    }
    output->scene_output = wlr_scene_output_create(view->scene, output->output);
    if (!output->scene_output) { wlr_output_destroy(output->output); free(output); return NULL; }
    wl_list_insert(&server->outputs, &output->link);
    listen_signal(&output->output->events.frame, &output->frame, output_frame);
    listen_signal(&output->output->events.commit, &output->commit, output_commit);
    if (!(configure ? mdw_output_resize(output, width, height) : mdw_output_viewport(output, 0, 0, width, height))) {
        mdw_output_destroy(output); return NULL;
    }
    return output;
}

MdwOutput *mdw_output_create(MdwServer *server, uint64_t id, int width, int height) {
    return create_output(server, id, width, height, true);
}

MdwOutput *mdw_output_borrow_dependents(MdwOutput *parent) {
    if (!parent || !parent->view || !parent->output || parent->parent || parent->dependents) return NULL;
    MdwOutput *output = create_output(parent->server, parent->view->id, 1, 1, false);
    if (!output) return NULL;
    output->parent = parent;
    parent->dependents = output;
    mdw_output_refresh(parent);
    return output;
}

bool mdw_output_resize(MdwOutput *output, int width, int height) {
    if (!output || output->parent || !output->view || width < 1 || height < 1 || width > 4096 || height > 4096) return false;
    int logical_width = (int)fmax(1, round(width / output->scale));
    int logical_height = (int)fmax(1, round(height / output->scale));
    if (output->view->constrain) output->view->constrain(output->view, &logical_width, &logical_height);
    double previous = output->render_scale;
    output->render_scale = hosted_buffer_scale(logical_width, logical_height, output->scale, 4096);
    if (!mdw_output_viewport(output, 0, 0, (int)fmax(1, round(logical_width * output->render_scale)),
            (int)fmax(1, round(logical_height * output->render_scale)))) {
        output->render_scale = previous;
        return false;
    }
    output->requested_width = width; output->requested_height = height;
    if (output->view->configure) output->view->configure(output->view, logical_width, logical_height);
    return true;
}

static void surface_scale(struct wlr_surface *surface, int sx, int sy, void *data) {
    (void)sx; (void)sy;
    double scale = *(double *)data;
    wlr_surface_set_preferred_buffer_scale(surface, (int)ceil(scale));
    wlr_fractional_scale_v1_notify_scale(surface, scale);
}

bool mdw_output_scale(MdwOutput *output, double scale) {
    if (!output || output->parent || !output->view || !output->view->configure ||
            !isfinite(scale) || scale < 0.25 || scale > 16) return false;
    if (output->scale == scale) return true;
    double previous = output->scale;
    output->scale = scale;
    if (!mdw_output_resize(output, output->requested_width, output->requested_height)) {
        output->scale = previous;
        return false;
    }
    wlr_surface_for_each_surface(output->view->surface, surface_scale, &scale);
    return true;
}

bool mdw_output_viewport(MdwOutput *output, int x, int y, int width, int height) {
    if (!output || !output->view || !output->output ||
            width < 1 || height < 1 || width > 4096 || height > 4096 ||
            x < -16384 || y < -16384 || x > 16384 || y > 16384) return false;
    bool committed = true;
    // A disabled wlroots output cannot commit a new mode. Retain the requested
    // viewport and apply it atomically with the next enable, before rendering.
    if (output->visible) {
        struct wlr_output_state state;
        wlr_output_state_init(&state);
        wlr_output_state_set_render_format(&state, DRM_FORMAT_ABGR8888);
        wlr_output_state_set_enabled(&state, true);
        wlr_output_state_set_scale(&state, output->render_scale);
        wlr_output_state_set_custom_mode(&state, width, height, 60000);
        committed = wlr_output_commit_state(output->output, &state);
        wlr_output_state_finish(&state);
    }
    if (committed) {
        output->viewport_width = width; output->viewport_height = height;
        wlr_scene_output_set_position(output->scene_output, x, y);
        mdw_output_refresh(output);
        if (output->server->keyboard_owner == output) mdw_input_geometry(output->server);
    }
    return committed;
}

bool mdw_output_set_visible(MdwOutput *output, bool visible) {
    if (!output || !output->output) return false;
    if (output->visible == visible) return true;
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_enabled(&state, visible);
    if (visible) {
        wlr_output_state_set_render_format(&state, DRM_FORMAT_ABGR8888);
        wlr_output_state_set_scale(&state, output->render_scale);
        wlr_output_state_set_custom_mode(&state, output->viewport_width, output->viewport_height, 60000);
    }
    bool committed = wlr_output_commit_state(output->output, &state);
    wlr_output_state_finish(&state);
    if (!committed) return false;
    output->visible = visible;
    if (visible) mdw_output_refresh(output);
    else mdw_output_focus(output, false);
    return true;
}

static uint32_t time_msec(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint32_t)((uint64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000);
}

static bool accepts_input(MdwOutput *output) {
    return output && output->visible && output->view && output->view->surface->mapped;
}

static void key_event(MdwOutput *output, uint32_t code, bool down) {
    MdwServer *server = output->server;
    struct wlr_keyboard_key_event event = {
        .time_msec = time_msec(), .keycode = code, .update_state = true,
        .state = down ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED,
    };
    wlr_seat_keyboard_notify_key(server->seat, event.time_msec, code, event.state);
    wlr_keyboard_notify_key(&server->keyboard, &event);
    output->keys[code] = down;
}

static const uint32_t button_codes[] = {BTN_LEFT, BTN_MIDDLE, BTN_RIGHT};

static void release_pointer(MdwOutput *output) {
    MdwServer *server = output->server;
    if (server->pointer_owner != output) return;
    for (int button = 0; button < 3; ++button) {
        if (output->buttons[button]) {
            if (!mdw_content_pointer_held(server))
                wlr_seat_pointer_notify_button(server->seat, time_msec(), button_codes[button],
                    WL_POINTER_BUTTON_STATE_RELEASED);
            output->buttons[button] = false;
        }
    }
    wlr_seat_pointer_notify_frame(server->seat);
    wlr_seat_pointer_notify_clear_focus(server->seat);
    server->pointer_owner = NULL;
    mdw_input_refresh(server);
}

static void release_keyboard(MdwOutput *output) {
    MdwServer *server = output->server;
    if (server->keyboard_owner != output) return;
    for (uint32_t code = 0; code <= KEY_MAX; ++code) {
        if (output->keys[code]) key_event(output, code, false);
    }
    wlr_seat_keyboard_notify_clear_focus(server->seat);
    if (output->view) mdw_view_dismiss_popups(output->view);
    if (output->view && output->view->activate && output->view->surface->mapped)
        output->view->activate(output->view, false);
    server->keyboard_owner = NULL;
    mdw_input_refresh(server);
}

void mdw_view_keyboard(struct MdwView *view, bool allowed) {
    view->keyboard_allowed = allowed;
    MdwOutput *owner = view->server->keyboard_owner;
    if (!allowed && owner && owner->view == view) release_keyboard(owner);
}

static void claim_pointer(MdwOutput *output) {
    MdwServer *server = output->server;
    if (server->pointer_owner == output) return;
    if (server->pointer_owner && server->pointer_owner->view == output->view) {
        memcpy(output->buttons, server->pointer_owner->buttons, sizeof(output->buttons));
        memset(server->pointer_owner->buttons, 0, sizeof(output->buttons));
        server->pointer_owner = output;
        mdw_input_refresh(server);
        return;
    }
    if (server->pointer_owner) release_pointer(server->pointer_owner);
    server->pointer_owner = output;
    mdw_input_refresh(server);
}

bool mdw_output_focus(MdwOutput *output, bool focused) {
    if (!output) return false;
    MdwServer *server = output->server;
    if (!focused) {
        release_pointer(output);
        release_keyboard(output);
        return true;
    }
    if (!accepts_input(output)) return false;
    claim_pointer(output);
    if (!output->view->keyboard_allowed) return true;
    if (server->keyboard_owner == output) return true;
    // Android roots are presentation handles, not new Wayland focus domains.
    if (server->keyboard_owner && server->keyboard_owner->view == output->view) {
        memcpy(output->keys, server->keyboard_owner->keys, sizeof(output->keys));
        memset(server->keyboard_owner->keys, 0, sizeof(output->keys));
        server->keyboard_owner = output;
        mdw_input_refresh(server);
        return true;
    }
    if (server->keyboard_owner) release_keyboard(server->keyboard_owner);
    server->keyboard_owner = output;
    if (output->view->activate) output->view->activate(output->view, true);
    wlr_seat_keyboard_notify_enter(server->seat, output->view->surface,
        server->keyboard.keycodes, server->keyboard.num_keycodes, &server->keyboard.modifiers);
    mdw_input_refresh(server);
    return true;
}

bool mdw_output_pointer(MdwOutput *output, double x, double y) {
    if (!accepts_input(output) || !isfinite(x) || !isfinite(y)) return false;
    // Pointer delivery selects its output independently of keyboard focus.
    claim_pointer(output);
    double local_x, local_y;
    // Hit-test the rendered scene, not the client's asynchronously acknowledged size.
    struct wlr_scene_node *node = wlr_scene_node_at(&output->view->scene->tree.node,
        output->scene_output->x + x * output->output->width / output->render_scale,
        output->scene_output->y + y * output->output->height / output->render_scale, &local_x, &local_y);
    struct wlr_scene_surface *scene_surface = node && node->type == WLR_SCENE_NODE_BUFFER
        ? wlr_scene_surface_try_from_buffer(wlr_scene_buffer_from_node(node)) : NULL;
    struct wlr_surface *surface = scene_surface ? scene_surface->surface : NULL;
    if (surface) {
        wlr_seat_pointer_notify_enter(output->server->seat, surface, local_x, local_y);
        wlr_seat_pointer_notify_motion(output->server->seat, time_msec(), local_x, local_y);
    } else wlr_seat_pointer_notify_clear_focus(output->server->seat);
    wlr_seat_pointer_notify_frame(output->server->seat);
    return surface != NULL;
}

struct CaretGeometry {
    struct wlr_surface *surface;
    int x, y;
    bool found;
};

static void caret_surface(struct wlr_scene_buffer *buffer, int x, int y, void *data) {
    struct CaretGeometry *geometry = data;
    struct wlr_scene_surface *scene = wlr_scene_surface_try_from_buffer(buffer);
    if (scene && scene->surface == geometry->surface) {
        geometry->x = x; geometry->y = y; geometry->found = true;
    }
}

bool mdw_output_caret(MdwOutput *output, struct wlr_surface *surface, const struct wlr_box *rect, float caret[4]) {
    if (!output || !output->view || !output->output || !surface || rect->width < 0 || rect->height < 0) return false;
    struct CaretGeometry geometry = {.surface = surface};
    wlr_scene_node_for_each_buffer(&output->view->scene->tree.node, caret_surface, &geometry);
    if (!geometry.found || output->viewport_width <= 0 || output->viewport_height <= 0) return false;
    double x = (double)geometry.x + rect->x - output->scene_output->x;
    double y = (double)geometry.y + rect->y - output->scene_output->y;
    caret[0] = x * output->render_scale / output->viewport_width;
    caret[1] = y * output->render_scale / output->viewport_height;
    caret[2] = (x + rect->width) * output->render_scale / output->viewport_width;
    caret[3] = (y + rect->height) * output->render_scale / output->viewport_height;
    return true;
}

bool mdw_output_button(MdwOutput *output, MdwButton button, bool down) {
    if (!accepts_input(output) || output->server->pointer_owner != output ||
            button < MDW_PRIMARY || button > MDW_SECONDARY) return false;
    if (output->buttons[button] == down) return true;
    if (mdw_content_pointer_held(output->server)) { output->buttons[button] = down; return true; }
    wlr_seat_pointer_notify_button(output->server->seat, time_msec(), button_codes[button],
        down ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
    wlr_seat_pointer_notify_frame(output->server->seat);
    output->buttons[button] = down;
    return true;
}

bool mdw_output_scroll(MdwOutput *output, double horizontal, double vertical) {
    if (!accepts_input(output) || output->server->pointer_owner != output ||
            !isfinite(horizontal) || !isfinite(vertical) ||
            fabs(horizontal) > 10000 || fabs(vertical) > 10000) return false;
    if (horizontal != 0) wlr_seat_pointer_notify_axis(output->server->seat, time_msec(),
        WL_POINTER_AXIS_HORIZONTAL_SCROLL, horizontal, 0, WL_POINTER_AXIS_SOURCE_CONTINUOUS,
        WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL);
    if (vertical != 0) wlr_seat_pointer_notify_axis(output->server->seat, time_msec(),
        WL_POINTER_AXIS_VERTICAL_SCROLL, vertical, 0, WL_POINTER_AXIS_SOURCE_CONTINUOUS,
        WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL);
    wlr_seat_pointer_notify_frame(output->server->seat);
    return true;
}

bool mdw_output_key(MdwOutput *output, uint32_t code, bool down) {
    if (!accepts_input(output) || output->server->keyboard_owner != output ||
            !output->view->keyboard_allowed || code == 0 || code > KEY_MAX) return false;
    if (output->keys[code] != down) key_event(output, code, down);
    return true;
}

void mdw_output_destroy(MdwOutput *output) {
    if (!output) return;
    if (output->parent) {
        output->parent->dependents = NULL;
        mdw_output_refresh(output->parent);
        output->parent = NULL;
    }
    if (output->dependents) {
        output->dependents->parent = NULL;
        release_output(output->dependents);
        output->dependents->view = NULL;
        output->dependents = NULL;
    }
    release_output(output);
    wl_list_remove(&output->link);
    free(output);
}

bool mdw_window_close(MdwServer *server, uint64_t id) {
    struct MdwView *view = find_view(server, id);
    if (!view || !view->close) return false;
    view->close(view);
    return true;
}

bool mdw_window_disconnect(MdwServer *server, uint64_t id) {
    struct MdwView *view = find_view(server, id);
    if (!view) return false;
    wl_client_destroy(wl_resource_get_client(view->surface->resource));
    return true;
}

bool mdw_output_text(MdwOutput *output, uint64_t editor, const char *text, bool composing, int cursor) {
    return accepts_input(output) && output->server->keyboard_owner == output &&
        mdw_input_text(output->server, editor, text, composing, cursor);
}

bool mdw_output_delete_text(MdwOutput *output, uint64_t editor, uint32_t revision, uint32_t before, uint32_t after,
        const char *preedit, int cursor) {
    return accepts_input(output) && output->server->keyboard_owner == output &&
        mdw_input_delete_text(output->server, editor, revision, before, after, preedit, cursor);
}

int mdw_server_dispatch(MdwServer *server, int timeout_ms) {
    // Host commands run outside Wayland dispatch and may schedule idle protocol
    // batches. Complete and flush those before waiting for the client's response.
    wl_event_loop_dispatch_idle(wl_display_get_event_loop(server->display));
    wl_display_flush_clients(server->display);
    int result = wl_event_loop_dispatch(wl_display_get_event_loop(server->display), timeout_ms);
    wl_display_flush_clients(server->display);
    return result;
}

void mdw_server_destroy(MdwServer *server) {
    if (!server) return;
    MdwOutput *output, *next;
    wl_list_for_each_safe(output, next, &server->outputs, link) mdw_output_destroy(output);
    if (server->display) wl_display_destroy_clients(server->display);
    mdw_cursor_finish(server);
    mdw_input_finish(server);
    mdw_shell_finish(server);
    if (server->shell) {
        wl_list_remove(&server->new_toplevel.link);
        wl_list_remove(&server->new_popup.link);
    }
    if (server->keyboard_initialized) {
        wl_list_remove(&server->modifiers.link);
        wlr_keyboard_finish(&server->keyboard);
    }
    mdw_content_finish(server);
    if (server->gpu_ready) {
        wl_event_source_remove(server->gpu_ready);
        close(server->gpu_ready_fd);
    }
    if (server->backend) wlr_backend_destroy(server->backend);
    if (server->display) wl_display_destroy(server->display);
    if (server->allocator) wlr_allocator_destroy(server->allocator);
    if (server->renderer) wlr_renderer_destroy(server->renderer);
    free(server);
}
