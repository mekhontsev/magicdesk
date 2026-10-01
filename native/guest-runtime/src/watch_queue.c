#define _GNU_SOURCE
#include "watch_queue.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <unistd.h>

enum { RECORD_BYTES = sizeof(struct inotify_event) + NAME_MAX + 1 };
struct record {
    struct record *next;
    size_t size;
    unsigned char bytes[RECORD_BYTES];
};
struct md_watch_queue {
    int writer, error;
    unsigned limit, count;
    size_t bytes;
    struct record *head, *tail, *free;
    struct record overflow;
    int overflow_queued;
};

int md_watch_queue_create(unsigned limit, int flags, struct md_watch_queue **out, int *reader) {
    if (!out || !reader) return -EFAULT;
    *out = NULL; *reader = -1;
    if (!limit || limit > INT_MAX / RECORD_BYTES || flags & ~(IN_NONBLOCK | IN_CLOEXEC)) return -EINVAL;
    struct md_watch_queue *q = calloc(1, sizeof(*q));
    if (!q) return -ENOMEM;
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC | O_NONBLOCK)) { int r = -errno; free(q); return r; }
    int r = 0;
    if (!(flags & IN_NONBLOCK) && fcntl(pipefd[0], F_SETFL, 0)) r = -errno;
    if (!r && !(flags & IN_CLOEXEC) && fcntl(pipefd[0], F_SETFD, 0)) r = -errno;
    if (r) { close(pipefd[0]); close(pipefd[1]); free(q); return r; }
    q->writer = pipefd[1]; q->limit = limit;
    struct inotify_event event = {.wd = -1, .mask = IN_Q_OVERFLOW};
    memcpy(q->overflow.bytes, &event, sizeof(event)); q->overflow.size = sizeof(event);
    *reader = pipefd[0]; *out = q;
    return 0;
}
static void free_records(struct md_watch_queue *q, struct record *r) {
    while (r) { struct record *next = r->next; if (r != &q->overflow) free(r); r = next; }
}
void md_watch_queue_destroy(struct md_watch_queue *q) {
    if (!q) return;
    free_records(q, q->head); free_records(q, q->free);
    close(q->writer); free(q);
}
int md_watch_queue_lifetime(const struct md_watch_queue *q) { return q->writer; }
size_t md_watch_queue_bytes(const struct md_watch_queue *q) { return q->bytes; }

static int append(struct md_watch_queue *q, struct record *r) {
    if (!q->head) {
        /* Exactly one marker describes a nonempty queue. The runtime's native
         * owner blocks SIGPIPE; a vanished last reader yields EPIPE. */
        char marker = 1;
        ssize_t written;
        do { written = write(q->writer, &marker, 1); } while (written < 0 && errno == EINTR);
        if (written != 1) { q->error = written < 0 ? -errno : -EIO; return q->error; }
    }
    r->next = NULL;
    if (q->tail) q->tail->next = r; else q->head = r;
    q->tail = r; q->count++; q->bytes += r->size;
    return 0;
}
static int overflow(struct md_watch_queue *q) {
    if (q->overflow_queued) return 0;
    int r = append(q, &q->overflow);
    if (!r) q->overflow_queued = 1;
    return r;
}
static int drain_marker(int reader) {
    /* Reopen only this borrowed kernel descriptor, with a separate O_NONBLOCK
     * description. Never change the guest's shared file status flags or block
     * the owner if an unmediated operation consumed its readiness marker. */
    char path[64];
    int length = snprintf(path, sizeof(path), "/proc/self/fd/%d", reader);
    if (length < 0 || (size_t)length >= sizeof(path)) return -EBADF;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    char marker = 0;
    ssize_t result;
    do { result = read(fd, &marker, 1); } while (result < 0 && errno == EINTR);
    close(fd);
    return result == 1 && marker == 1 ? 0 : -EIO;
}
int md_watch_queue_emit(struct md_watch_queue *q, int wd, uint32_t mask, uint32_t cookie,
        const char *name) {
    if (q->error) return q->error;
    size_t length = name ? strnlen(name, NAME_MAX + 1) : 0;
    if (length > NAME_MAX) return -ENAMETOOLONG;
    if (name && (!length || memchr(name, '/', length))) return -EINVAL;
    size_t padded = name ? (length + 1 + 15) & ~(size_t)15 : 0;
    struct inotify_event header = {.wd = wd, .mask = mask, .cookie = cookie, .len = (uint32_t)padded};
    unsigned char bytes[RECORD_BYTES] = {0};
    memcpy(bytes, &header, sizeof(header));
    if (name) memcpy(bytes + sizeof(header), name, length);
    size_t size = sizeof(header) + padded;
    if (q->tail && q->tail->size == size && !memcmp(q->tail->bytes, bytes, size)) return 0;
    if (q->count >= q->limit) return overflow(q);
    struct record *r = q->free;
    if (r) q->free = r->next; else r = malloc(sizeof(*r));
    if (!r) return overflow(q);
    r->size = size; memcpy(r->bytes, bytes, size);
    int result = append(q, r);
    if (result) { r->next = q->free; q->free = r; }
    return result;
}
ssize_t md_watch_queue_read(struct md_watch_queue *q, int reader, void *buffer, size_t capacity,
        int (*deliver)(void *, const void *, size_t), void *context) {
    if (q->error) return q->error;
    if (!q->head) return -EAGAIN;
    if (capacity < q->head->size) return -EINVAL;
    if (!buffer) return -EFAULT;
    size_t used = 0;
    unsigned count = 0;
    struct record *r = q->head;
    while (r && r->size <= capacity - used) {
        memcpy((char *)buffer + used, r->bytes, r->size);
        used += r->size; count++; r = r->next;
    }
    if (deliver) { int result = deliver(context, buffer, used); if (result) return result; }
    if (!r) {
        int result = drain_marker(reader);
        if (result) { q->error = result; return result; }
    }
    for (unsigned i = 0; i < count; i++) {
        struct record *done = q->head; q->head = done->next;
        if (done == &q->overflow) q->overflow_queued = 0;
        else { done->next = q->free; q->free = done; }
    }
    if (!q->head) q->tail = NULL;
    q->count -= count; q->bytes -= used;
    return (ssize_t)used;
}
