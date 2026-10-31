#define WLR_USE_UNSTABLE
#include "wayland_clock.h"
#include <assert.h>
#include <stdio.h>
#include <wlr/backend.h>
#include <wlr/types/wlr_output.h>
#include <wayland-server-core.h>

struct Fixture {
    struct wlr_output *output;
    struct wl_listener frame;
    unsigned frames;
    bool recommit;
};

static void enable(struct wlr_output *output, bool enabled);
static void request(void *data) { ++*(unsigned *)data; }
static void frame(struct wl_listener *listener, void *data) {
    (void)data;
    struct Fixture *fixture = wl_container_of(listener, fixture, frame);
    if (!mdw_clock_frame_ready(fixture->output)) return;
    ++fixture->frames;
    if (fixture->recommit) {
        fixture->recommit = false;
        enable(fixture->output, true);
    }
}

static void enable(struct wlr_output *output, bool enabled) {
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_enabled(&state, enabled);
    assert(wlr_output_commit_state(output, &state));
    wlr_output_state_finish(&state);
}

int main(void) {
    struct wl_event_loop *loop = wl_event_loop_create();
    struct wlr_backend *backend = mdw_clock_create(loop);
    assert(backend && wlr_backend_start(backend));
    unsigned requests = 0;
    mdw_clock_set_host(backend, request, &requests);
    struct Fixture first = {.output = mdw_clock_output(backend, 80, 60)};
    struct Fixture second = {.output = mdw_clock_output(backend, 80, 60)};
    assert(first.output && second.output);
    first.frame.notify = second.frame.notify = frame;
    wl_signal_add(&first.output->events.frame, &first.frame);
    wl_signal_add(&second.output->events.frame, &second.frame);
    enable(first.output, true);
    enable(second.output, true);
    for (int i = 0; i < 100; ++i) {
        wlr_output_schedule_frame(first.output);
        wlr_output_schedule_frame(second.output);
    }
    wl_event_loop_dispatch_idle(loop);
    assert(requests == 1 && first.frames == 0 && second.frames == 0);
    mdw_clock_tick(backend, 1000000000);
    assert(first.frames == 1 && second.frames == 1);
    for (int i = 0; i < 100; ++i) assert(wl_event_loop_dispatch(loop, 0) == 0);
    assert(requests == 1 && first.frames == 1 && second.frames == 1);
    wlr_output_schedule_frame(first.output);
    wl_event_loop_dispatch_idle(loop);
    assert(requests == 2);
    mdw_clock_tick(backend, 1008333333);
    assert(first.frames == 2 && second.frames == 1);
    enable(first.output, false);
    wlr_output_schedule_frame(first.output);
    wl_event_loop_dispatch_idle(loop);
    assert(requests == 2);
    enable(first.output, true);
    assert(requests == 3);
    wl_list_remove(&first.frame.link);
    wlr_output_destroy(first.output);
    mdw_clock_tick(backend, 1016666666);
    assert(second.frames == 1);
    wlr_output_schedule_frame(second.output);
    wl_event_loop_dispatch_idle(loop);
    assert(requests == 4);
    mdw_clock_set_host(backend, NULL, NULL);
    /* EVENT_WAIT: fallback timer must service pending damage without host vsync. */
    for (int i = 0; i < 10 && second.frames == 1; ++i) assert(wl_event_loop_dispatch(loop, 10) == 0);
    assert(second.frames == 2);
    mdw_clock_set_host(backend, request, &requests);
    wlr_output_schedule_frame(second.output);
    wl_event_loop_dispatch_idle(loop);
    assert(requests == 5 && second.frames == 2);
    mdw_clock_tick(backend, 1025000000);
    assert(second.frames == 3);
    second.recommit = true;
    wlr_output_schedule_frame(second.output);
    wl_event_loop_dispatch_idle(loop);
    assert(requests == 6);
    mdw_clock_tick(backend, 1033333333);
    wl_event_loop_dispatch_idle(loop);
    assert(second.frames == 4 && requests == 7);
    mdw_clock_tick(backend, 1041666666);
    assert(second.frames == 5 && requests == 7);
    wl_list_remove(&second.frame.link);
    wlr_backend_destroy(backend);
    wl_event_loop_destroy(loop);
    puts("frame clock: coalescing, independent outputs, idle, 120 Hz, clock replacement, reentrant commit, disable and pending destruction passed");
}
