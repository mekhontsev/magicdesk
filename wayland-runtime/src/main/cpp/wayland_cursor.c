#include "wayland_internal.h"
#include "wayland_renderer.h"
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#include <drm_fourcc.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_seat.h>

struct MdwCursor {
    MdwServer *server;
    MdwOutput *owner;
    struct wlr_surface *surface;
    struct wl_listener pointer_focus, set_cursor, commit, destroy;
    struct wlr_buffer *buffer;
    MdgReadback *readback;
    struct wl_event_source *pending;
    int pending_fd, hotspot_x, hotspot_y;
    bool hidden;
    uint32_t pixels[256 * 256];
};

static void listen(struct wl_signal *signal, struct wl_listener *listener,
        void (*notify)(struct wl_listener *, void *)) {
    listener->notify = notify;
    wl_signal_add(signal, listener);
}

static void cancel_read(struct MdwCursor *cursor) {
    if (cursor->pending) wl_event_source_remove(cursor->pending);
    cursor->pending = NULL;
    if (cursor->pending_fd >= 0) close(cursor->pending_fd);
    cursor->pending_fd = -1;
    mdg_readback_cancel(cursor->readback);
    if (cursor->buffer) wlr_buffer_unlock(cursor->buffer);
    cursor->buffer = NULL;
}

static void frame_done(struct MdwCursor *cursor) {
    if (!cursor->surface) return;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    wlr_surface_send_frame_done(cursor->surface, &now);
}

static void fallback(struct MdwCursor *cursor) {
    if (cursor->owner && cursor->server->events.cursor)
        cursor->server->events.cursor(cursor->server->events.context,
            cursor->owner, NULL, 0, 0, 0, 0, cursor->hidden);
    frame_done(cursor);
}

static int hotspot(int value, int scale, int extent) {
    int64_t scaled = (int64_t)value * scale;
    return scaled < 0 ? 0 : scaled >= extent ? extent - 1 : (int)scaled;
}

static void publish(struct MdwCursor *cursor, const void *data, size_t stride,
        uint32_t format, int width, int height) {
    if (stride < (size_t)width * 4 ||
            (format != DRM_FORMAT_ARGB8888 && format != DRM_FORMAT_XRGB8888 &&
             format != DRM_FORMAT_ABGR8888 && format != DRM_FORMAT_XBGR8888)) {
        fallback(cursor);
        return;
    }
    for (int y = 0; y < height; ++y) {
        const uint32_t *row = (const void *)((const char *)data + stride * y);
        for (int x = 0; x < width; ++x) {
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
            cursor->pixels[y * width + x] = value;
        }
    }
    int scale = cursor->surface->current.scale > 0 ? cursor->surface->current.scale : 1;
    cursor->server->events.cursor(cursor->server->events.context, cursor->owner,
        cursor->pixels, width, height, hotspot(cursor->hotspot_x, scale, width),
        hotspot(cursor->hotspot_y, scale, height), false);
    frame_done(cursor);
}

static void advance(struct MdwCursor *cursor);

static int ready(int fd, uint32_t mask, void *data) {
    (void)fd;
    (void)mask;
    struct MdwCursor *cursor = data;
    wl_event_source_remove(cursor->pending);
    cursor->pending = NULL;
    close(cursor->pending_fd);
    cursor->pending_fd = -1;
    advance(cursor);
    return 0;
}

static void advance(struct MdwCursor *cursor) {
    int fd;
    MdgReadbackStatus status = mdg_readback_poll(cursor->readback, &fd);
    if (status == MDG_READBACK_PENDING) {
        /* EVENT_WAIT: producer/renderer fence; a new commit, focus loss or teardown cancels it. */
        cursor->pending_fd = fd;
        cursor->pending = wl_event_loop_add_fd(wl_display_get_event_loop(cursor->server->display),
            fd, WL_EVENT_READABLE, ready, cursor);
        if (cursor->pending) return;
    } else if (status == MDG_READBACK_READY &&
            mdg_readback_read(cursor->readback, cursor->pixels, cursor->buffer->width * 4)) {
        publish(cursor, cursor->pixels, cursor->buffer->width * 4, DRM_FORMAT_ABGR8888,
            cursor->buffer->width, cursor->buffer->height);
        cancel_read(cursor);
        return;
    }
    cancel_read(cursor);
    fallback(cursor);
}

static void update(struct MdwCursor *cursor) {
    cancel_read(cursor);
    if (!cursor->owner || !cursor->server->events.cursor) return;
    struct wlr_buffer *buffer = cursor->surface && cursor->surface->buffer ? cursor->surface->buffer->source : NULL;
    if (!buffer || buffer->width < 1 || buffer->height < 1 || buffer->width > 256 || buffer->height > 256) {
        fallback(cursor);
        return;
    }
    void *data;
    uint32_t format;
    size_t stride;
    if (wlr_buffer_begin_data_ptr_access(buffer, WLR_BUFFER_DATA_PTR_ACCESS_READ, &data, &format, &stride)) {
        publish(cursor, data, stride, format, buffer->width, buffer->height);
        wlr_buffer_end_data_ptr_access(buffer);
        return;
    }
    MdgImage *image = mdw_buffer_image(buffer);
    if (image && !cursor->readback)
        cursor->readback = mdg_readback_create(mdw_renderer_device(cursor->server->renderer));
    if (!image || !mdg_readback_start(cursor->readback, image)) { fallback(cursor); return; }
    cursor->buffer = wlr_buffer_lock(buffer);
    advance(cursor);
}

void mdw_cursor_refresh(MdwServer *server) {
    struct MdwCursor *cursor = server->cursor;
    if (!cursor || cursor->owner == server->pointer_owner) return;
    cancel_read(cursor);
    if (cursor->owner && server->events.cursor)
        server->events.cursor(server->events.context, cursor->owner, NULL, 0, 0, 0, 0, false);
    cursor->owner = server->pointer_owner;
    update(cursor);
}

static void clear(struct MdwCursor *cursor) {
    cancel_read(cursor);
    if (cursor->surface) {
        wl_list_remove(&cursor->commit.link);
        wl_list_remove(&cursor->destroy.link);
    }
    cursor->surface = NULL;
    cursor->hidden = false;
}

static void pointer_focus(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwCursor *cursor = wl_container_of(listener, cursor, pointer_focus);
    clear(cursor);
    update(cursor);
}
static void commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwCursor *cursor = wl_container_of(listener, cursor, commit);
    update(cursor);
}
static void destroyed(struct wl_listener *listener, void *data) {
    (void)data;
    struct MdwCursor *cursor = wl_container_of(listener, cursor, destroy);
    clear(cursor);
    update(cursor);
}
static void set_cursor(struct wl_listener *listener, void *data) {
    struct MdwCursor *cursor = wl_container_of(listener, cursor, set_cursor);
    struct wlr_seat_pointer_request_set_cursor_event *event = data;
    if (event->seat_client != cursor->server->seat->pointer_state.focused_client ||
            !wlr_seat_client_validate_event_serial(event->seat_client, event->serial)) return;
    clear(cursor);
    cursor->surface = event->surface;
    cursor->hidden = event->surface == NULL;
    cursor->hotspot_x = event->hotspot_x;
    cursor->hotspot_y = event->hotspot_y;
    if (cursor->surface) {
        listen(&cursor->surface->events.commit, &cursor->commit, commit);
        listen(&cursor->surface->events.destroy, &cursor->destroy, destroyed);
    }
    update(cursor);
}

bool mdw_cursor_init(MdwServer *server) {
    struct MdwCursor *cursor = calloc(1, sizeof(*cursor));
    if (!cursor) return false;
    server->cursor = cursor;
    cursor->server = server;
    cursor->pending_fd = -1;
    listen(&server->seat->pointer_state.events.focus_change, &cursor->pointer_focus, pointer_focus);
    listen(&server->seat->events.request_set_cursor, &cursor->set_cursor, set_cursor);
    return true;
}

void mdw_cursor_finish(MdwServer *server) {
    struct MdwCursor *cursor = server->cursor;
    if (!cursor) return;
    clear(cursor);
    wl_list_remove(&cursor->pointer_focus.link);
    wl_list_remove(&cursor->set_cursor.link);
    mdg_readback_destroy(cursor->readback);
    free(cursor);
    server->cursor = NULL;
}
