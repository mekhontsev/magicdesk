#define _GNU_SOURCE
#include "fd_stream.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int descriptors(void) {
    DIR *d = opendir("/proc/self/fd"); assert(d);
    int count = 0;
    while (readdir(d)) ++count;
    closedir(d); return count;
}
static int reject(int fd, void *context) {
    (void)fd; ++*(int *)context; errno = EACCES; return -1;
}
static void malformed(bool denied) {
    int sockets[2]; assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0, sockets) == 0);
    int before = descriptors(), file = open("/dev/null", O_RDONLY | O_CLOEXEC); assert(file >= 0);
    int count = denied ? 1 : MDH_STREAM_FDS + 1, rights[MDH_STREAM_FDS + 1];
    for (int i = 0; i < count; ++i) rights[i] = file;
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(rights))]; } control;
    char byte = 'x'; struct iovec iov = {&byte, 1};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes,
        .msg_controllen = CMSG_SPACE(count * sizeof(int))};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(count * sizeof(int));
    memcpy(CMSG_DATA(c), rights, count * sizeof(int));
    assert(sendmsg(sockets[0], &msg, 0) == 1); close(file);
    MdhFdStream received = {0}; int calls = 0;
    assert(mdh_stream_receive(&received, sockets[1], denied ? reject : NULL, &calls) == -1);
    assert(errno == (denied ? EACCES : EMSGSIZE));
    assert(calls == (denied ? 1 : 0) && !received.count && !received.used);
    assert(descriptors() == before);
    close(sockets[0]); close(sockets[1]);
}

int main(void) {
    int baseline = descriptors(), sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0, sockets) == 0);
    int buffer = 1024;
    assert(setsockopt(sockets[0], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer)) == 0);
    MdhFdStream send = {0}, receive = {0};
    for (size_t i = 0; i < sizeof(send.bytes); ++i) send.bytes[i] = (char)(i % 113);
    send.used = sizeof(send.bytes);
    send.fds[send.count++] = open("/dev/null", O_RDONLY | O_CLOEXEC);
    send.fds[send.count++] = open("/dev/zero", O_RDONLY | O_CLOEXEC);
    assert(send.fds[0] >= 0 && send.fds[1] >= 0);
    assert(mdh_stream_send(&send, sockets[0]) == 0 && send.sent > 0 && send.used && !send.count);
    size_t offset = send.sent;
    assert(mdh_stream_send(&send, sockets[0]) == 0 && send.sent == offset); // Backpressure retains unsent bytes.
    size_t total = 0; int delivered = 0;
    for (int iteration = 0; iteration < 100 && total < MDH_STREAM_BYTES; ++iteration) {
        assert(mdh_stream_receive(&receive, sockets[1], NULL, NULL) == 0);
        if (!total) {
            assert(receive.count == 2);
            char byte;
            assert(read(receive.fds[0], &byte, 1) == 0);
            assert(read(receive.fds[1], &byte, 1) == 1 && byte == 0);
        } else assert(receive.count == 0);
        for (int i = 0; i < receive.count; ++i) assert(fcntl(receive.fds[i], F_GETFD) & FD_CLOEXEC);
        delivered += receive.count;
        for (size_t i = 0; i < receive.used; ++i) assert(receive.bytes[i] == (char)((total + i) % 113));
        total += receive.used; mdh_stream_clear(&receive);
        if (send.used) assert(mdh_stream_send(&send, sockets[0]) == 0);
    }
    assert(total == MDH_STREAM_BYTES && delivered == 2 && !send.used);
    assert(shutdown(sockets[0], SHUT_WR) == 0);
    assert(mdh_stream_receive(&receive, sockets[1], NULL, NULL) == 0 && receive.eof);
    assert(write(sockets[1], "reply", 5) == 5); // A read EOF does not close the opposite direction.
    char reply[5]; assert(read(sockets[0], reply, sizeof(reply)) == 5 && !memcmp(reply, "reply", 5));
    mdh_stream_clear(&send); mdh_stream_clear(&receive);
    close(sockets[0]); close(sockets[1]);
    malformed(false); malformed(true);
    assert(descriptors() == baseline);
    puts("FD stream: partial writes, backpressure, ordered single FD delivery, EOF, rejection and truncation cleanup passed");
}
