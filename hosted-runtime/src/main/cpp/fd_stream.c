#define _GNU_SOURCE
#include "fd_stream.h"
#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

void mdh_stream_clear(MdhFdStream *s) {
    for (int i = 0; i < s->count; ++i) close(s->fds[i]);
    *s = (MdhFdStream){0};
}

int mdh_stream_receive(MdhFdStream *s, int fd, int (*admit)(int, void *), void *context) {
    if (s->used || s->eof) { errno = EINVAL; return -1; }
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(MDH_STREAM_FDS * sizeof(int))]; } control;
    struct iovec iov = {s->bytes, sizeof(s->bytes)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    ssize_t n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC | MSG_DONTWAIT);
    if (n < 0) return errno == EAGAIN || errno == EINTR ? 0 : -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS) continue;
        size_t count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        int *fds = (void *)CMSG_DATA(c);
        for (size_t i = 0; i < count; ++i) {
            if (s->count < MDH_STREAM_FDS) s->fds[s->count++] = fds[i];
            else { close(fds[i]); msg.msg_flags |= MSG_CTRUNC; }
        }
    }
    if (msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) { errno = EMSGSIZE; goto reject; }
    if (admit) for (int i = 0; i < s->count; ++i) if (admit(s->fds[i], context) < 0) goto reject;
    if (n == 0) {
        if (s->count) { errno = EPROTO; goto reject; }
        s->eof = true;
    }
    s->used = (size_t)n;
    return 0;
reject: {
    int error = errno;
    mdh_stream_clear(s);
    errno = error;
    return -1;
}}

int mdh_stream_send(MdhFdStream *s, int fd) {
    if (!s->used) { errno = EINVAL; return -1; }
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(MDH_STREAM_FDS * sizeof(int))]; } control;
    struct iovec iov = {s->bytes + s->sent, s->used - s->sent};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
    if (s->count) {
        msg.msg_control = control.bytes;
        msg.msg_controllen = CMSG_SPACE(s->count * sizeof(int));
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(s->count * sizeof(int));
        memcpy(CMSG_DATA(c), s->fds, s->count * sizeof(int));
    }
    ssize_t n = sendmsg(fd, &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
    if (n < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    if (n <= 0) return -1;
    for (int i = 0; i < s->count; ++i) close(s->fds[i]);
    s->count = 0;
    s->sent += (size_t)n;
    if (s->sent == s->used) s->used = s->sent = 0;
    return 0;
}
