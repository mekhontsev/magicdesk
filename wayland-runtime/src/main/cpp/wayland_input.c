#include "wayland_internal.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <drm_fourcc.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_text_input_v3.h>

struct MdwTextInput {
    struct wl_list link;
    MdwServer *server;
    struct wlr_text_input_v3 *text;
    struct wl_listener enable, disable, destroy;
};

struct MdwInput {
    MdwServer *server;
    struct wl_list texts;
    struct wl_listener new_text, keyboard_focus, pointer_focus, set_cursor;
    struct wl_listener cursor_commit, cursor_destroy;
    struct wlr_surface *cursor;
    MdwOutput *cursor_owner, *text_owner;
    bool text_enabled, cursor_hidden;
    int hotspot_x, hotspot_y;
};

static void listen(struct wl_signal *signal, struct wl_listener *listener,
        void (*notify)(struct wl_listener *, void *)) {
    listener->notify = notify;
    wl_signal_add(signal, listener);
}

static struct wlr_text_input_v3 *active_text(struct MdwInput *input) {
    struct MdwTextInput *item;
    wl_list_for_each(item, &input->texts, link) {
        if (item->text->focused_surface && item->text->current_enabled) return item->text;
    }
    return NULL;
}

static void publish_cursor(struct MdwInput *input) {
    MdwServer *server = input->server;
    MdwOutput *output = server->pointer_owner;
    if (!output || !server->events.cursor) return;
    struct wlr_surface *surface = input->cursor;
    struct wlr_buffer *buffer = surface && surface->buffer ? surface->buffer->source : NULL;
    void *data;
    uint32_t format;
    size_t stride;
    if (!buffer || buffer->width < 1 || buffer->height < 1 || buffer->width > 256 || buffer->height > 256 ||
            !wlr_buffer_begin_data_ptr_access(buffer, WLR_BUFFER_DATA_PTR_ACCESS_READ, &data, &format, &stride)) {
        server->events.cursor(server->events.context, output, NULL, 0, 0, 0, 0, input->cursor_hidden);
        return;
    }
    uint32_t pixels[256 * 256];
    bool supported = stride >= (size_t)buffer->width * 4 &&
        (format == DRM_FORMAT_ARGB8888 || format == DRM_FORMAT_XRGB8888 ||
         format == DRM_FORMAT_ABGR8888 || format == DRM_FORMAT_XBGR8888);
    if (supported) {
        for (int y = 0; y < buffer->height; ++y) {
            const uint32_t *row = (const void *)((const char *)data + stride * y);
            for (int x = 0; x < buffer->width; ++x) {
                uint32_t value = row[x];
                if (format == DRM_FORMAT_ABGR8888 || format == DRM_FORMAT_XBGR8888)
                    value = (value & 0xff00ff00) | ((value & 0xff) << 16) | ((value >> 16) & 0xff);
                if (format == DRM_FORMAT_XRGB8888 || format == DRM_FORMAT_XBGR8888) value |= 0xff000000;
                unsigned alpha = value >> 24;
                if (alpha && alpha < 255) {
                    unsigned red = ((value >> 16) & 255) * 255 / alpha;
                    unsigned green = ((value >> 8) & 255) * 255 / alpha;
                    unsigned blue = (value & 255) * 255 / alpha;
                    value = (alpha << 24) | ((red > 255 ? 255 : red) << 16) |
                        ((green > 255 ? 255 : green) << 8) | (blue > 255 ? 255 : blue);
                }
                pixels[y * buffer->width + x] = value;
            }
        }
        int scale = surface->current.scale > 0 ? surface->current.scale : 1;
        server->events.cursor(server->events.context, output, pixels, buffer->width, buffer->height,
            input->hotspot_x < 0 ? 0 : (input->hotspot_x * scale >= buffer->width ? buffer->width - 1 : input->hotspot_x * scale),
            input->hotspot_y < 0 ? 0 : (input->hotspot_y * scale >= buffer->height ? buffer->height - 1 : input->hotspot_y * scale), false);
    } else server->events.cursor(server->events.context, output, NULL, 0, 0, 0, 0, false);
    wlr_buffer_end_data_ptr_access(buffer);
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    wlr_surface_send_frame_done(surface, &now);
}

void mdw_input_refresh(MdwServer *server) {
    struct MdwInput *input = server->input;
    if (!input) return;
    bool enabled = server->keyboard_owner && active_text(input);
    if (input->text_owner != server->keyboard_owner || input->text_enabled != enabled) {
        if (input->text_owner && input->text_owner != server->keyboard_owner && server->events.text_input)
            server->events.text_input(server->events.context, input->text_owner, false);
        input->text_owner = server->keyboard_owner;
        input->text_enabled = enabled;
        if (input->text_owner && server->events.text_input)
            server->events.text_input(server->events.context, input->text_owner, enabled);
    }
    if (input->cursor_owner != server->pointer_owner) {
        if (input->cursor_owner && server->events.cursor)
            server->events.cursor(server->events.context, input->cursor_owner, NULL, 0, 0, 0, 0, false);
        input->cursor_owner = server->pointer_owner;
        publish_cursor(input);
    }
}

static void text_changed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwTextInput *item = wl_container_of(listener, item, enable);
    mdw_input_refresh(item->server);
}
static void text_disabled(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwTextInput *item = wl_container_of(listener, item, disable);
    mdw_input_refresh(item->server);
}
static void text_destroyed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwTextInput *item = wl_container_of(listener, item, destroy);
    wl_list_remove(&item->enable.link); wl_list_remove(&item->disable.link);
    wl_list_remove(&item->destroy.link); wl_list_remove(&item->link);
    mdw_input_refresh(item->server);
    free(item);
}

static void enter_text(struct wlr_text_input_v3 *text, struct wlr_surface *surface) {
    if (surface && wl_resource_get_client(text->resource) != wl_resource_get_client(surface->resource)) surface = NULL;
    if (text->focused_surface == surface) return;
    if (text->focused_surface) wlr_text_input_v3_send_leave(text);
    if (surface) wlr_text_input_v3_send_enter(text, surface);
}
static void new_text(struct wl_listener *listener, void *data) {
    struct MdwInput *input = wl_container_of(listener, input, new_text);
    struct wlr_text_input_v3 *text = data;
    if (text->seat != input->server->seat) return;
    struct MdwTextInput *item = calloc(1, sizeof(*item));
    if (!item) { wl_client_post_no_memory(wl_resource_get_client(text->resource)); return; }
    item->server = input->server; item->text = text;
    wl_list_insert(&input->texts, &item->link);
    listen(&text->events.enable, &item->enable, text_changed);
    listen(&text->events.disable, &item->disable, text_disabled);
    listen(&text->events.destroy, &item->destroy, text_destroyed);
    enter_text(text, input->server->seat->keyboard_state.focused_surface);
}
static void keyboard_focus(struct wl_listener *listener, void *data) {
    struct MdwInput *input = wl_container_of(listener, input, keyboard_focus);
    struct wlr_seat_keyboard_focus_change_event *event = data;
    struct MdwTextInput *item;
    wl_list_for_each(item, &input->texts, link) enter_text(item->text, event->new_surface);
    mdw_input_refresh(input->server);
}

static void clear_cursor(struct MdwInput *input) {
    if (input->cursor) {
        wl_list_remove(&input->cursor_commit.link);
        wl_list_remove(&input->cursor_destroy.link);
    }
    input->cursor = NULL;
    input->cursor_hidden = false;
}
static void pointer_focus(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwInput *input = wl_container_of(listener, input, pointer_focus);
    clear_cursor(input);
    publish_cursor(input);
}
static void cursor_commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwInput *input = wl_container_of(listener, input, cursor_commit);
    publish_cursor(input);
}
static void cursor_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwInput *input = wl_container_of(listener, input, cursor_destroy);
    clear_cursor(input);
    publish_cursor(input);
}
static void set_cursor(struct wl_listener *listener, void *data) {
    struct MdwInput *input = wl_container_of(listener, input, set_cursor);
    struct wlr_seat_pointer_request_set_cursor_event *event = data;
    if (event->seat_client != input->server->seat->pointer_state.focused_client ||
            !wlr_seat_client_validate_event_serial(event->seat_client, event->serial)) return;
    clear_cursor(input);
    input->cursor = event->surface;
    input->cursor_hidden = event->surface == NULL;
    input->hotspot_x = event->hotspot_x; input->hotspot_y = event->hotspot_y;
    if (input->cursor) {
        listen(&input->cursor->events.commit, &input->cursor_commit, cursor_commit);
        listen(&input->cursor->events.destroy, &input->cursor_destroy, cursor_destroy);
    }
    publish_cursor(input);
}

bool mdw_input_text(MdwServer *server, const char *text, bool composing, int cursor) {
    struct wlr_text_input_v3 *target = server->input ? active_text(server->input) : NULL;
    if (!target || !text || cursor < 0 || strlen(text) > 4000 || (size_t)cursor > strlen(text)) return false;
    if (composing) wlr_text_input_v3_send_preedit_string(target, text, cursor, cursor);
    else {
        wlr_text_input_v3_send_preedit_string(target, "", 0, 0);
        wlr_text_input_v3_send_commit_string(target, text);
    }
    wlr_text_input_v3_send_done(target);
    return true;
}

bool mdw_input_init(MdwServer *server) {
    struct wlr_text_input_manager_v3 *manager = wlr_text_input_manager_v3_create(server->display);
    struct MdwInput *input = manager ? calloc(1, sizeof(*input)) : NULL;
    if (!input) return false;
    server->input = input; input->server = server;
    wl_list_init(&input->texts);
    listen(&manager->events.text_input, &input->new_text, new_text);
    listen(&server->seat->keyboard_state.events.focus_change, &input->keyboard_focus, keyboard_focus);
    listen(&server->seat->pointer_state.events.focus_change, &input->pointer_focus, pointer_focus);
    listen(&server->seat->events.request_set_cursor, &input->set_cursor, set_cursor);
    return true;
}
void mdw_input_finish(MdwServer *server) {
    struct MdwInput *input = server->input;
    if (!input) return;
    clear_cursor(input);
    wl_list_remove(&input->new_text.link); wl_list_remove(&input->keyboard_focus.link);
    wl_list_remove(&input->pointer_focus.link); wl_list_remove(&input->set_cursor.link);
    free(input); server->input = NULL;
}
