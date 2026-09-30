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
    const char *marker = NULL, *path;
    int contains = argc == 4 && !strcmp(argv[1], "--contains");
    if (argc == 4 && (contains || !strcmp(argv[1], "--marker")) && *argv[2]) {
        marker = argv[2]; path = argv[3];
    } else if (argc == 2) path = argv[1];
    else return 125;
    if (*path != '/') return 125;
    char *directory = strdup(path);
    if (!directory) return 125;
    char *slash = strrchr(directory, '/');
    if (slash == directory) slash[1] = 0; else *slash = 0;
    int watch = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (watch < 0 || inotify_add_watch(watch, directory, IN_MODIFY | IN_CLOSE_WRITE | IN_MOVED_TO) < 0) return 125;
    free(directory);
    long deadline = md_event_now() + (marker ? 45000000000LL : 15000000000LL);
    for (;;) {
        int receipt = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (receipt >= 0) {
            struct stat st;
            if (fstat(receipt, &st) || !S_ISREG(st.st_mode)) return 125;
            if (marker) {
                if (st.st_size < 0 || st.st_size > 1024 * 1024) return 125;
                char *text = calloc((size_t)st.st_size + 1, 1);
                if (!text) return 125;
                ssize_t n = read(receipt, text, (size_t)st.st_size);
                close(receipt);
                if (n < 0) { free(text); return 125; }
                char *start = text, *end;
                while ((end = strchr(start, '\n'))) {
                    *end = 0;
                    if (contains ? strstr(start, marker) != NULL : !strncmp(start, marker, strlen(marker))) {
                        puts(start); free(text); close(watch); return 0;
                    }
                    start = end + 1;
                }
                free(text);
            } else {
                char text[32] = {0}, *end;
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
            }
        } else if (errno != ENOENT) return 125;
        struct pollfd event = {watch, POLLIN, 0};
        /* EVENT_WAIT: complete process receipt or application marker; expiration fails. */
        if (md_event_wait(&event, 1, deadline) < 0) return 125;
        char events[4096];
        while (read(watch, events, sizeof(events)) > 0) { }
        if (errno != EAGAIN) return 125;
    }
}
