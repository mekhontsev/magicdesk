#include "wayland_internal.h"
#include <stdlib.h>
#include <string.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_text_input_v3.h>
#include "text-input-unstable-v3-protocol.h"

struct MdwTextInput {
    struct wl_list link;
    MdwServer *server;
    struct wlr_text_input_v3 *text;
    struct wl_listener enable, disable, commit, destroy;
    uint64_t editor;
    uint32_t revision, cursor, anchor;
    char *surrounding;
};

struct MdwInput {
    MdwServer *server;
    struct wl_list texts;
    struct wl_listener new_text, keyboard_focus;
    MdwOutput *text_owner;
    uint64_t editor, next_editor;
};

static void listen(struct wl_signal *signal, struct wl_listener *listener,
        void (*notify)(struct wl_listener *, void *)) {
    listener->notify = notify;
    wl_signal_add(signal, listener);
}

static struct MdwTextInput *active_text(struct MdwInput *input) {
    struct MdwTextInput *item;
    wl_list_for_each(item, &input->texts, link) {
        if (item->text->focused_surface && item->text->current_enabled) return item;
    }
    return NULL;
}

static void publish_text(struct MdwInput *input) {
    MdwServer *server = input->server;
    if (!input->text_owner || !server->events.text_input) return;
    struct MdwTextInput *item = active_text(input);
    MdwTextState state = {0};
    if (item && input->editor) {
        struct wlr_text_input_v3 *text = item->text;
        state.editor = item->editor;
        state.revision = item->revision;
        state.input_method_change = text->current.text_change_cause == ZWP_TEXT_INPUT_V3_CHANGE_CAUSE_INPUT_METHOD;
        // A client may publish optional context after enabling an initially empty editor.
        // Read committed features, not the feature set captured at enable time.
        if (text->current.features & WLR_TEXT_INPUT_V3_FEATURE_SURROUNDING_TEXT) {
            state.surrounding = text->current.surrounding.text;
            state.cursor = text->current.surrounding.cursor;
            state.anchor = text->current.surrounding.anchor;
        }
        if (text->current.features & WLR_TEXT_INPUT_V3_FEATURE_CONTENT_TYPE) {
            state.purpose = text->current.content_type.purpose;
            state.hints = text->current.content_type.hint;
        }
        if (text->current.features & WLR_TEXT_INPUT_V3_FEATURE_CURSOR_RECTANGLE)
            state.caret_valid = mdw_output_caret(input->text_owner, text->focused_surface,
                &text->current.cursor_rectangle, state.caret);
    }
    server->events.text_input(server->events.context, input->text_owner, &state);
}

void mdw_input_geometry(MdwServer *server) {
    if (server->input) publish_text(server->input);
}

void mdw_input_refresh(MdwServer *server) {
    struct MdwInput *input = server->input;
    if (!input) return;
    struct MdwTextInput *item = active_text(input);
    uint64_t editor = server->keyboard_owner && item ? item->editor : 0;
    if (input->text_owner != server->keyboard_owner || input->editor != editor) {
        if (input->text_owner && input->text_owner != server->keyboard_owner && server->events.text_input)
            server->events.text_input(server->events.context, input->text_owner, &(MdwTextState){0});
        input->text_owner = server->keyboard_owner;
        input->editor = editor;
        publish_text(input);
    }
    mdw_cursor_refresh(server);
}

static bool capture_context(struct MdwTextInput *item) {
    struct wlr_text_input_v3 *text = item->text;
    const char *surrounding = text->current.features & WLR_TEXT_INPUT_V3_FEATURE_SURROUNDING_TEXT
        ? text->current.surrounding.text : NULL;
    bool changed = surrounding ? !item->surrounding || strcmp(surrounding, item->surrounding)
        : item->surrounding != NULL;
    if (changed) {
        char *copy = surrounding ? strdup(surrounding) : NULL;
        if (surrounding && !copy) { wl_client_post_no_memory(wl_resource_get_client(text->resource)); return false; }
        free(item->surrounding);
        item->surrounding = copy;
    }
    // Deletions qualify the text/selection snapshot, not cursor-rectangle-only commits.
    if (changed || !item->revision || item->cursor != text->current.surrounding.cursor
            || item->anchor != text->current.surrounding.anchor) ++item->revision;
    item->cursor = text->current.surrounding.cursor;
    item->anchor = text->current.surrounding.anchor;
    return true;
}

static void text_changed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwTextInput *item = wl_container_of(listener, item, enable);
    item->editor = ++item->server->input->next_editor;
    item->revision = 0;
    if (!capture_context(item)) return;
    mdw_input_refresh(item->server);
}
static void text_committed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwTextInput *item = wl_container_of(listener, item, commit);
    if (!capture_context(item)) return;
    if (active_text(item->server->input) == item) publish_text(item->server->input);
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
    wl_list_remove(&item->commit.link);
    wl_list_remove(&item->destroy.link); wl_list_remove(&item->link);
    mdw_input_refresh(item->server);
    free(item->surrounding);
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
    listen(&text->events.commit, &item->commit, text_committed);
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

bool mdw_input_text(MdwServer *server, uint64_t editor, const char *text, bool composing, int cursor) {
    struct MdwTextInput *item = server->input ? active_text(server->input) : NULL;
    if (!item || !editor || item->editor != editor || !text || cursor < 0 || strlen(text) > 4000
            || (size_t)cursor > strlen(text) || ((unsigned char)text[cursor] & 0xc0) == 0x80) return false;
    struct wlr_text_input_v3 *target = item->text;
    if (composing) wlr_text_input_v3_send_preedit_string(target, text, cursor, cursor);
    else {
        wlr_text_input_v3_send_preedit_string(target, "", 0, 0);
        wlr_text_input_v3_send_commit_string(target, text);
    }
    wlr_text_input_v3_send_done(target);
    return true;
}

bool mdw_input_delete_text(MdwServer *server, uint64_t editor, uint32_t revision, uint32_t before, uint32_t after,
        const char *preedit, int position) {
    struct MdwTextInput *item = server->input ? active_text(server->input) : NULL;
    if (!item || !editor || item->editor != editor || item->revision != revision) return false;
    if (!preedit || strlen(preedit) > 4000 || position < 0 || (size_t)position > strlen(preedit)
            || ((unsigned char)preedit[position] & 0xc0) == 0x80) return false;
    struct wlr_text_input_v3 *target = item->text;
    const char *text = target->current.surrounding.text;
    if (!(target->current.features & WLR_TEXT_INPUT_V3_FEATURE_SURROUNDING_TEXT) || !text) return false;
    uint32_t cursor = target->current.surrounding.cursor, anchor = target->current.surrounding.anchor;
    uint32_t start = cursor < anchor ? cursor : anchor, end = cursor > anchor ? cursor : anchor;
    size_t length = strlen(text);
    if (end > length || before > start || after > length - end ||
            ((unsigned char)text[start - before] & 0xc0) == 0x80 ||
            ((unsigned char)text[end + after] & 0xc0) == 0x80) return false;
    wlr_text_input_v3_send_delete_surrounding_text(target, before, after);
    wlr_text_input_v3_send_preedit_string(target, preedit, position, position);
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
