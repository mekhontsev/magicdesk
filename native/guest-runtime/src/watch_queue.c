#define _GNU_SOURCE
#include "watch_queue.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/socket.h>
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
    unsigned reserved;
    size_t reserved_bytes;
};

int md_watch_queue_create(unsigned limit, int flags, struct md_watch_queue **out, int *reader) {
    if (!out || !reader) return -EFAULT;
    *out = NULL; *reader = -1;
    if (!limit || limit > INT_MAX / RECORD_BYTES || flags & ~(IN_NONBLOCK | IN_CLOEXEC)) return -EINVAL;
    struct md_watch_queue *q = calloc(1, sizeof(*q));
    if (!q) return -ENOMEM;
    int pipefd[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pipefd)) {
        int r = -errno; free(q); return r;
    }
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

static int signal_ready(struct md_watch_queue *q) {
    char marker = 1;
    ssize_t written;
    do { written = send(q->writer, &marker, 1, MSG_NOSIGNAL); } while (written < 0 && errno == EINTR);
    if (written != 1) q->error = written < 0 ? -errno : -EIO;
    return q->error;
}
static int append(struct md_watch_queue *q, struct record *r) {
    if (!q->head && signal_ready(q)) return q->error;
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
    char marker = 0;
    ssize_t result;
    do { result = recv(reader, &marker, 1, MSG_DONTWAIT); } while (result < 0 && errno == EINTR);
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
    if (q->tail && q->reserved != q->count && q->tail->size == size && !memcmp(q->tail->bytes, bytes, size)) return 0;
    if (q->count >= q->limit) return overflow(q);
    struct record *r = q->free;
    if (r) q->free = r->next; else r = malloc(sizeof(*r));
    if (!r) return overflow(q);
    r->size = size; memcpy(r->bytes, bytes, size);
    int result = append(q, r);
    if (result) { r->next = q->free; q->free = r; }
    return result;
}
ssize_t md_watch_queue_reserve(struct md_watch_queue *q, int reader, void *buffer, size_t capacity) {
    if (q->error) return q->error;
    if (q->reserved) return -EAGAIN;
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
    /* A delivery lease owns the head. Competing blocking readers wait for its
     * completion instead of repeatedly peeking the same readiness marker. */
    int error = drain_marker(reader);
    if (error) { q->error = error; return error; }
    q->reserved = count; q->reserved_bytes = used;
    return (ssize_t)used;
}
int md_watch_queue_complete(struct md_watch_queue *q, int delivered) {
    unsigned count = q->reserved;
    size_t used = q->reserved_bytes;
    q->reserved = 0; q->reserved_bytes = 0;
    if (!count) return 0;
    if (!delivered) return signal_ready(q);
    for (unsigned i = 0; i < count; i++) {
        struct record *done = q->head; q->head = done->next;
        if (done == &q->overflow) q->overflow_queued = 0;
        else { done->next = q->free; q->free = done; }
    }
    if (!q->head) q->tail = NULL;
    q->count -= count; q->bytes -= used;
    return q->head ? signal_ready(q) : 0;
}
ssize_t md_watch_queue_read(struct md_watch_queue *q, int reader, void *buffer, size_t capacity,
        int (*deliver)(void *, const void *, size_t), void *context) {
    ssize_t used = md_watch_queue_reserve(q, reader, buffer, capacity);
    if (used < 0) return used;
    int r = deliver ? deliver(context, buffer, (size_t)used) : 0;
    int completed = md_watch_queue_complete(q, !r);
    return r ? r : completed ? completed : used;
}
