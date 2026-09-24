#include "wayland_internal.h"
#include <stdlib.h>
#include <string.h>
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
    struct wl_listener new_text, keyboard_focus;
    MdwOutput *text_owner;
    bool text_enabled;
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
    mdw_cursor_refresh(server);
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
    return true;
}
void mdw_input_finish(MdwServer *server) {
    struct MdwInput *input = server->input;
    if (!input) return;
    wl_list_remove(&input->new_text.link); wl_list_remove(&input->keyboard_focus.link);
    free(input); server->input = NULL;
}
