#define _GNU_SOURCE
#include "watch_calls.h"
#include "fs_rpc.h"
#include "raw.h"
#include "interception.h"
#include "watch_activation.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <limits.h>

static long request(const struct md_fs *fs, unsigned operation, int first, int second, unsigned flags,
        struct md_fs_response *out) {
    struct md_fs_request q = {.operation = operation, .directory = {first, second}, .flags = flags};
    long r = md_fs_call(fs->endpoint, 5000, &q, out);
    return r ? r : out->result.error;
}
static long watched(const struct md_fs *fs, int fd) {
    if (fd < 0 || !*fs->endpoint) return 0;
    struct stat st;
    long r = RAW2(fstat, fd, &st);
    if (r || !S_ISSOCK(st.st_mode)) return r;
    struct md_fs_response out;
    r = request(fs, MD_FS_WATCH_CONTAINS, fd, -1, 0, &out);
    return r ? r : (long)out.result.position;
}
static long arm(int fd) {
    long r = RAW3(prctl, MD_GUEST_WATCH_FILTER, fd, 0);
    if (r) return r < 0 ? r : 0;
    uintptr_t gate = (uintptr_t)md_raw_return;
    extern char md_guest_watch_return[];
    uintptr_t watch = (uintptr_t)md_guest_watch_return;
#define ADAPT(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_DISPATCH),
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)(gate >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)gate, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)(watch >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)watch, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_read, 5, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_readv, 4, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_ioctl, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_splice, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_tee, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_sendfile, 2, 7),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_STMT(BPF_JMP | BPF_JA, 1),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, MD_WATCH_SELECTOR_MASK),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)fd & MD_WATCH_SELECTOR_MASK, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_WATCH),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        ADAPT(dup) ADAPT(dup3)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fcntl, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, F_DUPFD, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, F_DUPFD_CLOEXEC, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_DISPATCH),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
#undef ADAPT
    struct sock_fprog program = {sizeof(code) / sizeof(*code), code};
    r = RAW3(seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program);
    /* A divergent sibling is not success. Never publish an unmediated reader. */
    if (r > 0) return -ENOTSUP;
    if (!r) r = RAW3(prctl, MD_GUEST_WATCH_FILTER, fd, 1);
    return r;
}
long md_watch_arm(const struct md_fs *fs, int fd) {
    long r = watched(fs, fd);
    return r > 0 ? arm(fd) : r;
}
static int local_copy(void *output, const void *data, size_t size) {
    return (int)md_write_memory(output, data, size);
}
long md_watch_read_local(const struct md_fs *fs, long nr, const unsigned long *a) {
    int fd = (int)a[nr == SYS_sendfile ? 1 : 0];
    long r = watched(fs, fd);
    if (r <= 0) return r ? r : MD_WATCH_NATIVE;
    struct md_fs_response out;
    if (nr == SYS_ioctl) {
        if (a[1] != FIONREAD) return MD_WATCH_NATIVE;
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
    if (!*fs->endpoint) return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    struct md_fs_response out;
    long r;
    if (nr == SYS_inotify_init1) {
        r = request(fs, MD_FS_WATCH_CREATE, -1, -1, (unsigned)a[0], &out);
        if (r) return r;
        int fd = out.result.fd;
        r = md_watch_arm(fs, fd);
        if (!r && !(a[0] & IN_CLOEXEC)) r = RAW3(fcntl, fd, F_SETFD, 0);
        if (r) { RAW1(close, fd); return r; }
        return fd;
    }
    if (nr == SYS_inotify_rm_watch)
        return request(fs, MD_FS_WATCH_REMOVE, (int)a[0], -1, (unsigned)a[1], &out);
    if (nr == SYS_dup3 && (int)a[1] >= 0 && (int)a[0] != (int)a[1]
            && !((unsigned)a[2] & ~O_CLOEXEC)) {
        r = watched(fs, (int)a[0]);
        if (r < 0) return r;
        /* The destination is known before publication. Activation failure must
         * not close or replace the caller's previous destination descriptor. */
        if (r && (r = arm((int)a[1]))) return r;
    }
    r = md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    if (r < 0) return r;
    if (nr == SYS_dup3) return r;
    if (nr == SYS_fcntl && a[1] != F_DUPFD && a[1] != F_DUPFD_CLOEXEC) return r;
    long activation = md_watch_arm(fs, (int)r);
    if (activation) { RAW1(close, r); return activation; }
    return r;
}
int md_watch_received(const struct md_fs *fs, const void *address) {
    if (!*fs->endpoint) return 0;
    struct msghdr message;
    long r = md_read_memory(&message, address, sizeof(message));
    if (r) return (int)r;
    for (size_t pos = 0; pos + sizeof(struct cmsghdr) <= message.msg_controllen;) {
        struct cmsghdr header;
        r = md_read_memory(&header, (const char *)message.msg_control + pos, sizeof(header));
        if (r) return (int)r;
        if (header.cmsg_len < CMSG_LEN(0) || header.cmsg_len > message.msg_controllen - pos) return -EINVAL;
        if (header.cmsg_level == SOL_SOCKET && header.cmsg_type == SCM_RIGHTS) {
            for (size_t at = CMSG_LEN(0); at + sizeof(int) <= header.cmsg_len; at += sizeof(int)) {
                int fd;
                r = md_read_memory(&fd, (const char *)message.msg_control + pos + at, sizeof(fd));
                if (!r) r = md_watch_arm(fs, fd);
                if (r) return (int)r;
            }
        }
        pos += CMSG_ALIGN(header.cmsg_len);
    }
    return 0;
}
