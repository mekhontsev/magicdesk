#define _GNU_SOURCE
#include "wayland_internal.h"
#include "content_stream.h"
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <linux/input-event-codes.h>
#include <time.h>
#include <wlr/types/wlr_data_device.h>
#include <wlr/types/wlr_seat.h>

struct MdwContent;
struct Selection {
    struct MdwContent *content;
    struct wlr_data_source *source;
    struct wl_listener destroy;
    uint64_t id;
    int channel;
};
struct HostSource {
    struct wlr_data_source base;
    struct MdwContent *content;
    uint64_t id;
    int channel;
};
struct Transfer {
    struct wl_list link;
    struct MdwContent *content;
    struct MdwContentStream *stream;
    uint64_t id, request;
    bool reading;
};
struct MdwContent {
    MdwServer *server;
    struct wl_listener requested, changed, drag_requested, drag_destroy, drag_drop;
    struct Selection selections[2];
    struct wl_list transfers;
    uint64_t sequence;
    bool enabled, closing;
    struct wlr_drag *drag;
    struct wlr_data_source *pending_drag;
    MdwOutput *origin, *target;
    struct wl_event_source *drag_deadline, *drop_idle;
    struct wl_protocol_logger *protocol;
    uint64_t drag_id;
    bool bridged, drop_pending, delivered, external;
};
static const struct wlr_data_source_impl host_impl;
static void track(struct Selection *s, struct wlr_data_source *source);
static void offer(struct Selection *s);
static void cancel_drag(struct MdwContent *c, bool destroy_source);
static void maybe_drop(struct MdwContent *c);

static void protocol_request(void *data, enum wl_protocol_logger_type direction,
        const struct wl_protocol_logger_message *message) {
    struct MdwContent *c = data;
    // wlroots has no offer-accept signal. Observe the public wire request and
    // finish in an idle callback after its normal handler updates the source.
    if (c->drop_pending && direction == WL_PROTOCOL_LOGGER_REQUEST &&
            strcmp(wl_resource_get_class(message->resource), "wl_data_offer") == 0 &&
            (strcmp(message->message->name, "accept") == 0 || strcmp(message->message->name, "set_actions") == 0))
        maybe_drop(c);
}

static uint32_t now_ms(void) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint32_t)((uint64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000);
}
static void drag_event(struct MdwContent *c, MdwOutput *output, bool finished, bool accepted) {
    if (!c->closing && output && c->server->events.drag_event)
        c->server->events.drag_event(c->server->events.context, output, c->drag_id, finished, accepted);
}
static void clear_drag_deadline(struct MdwContent *c) {
    if (c->drag_deadline) wl_event_source_remove(c->drag_deadline);
    if (c->drop_idle) wl_event_source_remove(c->drop_idle);
    c->drag_deadline = c->drop_idle = NULL;
}
static int drag_expired(void *data) {
    struct MdwContent *c = data;
    drag_event(c, c->target, true, false);
    drag_event(c, c->origin, false, false);
    cancel_drag(c, true);
    return 0;
}
static void drag_destroyed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwContent *c = wl_container_of(listener, c, drag_destroy);
    wl_list_remove(&c->drag_destroy.link); wl_list_remove(&c->drag_drop.link);
    c->drag = NULL;
    if (!c->delivered) {
        drag_event(c, c->target, true, false);
        drag_event(c, c->origin, false, false);
        clear_drag_deadline(c);
        c->bridged = c->drop_pending = false; c->origin = c->target = NULL;
    }
}
static void drag_dropped(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwContent *c = wl_container_of(listener, c, drag_drop);
    c->delivered = true; c->drop_pending = false;
    if (!c->external) {
        // The existing guest source/offer owns the transfer; Android must not deliver a second copy.
        drag_event(c, c->target, true, true);
        if (!c->bridged) drag_event(c, c->origin, false, false);
        clear_drag_deadline(c);
    }
}
static bool watch_drag(struct MdwContent *c, struct wlr_drag *drag) {
    clear_drag_deadline(c);
    c->drag = drag;
    c->delivered = c->drop_pending = false;
    c->drag_destroy.notify = drag_destroyed; c->drag_drop.notify = drag_dropped;
    wl_signal_add(&drag->events.destroy, &c->drag_destroy);
    wl_signal_add(&drag->events.drop, &c->drag_drop);
    // EVENT_WAIT: Android drag completion or guest acceptance; expiry cancels the operation.
    c->drag_deadline = wl_event_loop_add_timer(wl_display_get_event_loop(c->server->display), drag_expired, c);
    if (!c->drag_deadline) return false;
    return wl_event_source_timer_update(c->drag_deadline, 30000) >= 0;
}
static void cancel_drag(struct MdwContent *c, bool destroy_source) {
    clear_drag_deadline(c);
    if (c->drag) c->drag->pointer_grab.interface->cancel(&c->drag->pointer_grab);
    // The grab consumes the release before normal pointer dispatch resumes.
    struct wlr_seat *seat = c->server->seat;
    while (seat->pointer_state.button_count > 0)
        wlr_seat_pointer_notify_button(seat, now_ms(), seat->pointer_state.buttons[0], WL_POINTER_BUTTON_STATE_RELEASED);
    if (destroy_source) {
        if (c->pending_drag) { wlr_data_source_destroy(c->pending_drag); c->pending_drag = NULL; }
        else if (seat->drag_source) wlr_data_source_destroy(seat->drag_source);
    }
    c->bridged = c->drop_pending = false; c->origin = c->target = NULL;
}
static void requested_drag(struct wl_listener *listener, void *data) {
    struct MdwContent *c = wl_container_of(listener, c, drag_requested);
    struct wlr_seat_request_start_drag_event *event = data;
    if (c->drag || !wlr_seat_validate_pointer_grab_serial(c->server->seat, event->origin, event->serial)) {
        event->drag->pointer_grab.interface->cancel(&event->drag->pointer_grab);
        return;
    }
    c->external = c->bridged = false;
    c->origin = c->server->pointer_owner; c->target = NULL;
    if (!watch_drag(c, event->drag)) { cancel_drag(c, false); return; }
    track(&c->selections[MDW_CONTENT_DRAG], event->drag->source);
    c->drag_id = c->selections[MDW_CONTENT_DRAG].id;
    wlr_seat_start_pointer_drag(c->server->seat, event->drag, event->serial);
    offer(&c->selections[MDW_CONTENT_DRAG]);
}

bool mdw_content_pointer_held(MdwServer *server) { return server->content && server->content->bridged; }
void mdw_content_output_released(MdwServer *server, MdwOutput *output) {
    struct MdwContent *c = server->content;
    if (c && (c->origin == output || c->target == output)) cancel_drag(c, !c->delivered);
}

static bool has_type(struct wlr_data_source *source, const char *type) {
    if (!source || !type) return false;
    char **p;
    wl_array_for_each(p, &source->mime_types) if (strcmp(*p, type) == 0) return true;
    return false;
}

static void offer(struct Selection *s) {
    struct MdwContent *c = s->content;
    if ((s->channel == MDW_CONTENT_CLIPBOARD && !c->enabled) || c->closing || !c->server->events.content_offer ||
            (s->source && s->source->impl == &host_impl)) return;
    char types[8192] = {0};
    size_t used = 0, count = 0;
    char **type;
    if (s->source) wl_array_for_each(type, &s->source->mime_types) {
        size_t length = strlen(*type);
        if (!length || length >= 128 || count++ >= 64 || used + length + 1 >= sizeof(types)) return;
        for (size_t i = 0; i < length; ++i) if ((*type)[i] < 32 || (*type)[i] > 126) return;
        if (used) types[used++] = '\n';
        memcpy(types + used, *type, length); used += length;
    }
    c->server->events.content_offer(c->server->events.context, s->channel, s->id,
        s->channel == MDW_CONTENT_DRAG ? c->server->pointer_owner : NULL, types);
}

static void source_gone(struct wl_listener *listener, void *data) {
    (void)data;
    struct Selection *s = wl_container_of(listener, s, destroy);
    wl_list_remove(&s->destroy.link); wl_list_init(&s->destroy.link);
    s->source = NULL; s->id = ++s->content->sequence;
    if (s->channel == MDW_CONTENT_CLIPBOARD) offer(s);
}

static void track(struct Selection *s, struct wlr_data_source *source) {
    wl_list_remove(&s->destroy.link); wl_list_init(&s->destroy.link);
    s->source = source; s->id = ++s->content->sequence;
    if (source) wl_signal_add(&source->events.destroy, &s->destroy);
}

static void selection_requested(struct wl_listener *listener, void *data) {
    struct MdwContent *c = wl_container_of(listener, c, requested);
    struct wlr_seat_request_set_selection_event *event = data;
    wlr_seat_set_selection(c->server->seat, event->source, event->serial);
}
static void selection_changed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwContent *c = wl_container_of(listener, c, changed);
    struct Selection *s = &c->selections[MDW_CONTENT_CLIPBOARD];
    track(s, c->server->seat->selection_source); offer(s);
}

static void stream_done(void *context, uint64_t id, int fd) {
    (void)id;
    struct Transfer *t = context;
    struct MdwContent *c = t->content;
    wl_list_remove(&t->link);
    if (t->reading && !c->closing && c->server->events.content_reply)
        c->server->events.content_reply(c->server->events.context, t->request, fd);
    free(t);
}

static struct Transfer *transfer_create(struct MdwContent *c, int fd, bool reading, uint64_t request) {
    if (wl_list_length(&c->transfers) >= 16) { close(fd); return NULL; }
    struct Transfer *t = calloc(1, sizeof(*t));
    if (!t) { close(fd); return NULL; }
    *t = (struct Transfer){.content = c, .id = ++c->sequence, .request = request, .reading = reading};
    t->stream = mdw_stream_create(wl_display_get_event_loop(c->server->display), t->id, fd, reading, stream_done, t);
    if (!t->stream) { free(t); return NULL; }
    wl_list_insert(&c->transfers, &t->link);
    return t;
}

static void host_send(struct wlr_data_source *base, const char *type, int32_t fd) {
    struct HostSource *s = wl_container_of(base, s, base);
    struct MdwContent *c = s->content;
    if (c->closing || !has_type(base, type) || !c->server->events.content_request) { close(fd); return; }
    struct Transfer *t = transfer_create(c, fd, false, 0);
    if (t) c->server->events.content_request(c->server->events.context, s->channel, s->id, t->id, type);
}
static void host_destroy(struct wlr_data_source *base) {
    struct HostSource *s = wl_container_of(base, s, base);
    struct MdwContent *c = s->content;
    if (c->pending_drag == base) c->pending_drag = NULL;
    if (s->channel == MDW_CONTENT_DRAG && c->external && c->drag_id == s->id) {
        drag_event(c, c->target, true, false);
        clear_drag_deadline(c); c->target = NULL; c->bridged = false;
    }
    free(s);
}
static void drop_idle(void *data) {
    struct MdwContent *c = data;
    c->drop_idle = NULL;
    if (!c->drop_pending || !c->drag || !c->drag->focus || !c->drag->source ||
            !c->drag->source->accepted || !c->drag->source->current_dnd_action) return;
    wlr_seat_pointer_notify_button(c->server->seat, now_ms(),
        c->server->seat->pointer_state.grab_button, WL_POINTER_BUTTON_STATE_RELEASED);
}
static void maybe_drop(struct MdwContent *c) {
    // Complete after wlroots has committed accept/action, never re-enter its request handler.
    if (c->drop_pending && !c->drop_idle)
        c->drop_idle = wl_event_loop_add_idle(wl_display_get_event_loop(c->server->display), drop_idle, c);
}
static void host_accept(struct wlr_data_source *base, uint32_t serial, const char *mime) {
    (void)serial; (void)mime;
    struct HostSource *s = wl_container_of(base, s, base);
    if (s->channel == MDW_CONTENT_DRAG) maybe_drop(s->content);
}
static void host_action(struct wlr_data_source *base, enum wl_data_device_manager_dnd_action action) {
    (void)action;
    struct HostSource *s = wl_container_of(base, s, base);
    maybe_drop(s->content);
}
static void host_finished(struct wlr_data_source *base) {
    struct HostSource *s = wl_container_of(base, s, base);
    struct MdwContent *c = s->content;
    if (c->external && c->drag_id == s->id) {
        drag_event(c, c->target, true, true);
        clear_drag_deadline(c); c->target = NULL; c->bridged = false;
    }
}
static const struct wlr_data_source_impl host_impl = {
    .send = host_send, .destroy = host_destroy, .accept = host_accept,
    .dnd_action = host_action, .dnd_finish = host_finished,
};

bool mdw_content_init(MdwServer *server) {
    struct MdwContent *c = calloc(1, sizeof(*c));
    if (!c) return false;
    c->server = server; server->content = c;
    wl_list_init(&c->transfers);
    for (int i = 0; i < 2; ++i) {
        c->selections[i] = (struct Selection){.content = c, .channel = i, .destroy.notify = source_gone};
        wl_list_init(&c->selections[i].destroy.link);
    }
    c->requested.notify = selection_requested;
    c->changed.notify = selection_changed;
    wl_signal_add(&server->seat->events.request_set_selection, &c->requested);
    wl_signal_add(&server->seat->events.set_selection, &c->changed);
    c->drag_requested.notify = requested_drag;
    wl_signal_add(&server->seat->events.request_start_drag, &c->drag_requested);
    c->protocol = wl_display_add_protocol_logger(server->display, protocol_request, c);
    if (!c->protocol) { mdw_content_finish(server); return false; }
    return true;
}

void mdw_content_enable(MdwServer *server, bool enabled) {
    struct MdwContent *c = server->content;
    if (!c || c->enabled == enabled) return;
    c->enabled = enabled;
    if (enabled) offer(&c->selections[MDW_CONTENT_CLIPBOARD]);
}

bool mdw_content_publish(MdwServer *server, int channel, uint64_t id, const char *types) {
    struct MdwContent *c = server->content;
    if (!c || !id || channel < 0 || channel > 1 || !types || strlen(types) >= 8192) return false;
    struct HostSource *s = calloc(1, sizeof(*s));
    if (!s) return false;
    wlr_data_source_init(&s->base, &host_impl);
    s->content = c; s->id = id; s->channel = channel;
    const char *start = types;
    size_t count = 0;
    while (*start) {
        const char *end = strchr(start, '\n');
        size_t length = end ? (size_t)(end - start) : strlen(start);
        if (!length || length >= 128 || ++count > 64) goto fail;
        for (size_t i = 0; i < length; ++i) if (start[i] < 32 || start[i] > 126) goto fail;
        char *type = strndup(start, length);
        if (!type) goto fail;
        char **slot = wl_array_add(&s->base.mime_types, sizeof(*slot));
        if (!slot) { free(type); goto fail; }
        *slot = type;
        start += length + (end != NULL);
    }
    if (channel == MDW_CONTENT_CLIPBOARD)
        wlr_seat_set_selection(server->seat, &s->base, wl_display_next_serial(server->display));
    else {
        if (c->drag) goto fail;
        if (c->pending_drag) wlr_data_source_destroy(c->pending_drag);
        s->base.actions = WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY;
        c->pending_drag = &s->base;
    }
    return true;
fail:
    wlr_data_source_destroy(&s->base); return false;
}

bool mdw_content_read(MdwServer *server, int channel, uint64_t id, uint64_t request, const char *type) {
    struct MdwContent *c = server->content;
    if (!c || channel < 0 || channel > 1) return false;
    struct Selection *s = &c->selections[channel];
    if (s->id != id || !has_type(s->source, type) || s->source->impl == &host_impl) return false;
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) < 0) return false;
    if (!transfer_create(c, fds[0], true, request)) { close(fds[1]); return false; }
    wlr_data_source_send(s->source, type, fds[1]);
    return true;
}

void mdw_content_reply(MdwServer *server, uint64_t request, int fd) {
    struct MdwContent *c = server->content;
    struct Transfer *t;
    wl_list_for_each(t, &c->transfers, link) {
        if (t->id != request || t->reading) continue;
        if (!mdw_stream_source(t->stream, fd)) mdw_stream_cancel(t->stream);
        return;
    }
}

void mdw_content_drag(MdwServer *server, MdwOutput *output, MdwDragAction action,
        uint64_t id, double x, double y, bool accepted) {
    struct MdwContent *c = server->content;
    if (!c || !output) return;
    if (action == MDW_DRAG_BEGIN) {
        if (!c->drag || c->delivered || c->external || c->drag_id != id) {
            if (server->events.drag_event)
                server->events.drag_event(server->events.context, output, id, false, false);
            return;
        }
        c->bridged = true;
        // Only the Android drag target drives the native grab until its completion.
        wlr_seat_pointer_notify_clear_focus(server->seat);
        return;
    }
    if (action == MDW_DRAG_ENTER) {
        if (c->pending_drag) {
            struct HostSource *source = wl_container_of(c->pending_drag, source, base);
            if (source->id != id) return;
            c->external = c->bridged = true;
            c->drag_id = id; c->origin = NULL;
        } else if (c->drag_id != id || !c->bridged || c->delivered) return;
        c->target = output;
    } else if (action == MDW_DRAG_MOVE) {
        if (c->target != output || c->drag_id != id || c->delivered) return;
        if (!mdw_output_pointer(output, x, y)) return;
        if (c->pending_drag) {
            struct wlr_seat_client *client = server->seat->pointer_state.focused_client;
            if (!client) return;
            struct wlr_drag *drag = wlr_drag_create(client, c->pending_drag, NULL);
            if (!drag) return;
            c->pending_drag = NULL;
            if (!watch_drag(c, drag)) {
                struct wlr_data_source *source = drag->source;
                cancel_drag(c, false); wlr_data_source_destroy(source); return;
            }
            wlr_seat_start_pointer_drag(server->seat, drag, wl_display_next_serial(server->display));
            wlr_seat_pointer_notify_button(server->seat, now_ms(), BTN_LEFT, WL_POINTER_BUTTON_STATE_PRESSED);
            mdw_output_pointer(output, x, y);
        }
        maybe_drop(c);
    } else if (action == MDW_DRAG_LEAVE) {
        if (c->target == output && c->drag_id == id && !c->delivered) {
            wlr_seat_pointer_notify_clear_focus(server->seat); c->target = NULL;
        }
    } else if (action == MDW_DRAG_DROP) {
        if (c->target != output || c->drag_id != id || c->delivered) return;
        if (!c->drag) { drag_event(c, output, true, false); cancel_drag(c, true); return; }
        c->drop_pending = true;
        maybe_drop(c);
    } else if (action == MDW_DRAG_ABORT && c->external && c->drag_id == id && !c->delivered) {
        cancel_drag(c, true);
    } else if (action == MDW_DRAG_FINISH && !c->external && c->drag_id == id) {
        if (!c->delivered) {
            struct wlr_data_source *source = c->selections[MDW_CONTENT_DRAG].source;
            if (accepted && source) {
                wlr_data_source_dnd_action(source, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);
                wlr_data_source_dnd_drop(source); wlr_data_source_dnd_finish(source);
            }
            c->delivered = true;
            cancel_drag(c, !accepted);
        } else { clear_drag_deadline(c); c->origin = c->target = NULL; c->bridged = false; }
    }
}

void mdw_content_finish(MdwServer *server) {
    struct MdwContent *c = server->content;
    if (!c) return;
    c->closing = true;
    if (c->protocol) wl_protocol_logger_destroy(c->protocol);
    cancel_drag(c, true);
    wl_list_remove(&c->drag_requested.link);
    wl_list_remove(&c->requested.link); wl_list_remove(&c->changed.link);
    wlr_seat_set_selection(server->seat, NULL, wl_display_next_serial(server->display));
    for (int i = 0; i < 2; ++i) wl_list_remove(&c->selections[i].destroy.link);
    struct Transfer *t, *next;
    wl_list_for_each_safe(t, next, &c->transfers, link) mdw_stream_cancel(t->stream);
    server->content = NULL; free(c);
}
