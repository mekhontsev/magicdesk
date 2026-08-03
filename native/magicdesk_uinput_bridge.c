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

#define BITS_PER_LONG (sizeof(unsigned long) * 8U)
#define BIT_WORDS(maximum) (((maximum) / BITS_PER_LONG) + 1U)
#define CONTROL_BUFFER_SIZE 2048
#define MAX_SOURCES 16
#define SOURCE_PATH_SIZE 128
#define MAGICDESK_VENDOR_ID 0x4d44
#define MAGICDESK_MOUSE_PRODUCT_ID 0x0001
#define MAGICDESK_MOUSE_LOCATION "magicdesk-mouse"

struct source_device {
    int fd;
    char path[SOURCE_PATH_SIZE];
    bool grabbed;
    bool key_down[KEY_MAX + 1];
};

struct bridge_state {
    struct source_device *sources;
    int source_count;
    int uinput_fd;
    uint16_t key_down_count[KEY_MAX + 1];
    bool forwarded_down[KEY_MAX + 1];
    bool started;
    bool pointer_restore_armed;
    bool pointer_moved;
};

static volatile sig_atomic_t stop_requested;

static void request_stop(int signal_number) {
    (void)signal_number;
    stop_requested = 1;
}

static bool bit_is_set(
        const unsigned long *bits,
        const unsigned int bit) {
    return (bits[bit / BITS_PER_LONG]
            & (1UL << (bit % BITS_PER_LONG))) != 0;
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

static void drain_source(const int source_fd) {
    struct input_event events[64];
    while (read(source_fd, events, sizeof(events)) > 0) {
        // Discard events accumulated before this source was captured.
    }
}

static int source_has_active_keys(const int source_fd) {
    unsigned long key_bits[BIT_WORDS(KEY_MAX)] = {0};
    if (ioctl(
                source_fd,
                EVIOCGKEY(sizeof(key_bits)),
                key_bits) < 0) {
        return -1;
    }
    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (bit_is_set(key_bits, code)) {
            return 1;
        }
    }
    return 0;
}

static int try_grab_source(struct source_device *source) {
    // Let Android finish an in-flight physical button sequence before capture.
    if (source->grabbed) {
        return 1;
    }
    const int active_before = source_has_active_keys(source->fd);
    if (active_before != 0) {
        return active_before < 0 ? -1 : 0;
    }
    drain_source(source->fd);
    if (ioctl(source->fd, EVIOCGRAB, 1) < 0) {
        return -1;
    }
    source->grabbed = true;
    const int active_after = source_has_active_keys(source->fd);
    if (active_after <= 0) {
        return active_after < 0 ? -1 : 1;
    }
    ioctl(source->fd, EVIOCGRAB, 0);
    source->grabbed = false;
    drain_source(source->fd);
    return 0;
}

static int open_source(
        struct source_device *source,
        const char *path,
        const bool grab) {
    memset(source, 0, sizeof(*source));
    source->fd = -1;
    if (strlen(path) >= sizeof(source->path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    strcpy(source->path, path);
    source->fd = open(
            source->path,
            O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (source->fd < 0) {
        return -1;
    }
    if (grab) {
        if (try_grab_source(source) < 0) {
            close(source->fd);
            source->fd = -1;
            return -1;
        }
    }
    return 0;
}

static int open_sources(
        struct source_device *sources,
        const int source_count,
        char **paths) {
    for (int index = 0; index < source_count; ++index) {
        if (open_source(&sources[index], paths[index], false) < 0) {
            fprintf(stderr,
                    "MAGICDESK_MOUSE_ERROR open=%s error=%s\n",
                    paths[index],
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static void close_source(struct source_device *source) {
    if (source->fd >= 0) {
        if (source->grabbed) {
            ioctl(source->fd, EVIOCGRAB, 0);
        }
        close(source->fd);
    }
    memset(source, 0, sizeof(*source));
    source->fd = -1;
}

static void release_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        close_source(&sources[index]);
    }
}

static int grab_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        if (try_grab_source(&sources[index]) < 0) {
            fprintf(stderr,
                    "MAGICDESK_MOUSE_ERROR grab=%s error=%s\n",
                    sources[index].path,
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static int clear_button_state(struct bridge_state *state) {
    bool released = false;
    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (!state->forwarded_down[code]) {
            continue;
        }
        if (emit_key(state->uinput_fd, (unsigned short)code, 0) < 0) {
            return -1;
        }
        released = true;
    }
    if (released && emit_sync(state->uinput_fd) < 0) {
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

static int parse_source_paths(
        const char *value,
        char paths[MAX_SOURCES][SOURCE_PATH_SIZE]) {
    int count = 0;
    const char *cursor = value;
    while (*cursor != '\0') {
        while (*cursor == ' ') {
            cursor++;
        }
        if (*cursor == '\0') {
            break;
        }
        const char *end = strchr(cursor, ' ');
        const size_t length = end == NULL
                ? strlen(cursor) : (size_t)(end - cursor);
        if (length == 0 || length >= SOURCE_PATH_SIZE
                || count >= MAX_SOURCES) {
            return -1;
        }
        memcpy(paths[count], cursor, length);
        paths[count][length] = '\0';
        bool duplicate = false;
        for (int index = 0; index < count; ++index) {
            if (strcmp(paths[index], paths[count]) == 0) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) {
            count++;
        }
        cursor = end == NULL ? cursor + length : end + 1;
    }
    return count;
}

static int reconcile_sources(
        struct bridge_state *state,
        const char *value) {
    char paths[MAX_SOURCES][SOURCE_PATH_SIZE];
    const int requested_count = parse_source_paths(value, paths);
    if (requested_count < 0) {
        fprintf(stderr, "MAGICDESK_MOUSE_ERROR sources=invalid\n");
        return 0;
    }
    bool unchanged = requested_count == state->source_count;
    for (int index = 0; unchanged && index < requested_count; ++index) {
        unchanged = strcmp(paths[index], state->sources[index].path) == 0;
    }
    if (unchanged) {
        return 0;
    }
    if (clear_button_state(state) < 0) {
        return -1;
    }

    struct source_device next[MAX_SOURCES];
    memset(next, 0, sizeof(next));
    for (int index = 0; index < MAX_SOURCES; ++index) {
        next[index].fd = -1;
    }
    int next_count = 0;
    for (int requested = 0; requested < requested_count; ++requested) {
        int existing = -1;
        for (int index = 0; index < state->source_count; ++index) {
            if (state->sources[index].fd >= 0
                    && strcmp(state->sources[index].path,
                            paths[requested]) == 0) {
                existing = index;
                break;
            }
        }
        if (existing >= 0) {
            next[next_count++] = state->sources[existing];
            state->sources[existing].fd = -1;
            state->sources[existing].path[0] = '\0';
            continue;
        }
        if (open_source(
                    &next[next_count],
                    paths[requested],
                    state->started) < 0) {
            fprintf(stderr,
                    "MAGICDESK_MOUSE_SOURCE_SKIPPED"
                    " path=%s error=%s\n",
                    paths[requested],
                    strerror(errno));
            continue;
        }
        next_count++;
    }
    release_sources(state->sources, state->source_count);
    memcpy(state->sources, next, sizeof(next));
    state->source_count = next_count;

    char output[96];
    snprintf(output, sizeof(output),
            "MAGICDESK_MOUSE_SOURCES count=%d",
            state->source_count);
    emit_line(output);
    return 0;
}

static int remove_source(
        struct bridge_state *state,
        const int source_index) {
    if (clear_button_state(state) < 0) {
        return -1;
    }
    close_source(&state->sources[source_index]);
    if (source_index + 1 < state->source_count) {
        memmove(
                &state->sources[source_index],
                &state->sources[source_index + 1],
                (size_t)(state->source_count - source_index - 1)
                        * sizeof(state->sources[0]));
    }
    state->source_count--;
    memset(&state->sources[state->source_count], 0,
            sizeof(state->sources[0]));
    state->sources[state->source_count].fd = -1;
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
        return write_event(state->uinput_fd, event);
    }
    if (event->value == 2) {
        return source->key_down[code]
                && state->forwarded_down[code]
                ? write_event(state->uinput_fd, event) : 0;
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
    return write_event(state->uinput_fd, event);
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
        if (state->pointer_restore_armed
                && (event->code == REL_X || event->code == REL_Y)
                && event->value != 0) {
            state->pointer_moved = true;
        }
        return write_event(state->uinput_fd, event);
    }
    if (event->type == EV_SYN) {
        if (write_event(state->uinput_fd, event) < 0) {
            return -1;
        }
        if (state->pointer_restore_armed && state->pointer_moved
                && event->code == SYN_REPORT) {
            state->pointer_restore_armed = false;
            state->pointer_moved = false;
            emit_line("MAGICDESK_MOUSE_POINTER_MOTION");
        }
        return 0;
    }
    return 0;
}

static int handle_control_line(
        struct bridge_state *state,
        const char *line) {
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
            if (!state->sources[index].grabbed) {
                if (try_grab_source(&state->sources[index]) < 0
                        && remove_source(state, index) < 0) {
                    result = -1;
                    stop_requested = 1;
                }
                break;
            }
            const size_t event_count =
                    (size_t)bytes / sizeof(events[0]);
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
    if (argc < 2 || argc - 1 > MAX_SOURCES) {
        fprintf(stderr,
                "usage: %s /dev/input/eventN [/dev/input/eventN ...]\n",
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
    if (open_sources(sources, source_count, &argv[1]) < 0) {
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    const int uinput_fd =
            open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (uinput_fd < 0) {
        fprintf(stderr,
                "MAGICDESK_MOUSE_ERROR uinput=open error=%s\n",
                strerror(errno));
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }
    if (create_virtual_mouse(uinput_fd) < 0) {
        fprintf(stderr,
                "MAGICDESK_MOUSE_ERROR uinput=create error=%s\n",
                strerror(errno));
        close(uinput_fd);
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    usleep(250000);
    if (grab_sources(sources, source_count) < 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }

    struct bridge_state state = {
        .sources = sources,
        .source_count = source_count,
        .uinput_fd = uinput_fd,
        .started = true,
    };
    printf("MAGICDESK_MOUSE_READY sources=%d uid=%d\n",
            source_count,
            getuid());
    fflush(stdout);

    const int result = forward_events(&state);
    clear_button_state(&state);
    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    release_sources(sources, state.source_count);
    free(sources);
    return result == 0 ? 0 : 1;
}
