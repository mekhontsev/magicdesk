#include "wayland_clock.h"
#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <wlr/backend/interface.h>
#include <wlr/interfaces/wlr_output.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/types/wlr_output_layer.h>

struct Clock {
    struct wlr_backend base;
    struct wl_event_loop *loop;
    struct wl_event_source *timer;
    struct wl_list outputs;
    struct wl_listener loop_destroy;
    void (*request)(void *);
    void *context;
    uint64_t sequence;
    unsigned next_output;
    int64_t last_tick;
    bool armed, started, ticking;
};

struct Output {
    struct wlr_output base;
    struct Clock *clock;
    struct wl_list link;
    struct wl_listener needs_frame;
    struct wl_event_source *present;
    uint32_t present_sequence;
    uint64_t scheduled;
};

static int64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000 + now.tv_nsec;
}

static void schedule(struct Output *output) {
    struct Clock *clock = output->clock;
    if (!output->scheduled) output->scheduled = ++clock->sequence;
    if (clock->armed) return;
    clock->armed = true;
    if (clock->request) clock->request(clock->context);
    else {
        int64_t remaining = clock->last_tick + 16666667 - monotonic_ns();
        /* EVENT_WAIT: fallback frame deadline; no timer remains armed at idle. */
        wl_event_source_timer_update(clock->timer, remaining > 0 ? (remaining + 999999) / 1000000 : 1);
    }
}

static void needs_frame(struct wl_listener *listener, void *data) {
    (void)data;
    struct Output *output = wl_container_of(listener, output, needs_frame);
    if (output->base.enabled) schedule(output);
}

static void present(void *data) {
    struct Output *output = data;
    output->present = NULL;
    struct wlr_output_event_present event = {
        .commit_seq = output->present_sequence, .presented = true,
    };
    wlr_output_send_present(&output->base, &event);
}

static bool test(struct wlr_output *base, const struct wlr_output_state *state) {
    (void)base;
    uint32_t supported = WLR_OUTPUT_STATE_BACKEND_OPTIONAL | WLR_OUTPUT_STATE_BUFFER |
        WLR_OUTPUT_STATE_ENABLED | WLR_OUTPUT_STATE_MODE;
    if (state->committed & ~supported) return false;
    if ((state->committed & WLR_OUTPUT_STATE_MODE) && state->mode_type != WLR_OUTPUT_STATE_MODE_CUSTOM) return false;
    if (state->committed & WLR_OUTPUT_STATE_LAYERS)
        for (size_t i = 0; i < state->layers_len; ++i) state->layers[i].accepted = true;
    return true;
}

static bool commit(struct wlr_output *base, const struct wlr_output_state *state) {
    struct Output *output = wl_container_of(base, output, base);
    if (!test(base, state)) return false;
    bool enabled = state->committed & WLR_OUTPUT_STATE_ENABLED ? state->enabled : base->enabled;
    if (enabled) {
        output->present_sequence = base->commit_seq + 1;
        if (!output->present) output->present = wl_event_loop_add_idle(output->clock->loop, present, output);
        schedule(output);
    } else output->scheduled = 0;
    return true;
}

static void output_destroy(struct wlr_output *base) {
    struct Output *output = wl_container_of(base, output, base);
    if (output->present) wl_event_source_remove(output->present);
    wl_list_remove(&output->needs_frame.link);
    wl_list_remove(&output->link);
    free(output);
}

static const struct wlr_output_impl output_impl = {.test = test, .commit = commit, .destroy = output_destroy};

struct wlr_output *mdw_clock_output(struct wlr_backend *base, unsigned width, unsigned height) {
    struct Clock *clock = wl_container_of(base, clock, base);
    struct Output *output = calloc(1, sizeof(*output));
    if (!output) return NULL;
    output->clock = clock;
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_custom_mode(&state, width, height, 60000);
    wlr_output_init(&output->base, base, &output_impl, clock->loop, &state);
    wlr_output_state_finish(&state);
    output->needs_frame.notify = needs_frame;
    wl_signal_add(&output->base.events.needs_frame, &output->needs_frame);
    wl_list_insert(&clock->outputs, &output->link);
    char name[48];
    snprintf(name, sizeof(name), "MagicDesk-%u", ++clock->next_output);
    wlr_output_set_name(&output->base, name);
    wlr_output_set_description(&output->base, "MagicDesk hosted output");
    if (clock->started) wl_signal_emit_mutable(&base->events.new_output, &output->base);
    return &output->base;
}

void mdw_clock_tick(struct wlr_backend *base, int64_t time_ns) {
    struct Clock *clock = wl_container_of(base, clock, base);
    clock->armed = false;
    clock->last_tick = time_ns;
    clock->ticking = true;
    uint64_t through = clock->sequence;
    struct Output *output, *next;
    wl_list_for_each_safe(output, next, &clock->outputs, link) {
        if (!output->scheduled || output->scheduled > through) continue;
        output->scheduled = 0;
        wlr_output_send_frame(&output->base);
    }
    clock->ticking = false;
}

bool mdw_clock_frame_ready(struct wlr_output *base) {
    struct Output *output = wl_container_of(base, output, base);
    if (output->clock->ticking) return true;
    schedule(output);
    return false;
}

static int timer_tick(void *data) {
    struct Clock *clock = data;
    mdw_clock_tick(&clock->base, monotonic_ns());
    return 0;
}

void mdw_clock_set_host(struct wlr_backend *base, void (*request)(void *), void *context) {
    struct Clock *clock = wl_container_of(base, clock, base);
    wl_event_source_timer_update(clock->timer, 0);
    clock->request = request;
    clock->context = context;
    clock->armed = false;
    struct Output *output;
    wl_list_for_each(output, &clock->outputs, link)
        if (output->scheduled) schedule(output);
}

static bool start(struct wlr_backend *base) {
    struct Clock *clock = wl_container_of(base, clock, base);
    clock->started = true;
    struct Output *output;
    wl_list_for_each(output, &clock->outputs, link)
        wl_signal_emit_mutable(&base->events.new_output, &output->base);
    return true;
}

static void destroy(struct wlr_backend *base) {
    struct Clock *clock = wl_container_of(base, clock, base);
    wlr_backend_finish(base);
    struct Output *output, *next;
    wl_list_for_each_safe(output, next, &clock->outputs, link) wlr_output_destroy(&output->base);
    wl_event_source_remove(clock->timer);
    wl_list_remove(&clock->loop_destroy.link);
    free(clock);
}

static uint32_t buffer_caps(struct wlr_backend *base) {
    (void)base;
    return WLR_BUFFER_CAP_DATA_PTR | WLR_BUFFER_CAP_DMABUF | WLR_BUFFER_CAP_SHM;
}

static const struct wlr_backend_impl backend_impl = {.start = start, .destroy = destroy, .get_buffer_caps = buffer_caps};

static void loop_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct Clock *clock = wl_container_of(listener, clock, loop_destroy);
    destroy(&clock->base);
}

struct wlr_backend *mdw_clock_create(struct wl_event_loop *loop) {
    struct Clock *clock = calloc(1, sizeof(*clock));
    if (!clock) return NULL;
    clock->loop = loop;
    clock->timer = wl_event_loop_add_timer(loop, timer_tick, clock);
    if (!clock->timer) { free(clock); return NULL; }
    wlr_backend_init(&clock->base, &backend_impl);
    wl_list_init(&clock->outputs);
    clock->loop_destroy.notify = loop_destroy;
    wl_event_loop_add_destroy_listener(loop, &clock->loop_destroy);
    return &clock->base;
}
