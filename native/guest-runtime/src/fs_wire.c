#define _GNU_SOURCE
#include "fs_wire.h"
#include "raw.h"
#include <errno.h>
#include <sys/socket.h>

long md_fs_address(const char *name, struct sockaddr_un *out) {
    if (!name || !*name) return -EINVAL;
    memset(out, 0, sizeof(*out)); out->sun_family = AF_UNIX;
    size_t n = 0;
    while (name[n]) {
        if (n + 1 >= sizeof(out->sun_path)) return -ENAMETOOLONG;
        out->sun_path[n+1] = name[n]; ++n;
    }
    return offsetof(struct sockaddr_un, sun_path) + 1 + n;
}
long md_fs_peer(int socket) {
    struct ucred cred; unsigned size = sizeof(cred);
    long r = RAW5(getsockopt, socket, SOL_SOCKET, SO_PEERCRED, &cred, &size);
    if (r < 0) return r;
    return size == sizeof(cred) && cred.uid == (unsigned)RAW0(geteuid) ? 0 : -EACCES;
}
void md_fs_close_rights(struct md_fs_rights *rights) {
    for (unsigned i = 0; i < rights->count; ++i) RAW1(close, rights->fd[i]);
    rights->count = 0;
}
long md_fs_send(int socket, const void *data, size_t size, const struct md_fs_rights *rights) {
    if (rights->count > MD_FS_MAX_FDS) return -EINVAL;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FS_MAX_FDS * sizeof(int))]; } control = {0};
    struct iovec io = {(void *)data, size};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1};
    if (rights->count) {
        size_t n = rights->count * sizeof(int);
        msg.msg_control = control.bytes; msg.msg_controllen = CMSG_SPACE(n);
        struct cmsghdr *c = (struct cmsghdr *)control.bytes;
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(n);
        memcpy(CMSG_DATA(c), rights->fd, n);
    }
    return RAW3(sendmsg, socket, &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
}
long md_fs_receive(int socket, void *data, size_t size, struct md_fs_rights *rights) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FS_MAX_FDS * sizeof(int))]; } control = {0};
    struct iovec io = {data, size};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
    rights->count = 0;
    long r = RAW3(recvmsg, socket, &msg, MSG_CMSG_CLOEXEC | MSG_DONTWAIT);
    if (r < 0) return r;
    int invalid = msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC);
    size_t offset = 0;
    while (offset + sizeof(struct cmsghdr) <= msg.msg_controllen) {
        struct cmsghdr *c = (struct cmsghdr *)(control.bytes + offset);
        if (c->cmsg_len < CMSG_LEN(0) || c->cmsg_len > msg.msg_controllen - offset) { invalid = 1; break; }
        size_t n = c->cmsg_len - CMSG_LEN(0);
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            if (n % sizeof(int)) invalid = 1;
            for (size_t i = 0; i < n / sizeof(int); ++i) {
                int fd; memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof(fd));
                if (rights->count < MD_FS_MAX_FDS) rights->fd[rights->count++] = fd;
                else { RAW1(close, fd); invalid = 1; }
            }
        } else invalid = 1;
        offset += CMSG_ALIGN(c->cmsg_len);
    }
    if (invalid) { md_fs_close_rights(rights); return -EPROTO; }
    return r;
}
