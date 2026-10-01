#define _GNU_SOURCE
#include "watch_broker.h"
#include "inode_watch.h"
#include "interception.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <limits.h>
#include <stdlib.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

struct watch_read {
    struct watch_read *next;
    struct seccomp_notif q;
    int listener, reader, vector_count, vector;
    size_t copied;
    struct iovec vectors[1024];
};
struct md_watch_broker {
    struct watch_read *pending, **tail, *free;
    unsigned allocated;
    char output[65536];
};
static int valid(struct watch_read *r) {
    return !ioctl(r->listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &r->q.id);
}
static int reply(int listener, uint64_t id, long result, int flags) {
    struct seccomp_notif_resp response = {.id = id, .flags = flags,
        .val = result < 0 && result >= -4095 ? 0 : result,
        .error = result < 0 && result >= -4095 ? (int)result : 0};
    return !ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &response) || errno == ENOENT ? 0 : -errno;
}
static int deliver(void *context, const void *data, size_t size) {
    struct watch_read *r = context;
    if (!valid(r)) return -ECANCELED;
    if (!size) return 0;
    struct iovec local = {(void *)data, size};
    struct iovec remote = {r->vectors[r->vector].iov_base, size};
    ssize_t n = process_vm_writev(r->q.pid, &local, 1, &remote, 1, 0);
    if (n < 0 && (errno == EPERM || errno == EACCES)) return -EACCES;
    return n == (ssize_t)size ? 0 : -EFAULT;
}
static void recycle(struct md_watch_broker *b, struct watch_read *r) {
    close(r->reader); r->reader = -1;
    r->next = b->free; b->free = r;
}
/* 1 completed, 0 waiting, negative service error. Kernel inotify uses legacy
 * readv: a record must fit within one iovec, not the sum of their lengths. */
static int run(struct md_watch_broker *b, struct md_inode_store *s, struct watch_read *r) {
    if (!valid(r)) return 1;
    long result = 0;
    while (r->vector < r->vector_count) {
        if (r->q.data.nr == SYS_readv && !r->vectors[r->vector].iov_len) { r->vector++; continue; }
        size_t capacity = r->vectors[r->vector].iov_len;
        if (capacity > sizeof(b->output)) capacity = sizeof(b->output);
        result = md_inode_watch_read(s, r->reader, b->output, capacity, deliver, r);
        if (result == -EAGAIN) {
            if (r->copied) break;
            int flags = fcntl(r->reader, F_GETFL);
            if (flags < 0) result = -errno;
            else if (!(flags & O_NONBLOCK)) return 0;
        }
        if (result < 0) break;
        r->copied += (size_t)result;
        if ((size_t)result < r->vectors[r->vector].iov_len) break;
        r->vector++;
    }
    if (r->copied) result = (long)r->copied;
    else if (result == -EACCES) result = MD_WATCH_TASK_AFFINE;
    int error = reply(r->listener, r->q.id, result, 0);
    return error ? error : 1;
}
struct md_watch_broker *md_watch_broker_create(void) {
    struct md_watch_broker *b = calloc(1, sizeof(*b));
    if (b) b->tail = &b->pending;
    return b;
}
int md_watch_broker_submit(struct md_watch_broker *b, struct md_inode_store *s, int listener,
        const struct seccomp_notif *q, int reader) {
    if (reader < 0) return reply(listener, q->id,
        reader == -EPERM || reader == -EACCES ? MD_WATCH_TASK_AFFINE : reader, 0);
    if (!md_inode_watch_contains(s, reader)) {
        close(reader);
        return reply(listener, q->id, 0, SECCOMP_USER_NOTIF_FLAG_CONTINUE);
    }
    if (q->data.nr != SYS_read && q->data.nr != SYS_readv) {
        long result = -EINVAL;
        int flags = 0;
        if (q->data.nr == SYS_ioctl && q->data.args[1] == FIONREAD) {
            result = md_inode_watch_bytes(s, reader);
            if (result >= 0) {
                int bytes = (int)result;
                struct iovec local = {&bytes, sizeof(bytes)}, remote = {(void *)q->data.args[2], sizeof(bytes)};
                ssize_t n = process_vm_writev(q->pid, &local, 1, &remote, 1, 0);
                result = n == sizeof(bytes) ? 0 : n < 0 && (errno == EPERM || errno == EACCES)
                    ? MD_WATCH_TASK_AFFINE : -EFAULT;
            }
        } else if (q->data.nr == SYS_ioctl) { result = 0; flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE; }
        close(reader);
        return reply(listener, q->id, result, flags);
    }
    struct watch_read *r = b->free;
    if (r) b->free = r->next;
    else if (b->allocated < 1024) { r = calloc(1, sizeof(*r)); if (r) b->allocated++; }
    if (!r) { close(reader); return reply(listener, q->id, -ENOMEM, 0); }
    r->reader = reader; r->listener = listener; r->q = *q;
    r->vector = 0; r->copied = 0;
    int error = 0;
    if (q->data.nr == SYS_read) {
        r->vector_count = 1;
        r->vectors[0] = (struct iovec){(void *)q->data.args[1], q->data.args[2]};
    } else if (q->data.args[2] > 1024) error = -EINVAL;
    else {
        r->vector_count = (int)q->data.args[2];
        size_t bytes = (size_t)r->vector_count * sizeof(struct iovec);
        struct iovec local = {r->vectors, bytes}, remote = {(void *)q->data.args[1], bytes};
        if (bytes) {
            ssize_t copied = process_vm_readv(q->pid, &local, 1, &remote, 1, 0);
            if (copied != (ssize_t)bytes)
                error = copied < 0 && (errno == EPERM || errno == EACCES) ? -EACCES : -EFAULT;
        }
        size_t total = 0;
        for (int i = 0; !error && i < r->vector_count; ++i) {
            if (r->vectors[i].iov_len > (size_t)SSIZE_MAX - total) error = -EINVAL;
            else total += r->vectors[i].iov_len;
        }
        if (!error && !total) r->vector_count = 0;
    }
    if (error) { recycle(b, r); return reply(listener, q->id, error == -EACCES ? MD_WATCH_TASK_AFFINE : error, 0); }
    int done = run(b, s, r);
    if (done) { recycle(b, r); return done < 0 ? done : 0; }
    /* EVENT_WAIT: namespace/native readiness or notification cancellation. The
     * caller's signals interrupt the original read; no timeout creates data. */
    r->next = NULL; *b->tail = r; b->tail = &r->next;
    return 0;
}
int md_watch_broker_progress(struct md_watch_broker *b, struct md_inode_store *s) {
    struct watch_read **link = &b->pending;
    while (*link) {
        struct watch_read *r = *link;
        int done = run(b, s, r);
        if (done) { *link = r->next; recycle(b, r); if (done < 0) return done; }
        else link = &r->next;
    }
    b->tail = link;
    return 0;
}
void md_watch_broker_destroy(struct md_watch_broker *b) {
    if (!b) return;
    while (b->pending) { struct watch_read *r = b->pending; b->pending = r->next; recycle(b, r); }
    while (b->free) { struct watch_read *r = b->free; b->free = r->next; free(r); }
    free(b);
}
