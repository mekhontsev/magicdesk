#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

static int close_unrelated(int preserved) {
    DIR *directory = opendir("/proc/self/fd");
    if (!directory) return -1;
    struct dirent *entry;
    while ((entry = readdir(directory))) {
        int descriptor = atoi(entry->d_name);
        if (descriptor > STDERR_FILENO && descriptor != preserved && descriptor != dirfd(directory))
            close(descriptor);
    }
    return closedir(directory);
}

int main(int argc, char **argv) {
    if (argc < 3 || argv[2][0] != '/') return 2;
    char *end;
    errno = 0;
    long value = strtol(argv[1], &end, 10);
    if (errno || !*argv[1] || *end || value <= STDERR_FILENO || value > INT_MAX) return 2;
    int descriptor = value;
    int type;
    socklen_t size = sizeof(type);
    if (getsockopt(descriptor, SOL_SOCKET, SO_TYPE, &type, &size) < 0 || type != SOCK_STREAM) return 2;
    if (close_unrelated(descriptor) < 0 || setenv("WAYLAND_SOCKET", argv[1], 1) < 0 ||
            fcntl(descriptor, F_SETFD, 0) < 0) {
        perror("Wayland client descriptor");
        return 1;
    }
    execv(argv[2], &argv[2]);
    perror("Wayland client exec");
    return 127;
}
