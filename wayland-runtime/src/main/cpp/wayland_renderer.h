#ifndef MAGICDESK_WAYLAND_RENDERER_H
#define MAGICDESK_WAYLAND_RENDERER_H
#include <graphics.h>
struct wlr_renderer;
struct wlr_allocator;
struct wlr_buffer;
struct wlr_renderer *mdw_renderer_create(void);
struct wlr_allocator *mdw_allocator_create(struct wlr_renderer *renderer);
MdgImage *mdw_buffer_image(struct wlr_buffer *buffer);
MdgDevice *mdw_renderer_device(struct wlr_renderer *renderer);
#endif
