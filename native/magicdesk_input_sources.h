#ifndef MAGICDESK_INPUT_SOURCES_H
#define MAGICDESK_INPUT_SOURCES_H

#include <linux/input.h>
#include <stdbool.h>
#include <stdint.h>
#include <sys/time.h>

#define MAX_SOURCES 16
#define SOURCE_PATH_SIZE 128

struct source_device {
    int fd;
    char path[SOURCE_PATH_SIZE];
    bool grabbed;
    bool repeat_overridden;
    unsigned int original_repeat[2];
    bool key_down[KEY_MAX + 1];
    bool consumed[KEY_MAX + 1];
};

typedef int (*magicdesk_clear_input_state_fn)(void *context);

int magicdesk_open_sources(
        struct source_device *sources,
        int source_count,
        char **paths,
        const char *component);

int magicdesk_grab_sources(
        struct source_device *sources,
        int source_count,
        const char *component);

int magicdesk_try_grab_source(struct source_device *source);

void magicdesk_ungrab_sources(
        struct source_device *sources,
        int source_count);

int magicdesk_override_source_repeat(
        struct source_device *source,
        unsigned int delay_ms,
        unsigned int period_ms);

void magicdesk_release_sources(
        struct source_device *sources,
        int source_count);

int magicdesk_reconcile_sources(
        struct source_device *sources,
        int *source_count,
        const char *value,
        bool grab_new_sources,
        magicdesk_clear_input_state_fn clear_input_state,
        void *context,
        const char *component);

int magicdesk_remove_source(
        struct source_device *sources,
        int *source_count,
        int source_index,
        magicdesk_clear_input_state_fn clear_input_state,
        void *context);

int magicdesk_grabbed_source_count(
        const struct source_device *sources,
        int source_count);

int64_t magicdesk_input_event_age_millis(struct timeval event_time);

#endif
