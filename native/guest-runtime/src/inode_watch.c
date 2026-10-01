#define _GNU_SOURCE
#include "inode_watch.h"
#include "inode_internal.h"
#include "watch_queue.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <unistd.h>

enum { MAX_INSTANCES = 128, MAX_WATCHES = 8192, QUEUE_LIMIT = 16384 };
#define DATA_EVENTS (IN_ACCESS | IN_MODIFY | IN_ATTRIB | IN_OPEN | IN_CLOSE)
struct subscription {
    struct subscription *next;
    int wd, native;
    unsigned mask;
    char object[33];
    dev_t device;
    ino_t inode;
    int64_t after;
};
struct instance {
    struct instance *next;
    struct subscription *subscriptions;
    struct md_watch_queue *queue;
    dev_t device;
    ino_t inode;
    int last_wd;
};
struct md_inode_watches {
    struct instance *instances;
    int native, epoll, presence, database, objects;
    int sources[MDI_SOURCES];
    unsigned instance_count, subscription_count;
    int64_t cursor;
};
static int epoll_add(int epoll, int fd, unsigned events) {
    struct epoll_event event = {.events = events, .data.fd = fd};
    return epoll_ctl(epoll, EPOLL_CTL_ADD, fd, &event) ? -errno : 0;
}
static int watch_path(int native, int fd, const char *suffix, unsigned mask) {
    char path[80];
    int n = snprintf(path, sizeof(path), "/proc/self/fd/%d%s", fd, suffix);
    if (n < 0 || (size_t)n >= sizeof(path)) return -ENAMETOOLONG;
    int wd = inotify_add_watch(native, path, mask);
    return wd < 0 ? -errno : wd;
}
static int journal_end(struct md_inode_store *s, int64_t *end) {
    sqlite3_stmt *q = NULL;
    int r = mdi_query_acquire(s, MDI_EVENT_END, &q);
    if (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_ROW) *end = sqlite3_column_int64(q, 0);
        else r = mdi_sql_failure(rc);
    }
    return mdi_query_release(q, r);
}
static struct instance *find(struct md_inode_store *s, int fd) {
    struct stat st;
    if (!s->watches || fstat(fd, &st)) return NULL;
    for (struct instance *i = s->watches->instances; i; i = i->next)
        if (st.st_dev == i->device && st.st_ino == i->inode && S_ISSOCK(st.st_mode)) return i;
    return NULL;
}
int md_inode_watch_contains(struct md_inode_store *s, int fd) { return find(s, fd) != NULL; }
static void retire_native(struct md_inode_watches *w, int wd) {
    for (struct instance *i = w->instances; i; i = i->next)
        for (struct subscription *p = i->subscriptions; p; p = p->next)
            if (p->native == wd) return;
    for (unsigned n = 0; n < MDI_SOURCES; ++n) if (w->sources[n] == wd) return;
    if (wd >= 0 && wd != w->database) inotify_rm_watch(w->native, wd);
}
static void free_instance(struct md_inode_watches *w, struct instance *i) {
    while (i->subscriptions) {
        struct subscription *next = i->subscriptions->next;
        int wd = i->subscriptions->native;
        free(i->subscriptions); i->subscriptions = next; --w->subscription_count;
        retire_native(w, wd);
    }
    epoll_ctl(w->epoll, EPOLL_CTL_DEL, md_watch_queue_lifetime(i->queue), NULL);
    md_watch_queue_destroy(i->queue);
    free(i); --w->instance_count;
}
void md_inode_watch_close(struct md_inode_store *s) {
    struct md_inode_watches *w = s->watches;
    if (!w) return;
    while (w->instances) {
        struct instance *next = w->instances->next;
        free_instance(w, w->instances); w->instances = next;
    }
    if (w->native >= 0) close(w->native);
    if (w->epoll >= 0) close(w->epoll);
    if (w->presence >= 0) close(w->presence);
    free(w); s->watches = NULL;
}
static int start(struct md_inode_store *s) {
    if (s->watches) return 0;
    struct md_inode_watches *w = calloc(1, sizeof(*w));
    if (!w) return -ENOMEM;
    w->native = w->epoll = w->presence = -1;
    for (unsigned i = 0; i < MDI_SOURCES; ++i) w->sources[i] = -1;
    s->watches = w;
    int r = mdi_begin(s, 0);
    if (r) { md_inode_watch_close(s); return r; }
    w->presence = openat(s->root, "watch.lock", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (w->presence < 0) r = -errno;
    if (!r && flock(w->presence, LOCK_SH | LOCK_NB)) r = -errno;
    if (!r && (w->native = inotify_init1(IN_NONBLOCK | IN_CLOEXEC)) < 0) r = -errno;
    if (!r && (w->epoll = epoll_create1(EPOLL_CLOEXEC)) < 0) r = -errno;
    if (!r) r = epoll_add(w->epoll, w->native, EPOLLIN);
    if (!r && (w->database = watch_path(w->native, s->root, "/namespace.db", IN_MODIFY)) < 0) r = w->database;
    if (!r && (w->objects = watch_path(w->native, s->objects, "", DATA_EVENTS)) < 0) r = w->objects;
    w->sources[0] = w->objects;
    for (unsigned i = 1; !r && i < MDI_SOURCES; ++i) if (s->sources[i] >= 0) {
        w->sources[i] = watch_path(w->native, s->sources[i], "", DATA_EVENTS);
        if (w->sources[i] < 0) r = w->sources[i];
    }
    if (!r) r = journal_end(s, &w->cursor);
    r = mdi_finish(s, r);
    if (r) md_inode_watch_close(s);
    return r;
}
int md_inode_watch_create(struct md_inode_store *s, int flags) {
    if (flags & ~(IN_NONBLOCK | IN_CLOEXEC)) return -EINVAL;
    int r = start(s);
    if (r) return r;
    struct md_inode_watches *w = s->watches;
    if (w->instance_count == MAX_INSTANCES) return -EMFILE;
    struct instance *i = calloc(1, sizeof(*i));
    if (!i) { if (!w->instances) md_inode_watch_close(s); return -ENOMEM; }
    int fd = -1;
    r = md_watch_queue_create(QUEUE_LIMIT, flags, &i->queue, &fd);
    struct stat st;
    if (!r && fstat(fd, &st)) r = -errno;
    if (!r) r = epoll_add(w->epoll, md_watch_queue_lifetime(i->queue), 0);
    if (r) {
        if (fd >= 0) close(fd);
        md_watch_queue_destroy(i->queue); free(i);
        if (!w->instances) md_inode_watch_close(s);
        return r;
    }
    i->device = st.st_dev; i->inode = st.st_ino;
    i->next = w->instances; w->instances = i; ++w->instance_count;
    return fd;
}
static int remove_subscription(struct md_inode_watches *w, struct instance *i, struct subscription **link) {
    struct subscription *p = *link;
    int r = md_watch_queue_emit(i->queue, p->wd, IN_IGNORED, 0, NULL);
    int native = p->native;
    *link = p->next; free(p); --w->subscription_count;
    retire_native(w, native);
    return r == -EPIPE ? 0 : r;
}
static int publish(struct md_inode_watches *w, struct instance *i, struct subscription **link,
        unsigned mask, unsigned cookie, const char *name) {
    struct subscription *p = *link;
    int r = 0, delivered = !!((p->mask & mask & IN_ALL_EVENTS) || (mask & IN_UNMOUNT));
    if (delivered) r = md_watch_queue_emit(i->queue, p->wd, mask, cookie, name);
    if (mask & (IN_DELETE_SELF | IN_IGNORED) || (delivered && (p->mask & IN_ONESHOT))) {
        int removed = remove_subscription(w, i, link);
        return r ? r : removed;
    }
    return r == -EPIPE ? 0 : r;
}
static int overflow(struct md_inode_watches *w) {
    for (struct instance *i = w->instances; i; i = i->next) {
        int r = md_watch_queue_emit(i->queue, -1, IN_Q_OVERFLOW, 0, NULL);
        if (r && r != -EPIPE) return r;
    }
    return 0;
}
static int journal(struct md_inode_store *s) {
    struct md_inode_watches *w = s->watches;
    sqlite3_stmt *q = NULL;
    int r = mdi_query_acquire(s, MDI_EVENT_SCAN, &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, w->cursor));
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc != SQLITE_ROW) { r = mdi_sql_error(rc); break; }
        int64_t sequence = sqlite3_column_int64(q, 0);
        if (sequence != w->cursor + 1) r = overflow(w);
        const char *parent = (const char *)sqlite3_column_text(q, 1);
        const char *object = (const char *)sqlite3_column_text(q, 2);
        int n = sqlite3_column_bytes(q, 3);
        if (!parent || !object || n < 0 || n > NAME_MAX) { r = -EIO; break; }
        char name[NAME_MAX+1];
        if (n) memcpy(name, sqlite3_column_blob(q, 3), (size_t)n);
        name[n] = 0;
        unsigned mask = (unsigned)sqlite3_column_int64(q, 4);
        unsigned cookie = (unsigned)sqlite3_column_int64(q, 5);
        if (mask & IN_MOVED_FROM) cookie = (unsigned)((sequence - 1) % UINT32_MAX + 1);
        for (struct instance *i = w->instances; i && !r; i = i->next) {
            struct subscription **link = &i->subscriptions;
            while (*link && !r) {
                struct subscription *p = *link;
                if (sequence > p->after && !strcmp(p->object, *parent ? parent : object))
                    r = publish(w, i, link, mask, cookie, *parent ? name : NULL);
                if (*link == p) link = &p->next;
            }
        }
        w->cursor = sequence;
    }
    return mdi_query_release(q, r);
}
static int native_event(struct md_inode_store *s, const struct inotify_event *event) {
    struct md_inode_watches *w = s->watches;
    if (event->mask & IN_Q_OVERFLOW) return overflow(w);
    if (event->wd == w->database) return 0;
    int r = 0;
    int source = -1;
    for (unsigned i = 0; i < MDI_SOURCES; ++i) if (event->wd == w->sources[i]) { source = (int)i; break; }
    if (source >= 0) {
        if (!event->len || strlen(event->name) != 32) return 0;
        sqlite3_stmt *object = NULL;
        r = mdi_query_acquire(s, MDI_BACKING_OBJECT, &object);
        if (!r) r = mdi_bind_id(object, 1, event->name);
        if (!r) r = mdi_sql_error(sqlite3_bind_int(object, 2, source));
        char id[33] = {0};
        if (!r) {
            int rc = mdi_step(s, object);
            if (rc == SQLITE_ROW && sqlite3_column_bytes(object, 0) == 32)
                memcpy(id, sqlite3_column_text(object, 0), 32);
            else if (rc != SQLITE_DONE) r = mdi_sql_failure(rc);
        }
        r = mdi_query_release(object, r);
        if (r || !*id) return r;
        /* Virtual subscriptions follow logical objects across copy-up. Watching
         * the retained backing directory observes the first write even before
         * another namespace owner processes the publication commit. */
        for (struct instance *i = w->instances; i && !r; i = i->next) {
            struct subscription **link = &i->subscriptions;
            while (*link && !r) {
                struct subscription *p = *link;
                if (!strcmp(p->object, id)) r = publish(w, i, link, event->mask, 0, NULL);
                if (*link == p) link = &p->next;
            }
        }
        /* Resolve aliases once per native event, not once per subscription. */
        sqlite3_stmt *q = NULL;
        if (!r) r = mdi_query_acquire(s, MDI_EVENT_NAMES, &q);
        if (!r) r = mdi_bind_id(q, 1, event->name);
        if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 2, source));
        while (!r) {
            int rc = mdi_step(s, q);
            if (rc != SQLITE_ROW) { r = mdi_sql_error(rc); break; }
            const char *parent = (const char *)sqlite3_column_text(q, 0);
            int n = sqlite3_column_bytes(q, 1);
            if (!parent || n < 1 || n > NAME_MAX) { r = -EIO; break; }
            char name[NAME_MAX+1];
            memcpy(name, sqlite3_column_blob(q, 1), (size_t)n); name[n] = 0;
            for (struct instance *i = w->instances; i && !r; i = i->next) {
                struct subscription **link = &i->subscriptions;
                while (*link && !r) {
                    struct subscription *p = *link;
                    if (!strcmp(p->object, parent)) r = publish(w, i, link, event->mask, 0, name);
                    if (*link == p) link = &p->next;
                }
            }
        }
        return mdi_query_release(q, r);
    }
    for (struct instance *i = w->instances; i && !r; i = i->next) {
        struct subscription **link = &i->subscriptions;
        while (*link && !r) {
            struct subscription *p = *link;
            if (p->native == event->wd) r = publish(w, i, link, event->mask, event->cookie,
                event->len ? event->name : NULL);
            if (*link == p) link = &p->next;
        }
    }
    return r;
}
static int drain_native(struct md_inode_store *s, size_t budget) {
    union { struct inotify_event align; char bytes[65536]; } buffer;
    int r = 0;
    while (budget && !r) {
        ssize_t size = read(s->watches->native, buffer.bytes, sizeof(buffer.bytes));
        if (size < 0) { if (errno != EAGAIN && errno != EINTR) r = -errno; break; }
        if (!size) { r = -EIO; break; }
        for (size_t pos = 0; pos < (size_t)size && !r;) {
            struct inotify_event *e = (struct inotify_event *)(buffer.bytes + pos);
            r = native_event(s, e);
            pos += sizeof(*e) + e->len;
        }
        budget = (size_t)size >= budget ? 0 : budget - (size_t)size;
    }
    return r;
}
int md_inode_watch_pump(struct md_inode_store *s) {
    struct md_inode_watches *w = s->watches;
    if (!w) return 0;
    struct epoll_event events[MAX_INSTANCES+1];
    int n = epoll_wait(w->epoll, events, MAX_INSTANCES+1, 0);
    if (n < 0) return errno == EINTR ? 0 : -errno;
    for (int j = 0; j < n; ++j) if (events[j].events & (EPOLLERR | EPOLLHUP)) {
        struct instance **link = &w->instances;
        while (*link && md_watch_queue_lifetime((*link)->queue) != events[j].data.fd) link = &(*link)->next;
        if (*link) { struct instance *i = *link; *link = i->next; free_instance(w, i); }
    }
    if (!w->instances) { md_inode_watch_close(s); return 0; }
    int r = mdi_begin(s, 0);
    if (r) return r;
    r = journal(s);
    /* Level-triggered readiness retains the remainder. A busy native writer
     * must not hold the namespace transaction indefinitely. */
    if (!r) r = drain_native(s, 4 * 65536);
    return mdi_finish(s, r);
}
int md_inode_watch_pollfd(struct md_inode_store *s) { return s->watches ? s->watches->epoll : -1; }
int md_inode_watch_add(struct md_inode_store *s, int fd, int object, unsigned mask) {
    unsigned valid = IN_ALL_EVENTS | IN_UNMOUNT | IN_IGNORED | IN_Q_OVERFLOW | IN_ISDIR
        | IN_ONESHOT | IN_ONLYDIR | IN_DONT_FOLLOW | IN_EXCL_UNLINK | IN_MASK_ADD | IN_MASK_CREATE;
    if (!(mask & IN_ALL_EVENTS) || (mask & ~valid) || ((mask & IN_MASK_ADD) && (mask & IN_MASK_CREATE))) return -EINVAL;
    int r = md_inode_watch_pump(s);
    if (r) return r;
    struct instance *i = find(s, fd);
    if (!i) return -EINVAL;
    struct stat st;
    if (fstat(object, &st)) return -errno;
    if ((mask & IN_ONLYDIR) && !S_ISDIR(st.st_mode)) return -ENOTDIR;
    char identity[33] = {0};
    r = md_inode_object_id(s, object, identity);
    if (r && r != -EXDEV) return r;
    struct subscription *p = i->subscriptions;
    while (p && (*identity ? strcmp(p->object, identity) != 0
            : *p->object || p->device != st.st_dev || p->inode != st.st_ino)) p = p->next;
    if (p) {
        if (mask & IN_MASK_CREATE) return -EEXIST;
        p->mask = mask & IN_MASK_ADD ? p->mask | mask : mask;
        return p->wd;
    }
    struct md_inode_watches *w = s->watches;
    if (w->subscription_count == MAX_WATCHES) return -ENOSPC;
    if (i->last_wd == INT_MAX) return -ENOSPC;
    p = calloc(1, sizeof(*p));
    if (!p) return -ENOMEM;
    r = mdi_begin(s, 0);
    if (r) { free(p); return r; }
    /* Registration excludes already queued native data, just as the journal
     * cursor excludes earlier commits. Snapshot the finite backlog so writers
     * cannot extend registration indefinitely. */
    int backlog = 0;
    if (ioctl(w->native, FIONREAD, &backlog)) r = -errno;
    if (!r) r = drain_native(s, (size_t)backlog);
    struct mdi_node node;
    if (!r) r = mdi_fd(s, object, &node);
    if (!r) memcpy(p->object, node.id, sizeof(p->object));
    else if (r == -EXDEV) r = 0;
    if (!r) r = journal_end(s, &p->after);
    if (!r) {
        if (*p->object) {
            p->native = -1;
        } else {
            p->native = watch_path(w->native, object, "", IN_ALL_EVENTS | IN_MASK_ADD);
            if (p->native < 0) r = p->native;
        }
    }
    r = mdi_finish(s, r);
    if (r) { free(p); return r; }
    p->mask = mask; p->device = st.st_dev; p->inode = st.st_ino;
    p->wd = ++i->last_wd;
    p->next = i->subscriptions; i->subscriptions = p; ++w->subscription_count;
    return p->wd;
}
int md_inode_watch_remove(struct md_inode_store *s, int fd, int wd) {
    int r = md_inode_watch_pump(s);
    if (r) return r;
    struct instance *i = find(s, fd);
    if (!i) return -EINVAL;
    struct subscription **link = &i->subscriptions;
    while (*link && (*link)->wd != wd) link = &(*link)->next;
    return *link ? remove_subscription(s->watches, i, link) : -EINVAL;
}
ssize_t md_inode_watch_bytes(struct md_inode_store *s, int fd) {
    int r = md_inode_watch_pump(s);
    if (r) return r;
    struct instance *i = find(s, fd);
    return i ? (ssize_t)md_watch_queue_bytes(i->queue) : -EINVAL;
}
ssize_t md_inode_watch_read(struct md_inode_store *s, int fd, void *data, size_t capacity,
        int (*deliver)(void *, const void *, size_t), void *context) {
    int r = md_inode_watch_pump(s);
    if (r) return r;
    struct instance *i = find(s, fd);
    return i ? md_watch_queue_read(i->queue, fd, data, capacity, deliver, context) : -EINVAL;
}
ssize_t md_inode_watch_reserve(struct md_inode_store *s, int fd, void *data, size_t capacity) {
    int r = md_inode_watch_pump(s);
    if (r) return r;
    struct instance *i = find(s, fd);
    return i ? md_watch_queue_reserve(i->queue, fd, data, capacity) : -EINVAL;
}
int md_inode_watch_complete(struct md_inode_store *s, int fd, int delivered) {
    struct instance *i = find(s, fd);
    return i ? md_watch_queue_complete(i->queue, delivered) : -EINVAL;
}
