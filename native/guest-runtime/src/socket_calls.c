#define _GNU_SOURCE
#include "socket_calls.h"
#include "file_calls.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>

static long domain(int fd) {
    int family = 0;
    unsigned size = sizeof(family);
    long r = RAW5(getsockopt, fd, SOL_SOCKET, SO_DOMAIN, &family, &size);
    return r < 0 ? r : family;
}
static long invoke(long nr, const unsigned long *a) {
    return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
}
long md_socket_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    if (nr == SYS_sendmmsg) {
        long family = domain((int)a[0]);
        if (family < 0) return family;
        /* Reject the whole Unix batch before sending; no partial host-path fallback. */
        return family == AF_UNIX ? -ENOTSUP : invoke(nr, a);
    }
    unsigned address_index = nr == SYS_sendto ? 4 : 1;
    unsigned length_index = address_index + 1;
    const void *pointer = (void *)a[address_index];
    unsigned length = (unsigned)a[length_index];
    struct msghdr message;
    if (nr == SYS_sendmsg) {
        long r = md_read_memory(&message, (void *)a[1], sizeof(message));
        if (r < 0) return r;
        pointer = message.msg_name;
        length = message.msg_namelen;
    }
    if (!pointer || length < sizeof(sa_family_t) || length > sizeof(struct sockaddr_storage))
        return invoke(nr, a);
    sa_family_t family;
    long r = md_read_memory(&family, pointer, sizeof(family));
    if (r < 0) return r;
    if (family != AF_UNIX) return invoke(nr, a);
    if (length > sizeof(struct sockaddr_un)) return -EINVAL;
    struct sockaddr_un address;
    r = md_read_memory(&address, pointer, length);
    if (r < 0) return r;
    /* Abstract addresses are binary names, including every supplied zero byte.
     * Family-only bind is the kernel's autobind request. Neither is a pathname. */
    if (length == offsetof(struct sockaddr_un, sun_path) || !address.sun_path[0])
        return invoke(nr, a);
    r = domain((int)a[0]);
    if (r < 0) return r;
    if (r != AF_UNIX) return invoke(nr, a);
    /* Named creation needs a namespace transaction, not a kernel write outside it. */
    if (nr == SYS_bind) return -ENOTSUP;
    char path[sizeof(address.sun_path) + 1];
    size_t size = length - offsetof(struct sockaddr_un, sun_path);
    memcpy(path, address.sun_path, size);
    path[size] = 0;
    unsigned long open_args[6] = {(unsigned long)AT_FDCWD, (unsigned long)path,
                                  O_PATH | O_CLOEXEC, 0, 0, 0};
    long fd = md_file_call(fs, exe, SYS_openat, open_args);
    if (fd < 0) return fd;
    /* Keep the inode alive for the entire kernel call. Path permissions were
     * checked by the file adapter; the kernel still checks socket access/type. */
    md_copy(address.sun_path, sizeof(address.sun_path), "/proc/thread-self/fd/");
    md_decimal(address.sun_path + md_length(address.sun_path), (unsigned)fd);
    length = (unsigned)(offsetof(struct sockaddr_un, sun_path) + md_length(address.sun_path) + 1);
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    if (nr == SYS_sendmsg) {
        message.msg_name = &address;
        message.msg_namelen = length;
        args[1] = (unsigned long)&message;
    } else {
        args[address_index] = (unsigned long)&address;
        args[length_index] = length;
    }
    r = invoke(nr, args);
    RAW1(close, fd);
    return r;
}
