#define _GNU_SOURCE
#include "event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

/* Host-side fixture receipt: compositor window removal is not process completion. */
int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] != '/') return 125;
    char *directory = strdup(argv[1]);
    if (!directory) return 125;
    char *slash = strrchr(directory, '/');
    if (slash == directory) slash[1] = 0; else *slash = 0;
    int watch = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (watch < 0 || inotify_add_watch(watch, directory, IN_CLOSE_WRITE | IN_MOVED_TO) < 0) return 125;
    free(directory);
    long deadline = md_event_now() + 15000000000LL;
    for (;;) {
        int receipt = open(argv[1], O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (receipt >= 0) {
            char text[32] = {0}, *end;
            struct stat st;
            if (fstat(receipt, &st) || !S_ISREG(st.st_mode)) return 125;
            ssize_t count = read(receipt, text, sizeof(text) - 1);
            close(receipt);
            if (count < 0) return 125;
            if (count && text[count - 1] == '\n') {
                long code = strtol(text, &end, 10);
                if (end == text || *end != '\n' || end[1] || code < 0 || code > 255) return 125;
                close(watch);
                printf("Process exit=%ld\n", code);
                return (int)code;
            }
        } else if (errno != ENOENT) return 125;
        struct pollfd event = {watch, POLLIN, 0};
        /* EVENT_WAIT: final receipt publication after process exit; deadline fails, never implies completion. */
        if (md_event_wait(&event, 1, deadline) < 0) return 125;
        char events[4096];
        while (read(watch, events, sizeof(events)) > 0) { }
        if (errno != EAGAIN) return 125;
    }
}
