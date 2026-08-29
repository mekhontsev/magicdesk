#include "magicdesk_input_sources.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define BITS_PER_LONG (sizeof(unsigned long) * 8U)
#define BIT_WORDS(maximum) (((maximum) / BITS_PER_LONG) + 1U)

static bool bit_is_set(
        const unsigned long *bits,
        const unsigned int bit) {
    return (bits[bit / BITS_PER_LONG]
            & (1UL << (bit % BITS_PER_LONG))) != 0;
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

int magicdesk_try_grab_source(struct source_device *source) {
    // Let Android finish an in-flight key or button sequence before capture.
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
    if (active_after == 0) {
        return 1;
    }
    ioctl(source->fd, EVIOCGRAB, 0);
    source->grabbed = false;
    drain_source(source->fd);
    return active_after < 0 ? -1 : 0;
}

void magicdesk_ungrab_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        struct source_device *source = &sources[index];
        if (!source->grabbed) {
            continue;
        }
        ioctl(source->fd, EVIOCGRAB, 0);
        source->grabbed = false;
        // Events queued while this fd held the grab were not delivered to
        // Android and therefore cannot initialize its physical pointer.
        drain_source(source->fd);
    }
}

int magicdesk_override_source_repeat(
        struct source_device *source,
        const unsigned int delay_ms,
        const unsigned int period_ms) {
    if (source->fd < 0) {
        errno = EBADF;
        return -1;
    }
    if (!source->repeat_overridden
            && ioctl(source->fd, EVIOCGREP,
                    source->original_repeat) < 0) {
        return errno == EINVAL || errno == ENOTTY ? 0 : -1;
    }
    const unsigned int requested[2] = {
        delay_ms,
        period_ms,
    };
    if (ioctl(source->fd, EVIOCSREP, requested) < 0) {
        return errno == EINVAL || errno == ENOTTY ? 0 : -1;
    }
    source->repeat_overridden = true;
    return 1;
}

static void restore_source_repeat(struct source_device *source) {
    if (source->fd >= 0 && source->repeat_overridden) {
        ioctl(source->fd, EVIOCSREP, source->original_repeat);
        source->repeat_overridden = false;
    }
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
    if (grab && magicdesk_try_grab_source(source) < 0) {
        close(source->fd);
        source->fd = -1;
        return -1;
    }
    return 0;
}

int magicdesk_open_sources(
        struct source_device *sources,
        const int source_count,
        char **paths,
        const char *component) {
    for (int index = 0; index < source_count; ++index) {
        if (open_source(&sources[index], paths[index], false) < 0) {
            fprintf(stderr,
                    "MAGICDESK_%s_ERROR open=%s error=%s\n",
                    component,
                    paths[index],
                    strerror(errno));
            return -1;
        }
    }
    return 0;
}

static void close_source(struct source_device *source) {
    if (source->fd >= 0) {
        restore_source_repeat(source);
        if (source->grabbed) {
            ioctl(source->fd, EVIOCGRAB, 0);
        }
        close(source->fd);
    }
    memset(source, 0, sizeof(*source));
    source->fd = -1;
}

void magicdesk_release_sources(
        struct source_device *sources,
        const int source_count) {
    for (int index = 0; index < source_count; ++index) {
        close_source(&sources[index]);
    }
}

int magicdesk_grab_sources(
        struct source_device *sources,
        const int source_count,
        const char *component) {
    for (int index = 0; index < source_count; ++index) {
        if (magicdesk_try_grab_source(&sources[index]) < 0) {
            fprintf(stderr,
                    "MAGICDESK_%s_ERROR grab=%s error=%s\n",
                    component,
                    sources[index].path,
                    strerror(errno));
            return -1;
        }
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

int magicdesk_reconcile_sources(
        struct source_device *sources,
        int *source_count,
        const char *value,
        const bool grab_new_sources,
        magicdesk_clear_input_state_fn clear_input_state,
        void *context,
        const char *component) {
    char paths[MAX_SOURCES][SOURCE_PATH_SIZE];
    const int requested_count = parse_source_paths(value, paths);
    if (requested_count < 0) {
        fprintf(stderr,
                "MAGICDESK_%s_ERROR sources=invalid\n",
                component);
        return 0;
    }
    bool unchanged = requested_count == *source_count;
    for (int index = 0; unchanged && index < requested_count; ++index) {
        unchanged = strcmp(paths[index], sources[index].path) == 0;
    }
    if (unchanged) {
        return 0;
    }
    if (clear_input_state(context) < 0) {
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
        for (int index = 0; index < *source_count; ++index) {
            if (sources[index].fd >= 0
                    && strcmp(sources[index].path,
                            paths[requested]) == 0) {
                existing = index;
                break;
            }
        }
        if (existing >= 0) {
            next[next_count++] = sources[existing];
            sources[existing].fd = -1;
            sources[existing].path[0] = '\0';
            continue;
        }
        if (open_source(
                    &next[next_count],
                    paths[requested],
                    grab_new_sources) < 0) {
            fprintf(stderr,
                    "MAGICDESK_%s_SOURCE_SKIPPED path=%s error=%s\n",
                    component,
                    paths[requested],
                    strerror(errno));
            continue;
        }
        next_count++;
    }
    magicdesk_release_sources(sources, *source_count);
    memcpy(sources, next, sizeof(next));
    *source_count = next_count;
    return 1;
}

int magicdesk_remove_source(
        struct source_device *sources,
        int *source_count,
        const int source_index,
        magicdesk_clear_input_state_fn clear_input_state,
        void *context) {
    if (clear_input_state(context) < 0) {
        return -1;
    }
    close_source(&sources[source_index]);
    if (source_index + 1 < *source_count) {
        memmove(
                &sources[source_index],
                &sources[source_index + 1],
                (size_t)(*source_count - source_index - 1)
                        * sizeof(sources[0]));
    }
    (*source_count)--;
    memset(&sources[*source_count], 0, sizeof(sources[0]));
    sources[*source_count].fd = -1;
    return 0;
}

int magicdesk_grabbed_source_count(
        const struct source_device *sources,
        const int source_count) {
    int count = 0;
    for (int index = 0; index < source_count; ++index) {
        if (sources[index].grabbed) {
            count++;
        }
    }
    return count;
}

static int64_t timeval_micros(const struct timeval value) {
    return (int64_t)value.tv_sec * 1000000LL
            + (int64_t)value.tv_usec;
}

int64_t magicdesk_input_event_age_millis(
        const struct timeval event_time) {
    const int64_t event_micros = timeval_micros(event_time);
    if (event_micros <= 0) {
        return -1;
    }
    struct timeval realtime = {0};
    struct timespec monotonic = {0};
    gettimeofday(&realtime, NULL);
    clock_gettime(CLOCK_MONOTONIC, &monotonic);
    const int64_t realtime_age =
            timeval_micros(realtime) - event_micros;
    const int64_t monotonic_micros =
            (int64_t)monotonic.tv_sec * 1000000LL
                    + (int64_t)monotonic.tv_nsec / 1000LL;
    const int64_t monotonic_age = monotonic_micros - event_micros;
    int64_t selected = -1;
    if (realtime_age >= 0) {
        selected = realtime_age;
    }
    if (monotonic_age >= 0
            && (selected < 0 || monotonic_age < selected)) {
        selected = monotonic_age;
    }
    return selected < 0 ? -1 : selected / 1000LL;
}
