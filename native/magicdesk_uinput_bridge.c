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
#include <unistd.h>

#include "magicdesk_input_sources.h"

#define CONTROL_BUFFER_SIZE 2048
#define MAGICDESK_VENDOR_ID 0x4d44
#define MAGICDESK_MOUSE_PRODUCT_ID 0x0001
#define MAGICDESK_MOUSE_LOCATION "magicdesk-mouse"
#define WHEEL_HI_RES_UNITS_PER_STEP 120

struct bridge_state {
    struct source_device *sources;
    int source_count;
    int uinput_fd;
    uint16_t key_down_count[KEY_MAX + 1];
    bool forwarded_down[KEY_MAX + 1];
    bool pointer_restore_armed;
    bool pointer_reactivation_armed;
    bool pointer_moved;
    bool capture_enabled;
    int pointer_activation_direction;
    uint64_t physical_reports;
    uint64_t physical_motion_reports;
    uint64_t forwarded_reports;
    uint64_t forwarded_motion_reports;
    uint64_t write_errors;
    struct timeval last_physical_motion;
    struct timeval last_forwarded_motion;
    bool report_has_motion;
};

static volatile sig_atomic_t stop_requested;

static void emit_line(const char *line);

static void request_stop(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static int write_event(
        struct bridge_state *state,
        const int uinput_fd,
        const struct input_event *event) {
    const ssize_t bytes = write(uinput_fd, event, sizeof(*event));
    if (bytes == (ssize_t)sizeof(*event)) {
        return 0;
    }
    state->write_errors++;
    return -1;
}

static int emit_key(
        struct bridge_state *state,
        const int uinput_fd,
        const unsigned short code,
        const int value) {
    struct input_event event = {
        .type = EV_KEY,
        .code = code,
        .value = value,
    };
    gettimeofday(&event.time, NULL);
    return write_event(state, uinput_fd, &event);
}

static int emit_sync(
        struct bridge_state *state,
        const int uinput_fd) {
    struct input_event event = {
        .type = EV_SYN,
        .code = SYN_REPORT,
        .value = 0,
    };
    gettimeofday(&event.time, NULL);
    return write_event(state, uinput_fd, &event);
}

static int emit_relative(
        struct bridge_state *state,
        const int uinput_fd,
        const unsigned short code,
        const int value) {
    if (value == 0) {
        return 0;
    }
    struct input_event event = {
        .type = EV_REL,
        .code = code,
        .value = value,
    };
    gettimeofday(&event.time, NULL);
    return write_event(state, uinput_fd, &event);
}

static int emit_wheel_steps(
        struct bridge_state *state,
        const int uinput_fd,
        const int steps) {
    if (steps == 0) {
        return 0;
    }
    return emit_relative(
                    state,
                    uinput_fd,
                    REL_WHEEL_HI_RES,
                    steps * WHEEL_HI_RES_UNITS_PER_STEP) < 0
            || emit_relative(state, uinput_fd, REL_WHEEL, steps) < 0
            || emit_sync(state, uinput_fd) < 0 ? -1 : 0;
}

static int activate_pointer(
        struct bridge_state *state,
        const int uinput_fd,
        const int direction) {
    return emit_relative(state, uinput_fd, REL_X, direction) < 0
            || emit_sync(state, uinput_fd) < 0
            || emit_relative(state, uinput_fd, REL_X, -direction) < 0
            || emit_sync(state, uinput_fd) < 0 ? -1 : 0;
}

static void emit_stats(
        const struct bridge_state *state,
        const unsigned long long request_id) {
    char output[512];
    snprintf(output, sizeof(output),
            "MAGICDESK_MOUSE_STATS request=%llu"
            " physicalReports=%llu physicalMotionReports=%llu"
            " forwardedReports=%llu forwardedMotionReports=%llu"
            " writeErrors=%llu lastPhysicalMotionAgeMs=%lld"
            " lastForwardedMotionAgeMs=%lld sources=%d grabbed=%d"
            " capture=%d restoreArmed=%d reactivateArmed=%d",
            request_id,
            (unsigned long long)state->physical_reports,
            (unsigned long long)state->physical_motion_reports,
            (unsigned long long)state->forwarded_reports,
            (unsigned long long)state->forwarded_motion_reports,
            (unsigned long long)state->write_errors,
            (long long)magicdesk_input_event_age_millis(
                    state->last_physical_motion),
            (long long)magicdesk_input_event_age_millis(
                    state->last_forwarded_motion),
            state->source_count,
            magicdesk_grabbed_source_count(
                    state->sources, state->source_count),
            state->capture_enabled ? 1 : 0,
            state->pointer_restore_armed ? 1 : 0,
            state->pointer_reactivation_armed ? 1 : 0);
    emit_line(output);
}

static void emit_line(const char *line) {
    printf("%s\n", line);
    fflush(stdout);
}

static int create_virtual_mouse(const int uinput_fd) {
    // Nubia maps right clicks from physical-bus mice to Android Back.
    struct uinput_setup setup = {
        .id = {
            .bustype = BUS_VIRTUAL,
            .vendor = MAGICDESK_VENDOR_ID,
            .product = MAGICDESK_MOUSE_PRODUCT_ID,
            .version = 1,
        },
    };
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "MagicDesk Mouse");

    if (ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN) < 0
            || ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY) < 0
            || ioctl(uinput_fd, UI_SET_EVBIT, EV_REL) < 0
            || ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_POINTER) < 0
            || ioctl(uinput_fd, UI_SET_PHYS,
                    MAGICDESK_MOUSE_LOCATION) < 0) {
        return -1;
    }
    for (unsigned int code = BTN_MOUSE; code <= BTN_TASK; ++code) {
        if (ioctl(uinput_fd, UI_SET_KEYBIT, code) < 0) {
            return -1;
        }
    }
    for (unsigned int code = 0; code <= REL_MAX; ++code) {
        if (ioctl(uinput_fd, UI_SET_RELBIT, code) < 0) {
            return -1;
        }
    }
    if (ioctl(uinput_fd, UI_DEV_SETUP, &setup) < 0
            || ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        return -1;
    }
    return 0;
}

static int clear_button_state(void *context) {
    struct bridge_state *state = context;
    bool released = false;
    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (!state->forwarded_down[code]) {
            continue;
        }
        if (emit_key(
                    state,
                    state->uinput_fd,
                    (unsigned short)code,
                    0) < 0) {
            return -1;
        }
        released = true;
    }
    if (released && emit_sync(state, state->uinput_fd) < 0) {
        return -1;
    }
    memset(state->key_down_count, 0, sizeof(state->key_down_count));
    memset(state->forwarded_down, 0, sizeof(state->forwarded_down));
    for (int index = 0; index < state->source_count; ++index) {
        memset(state->sources[index].key_down, 0,
                sizeof(state->sources[index].key_down));
    }
    return 0;
}

static int reconcile_sources(
        struct bridge_state *state,
        const char *value) {
    const int result = magicdesk_reconcile_sources(
            state->sources,
            &state->source_count,
            value,
            false,
            clear_button_state,
            state,
            "MOUSE");
    if (result < 0) {
        return -1;
    }
    if (result > 0) {
        if (state->capture_enabled
                && magicdesk_grab_sources(
                        state->sources,
                        state->source_count,
                        "MOUSE") < 0) {
            return -1;
        }
        char output[96];
        snprintf(output, sizeof(output),
                "MAGICDESK_MOUSE_SOURCES count=%d",
                state->source_count);
        emit_line(output);
    }
    return 0;
}

static int remove_source(
        struct bridge_state *state,
        const int source_index) {
    if (magicdesk_remove_source(
                state->sources,
                &state->source_count,
                source_index,
                clear_button_state,
                state) < 0) {
        return -1;
    }
    char output[96];
    snprintf(output, sizeof(output),
            "MAGICDESK_MOUSE_SOURCES count=%d",
            state->source_count);
    emit_line(output);
    return 0;
}

static int process_key_event(
        struct bridge_state *state,
        struct source_device *source,
        const struct input_event *event) {
    const unsigned short code = event->code;
    if (code > KEY_MAX) {
        return 0;
    }
    if (event->value == 1) {
        if (source->key_down[code]) {
            return 0;
        }
        source->key_down[code] = true;
        if (state->key_down_count[code]++ > 0) {
            return 0;
        }
        if (code == BTN_RIGHT) {
            return 0;
        }
        state->forwarded_down[code] = true;
        return write_event(state, state->uinput_fd, event);
    }
    if (event->value == 2) {
        return source->key_down[code]
                && state->forwarded_down[code]
                ? write_event(state, state->uinput_fd, event) : 0;
    }
    if (event->value != 0 || !source->key_down[code]) {
        return 0;
    }
    source->key_down[code] = false;
    if (state->key_down_count[code] > 0) {
        state->key_down_count[code]--;
    }
    if (state->key_down_count[code] > 0
            || !state->forwarded_down[code]) {
        if (code == BTN_RIGHT && state->key_down_count[code] == 0) {
            emit_line("MAGICDESK_MOUSE_SECONDARY_CLICK");
        }
        return 0;
    }
    state->forwarded_down[code] = false;
    return write_event(state, state->uinput_fd, event);
}

static int process_event(
        struct bridge_state *state,
        const int source_index,
        const struct input_event *event) {
    if (event->type == EV_KEY) {
        return process_key_event(
                state, &state->sources[source_index], event);
    }
    if (event->type == EV_REL) {
        if ((event->code == REL_X || event->code == REL_Y)
                && event->value != 0) {
            state->report_has_motion = true;
        }
        if ((state->pointer_restore_armed
                    || state->pointer_reactivation_armed)
                && (event->code == REL_X || event->code == REL_Y)
                && event->value != 0) {
            state->pointer_moved = true;
        }
        return write_event(state, state->uinput_fd, event);
    }
    if (event->type == EV_SYN) {
        const bool report_complete = event->code == SYN_REPORT;
        if (report_complete) {
            state->physical_reports++;
            if (state->report_has_motion) {
                state->physical_motion_reports++;
                state->last_physical_motion = event->time;
            }
        }
        if (write_event(state, state->uinput_fd, event) < 0) {
            return -1;
        }
        if (report_complete) {
            state->forwarded_reports++;
            if (state->report_has_motion) {
                state->forwarded_motion_reports++;
                state->last_forwarded_motion = event->time;
            }
            state->report_has_motion = false;
        }
        if (state->pointer_moved && report_complete) {
            state->pointer_moved = false;
            if (state->pointer_restore_armed) {
                state->pointer_restore_armed = false;
                emit_line("MAGICDESK_MOUSE_POINTER_MOTION");
            }
            if (state->pointer_reactivation_armed) {
                state->pointer_reactivation_armed = false;
                emit_line("MAGICDESK_MOUSE_POINTER_REACTIVATE");
            }
        }
        return 0;
    }
    return 0;
}

static int handle_control_line(
        struct bridge_state *state,
        const char *line) {
    int first = 0;
    if (strcmp(line, "start") == 0) {
        // The virtual device must be associated with the desktop before a
        // physical report is captured and forwarded through it.
        state->capture_enabled = true;
        return magicdesk_grab_sources(
                state->sources, state->source_count, "MOUSE");
    }
    if (strcmp(line, "stop") == 0) {
        state->capture_enabled = false;
        if (clear_button_state(state) < 0) {
            return -1;
        }
        magicdesk_ungrab_sources(
                state->sources, state->source_count);
        emit_line("MAGICDESK_MOUSE_CAPTURE_STOPPED");
        return 0;
    }
    if (strcmp(line, "sources") == 0) {
        return reconcile_sources(state, "");
    }
    if (strncmp(line, "sources ", 8) == 0) {
        return reconcile_sources(state, line + 8);
    }
    if (strcmp(line, "restore-pointer-on-motion") == 0) {
        state->pointer_restore_armed = true;
        state->pointer_moved = false;
        return 0;
    }
    if (strcmp(line, "reactivate-pointer-on-motion") == 0) {
        state->pointer_reactivation_armed = true;
        state->pointer_moved = false;
        return 0;
    }
    if (strcmp(line, "activate-pointer") == 0) {
        const int result = activate_pointer(
                state,
                state->uinput_fd,
                state->pointer_activation_direction);
        if (result == 0) {
            // Alternating the pulse prevents edge clamping from accumulating
            // a one-way cursor offset during long touchpad sessions.
            state->pointer_activation_direction =
                    -state->pointer_activation_direction;
        }
        return result;
    }
    unsigned long long request_id = 0;
    if (sscanf(line, "stats %llu", &request_id) == 1) {
        emit_stats(state, request_id);
        return 0;
    }
    if (sscanf(line, "scroll %d", &first) == 1) {
        return emit_wheel_steps(state, state->uinput_fd, first);
    }
    return 0;
}

static int read_control(
        struct bridge_state *state,
        char *control_buffer,
        size_t *control_length) {
    char bytes[256];
    const ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
    if (count <= 0) {
        return -1;
    }
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
    char control_buffer[CONTROL_BUFFER_SIZE];
    size_t control_length = 0;
    struct input_event events[64];
    int result = 0;
    while (!stop_requested) {
        struct pollfd poll_descriptors[MAX_SOURCES + 1] = {0};
        const int descriptor_count = state->source_count + 1;
        poll_descriptors[0].fd = STDIN_FILENO;
        poll_descriptors[0].events = POLLIN | POLLHUP;
        for (int index = 0; index < state->source_count; ++index) {
            poll_descriptors[index + 1].fd = state->sources[index].fd;
            poll_descriptors[index + 1].events =
                    POLLIN | POLLHUP | POLLERR;
        }

        const int poll_result =
                poll(poll_descriptors, (nfds_t)descriptor_count, -1);
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            result = -1;
            break;
        }
        if ((poll_descriptors[0].revents & (POLLHUP | POLLERR)) != 0) {
            break;
        }
        if ((poll_descriptors[0].revents & POLLIN) != 0) {
            if (read_control(
                    state,
                    control_buffer,
                    &control_length) < 0) {
                break;
            }
            continue;
        }

        for (int index = 0; index < state->source_count; ++index) {
            const short revents = poll_descriptors[index + 1].revents;
            if ((revents & (POLLHUP | POLLERR | POLLNVAL)) != 0) {
                if (remove_source(state, index) < 0) {
                    result = -1;
                    stop_requested = 1;
                }
                break;
            }
            if ((revents & POLLIN) == 0) {
                continue;
            }
            const ssize_t bytes = read(
                    state->sources[index].fd, events, sizeof(events));
            if (bytes < 0) {
                if (errno == EAGAIN || errno == EINTR) {
                    continue;
                }
                if (remove_source(state, index) < 0) {
                    result = -1;
                    stop_requested = 1;
                }
                break;
            }
            if (bytes == 0) {
                if (remove_source(state, index) < 0) {
                    result = -1;
                    stop_requested = 1;
                }
                break;
            }
            const size_t event_count =
                    (size_t)bytes / sizeof(events[0]);
            if (!state->sources[index].grabbed) {
                const int grabbed = state->capture_enabled
                        ? magicdesk_try_grab_source(
                                &state->sources[index])
                        : 0;
                if (grabbed < 0
                        && remove_source(state, index) < 0) {
                    result = -1;
                    stop_requested = 1;
                }
                break;
            }
            for (size_t event_index = 0;
                    event_index < event_count;
                    ++event_index) {
                const unsigned short type = events[event_index].type;
                if (type != EV_SYN && type != EV_KEY && type != EV_REL) {
                    continue;
                }
                if (process_event(
                            state,
                            index,
                            &events[event_index]) < 0) {
                    result = -1;
                    stop_requested = 1;
                    break;
                }
            }
        }
    }
    return result;
}

int main(int argc, char **argv) {
    if (argc < 1 || argc - 1 > MAX_SOURCES) {
        fprintf(stderr,
                "usage: %s [/dev/input/eventN ...]\n",
                argv[0]);
        return 64;
    }

    signal(SIGINT, request_stop);
    signal(SIGTERM, request_stop);
    signal(SIGPIPE, SIG_IGN);

    const int source_count = argc - 1;
    struct source_device *sources =
            calloc(MAX_SOURCES, sizeof(*sources));
    if (sources == NULL) {
        perror("allocate sources");
        return 1;
    }
    for (int index = 0; index < MAX_SOURCES; ++index) {
        sources[index].fd = -1;
    }
    if (magicdesk_open_sources(
                sources,
                source_count,
                &argv[1],
                "MOUSE") < 0) {
        magicdesk_release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    const int uinput_fd =
            open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (uinput_fd < 0) {
        fprintf(stderr,
                "MAGICDESK_MOUSE_ERROR uinput=open error=%s\n",
                strerror(errno));
        magicdesk_release_sources(sources, source_count);
        free(sources);
        return 1;
    }
    if (create_virtual_mouse(uinput_fd) < 0) {
        fprintf(stderr,
                "MAGICDESK_MOUSE_ERROR uinput=create error=%s\n",
                strerror(errno));
        close(uinput_fd);
        magicdesk_release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    struct bridge_state state = {
        .sources = sources,
        .source_count = source_count,
        .uinput_fd = uinput_fd,
        .pointer_activation_direction = 1,
    };
    printf("MAGICDESK_MOUSE_READY sources=%d uid=%d\n",
            source_count,
            getuid());
    fflush(stdout);

    const int result = forward_events(&state);
    clear_button_state(&state);
    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    magicdesk_release_sources(sources, state.source_count);
    free(sources);
    return result == 0 ? 0 : 1;
}
