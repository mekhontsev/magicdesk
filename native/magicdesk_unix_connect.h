#ifndef MAGICDESK_UNIX_CONNECT_H
#define MAGICDESK_UNIX_CONNECT_H
#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <unistd.h>

static int md_unix_connect(const char *name) {
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    size_t length = strlen(name);
    if (!length || length > sizeof(address.sun_path) - 2) { errno = EINVAL; return -1; }
    int abstract = name[0] != '/';
    memcpy(address.sun_path + abstract, name, length);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct timeval timeout = {.tv_sec = 5};
    if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) {
        int error = errno; close(fd); errno = error; return -1;
    }
    if (connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + length + 1)) {
        int error = errno; close(fd); errno = error; return -1;
    }
    return fd;
}
#endif
