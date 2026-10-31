#define _GNU_SOURCE
#include "watch_calls.h"
#include "fs_rpc.h"
#include "raw.h"
#include "interception.h"
#include "fd_filter.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <limits.h>

static long request(const struct md_fs *fs, unsigned operation, int first, int second, unsigned flags,
        struct md_fs_response *out) {
    struct md_fs_request q = {.operation = operation, .directory = {first, second}, .flags = flags};
    long r = md_fs_call(fs->endpoint, 5000, &q, out);
    return r ? r : out->result.error;
}
long md_watch_contains(const struct md_fs *fs, int fd) {
    if (fd < 0 || !*fs->endpoint) return 0;
    struct stat st;
    long r = RAW2(fstat, fd, &st);
    if (!r && !S_ISSOCK(st.st_mode)) return 0;
    /* This is only a local fast rejection, not guest IO. A sandbox may deny
     * fstat while allowing dup/read; the filesystem owner still determines
     * watch identity from the captured descriptor, without relaxing filters. */
    struct md_fs_response out;
    r = request(fs, MD_FS_WATCH_CONTAINS, fd, -1, 0, &out);
    return r ? r : (long)out.result.position;
}
static int local_copy(void *output, const void *data, size_t size) {
    return (int)md_write_memory(output, data, size);
}
long md_watch_read_local(const struct md_fs *fs, long nr, const unsigned long *a) {
    if (md_watch_ioctl_native(nr,a[1])) return MD_CALL_NATIVE;
    int fd = (int)a[nr == SYS_sendfile ? 1 : 0];
    long r = md_watch_contains(fs, fd);
    if (r <= 0) return r ? r : MD_CALL_NATIVE;
    struct md_fs_response out;
    if (nr == SYS_ioctl) {
        r = request(fs, MD_FS_WATCH_BYTES, fd, -1, 0, &out);
        int bytes = (int)out.result.position;
        return r ? r : md_write_memory((void *)a[2], &bytes, sizeof(bytes));
    }
    if (nr != SYS_read && nr != SYS_readv) return -EINVAL;
    struct iovec vectors[1024];
    unsigned count = 1;
    if (nr == SYS_read) vectors[0] = (struct iovec){(void *)a[1], a[2]};
    else {
        if (a[2] > 1024) return -EINVAL;
        count = (unsigned)a[2];
        r = md_read_memory(vectors, (void *)a[1], count * sizeof(*vectors));
        if (r) return r;
        size_t total = 0;
        for (unsigned i = 0; i < count; ++i) {
            if (vectors[i].iov_len > (size_t)PTRDIFF_MAX - total) return -EINVAL;
            total += vectors[i].iov_len;
        }
        if (!total) return 0;
    }
    size_t copied = 0;
    for (unsigned i = 0; i < count; ++i) {
        if (nr == SYS_readv && !vectors[i].iov_len) continue;
        struct md_fs_request q = {.operation = MD_FS_WATCH_READ, .directory = {fd, -1},
            .capacity = vectors[i].iov_len > PATH_MAX ? PATH_MAX : (unsigned)vectors[i].iov_len};
        r = md_fs_call_deliver(fs->endpoint, 5000, &q, &out, local_copy, vectors[i].iov_base);
        if (!r) r = out.result.error;
        if (r) {
            if (copied) return (long)copied;
            if (r == -EAGAIN) {
                long flags = RAW2(fcntl, fd, F_GETFL);
                if (flags < 0) return flags;
                if (!(flags & O_NONBLOCK)) return MD_WATCH_WAIT;
            }
            return r;
        }
        copied += out.result.size;
        if (out.result.size < vectors[i].iov_len) break;
    }
    return (long)copied;
}
long md_watch_call(const struct md_fs *fs, long nr, const unsigned long *a) {
    if (!*fs->endpoint && (nr==SYS_inotify_init1 || nr==SYS_inotify_rm_watch))
        return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    struct md_fs_response out;
    long r;
    if (nr == SYS_inotify_init1) {
        r = request(fs, MD_FS_WATCH_CREATE, -1, -1, (unsigned)a[0], &out);
        if (r) return r;
        int fd = out.result.fd;
        r = md_fd_filter_arm(MD_FD_SELECTOR_WATCH,fd);
        if (!r && !(a[0] & IN_CLOEXEC)) r = RAW3(fcntl, fd, F_SETFD, 0);
        if (r) { RAW1(close, fd); return r; }
        return fd;
    }
    if (nr == SYS_inotify_rm_watch)
        return request(fs, MD_FS_WATCH_REMOVE, (int)a[0], -1, (unsigned)a[1], &out);
    return -ENOSYS;
}
