#ifndef MD_WATCH_QUEUE_H
#define MD_WATCH_QUEUE_H
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

/* Single-owner inotify record queue. The returned stream is readiness only:
 * guest reads must be mediated. The owner retains only its write end, so the
 * last reader's close is observable even after dup/fork/SCM_RIGHTS.
 * No SQLite, guest pathname resolution, guest memory or syscall policy lives here. */
struct md_watch_queue;
int md_watch_queue_create(unsigned limit, int flags, struct md_watch_queue **, int *reader);
void md_watch_queue_destroy(struct md_watch_queue *);
/* Borrowed peer: poll with events=0 to observe HUP/ERR on last close. */
int md_watch_queue_lifetime(const struct md_watch_queue *);
size_t md_watch_queue_bytes(const struct md_watch_queue *);
ssize_t md_watch_queue_reserve(struct md_watch_queue *, int, void *, size_t);
int md_watch_queue_complete(struct md_watch_queue *, int delivered);
int md_watch_queue_emit(struct md_watch_queue *, int wd, uint32_t mask, uint32_t cookie,
        const char *name);
/* Empty returns EAGAIN even for a blocking guest: its caller owns the pending
 * read and signal cancellation. An undersized first record returns EINVAL.
 * Rejected delivery consumes nothing. No other owner may read the stream, change
 * its flags or reenter the queue during delivery. The descriptor is borrowed. */
ssize_t md_watch_queue_read(struct md_watch_queue *, int reader, void *buffer, size_t capacity,
        int (*deliver)(void *, const void *, size_t), void *context);
#endif
