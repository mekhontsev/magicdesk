#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define BITS_PER_LONG (sizeof(unsigned long) * 8U)
#define BIT_WORDS(maximum) (((maximum) / BITS_PER_LONG) + 1U)
#define HEARTBEAT_TIMEOUT_SECONDS 6

struct source_device {
    int fd;
    const char *path;
};

static volatile sig_atomic_t stop_requested;

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

static int enable_source_capabilities(
        const int source_fd,
        const int uinput_fd) {
    unsigned long key_bits[BIT_WORDS(KEY_MAX)] = {0};
    unsigned long rel_bits[BIT_WORDS(REL_MAX)] = {0};
    unsigned long property_bits[BIT_WORDS(INPUT_PROP_MAX)] = {0};

    if (ioctl(source_fd, EVIOCGBIT(EV_KEY, sizeof(key_bits)), key_bits) < 0
            || ioctl(source_fd, EVIOCGBIT(EV_REL, sizeof(rel_bits)), rel_bits) < 0) {
        return -1;
    }

    for (unsigned int code = 0; code <= KEY_MAX; ++code) {
        if (bit_is_set(key_bits, code)
                && ioctl(uinput_fd, UI_SET_KEYBIT, code) < 0) {
            return -1;
        }
    }
    for (unsigned int code = 0; code <= REL_MAX; ++code) {
        if (bit_is_set(rel_bits, code)
                && ioctl(uinput_fd, UI_SET_RELBIT, code) < 0) {
            return -1;
        }
    }
    if (ioctl(source_fd, EVIOCGPROP(sizeof(property_bits)), property_bits) >= 0) {
        for (unsigned int property = 0;
                property <= INPUT_PROP_MAX;
                ++property) {
            if (bit_is_set(property_bits, property)
                    && ioctl(uinput_fd, UI_SET_PROPBIT, property) < 0) {
                return -1;
            }
        }
    }
    return 0;
}

static int create_virtual_mouse(
        const struct source_device *sources,
        const int source_count,
        const int uinput_fd) {
    struct uinput_setup setup = {
        .id = {
            .bustype = BUS_VIRTUAL,
            .vendor = 0x4d44,
            .product = 0x0001,
            .version = 1,
        },
    };
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "MagicDesk Shizuku Mouse");

    if (ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN) < 0
            || ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY) < 0
            || ioctl(uinput_fd, UI_SET_EVBIT, EV_REL) < 0) {
        return -1;
    }
    for (int index = 0; index < source_count; ++index) {
        if (enable_source_capabilities(sources[index].fd, uinput_fd) < 0) {
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
                    "MAGICDESK_SHIZUKU_MOUSE_ERROR open=%s error=%s\n",
                    sources[index].path,
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static int grab_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        if (ioctl(sources[index].fd, EVIOCGRAB, 1) < 0) {
            fprintf(stderr,
                    "MAGICDESK_SHIZUKU_MOUSE_ERROR grab=%s error=%s\n",
                    sources[index].path,
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static int forward_events(
        struct source_device *sources,
        const int source_count,
        const int uinput_fd) {
    const int descriptor_count = source_count + 1;
    struct pollfd *poll_descriptors =
            calloc((size_t)descriptor_count, sizeof(*poll_descriptors));
    if (poll_descriptors == NULL) {
        return -1;
    }
    poll_descriptors[0].fd = STDIN_FILENO;
    poll_descriptors[0].events = POLLIN | POLLHUP;
    for (int index = 0; index < source_count; ++index) {
        poll_descriptors[index + 1].fd = sources[index].fd;
        poll_descriptors[index + 1].events = POLLIN | POLLHUP | POLLERR;
    }

    long last_heartbeat = monotonic_seconds();
    struct input_event events[64];
    char heartbeat[64];
    int result = 0;
    while (!stop_requested) {
        const int poll_result =
                poll(poll_descriptors, (nfds_t)descriptor_count, 500);
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
                    "MAGICDESK_SHIZUKU_MOUSE_ERROR heartbeat=timeout\n");
            result = -1;
            break;
        }
        if ((poll_descriptors[0].revents & (POLLHUP | POLLERR)) != 0) {
            break;
        }
        if ((poll_descriptors[0].revents & POLLIN) != 0) {
            const ssize_t bytes =
                    read(STDIN_FILENO, heartbeat, sizeof(heartbeat));
            if (bytes <= 0) {
                break;
            }
            last_heartbeat = monotonic_seconds();
        }

        for (int index = 0; index < source_count; ++index) {
            const short revents = poll_descriptors[index + 1].revents;
            if ((revents & (POLLHUP | POLLERR)) != 0) {
                result = -1;
                stop_requested = 1;
                break;
            }
            if ((revents & POLLIN) == 0) {
                continue;
            }
            const ssize_t bytes =
                    read(sources[index].fd, events, sizeof(events));
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
                const unsigned short type = events[event_index].type;
                if (type != EV_SYN && type != EV_KEY && type != EV_REL) {
                    continue;
                }
                if (write(
                            uinput_fd,
                            &events[event_index],
                            sizeof(events[event_index]))
                        != sizeof(events[event_index])) {
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
    if (argc < 2) {
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
            calloc((size_t)source_count, sizeof(*sources));
    if (sources == NULL) {
        perror("allocate sources");
        return 1;
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
                "MAGICDESK_SHIZUKU_MOUSE_ERROR uinput=open error=%s\n",
                strerror(errno));
        release_sources(sources, source_count);
        free(sources);
        return 1;
    }
    if (create_virtual_mouse(sources, source_count, uinput_fd) < 0) {
        fprintf(stderr,
                "MAGICDESK_SHIZUKU_MOUSE_ERROR uinput=create error=%s\n",
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

    printf("MAGICDESK_SHIZUKU_MOUSE_READY sources=%d uid=%d\n",
            source_count,
            getuid());
    fflush(stdout);
    const int result =
            forward_events(sources, source_count, uinput_fd);

    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    release_sources(sources, source_count);
    free(sources);
    return result == 0 ? 0 : 1;
}
