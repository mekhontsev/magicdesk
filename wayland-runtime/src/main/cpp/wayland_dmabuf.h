#ifndef MAGICDESK_WAYLAND_DMABUF_H
#define MAGICDESK_WAYLAND_DMABUF_H
#include <stdbool.h>
struct wl_display;
struct wlr_renderer;
/* The renderer outlives the display and its clients. No global on software-only devices. */
bool mdw_dmabuf_init(struct wl_display *display, struct wlr_renderer *renderer);
#endif
