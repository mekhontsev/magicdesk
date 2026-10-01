#define _GNU_SOURCE
#include "fs_worker.h"
#include "fs_service.h"
#include "image_catalogue.h"
#include "event_wait.h"
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <unistd.h>

struct md_fs_worker {
    pthread_t thread;
    pthread_mutex_t lock;
    int wake, done, stop, ready, error, statistics;
    const char *store, *endpoint, *admit;
    struct md_fs_work *completed, **completed_tail;
    int notification_fd, exited;
    void *notification_context;
    int (*notification)(void *, struct md_inode_store *, struct md_image_catalogue *, short);
};
static void wake(int fd) {
    uint64_t one = 1;
    while (write(fd, &one, sizeof(one)) < 0 && errno == EINTR) { }
}
static void drain(int fd) {
    uint64_t value;
    while (read(fd, &value, sizeof(value)) < 0 && errno == EINTR) { }
}
static void run_ready(void *context) {
    struct md_fs_worker *w = context;
    drain(w->wake);
}
static int notification_fd(void *context) {
    struct md_fs_worker *w = context;
    pthread_mutex_lock(&w->lock); int fd = w->notification_fd; pthread_mutex_unlock(&w->lock);
    return fd;
}
static int notification(void *context, struct md_inode_store *s, struct md_image_catalogue *images, short events) {
    struct md_fs_worker *w = context;
    pthread_mutex_lock(&w->lock);
    void *owner = w->notification_context;
    int (*ready)(void *, struct md_inode_store *, struct md_image_catalogue *, short) = w->notification;
    pthread_mutex_unlock(&w->lock);
    return ready(owner, s, images, events);
}
static void *run(void *context) {
    struct md_fs_worker *w = context;
    struct md_inode_store *store = NULL;
    struct md_image_catalogue *images = NULL;
    int listener = -1, error = md_inode_store_open(w->store, 0, &store);
    if (!error && w->admit) error = md_image_catalogue_open(store, w->admit, &images);
    if (!error) { listener = md_fs_listen(w->endpoint); if (listener < 0) error = listener; }
    pthread_mutex_lock(&w->lock); w->error = error; pthread_mutex_unlock(&w->lock);
    wake(w->ready);
    struct md_inode_statistics stats = {0};
    struct md_fs_statistics rpc = {0};
    if (!error) {
        if (w->statistics) md_inode_measure(store, &stats);
        struct md_fs_work_source source = {.fd = w->wake, .context = w, .ready = run_ready,
            .notification_fd = notification_fd, .notification = notification};
        error = md_fs_serve(store, images, listener, w->stop, 5000, w->statistics ? &rpc : NULL, &source);
    }
    if (listener >= 0) close(listener);
    md_image_catalogue_close(images); md_inode_store_close(store);
    if (w->statistics) {
        const struct md_cost *costs[] = {&stats.prepare, &stats.step, &stats.transaction, &stats.lock};
        const char *names[] = {"prepare", "step", "transaction", "lock"};
        for (unsigned i = 0; i < 4; ++i) fprintf(stderr, "MD_STORE phase=%s calls=%llu ns=%llu\n", names[i],
            (unsigned long long)costs[i]->calls, (unsigned long long)costs[i]->nanoseconds);
        fprintf(stderr, "MD_STORE queryReuses=%llu\n", (unsigned long long)stats.query_reuses);
        fprintf(stderr, "MD_STORE linkCountQueries=%llu membershipQueries=%llu\n",
            (unsigned long long)stats.link_count_queries, (unsigned long long)stats.membership_queries);
        for (unsigned i = 0; i <= MD_FS_OPEN_IMAGE; ++i) if (rpc.operation[i].calls)
            fprintf(stderr, "MD_FS operation=%u calls=%llu ns=%llu\n", i,
                (unsigned long long)rpc.operation[i].calls,
                (unsigned long long)rpc.operation[i].nanoseconds);
        struct rusage usage;
        if (!getrusage(RUSAGE_THREAD, &usage)) fprintf(stderr, "MD_CPU namespaceWorker userUs=%llu systemUs=%llu\n",
            (unsigned long long)usage.ru_utime.tv_sec * 1000000 + usage.ru_utime.tv_usec,
            (unsigned long long)usage.ru_stime.tv_sec * 1000000 + usage.ru_stime.tv_usec);
    }
    pthread_mutex_lock(&w->lock);
    w->error = error ? error : -ESHUTDOWN;
    w->exited = 1;
    pthread_mutex_unlock(&w->lock);
    wake(w->done);
    return NULL;
}
int md_fs_worker_start(const char *store, const char *endpoint, const char *admit,
        int statistics, struct md_fs_worker **out) {
    struct md_fs_worker *w = calloc(1, sizeof(*w));
    if (!w) return -ENOMEM;
    w->wake = w->done = w->stop = w->ready = -1;
    w->notification_fd = -1;
    w->store = store; w->endpoint = endpoint; w->admit = admit; w->statistics = statistics;
    w->completed_tail = &w->completed;
    int error = pthread_mutex_init(&w->lock, NULL);
    if (error) { free(w); return -error; }
    int *fds[] = {&w->wake, &w->done, &w->stop, &w->ready};
    for (unsigned i = 0; i < 4; ++i) {
        *fds[i] = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        if (*fds[i] < 0) { error = -errno; goto failed; }
    }
    error = pthread_create(&w->thread, NULL, run, w);
    if (error) { error = -error; goto failed; }
    /* EVENT_WAIT: worker publishes namespace/listener readiness; deadline fails
     * startup. The guardian bounds teardown if kernel filesystem IO is stuck. */
    error = (int)md_event_wait_fd(w->ready, POLLIN, md_event_now() + 30000000000LL);
    if (error >= 0) { pthread_mutex_lock(&w->lock); error = w->error; pthread_mutex_unlock(&w->lock); }
    if (error < 0) {
        if (md_fs_worker_stop(w) == -ETIMEDOUT) _exit(125);
        return error;
    }
    *out = w; return 0;
failed:
    for (unsigned i = 0; i < 4; ++i) if (*fds[i] >= 0) close(*fds[i]);
    pthread_mutex_destroy(&w->lock); free(w); return error;
}
int md_fs_worker_fd(struct md_fs_worker *w) { return w ? w->done : -1; }
int md_fs_worker_error(struct md_fs_worker *w) {
    pthread_mutex_lock(&w->lock); int error = w->error; pthread_mutex_unlock(&w->lock);
    return error;
}
void md_fs_worker_notifications(struct md_fs_worker *w, int fd, void *context,
        int (*ready)(void *, struct md_inode_store *, struct md_image_catalogue *, short)) {
    pthread_mutex_lock(&w->lock);
    w->notification_fd = fd; w->notification_context = context; w->notification = ready;
    pthread_mutex_unlock(&w->lock); wake(w->wake);
}
void md_fs_worker_publish(struct md_fs_worker *w, struct md_fs_work *work) {
    work->next = NULL;
    pthread_mutex_lock(&w->lock);
    *w->completed_tail = work; w->completed_tail = &work->next;
    pthread_mutex_unlock(&w->lock); wake(w->done);
}
struct md_fs_work *md_fs_worker_completed(struct md_fs_worker *w) {
    drain(w->done);
    pthread_mutex_lock(&w->lock);
    struct md_fs_work *completed = w->completed;
    w->completed = NULL; w->completed_tail = &w->completed;
    pthread_mutex_unlock(&w->lock);
    return completed;
}
int md_fs_worker_stop(struct md_fs_worker *w) {
    if (!w) return 0;
    wake(w->stop);
    /* EVENT_WAIT: worker exit after stop readiness; expiry leaves its memory
     * owned by the process, whose caller must terminate instead of freeing it. */
    int64_t deadline = md_event_now() + 10000000000LL;
    for (;;) {
        pthread_mutex_lock(&w->lock); int exited = w->exited; pthread_mutex_unlock(&w->lock);
        if (exited) break;
        int r = (int)md_event_wait_fd(w->done, POLLIN, deadline);
        if (r < 0) return r;
        drain(w->done);
    }
    int error = pthread_join(w->thread, NULL);
    if (!error && w->error != -ESHUTDOWN) error = -w->error;
    close(w->wake); close(w->done); close(w->stop); close(w->ready);
    pthread_mutex_destroy(&w->lock); free(w);
    return -error;
}
