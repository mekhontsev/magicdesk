#ifndef MAGICDESK_WAYLAND_CLOCK_H
#define MAGICDESK_WAYLAND_CLOCK_H

#include <stdint.h>
#include <stdbool.h>
struct wl_event_loop;
struct wlr_backend;
struct wlr_output;

/* Host requests are one-shot and must not deliver a tick synchronously. */
struct wlr_backend *mdw_clock_create(struct wl_event_loop *loop);
struct wlr_output *mdw_clock_output(struct wlr_backend *backend, unsigned width, unsigned height);
void mdw_clock_set_host(struct wlr_backend *backend, void (*request)(void *), void *context);
void mdw_clock_tick(struct wlr_backend *backend, int64_t time_ns);
bool mdw_clock_frame_ready(struct wlr_output *output);

#endif
