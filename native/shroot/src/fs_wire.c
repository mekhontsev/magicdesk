#define _GNU_SOURCE
#include "fs_wire.h"
#include "raw.h"
#include "fd_client.h"
#include <errno.h>
#include <fcntl.h>
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
long md_fs_private_peer(int socket) {
    int value; unsigned size=sizeof(value);
    long r=RAW5(getsockopt,socket,SOL_SOCKET,SO_DOMAIN,&value,&size);
    if (r<0) return r;
    if (size!=sizeof(value) || value!=AF_UNIX) return -EAFNOSUPPORT;
    size=sizeof(value); r=RAW5(getsockopt,socket,SOL_SOCKET,SO_TYPE,&value,&size);
    if (r<0) return r;
    if (size!=sizeof(value) || value!=SOCK_SEQPACKET) return -EPROTOTYPE;
    long flags=RAW3(fcntl,socket,F_GETFL,0), descriptor=RAW3(fcntl,socket,F_GETFD,0);
    if (flags<0 || descriptor<0) return flags<0 ? flags : descriptor;
    if (!(flags&O_NONBLOCK) || !(descriptor&FD_CLOEXEC)) return -EINVAL;
    struct ucred cred; size=sizeof(cred);
    r=RAW5(getsockopt,socket,SOL_SOCKET,SO_PEERCRED,&cred,&size);
    if (r<0) return r;
    return size==sizeof(cred) && cred.uid==(unsigned)RAW0(geteuid) && cred.pid>0 ? cred.pid : -EACCES;
}
void md_fs_close_rights(struct md_fs_rights *rights) {
    for (unsigned i = 0; i < rights->count; ++i) RAW1(close, rights->fd[i]);
    rights->count = 0;
}
long md_fs_send(int socket, const void *data, size_t size, const struct md_fs_rights *rights) {
    if (rights->count > MD_FS_MAX_FDS) return -EINVAL;
    return md_fs_send_fds(socket,data,size,rights->fd,rights->count);
}
long md_fs_send_fds(int socket, const void *data, size_t size, const int *fds, unsigned count) {
    if (count>MD_FD_RECEIPT_MAX || (count && !fds)) return -EINVAL;
    _Alignas(struct cmsghdr) char control[CMSG_SPACE(count * sizeof(int))];
    memset(control,0,sizeof(control));
    struct iovec io = {(void *)data, size};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1};
    if (count) {
        size_t n = count * sizeof(int);
        msg.msg_control = control; msg.msg_controllen = CMSG_SPACE(n);
        struct cmsghdr *c = (struct cmsghdr *)control;
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(n);
        memcpy(CMSG_DATA(c), fds, n);
    }
    return RAW3(sendmsg, socket, &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
}
long md_fs_receive(int socket, void *data, size_t size, struct md_fs_rights *rights) {
    return md_fs_receive_fds(socket,data,size,rights->fd,MD_FS_MAX_FDS,&rights->count);
}
static long receive_fds(int socket, void *data, size_t size, int *fds, unsigned capacity, unsigned *count, int flags) {
    if (!count || capacity>MD_FD_RECEIPT_MAX || (capacity && !fds)) return -EINVAL;
    *count=0;
    _Alignas(struct cmsghdr) char control[CMSG_SPACE(capacity * sizeof(int))];
    memset(control,0,sizeof(control));
    struct iovec io = {data, size};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1,
        .msg_control = control, .msg_controllen = sizeof(control)};
    long r = RAW3(recvmsg, socket, &msg, MSG_CMSG_CLOEXEC | MSG_DONTWAIT | flags);
    if (r < 0) return r;
    int invalid = msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC);
    size_t offset = 0; unsigned received=0;
    while (offset + sizeof(struct cmsghdr) <= msg.msg_controllen) {
        struct cmsghdr *c = (struct cmsghdr *)(control + offset);
        if (c->cmsg_len < CMSG_LEN(0) || c->cmsg_len > msg.msg_controllen - offset) { invalid = 1; break; }
        size_t n = c->cmsg_len - CMSG_LEN(0);
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            if (n % sizeof(int)) invalid = 1;
            for (size_t i = 0; i < n / sizeof(int); ++i) {
                int fd; memcpy(&fd, CMSG_DATA(c) + i * sizeof(int), sizeof(fd));
                if (received < capacity) fds[received++] = fd;
                else { RAW1(close, fd); invalid = 1; }
            }
        } else invalid = 1;
        offset += CMSG_ALIGN(c->cmsg_len);
    }
    if (invalid) { for (unsigned i=0;i<received;++i) RAW1(close,fds[i]); return -EPROTO; }
    *count=received;
    return r;
}
long md_fs_receive_fds(int socket, void *data, size_t size, int *fds, unsigned capacity, unsigned *count) {
    return receive_fds(socket,data,size,fds,capacity,count,0);
}
long md_fs_peek_fds(int socket, void *data, size_t size, int *fds, unsigned capacity, unsigned *count) {
    return receive_fds(socket,data,size,fds,capacity,count,MSG_PEEK);
}
int md_fs_rights_transferable(const int *fds, unsigned count) {
    if (!count || count>MD_FS_MAX_FDS || !fds) return -EINVAL;
    int pair[2], received[MD_FS_MAX_FDS]; unsigned installed=0;
    long r=RAW4(socketpair,AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,pair);
    if (r<0) return (int)r;
    char byte=1;
    r=md_fs_send_fds(pair[0],&byte,1,fds,count);
    if (r==1) r=md_fs_receive_fds(pair[1],&byte,1,received,count,&installed);
    for (unsigned i=0;i<installed;++i) RAW1(close,received[i]);
    RAW1(close,pair[0]); RAW1(close,pair[1]);
    /* An LSM may admit open(O_PATH) but reject file_receive. This private
     * packet has exact capacity; a truncated control means lost authority. */
    if (r==-EPROTO || r==-EACCES || r==-EPERM) return 0;
    if (r<0) return (int)r;
    return r==1 && byte==1 && installed==count;
}
long md_fs_receive_receipt(int socket, struct md_fd_receipt *receipt, unsigned capacity,
        struct md_fs_rights *rights, uint64_t exchange) {
    rights->count=0;
    if (!capacity || capacity>MD_FS_MAX_FDS) return -EINVAL;
    int reset=md_fd_receipt_reset(receipt,capacity);
    if (reset) return reset;
    long r=md_fd_control(MD_FD_RECEIVE,exchange,0,0);
    if (r<0) return r;
    r=RAW3(recvmsg,socket,&receipt->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT);
    long end=md_fd_control(MD_FD_RECEIVED,exchange,0,0);
    int count=md_fd_receipt_read(receipt,capacity,rights->fd);
    if (count>=0) rights->count=(unsigned)count;
    if (end<0 || count<0 || (r>=0 && (r!=1 || receipt->byte!=1
            || (receipt->message.msg_flags&(MSG_TRUNC|MSG_CTRUNC))))) {
        md_fs_close_rights(rights); return end<0 ? end : count<0 ? count : -EPROTO;
    }
    return r;
}
