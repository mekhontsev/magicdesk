#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define BITS_PER_LONG (sizeof(unsigned long) * 8U)
#define BIT_WORDS(maximum) (((maximum) / BITS_PER_LONG) + 1U)
#define HEARTBEAT_TIMEOUT_SECONDS 6
#define MAX_QUEUED_EVENTS 2048
#define CONTROL_BUFFER_SIZE 256
#define MAX_LAYOUTS 16
#define MAGICDESK_VENDOR_ID 0x4d44
#define MAGICDESK_KEYBOARD_PRODUCT_BASE 0x4b00

#define MOD_CTRL (1U << 0)
#define MOD_ALT (1U << 1)
#define MOD_SHIFT (1U << 2)
#define MOD_META (1U << 3)

struct source_device {
    int fd;
    const char *path;
    bool key_down[KEY_MAX + 1];
    bool consumed[KEY_MAX + 1];
};

struct queued_event {
    int source_index;
    struct input_event event;
};

struct bridge_state {
    struct source_device *sources;
    int source_count;
    int *uinput_fds;
    int layout_count;
    int active_layout;
    uint16_t key_down_count[KEY_MAX + 1];
    bool forwarded_down[KEY_MAX + 1];
    bool modifier_pending[KEY_MAX + 1];
    bool modifier_consumed[KEY_MAX + 1];
    unsigned long modifier_order[KEY_MAX + 1];
    struct input_event modifier_down_event[KEY_MAX + 1];
    unsigned long next_modifier_order;
    bool alt_tab_active;
    bool started;
    bool paused;
    struct queued_event queue[MAX_QUEUED_EVENTS];
    size_t queue_head;
    size_t queue_count;
};

static volatile sig_atomic_t stop_requested;

static int active_uinput_fd(const struct bridge_state *state) {
    return state->uinput_fds[state->active_layout];
}

static void request_stop(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static bool bit_is_set(
        const unsigned long *bits,
        unsigned int bit) {
    return (bits[bit / BITS_PER_LONG]
            & (1UL << (bit % BITS_PER_LONG))) != 0;
}

static long monotonic_seconds(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) < 0) {
        return 0;
    }
    return value.tv_sec;
}

static bool is_modifier(const unsigned short code) {
    return code == KEY_LEFTCTRL
            || code == KEY_RIGHTCTRL
            || code == KEY_LEFTALT
            || code == KEY_RIGHTALT
            || code == KEY_LEFTSHIFT
            || code == KEY_RIGHTSHIFT
            || code == KEY_LEFTMETA
            || code == KEY_RIGHTMETA;
}

static bool is_meta_modifier(const unsigned short code) {
    return code == KEY_LEFTMETA || code == KEY_RIGHTMETA;
}

static unsigned int modifier_mask(
        const struct bridge_state *state) {
    unsigned int mask = 0;
    if (state->key_down_count[KEY_LEFTCTRL] > 0
            || state->key_down_count[KEY_RIGHTCTRL] > 0) {
        mask |= MOD_CTRL;
    }
    if (state->key_down_count[KEY_LEFTALT] > 0
            || state->key_down_count[KEY_RIGHTALT] > 0) {
        mask |= MOD_ALT;
    }
    if (state->key_down_count[KEY_LEFTSHIFT] > 0
            || state->key_down_count[KEY_RIGHTSHIFT] > 0) {
        mask |= MOD_SHIFT;
    }
    if (state->key_down_count[KEY_LEFTMETA] > 0
            || state->key_down_count[KEY_RIGHTMETA] > 0) {
        mask |= MOD_META;
    }
    return mask;
}

static int write_event(
        const int uinput_fd,
        const struct input_event *event) {
    const ssize_t bytes = write(uinput_fd, event, sizeof(*event));
    return bytes == (ssize_t)sizeof(*event) ? 0 : -1;
}

static int emit_key(
        const int uinput_fd,
        const unsigned short code,
        const int value) {
    struct input_event event = {
        .type = EV_KEY,
        .code = code,
        .value = value,
    };
    gettimeofday(&event.time, NULL);
    return write_event(uinput_fd, &event);
}

static int emit_sync(const int uinput_fd) {
    struct input_event event = {
        .type = EV_SYN,
        .code = SYN_REPORT,
        .value = 0,
    };
    gettimeofday(&event.time, NULL);
    return write_event(uinput_fd, &event);
}

static void emit_line(const char *line) {
    printf("%s\n", line);
    fflush(stdout);
}

static void consume_modifiers(struct bridge_state *state) {
    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (is_modifier((unsigned short)code)
                && state->key_down_count[code] > 0) {
            state->modifier_consumed[code] = true;
        }
    }
}

static int flush_pending_modifiers(
        struct bridge_state *state) {
    while (true) {
        int selected = -1;
        unsigned long selected_order = 0;
        for (unsigned int code = 0; code <= KEY_MAX; ++code) {
            if (!state->modifier_pending[code]
                    || state->forwarded_down[code]) {
                continue;
            }
            if (selected < 0
                    || state->modifier_order[code] < selected_order) {
                selected = (int)code;
                selected_order = state->modifier_order[code];
            }
        }
        if (selected < 0) {
            return 0;
        }
        if (write_event(
                    active_uinput_fd(state),
                    &state->modifier_down_event[selected]) < 0) {
            return -1;
        }
        state->forwarded_down[selected] = true;
        state->modifier_consumed[selected] = false;
    }
}

static const char *shortcut_action(
        const unsigned short code,
        const unsigned int modifiers) {
    if (modifiers == MOD_CTRL && code == KEY_SPACE) {
        return "CTRL_SPACE";
    }
    if (modifiers == MOD_ALT && code == KEY_F4) {
        return "ALT_F4";
    }
    if (modifiers != MOD_META) {
        return NULL;
    }
    switch (code) {
        case KEY_BACKSPACE:
            return "META_BACKSPACE";
        case KEY_L:
            return "META_L";
        case KEY_N:
            return "META_N";
        case KEY_UP:
            return "META_UP";
        case KEY_DOWN:
            return "META_DOWN";
        case KEY_LEFT:
            return "META_LEFT";
        case KEY_RIGHT:
            return "META_RIGHT";
        case KEY_D:
            return "META_D";
        case KEY_SYSRQ:
            return "META_PRINT_SCREEN";
        case KEY_SLASH:
            return "META_SLASH";
        default:
            return NULL;
    }
}

static int process_modifier_event(
        struct bridge_state *state,
        struct source_device *source,
        const struct input_event *event) {
    const unsigned short code = event->code;
    if (event->value == 1) {
        if (source->key_down[code]) {
            return 0;
        }
        source->key_down[code] = true;
        if (state->key_down_count[code]++ == 0) {
            state->modifier_pending[code] = true;
            state->modifier_consumed[code] = false;
            state->modifier_order[code] =
                    ++state->next_modifier_order;
            state->modifier_down_event[code] = *event;
        }
        return 0;
    }
    if (event->value == 2) {
        return 0;
    }
    if (event->value != 0 || !source->key_down[code]) {
        return 0;
    }

    source->key_down[code] = false;
    if (state->key_down_count[code] > 0) {
        state->key_down_count[code]--;
    }
    if (state->key_down_count[code] > 0) {
        return 0;
    }

    if ((code == KEY_LEFTALT || code == KEY_RIGHTALT)
            && state->alt_tab_active) {
        state->alt_tab_active = false;
        emit_line("MAGICDESK_ALT_TAB_COMMIT");
    }

    int result = 0;
    if (state->forwarded_down[code]) {
        result = write_event(active_uinput_fd(state), event);
    } else if (state->modifier_pending[code]
            && !state->modifier_consumed[code]
            && !is_meta_modifier(code)) {
        if (write_event(
                    active_uinput_fd(state),
                    &state->modifier_down_event[code]) < 0
                || write_event(active_uinput_fd(state), event) < 0) {
            result = -1;
        }
    }
    state->forwarded_down[code] = false;
    state->modifier_pending[code] = false;
    state->modifier_consumed[code] = false;
    return result;
}

static int process_key_event(
        struct bridge_state *state,
        struct source_device *source,
        const struct input_event *event) {
    const unsigned short code = event->code;
    if (code > KEY_MAX) {
        return 0;
    }
    if (is_modifier(code)) {
        return process_modifier_event(state, source, event);
    }

    if (event->value == 1) {
        const bool first_for_source = !source->key_down[code];
        if (first_for_source) {
            source->key_down[code] = true;
            state->key_down_count[code]++;
        }
        const bool first_global =
                first_for_source && state->key_down_count[code] == 1;
        const unsigned int modifiers = modifier_mask(state);
        if (first_global
                && code == KEY_ESC
                && modifiers == 0) {
            emit_line("MAGICDESK_SHORTCUT ESCAPE");
        }

        if (first_global
                && code == KEY_TAB
                && (modifiers == MOD_ALT
                        || modifiers == (MOD_ALT | MOD_SHIFT))) {
            source->consumed[code] = true;
            consume_modifiers(state);
            state->alt_tab_active = true;
            emit_line(modifiers == (MOD_ALT | MOD_SHIFT)
                    ? "MAGICDESK_ALT_TAB_ADVANCE reverse"
                    : "MAGICDESK_ALT_TAB_ADVANCE forward");
            return 0;
        }

        const char *action =
                first_global ? shortcut_action(code, modifiers) : NULL;
        if (action != NULL) {
            char line[96];
            source->consumed[code] = true;
            consume_modifiers(state);
            snprintf(line, sizeof(line),
                    "MAGICDESK_SHORTCUT %s", action);
            emit_line(line);
            if (strcmp(action, "CTRL_SPACE") == 0) {
                state->paused = true;
            }
            return 0;
        }

        if (source->consumed[code] || !first_global) {
            return 0;
        }
        if (flush_pending_modifiers(state) < 0
                || write_event(active_uinput_fd(state), event) < 0) {
            return -1;
        }
        state->forwarded_down[code] = true;
        return 0;
    }

    if (event->value == 2) {
        if (source->consumed[code]) {
            return 0;
        }
        if (flush_pending_modifiers(state) < 0) {
            return -1;
        }
        return write_event(active_uinput_fd(state), event);
    }

    if (event->value != 0) {
        return 0;
    }
    if (source->consumed[code]) {
        source->consumed[code] = false;
        if (source->key_down[code]) {
            source->key_down[code] = false;
            if (state->key_down_count[code] > 0) {
                state->key_down_count[code]--;
            }
        }
        return 0;
    }
    if (!source->key_down[code]) {
        return 0;
    }
    source->key_down[code] = false;
    if (state->key_down_count[code] > 0) {
        state->key_down_count[code]--;
    }
    if (state->key_down_count[code] > 0
            || !state->forwarded_down[code]) {
        return 0;
    }
    state->forwarded_down[code] = false;
    return write_event(active_uinput_fd(state), event);
}

static int process_event(
        struct bridge_state *state,
        const int source_index,
        const struct input_event *event) {
    if (event->type == EV_KEY) {
        return process_key_event(
                state, &state->sources[source_index], event);
    }
    if (event->type == EV_SYN) {
        return write_event(active_uinput_fd(state), event);
    }
    return 0;
}

static int queue_event(
        struct bridge_state *state,
        const int source_index,
        const struct input_event *event) {
    if (state->queue_count >= MAX_QUEUED_EVENTS) {
        fprintf(stderr,
                "MAGICDESK_SHIZUKU_KEYBOARD_ERROR queue=overflow\n");
        return -1;
    }
    const size_t index =
            (state->queue_head + state->queue_count)
                    % MAX_QUEUED_EVENTS;
    state->queue[index].source_index = source_index;
    state->queue[index].event = *event;
    state->queue_count++;
    return 0;
}

static int drain_queue(struct bridge_state *state) {
    while (!state->paused && state->queue_count > 0) {
        const struct queued_event queued =
                state->queue[state->queue_head];
        state->queue_head =
                (state->queue_head + 1) % MAX_QUEUED_EVENTS;
        state->queue_count--;
        if (process_event(
                    state,
                    queued.source_index,
                    &queued.event) < 0) {
            return -1;
        }
    }
    return 0;
}

static int enable_source_capabilities(
        const int source_fd,
        const int uinput_fd) {
    unsigned long key_bits[BIT_WORDS(KEY_MAX)] = {0};
    if (ioctl(
                source_fd,
                EVIOCGBIT(EV_KEY, sizeof(key_bits)),
                key_bits) < 0) {
        return -1;
    }
    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (bit_is_set(key_bits, code)
                && ioctl(uinput_fd, UI_SET_KEYBIT, code) < 0) {
            return -1;
        }
    }
    return 0;
}

static int create_virtual_keyboard(
        const struct source_device *sources,
        const int source_count,
        const int uinput_fd,
        const int layout_index) {
    struct input_id source_id = {0};
    if (ioctl(sources[0].fd, EVIOCGID, &source_id) < 0) {
        return -1;
    }
    struct uinput_setup setup = {
        .id = source_id,
    };
    setup.id.vendor = MAGICDESK_VENDOR_ID;
    setup.id.product = (uint16_t)(
            MAGICDESK_KEYBOARD_PRODUCT_BASE + layout_index);
    setup.id.version = 1;
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE,
            "MagicDesk Shizuku Keyboard %d", layout_index);

    char location[64];
    snprintf(location, sizeof(location),
            "magicdesk-shizuku-keyboard-%d", layout_index);

    if (ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN) < 0
            || ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY) < 0
            || ioctl(uinput_fd, UI_SET_PHYS,
                    location) < 0) {
        return -1;
    }
    for (int index = 0; index < source_count; ++index) {
        if (enable_source_capabilities(
                    sources[index].fd, uinput_fd) < 0) {
            return -1;
        }
    }
    if (ioctl(uinput_fd, UI_DEV_SETUP, &setup) < 0
            || ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        return -1;
    }
    return 0;
}

static void release_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        if (sources[index].fd < 0) {
            continue;
        }
        ioctl(sources[index].fd, EVIOCGRAB, 0);
        close(sources[index].fd);
        sources[index].fd = -1;
    }
}

static int open_sources(
        struct source_device *sources,
        const int source_count,
        char **paths) {
    for (int index = 0; index < source_count; ++index) {
        sources[index].fd = -1;
        sources[index].path = paths[index];
        sources[index].fd = open(
                sources[index].path,
                O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (sources[index].fd < 0) {
            fprintf(stderr,
                    "MAGICDESK_SHIZUKU_KEYBOARD_ERROR open=%s error=%s\n",
                    sources[index].path,
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static void drain_source(const int source_fd) {
    struct input_event events[64];
    while (read(source_fd, events, sizeof(events)) > 0) {
        // Discard events accumulated before routing was ready.
    }
}

static int grab_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        drain_source(sources[index].fd);
        if (ioctl(sources[index].fd, EVIOCGRAB, 1) < 0) {
            fprintf(stderr,
                    "MAGICDESK_SHIZUKU_KEYBOARD_ERROR grab=%s error=%s\n",
                    sources[index].path,
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static int release_forwarded_keys(struct bridge_state *state) {
    bool released = false;
    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (!state->forwarded_down[code]) {
            continue;
        }
        if (emit_key(
                    active_uinput_fd(state),
                    (unsigned short)code,
                    0) < 0) {
            return -1;
        }
        state->forwarded_down[code] = false;
        released = true;
    }
    return !released || emit_sync(active_uinput_fd(state)) == 0 ? 0 : -1;
}

static int handle_control_line(
        struct bridge_state *state,
        const char *line) {
    if (strncmp(line, "layout ", 7) == 0) {
        char *end = NULL;
        const long index = strtol(line + 7, &end, 10);
        if (end == line + 7 || *end != '\0'
                || index < 0 || index >= state->layout_count) {
            fprintf(stderr,
                    "MAGICDESK_SHIZUKU_KEYBOARD_ERROR layout=%s\n",
                    line + 7);
            return -1;
        }
        if (index != state->active_layout) {
            if (release_forwarded_keys(state) < 0) {
                return -1;
            }
            state->active_layout = (int)index;
        }
        char output[96];
        snprintf(output, sizeof(output),
                "MAGICDESK_SHIZUKU_KEYBOARD_LAYOUT index=%d",
                state->active_layout);
        emit_line(output);
    } else if (strcmp(line, "start") == 0 && !state->started) {
        if (grab_sources(state->sources, state->source_count) < 0) {
            return -1;
        }
        state->started = true;
        emit_line("MAGICDESK_SHIZUKU_KEYBOARD_STARTED");
    } else if (strcmp(line, "resume") == 0 && state->paused) {
        state->paused = false;
        if (drain_queue(state) < 0) {
            return -1;
        }
    }
    return 0;
}

static int read_control(
        struct bridge_state *state,
        char *control_buffer,
        size_t *control_length,
        long *last_heartbeat) {
    char bytes[128];
    const ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
    if (count <= 0) {
        return -1;
    }
    *last_heartbeat = monotonic_seconds();
    for (ssize_t index = 0; index < count; ++index) {
        const char value = bytes[index];
        if (value == '\n') {
            control_buffer[*control_length] = '\0';
            if (handle_control_line(state, control_buffer) < 0) {
                return -1;
            }
            *control_length = 0;
            continue;
        }
        if (value == '\r') {
            continue;
        }
        if (*control_length + 1 >= CONTROL_BUFFER_SIZE) {
            *control_length = 0;
            continue;
        }
        control_buffer[(*control_length)++] = value;
    }
    return 0;
}

static int forward_events(struct bridge_state *state) {
    const int descriptor_count = state->source_count + 1;
    struct pollfd *poll_descriptors =
            calloc((size_t)descriptor_count, sizeof(*poll_descriptors));
    if (poll_descriptors == NULL) {
        return -1;
    }
    poll_descriptors[0].fd = STDIN_FILENO;
    poll_descriptors[0].events = POLLIN | POLLHUP;
    for (int index = 0; index < state->source_count; ++index) {
        poll_descriptors[index + 1].fd = state->sources[index].fd;
        poll_descriptors[index + 1].events =
                POLLIN | POLLHUP | POLLERR;
    }

    long last_heartbeat = monotonic_seconds();
    char control_buffer[CONTROL_BUFFER_SIZE];
    size_t control_length = 0;
    struct input_event events[64];
    int result = 0;
    while (!stop_requested) {
        const int poll_result =
                poll(poll_descriptors, (nfds_t)descriptor_count, 250);
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            result = -1;
            break;
        }
        if (monotonic_seconds() - last_heartbeat
                > HEARTBEAT_TIMEOUT_SECONDS) {
            fprintf(stderr,
                    "MAGICDESK_SHIZUKU_KEYBOARD_ERROR heartbeat=timeout\n");
            result = -1;
            break;
        }
        if ((poll_descriptors[0].revents
                & (POLLHUP | POLLERR)) != 0) {
            break;
        }
        if ((poll_descriptors[0].revents & POLLIN) != 0
                && read_control(
                        state,
                        control_buffer,
                        &control_length,
                        &last_heartbeat) < 0) {
            break;
        }
        if (!state->started) {
            continue;
        }

        for (int index = 0;
                index < state->source_count;
                ++index) {
            const short revents =
                    poll_descriptors[index + 1].revents;
            if ((revents & (POLLHUP | POLLERR)) != 0) {
                result = -1;
                stop_requested = 1;
                break;
            }
            if ((revents & POLLIN) == 0) {
                continue;
            }
            const ssize_t bytes = read(
                    state->sources[index].fd,
                    events,
                    sizeof(events));
            if (bytes < 0) {
                if (errno == EAGAIN || errno == EINTR) {
                    continue;
                }
                result = -1;
                stop_requested = 1;
                break;
            }
            if (bytes == 0) {
                result = -1;
                stop_requested = 1;
                break;
            }
            const size_t event_count =
                    (size_t)bytes / sizeof(events[0]);
            for (size_t event_index = 0;
                    event_index < event_count;
                    ++event_index) {
                const unsigned short type =
                        events[event_index].type;
                if (type != EV_SYN && type != EV_KEY) {
                    continue;
                }
                const int event_result = state->paused
                        ? queue_event(
                                state,
                                index,
                                &events[event_index])
                        : process_event(
                                state,
                                index,
                                &events[event_index]);
                if (event_result < 0) {
                    result = -1;
                    stop_requested = 1;
                    break;
                }
            }
        }
    }
    free(poll_descriptors);
    return result;
}

int main(int argc, char **argv) {
    if (argc < 4 || strcmp(argv[1], "--layouts") != 0) {
        fprintf(stderr,
                "usage: %s --layouts N"
                " /dev/input/eventN [/dev/input/eventN ...]\n",
                argv[0]);
        return 64;
    }

    char *layout_end = NULL;
    const long parsed_layout_count = strtol(argv[2], &layout_end, 10);
    if (layout_end == argv[2] || *layout_end != '\0'
            || parsed_layout_count <= 0
            || parsed_layout_count > MAX_LAYOUTS) {
        fprintf(stderr,
                "MAGICDESK_SHIZUKU_KEYBOARD_ERROR layouts=%s\n",
                argv[2]);
        return 64;
    }
    const int layout_count = (int)parsed_layout_count;

    signal(SIGINT, request_stop);
    signal(SIGTERM, request_stop);
    signal(SIGPIPE, SIG_IGN);

    const int source_count = argc - 3;
    struct source_device *sources =
            calloc((size_t)source_count, sizeof(*sources));
    if (sources == NULL) {
        perror("allocate sources");
        return 1;
    }
    if (open_sources(sources, source_count, &argv[3]) < 0) {
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    int *uinput_fds = calloc(
            (size_t)layout_count, sizeof(*uinput_fds));
    if (uinput_fds == NULL) {
        perror("allocate virtual keyboards");
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }
    for (int index = 0; index < layout_count; ++index) {
        uinput_fds[index] = -1;
    }
    int created_layouts = 0;
    for (int index = 0; index < layout_count; ++index) {
        const int uinput_fd = open(
                "/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (uinput_fd < 0
                || create_virtual_keyboard(
                        sources,
                        source_count,
                        uinput_fd,
                        index) < 0) {
            fprintf(stderr,
                    "MAGICDESK_SHIZUKU_KEYBOARD_ERROR"
                    " uinput=create layout=%d error=%s\n",
                    index,
                    strerror(errno));
            if (uinput_fd >= 0) {
                close(uinput_fd);
            }
            break;
        }
        uinput_fds[index] = uinput_fd;
        created_layouts++;
    }
    if (created_layouts != layout_count) {
        for (int index = 0; index < created_layouts; ++index) {
            ioctl(uinput_fds[index], UI_DEV_DESTROY);
            close(uinput_fds[index]);
        }
        free(uinput_fds);
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    struct bridge_state state = {
        .sources = sources,
        .source_count = source_count,
        .uinput_fds = uinput_fds,
        .layout_count = layout_count,
        .active_layout = 0,
    };
    printf("MAGICDESK_SHIZUKU_KEYBOARD_READY"
            " sources=%d layouts=%d uid=%d\n",
            source_count,
            layout_count,
            getuid());
    fflush(stdout);

    const int result = forward_events(&state);
    release_forwarded_keys(&state);
    for (int index = 0; index < layout_count; ++index) {
        ioctl(uinput_fds[index], UI_DEV_DESTROY);
        close(uinput_fds[index]);
    }
    free(uinput_fds);
    release_sources(sources, source_count);
    free(sources);
    return result == 0 ? 0 : 1;
}
