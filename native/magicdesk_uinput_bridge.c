#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <unistd.h>


#define CONTROL_BUFFER_SIZE 2048
#define MAGICDESK_VENDOR_ID 0x4d44
#define MAGICDESK_MOUSE_PRODUCT_ID 0x0001
#define MAGICDESK_MOUSE_LOCATION "magicdesk-mouse"
#define WHEEL_HI_RES_UNITS_PER_STEP 120

struct bridge_state {
    int uinput_fd;
    bool control_primary_down;
    uint64_t write_errors;
    uint64_t reports;
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
        if (event->type == EV_SYN && event->code == SYN_REPORT) state->reports++;
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

static int emit_click(
        struct bridge_state *state,
        const int uinput_fd,
        const unsigned short code) {
    return emit_key(state, uinput_fd, code, 1) < 0
            || emit_sync(state, uinput_fd) < 0
            || emit_key(state, uinput_fd, code, 0) < 0
            || emit_sync(state, uinput_fd) < 0 ? -1 : 0;
}

static int emit_wheel_steps(
        struct bridge_state *state,
        const int uinput_fd,
        const int steps) {
    if (steps == 0) {
        return 0;
    }
    if (steps > INT_MAX / WHEEL_HI_RES_UNITS_PER_STEP
            || steps < INT_MIN / WHEEL_HI_RES_UNITS_PER_STEP) return -1;
    return emit_relative(
                    state,
                    uinput_fd,
                    REL_WHEEL_HI_RES,
                    steps * WHEEL_HI_RES_UNITS_PER_STEP) < 0
            || emit_relative(state, uinput_fd, REL_WHEEL, steps) < 0
            || emit_sync(state, uinput_fd) < 0 ? -1 : 0;
}

static void emit_stats(const struct bridge_state *state,
        const unsigned long long request_id) {
    printf("MAGICDESK_MOUSE_STATS request=%llu reports=%llu writeErrors=%llu\n",
            request_id, (unsigned long long)state->reports,
            (unsigned long long)state->write_errors);
    fflush(stdout);
}

static void emit_line(const char *line) {
    printf("%s\n", line);
    fflush(stdout);
}

static int configure_virtual_mouse(const int uinput_fd) {
    unsigned int version = 0;
    if (ioctl(uinput_fd, UI_GET_VERSION, &version) < 0) return -1;
    struct uinput_setup setup = {
        .id = {
            .bustype = BUS_VIRTUAL,
            .vendor = MAGICDESK_VENDOR_ID,
            .product = MAGICDESK_MOUSE_PRODUCT_ID,
            .version = 1,
        },
    };
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "MagicDesk Mouse");
    if (version >= 5) return ioctl(uinput_fd, UI_DEV_SETUP, &setup);

    // Android releases do not determine the kernel's uinput protocol version.
    // Version 4 configures the same device with one complete descriptor write.
    struct uinput_user_dev device = {.id = setup.id};
    memcpy(device.name, setup.name, sizeof(device.name));
    const ssize_t count = write(uinput_fd, &device, sizeof(device));
    if (count == (ssize_t)sizeof(device)) return 0;
    if (count >= 0) errno = EIO;
    return -1;
}

static int create_virtual_mouse(const int uinput_fd, const char **stage) {
    *stage = "capabilities";
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
    *stage = "setup";
    if (configure_virtual_mouse(uinput_fd) < 0) return -1;
    *stage = "create";
    return ioctl(uinput_fd, UI_DEV_CREATE);
}

static int set_control_primary(struct bridge_state *state, const bool pressed) {
    if (state->control_primary_down == pressed) return 0;
    if (emit_key(state, state->uinput_fd, BTN_LEFT, pressed ? 1 : 0) < 0
            || emit_sync(state, state->uinput_fd) < 0) return -1;
    state->control_primary_down = pressed;
    return 0;
}

static int handle_control_line(
        struct bridge_state *state,
        const char *line) {
    int first = 0;
    int second = 0;
    if (sscanf(line, "move %d %d", &first, &second) == 2) {
        if (emit_relative(state, state->uinput_fd, REL_X, first) < 0
                || emit_relative(state, state->uinput_fd, REL_Y, second) < 0) {
            return -1;
        }
        return first != 0 || second != 0
                ? emit_sync(state, state->uinput_fd) : 0;
    }
    if (strcmp(line, "click-primary") == 0) {
        if (state->control_primary_down) {
            return 0;
        }
        return emit_click(state, state->uinput_fd, BTN_LEFT);
    }
    if (strcmp(line, "click-secondary") == 0) {
        return emit_click(state, state->uinput_fd, BTN_RIGHT);
    }
    if (strcmp(line, "primary-down") == 0) {
        return set_control_primary(state, true);
    }
    if (strcmp(line, "primary-up") == 0) {
        return set_control_primary(state, false);
    }
    unsigned long long request_id = 0;
    if (sscanf(line, "stats %llu", &request_id) == 1) {
        emit_stats(state, request_id);
        return 0;
    }
    if (sscanf(line, "scroll %d", &first) == 1) {
        return emit_wheel_steps(state, state->uinput_fd, first);
    }
    return -1;
}

static int read_control(
        struct bridge_state *state,
        char *control_buffer,
        size_t *control_length) {
    char bytes[256];
    const ssize_t count = read(STDIN_FILENO, bytes, sizeof(bytes));
    if (count <= 0) {
        return count < 0 && errno == EINTR ? 0 : -1;
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
            return -1;
        }
        control_buffer[(*control_length)++] = value;
    }
    return 0;
}

int main(void) {
    struct sigaction action = {.sa_handler = request_stop};
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    signal(SIGPIPE, SIG_IGN);
    const char *stage = "open";
    const int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0 || create_virtual_mouse(fd, &stage) < 0) {
        fprintf(stderr, "MAGICDESK_MOUSE_ERROR stage=%s create=%s\n",
                stage, strerror(errno));
        if (fd >= 0) close(fd);
        return 1;
    }
    struct bridge_state state = {.uinput_fd = fd};
    emit_line("MAGICDESK_MOUSE_READY");
    char buffer[CONTROL_BUFFER_SIZE];
    size_t length = 0;
    while (!stop_requested) {
        struct pollfd input = {.fd = STDIN_FILENO, .events = POLLIN};
        const int result = poll(&input, 1, -1);
        if (result < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if ((input.revents & POLLIN) && read_control(&state, buffer, &length) < 0) break;
        if (input.revents & (POLLHUP | POLLERR | POLLNVAL)) break;
    }
    set_control_primary(&state, false);
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
