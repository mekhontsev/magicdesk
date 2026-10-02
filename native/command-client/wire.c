#define _GNU_SOURCE
#include "wire.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

int md_command_send(int fd, const void *data, size_t size) {
    const char *bytes = data;
    while (size) {
        ssize_t n = send(fd, bytes, size, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return n ? -errno : -EPIPE;
        bytes += n; size -= (size_t)n;
    }
    return 0;
}
int md_command_receive(int fd, void *data, size_t size) {
    char *bytes = data;
    while (size) {
        ssize_t n = recv(fd, bytes, size, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return n ? -errno : -ECONNRESET;
        bytes += n; size -= (size_t)n;
    }
    return 0;
}
int md_command_number(int fd, uint32_t value) {
    value = htonl(value); return md_command_send(fd, &value, sizeof(value));
}
int md_command_read_number(int fd, uint32_t *value) {
    uint32_t wire;
    int r = md_command_receive(fd, &wire, sizeof(wire));
    if (!r) *value = ntohl(wire);
    return r;
}
int md_command_text(int fd, const char *text) {
    size_t size = strlen(text);
    if (size > MD_COMMAND_REQUEST_LIMIT) return -E2BIG;
    int r = md_command_number(fd, (uint32_t)size);
    return r ? r : md_command_send(fd, text, size);
}
int md_command_read_text(int fd, char *text, size_t capacity) {
    uint32_t size;
    int r = md_command_read_number(fd, &size);
    if (!r && size >= capacity) r = -E2BIG;
    if (!r) r = md_command_receive(fd, text, size);
    if (!r) { text[size] = 0; if (strlen(text) != size) r = -EINVAL; }
    return r;
}
int md_command_connect(const char *endpoint, const char *build, uint32_t operation) {
    if (!endpoint || !build || !*build || strlen(build) > 128) return -ENOTCONN;
    char *end;
    unsigned long port = strtoul(endpoint, &end, 10);
    if (!port || port > 65535 || *end != ':' || strlen(end+1) != 43) return -EINVAL;
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -errno;
    struct sockaddr_in address = {.sin_family=AF_INET, .sin_port=htons((uint16_t)port),
        .sin_addr={.s_addr=htonl(INADDR_LOOPBACK)}};
    int r = connect(fd, (void *)&address, sizeof(address));
    if (r && errno == EINPROGRESS) {
        struct pollfd event = {.fd=fd, .events=POLLOUT};
        // EVENT_WAIT: local connection completion; expiry rejects connection, never retries a command.
        r = poll(&event, 1, 5000);
        if (r <= 0) r = r ? -errno : -ETIMEDOUT;
        else {
            int error = 0; socklen_t size = sizeof(error);
            r = getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) ? -errno : -error;
        }
    } else if (r) r = -errno;
    if (!r && fcntl(fd, F_SETFL, 0)) r = -errno;
    struct timeval deadline = {.tv_sec=120};
    // EVENT_WAIT: request/reply bytes; timeout leaves action outcome unknown and is never replayed.
    if (!r && (setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&deadline,sizeof(deadline))
            || setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&deadline,sizeof(deadline)))) r = -errno;
    if (!r) r = md_command_number(fd, MD_COMMAND_MAGIC);
    if (!r) r = md_command_number(fd, operation);
    if (!r) r = md_command_text(fd, end+1);
    if (!r) r = md_command_text(fd, build);
    if (r) { close(fd); return r; }
    return fd;
}

int md_command_lease(void) {
    const char *endpoint = getenv("MAGICDESK_COMMAND_ENDPOINT"), *build = getenv("MAGICDESK_COMMAND_BUILD");
    int fd = md_command_connect(endpoint, build, MD_COMMAND_LEASE);
    if (fd < 0) return fd;
    uint32_t status;
    int r = md_command_read_number(fd, &status);
    if (!r && status) r = -EACCES;
    char key[128], selected[160];
    if (!r) r = md_command_read_text(fd, key, sizeof(key));
    if (!r && strlen(key) != 43) r = -EPROTO;
    if (!r) {
        const char *colon = strchr(endpoint, ':');
        if (!colon || snprintf(selected,sizeof(selected),"%.*s:%s",(int)(colon-endpoint),endpoint,key) >= (int)sizeof(selected)) r = -EPROTO;
    }
    if (!r && setenv("MAGICDESK_COMMAND_ENDPOINT", selected, 1)) r = -errno;
    if (r) { close(fd); return r; }
    return fd;
}
