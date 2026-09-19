#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

/* Runs in the client's filesystem and credentials, not the X server's. */
static int transfer(int fd, void *data, size_t size, int writing) {
    char *p = data;
    while (size) {
        ssize_t n = writing ? send(fd, p, size, MSG_NOSIGNAL) : read(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n;
        size -= (size_t)n;
    }
    return 0;
}

static int reply(int socket, int fd, int error) {
    unsigned char status = error > 0 && error < 256 ? (unsigned char)error : fd < 0 ? EIO : 0;
    struct iovec io = {&status, 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } ancillary = {0};
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1};
    if (fd >= 0) {
        message.msg_control = ancillary.bytes;
        message.msg_controllen = sizeof(ancillary.bytes);
        struct cmsghdr *c = CMSG_FIRSTHDR(&message);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    }
    ssize_t n;
    do { n = sendmsg(socket, &message, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n == 1 ? 0 : -1;
}

static int serve(int socket) {
    for (;;) {
        unsigned char length[4];
        if (transfer(socket, length, sizeof(length), 0)) return 0;
        uint32_t size = (uint32_t)length[0] << 24 | (uint32_t)length[1] << 16
                | (uint32_t)length[2] << 8 | length[3];
        char path[4097];
        if (!size || size >= sizeof(path) || transfer(socket, path, size, 0)) return 1;
        if (path[0] != '/' || memchr(path, 0, size)) return 1;
        path[size] = 0;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        int error = fd < 0 ? errno : 0;
        struct stat st;
        if (fd >= 0 && (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 0
                || st.st_size > 128LL * 1024 * 1024)) {
            close(fd); fd = -1; error = EINVAL;
        }
        int result = reply(socket, fd, error);
        if (fd >= 0) close(fd);
        if (result) return 1;
    }
}

int main(int argc, char **argv) {
    const char *endpoint = getenv("MAGICDESK_GUEST_FILES_SOCKET");
    const char *token = getenv("MAGICDESK_GUEST_FILES_TOKEN");
    if (argc < 3 || strcmp(argv[1], "--") || !endpoint || !token || strlen(token) != 64) {
        fprintf(stderr, "Guest file bridge requires its session environment and -- PROGRAM ARG...\n");
        return 2;
    }
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    size_t length = strlen(endpoint);
    if (!length || length > sizeof(address.sun_path) - 2) return 2;
    memcpy(address.sun_path + 1, endpoint, length);
    int socketFd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct timeval timeout = {.tv_sec = 10};
    if (socketFd < 0 || setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))
            || setsockopt(socketFd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))
            || connect(socketFd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + length)
            || transfer(socketFd, (void *)token, 64, 1)) {
        perror("Guest file bridge connection"); return 1;
    }
    unsigned char accepted;
    if (transfer(socketFd, &accepted, 1, 0) || accepted != 0) {
        fprintf(stderr, "Guest file bridge authorization failed\n"); return 1;
    }
    timeout.tv_sec = 0;
    if (setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))) return 1;
    unsetenv("MAGICDESK_GUEST_FILES_TOKEN");
    unsetenv("MAGICDESK_GUEST_FILES_SOCKET");
    pid_t worker = fork();
    if (worker < 0) { perror("Guest file bridge fork"); return 1; }
    if (!worker) {
        // The X session owns this worker through its socket. Do not retain the command's pipes.
        int null = open("/dev/null", O_RDWR);
        for (int i = 0; i < 3; i++) { if (null >= 0) dup2(null, i); else close(i); }
        if (null > 2) close(null);
        _exit(serve(socketFd));
    }
    close(socketFd);
    execvp(argv[2], argv + 2);
    perror("Guest command");
    kill(worker, SIGTERM);
    return 127;
}
