#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

static int receive_descriptor(int transport) {
    char marker = 0;
    struct iovec payload = {.iov_base = &marker, .iov_len = 1};
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(16 * sizeof(int))]; } control;
    struct msghdr message = {.msg_iov = &payload, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    ssize_t count;
    do { count = recvmsg(transport, &message, MSG_CMSG_CLOEXEC); } while (count < 0 && errno == EINTR);
    if (count < 0) return -1;
    int received = -1;
    unsigned descriptors = 0;
    for (struct cmsghdr *header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS) continue;
        size_t length = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        const int *values = (const int *)CMSG_DATA(header);
        for (size_t index = 0; index < length; ++index) {
            if (descriptors++ == 0) received = values[index];
            else close(values[index]);
        }
    }
    if (count != 1 || marker != 1 || descriptors != 1 || (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC))) {
        if (received >= 0) close(received);
        errno = EPROTO;
        return -1;
    }
    return received;
}

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
    if (argc < 5 || !*argv[1] || strlen(argv[1]) >= sizeof(((struct sockaddr_un *)0)->sun_path) - 1
            || strlen(argv[2]) != 64 || argv[4][0] != '/') return 2;
    char *end;
    errno = 0;
    unsigned long expected_uid = strtoul(argv[3], &end, 10);
    if (errno || !*argv[3] || *end || expected_uid > UINT_MAX || argv[3][0] == '-') return 2;
    int transport = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (transport < 0) { perror("Wayland client socket"); return 1; }
    struct timeval timeout = {.tv_sec = 10};
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    memcpy(address.sun_path + 1, argv[1], strlen(argv[1]));
    socklen_t address_size = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(argv[1]);
    struct ucred peer = {0};
    socklen_t peer_size = sizeof(peer);
    if (setsockopt(transport, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0
            || setsockopt(transport, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0
            || connect(transport, (struct sockaddr *)&address, address_size) < 0
            || getsockopt(transport, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) < 0) {
        perror("Wayland client channel");
        close(transport);
        return 1;
    }
    if (peer_size != sizeof(peer) || peer.uid != expected_uid) {
        fprintf(stderr, "Wayland client owner UID mismatch: expected %lu, received %lu\n",
            expected_uid, (unsigned long)peer.uid);
        close(transport);
        return 1;
    }
    size_t sent = 0;
    while (sent < 64) {
        ssize_t count = send(transport, argv[2] + sent, 64 - sent, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { close(transport); return 1; }
        sent += (size_t)count;
    }
    int descriptor = receive_descriptor(transport);
    close(transport);
    if (descriptor < 0) { perror("Wayland client descriptor"); return 1; }
    char value[32];
    snprintf(value, sizeof(value), "%d", descriptor);
    if (close_unrelated(descriptor) < 0 || setenv("WAYLAND_SOCKET", value, 1) < 0
            || fcntl(descriptor, F_SETFD, 0) < 0) {
        close(descriptor);
        return 1;
    }
    execv(argv[4], &argv[4]);
    perror("Wayland client exec");
    close(descriptor);
    return 127;
}