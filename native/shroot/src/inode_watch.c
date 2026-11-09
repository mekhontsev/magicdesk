#define _GNU_SOURCE
#include "inode_watch.h"
#include "inode_internal.h"
#include "inode_leaf.h"
#include "watch_queue.h"
#include "watch_source.h"
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

#define DATA_EVENTS (IN_ACCESS | IN_MODIFY | IN_ATTRIB | IN_OPEN | IN_CLOSE)
enum { PROVIDER_EVENT_BUDGET=16384 };
struct subscription {
    struct subscription *next;
    int wd, native;
    unsigned mask;
    char object[33];
    dev_t device;
    ino_t inode;
    int64_t after;
    struct watch_source *source;
    struct provider_source *provider;
    struct md_watch_interest *interest;
    struct md_user_charge *charge;
    uint64_t provider_object,provider_after;
};
struct instance {
    struct instance *next;
    struct subscription *subscriptions;
    struct md_watch_queue *queue;
    struct md_user_charge *charge;
    dev_t device;
    ino_t inode;
    int last_wd;
};
struct watch_source {
    struct watch_source *next;
    struct md_inode_store *store;
    int owned, native, presence, database, objects, control, publishing;
    int sources[MDI_SOURCES];
    int64_t cursor;
    unsigned subscriptions, logical_subscriptions;
    unsigned birth_cookie;
};
struct md_inode_watches {
    struct instance *instances;
    struct watch_source *sources;
    int epoll;
    struct provider_source *providers;
};
struct provider_source {
    struct provider_source *next;
    struct md_watch_source *source;
    struct md_watch_listener *listener;
    unsigned subscriptions;
};
static atomic_uint queue_limit=16384;
unsigned md_inode_watch_queue_limit(void) {
    return atomic_load_explicit(&queue_limit,memory_order_relaxed);
}
int md_inode_watch_set_queue_limit(unsigned limit) {
    if (limit>INT_MAX) return -EINVAL;
    atomic_store_explicit(&queue_limit,limit,memory_order_relaxed);
    return 0;
}
static int epoll_add(int epoll, int fd, unsigned events) {
    struct epoll_event event = {.events = events, .data.fd = fd};
    return epoll_ctl(epoll, EPOLL_CTL_ADD, fd, &event) ? -errno : 0;
}
static int source_ready(const struct epoll_event *events,int count,int fd) {
    for (int i=0;i<count;++i) if (events[i].data.fd==fd && (events[i].events&EPOLLIN)) return 1;
    return 0;
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
static void retire_native(struct md_inode_watches *w, struct watch_source *source, int wd) {
    if (!source) return;
    for (struct instance *i = w->instances; i; i = i->next)
        for (struct subscription *p = i->subscriptions; p; p = p->next)
            if (p->source==source && p->native == wd) return;
    for (unsigned n = 0; n < MDI_SOURCES; ++n) if (source->sources[n] == wd) return;
    if (wd >= 0 && wd != source->database && wd != source->control) inotify_rm_watch(source->native, wd);
}
static int valid_mask(unsigned mask) {
    unsigned valid=IN_ALL_EVENTS|IN_UNMOUNT|IN_IGNORED|IN_Q_OVERFLOW|IN_ISDIR
        |IN_ONESHOT|IN_ONLYDIR|IN_DONT_FOLLOW|IN_EXCL_UNLINK|IN_MASK_ADD|IN_MASK_CREATE;
    return (mask&IN_ALL_EVENTS) && !(mask&~valid)
        && !((mask&IN_MASK_ADD) && (mask&IN_MASK_CREATE));
}
static void logical_source_stop(struct md_inode_watches *w,struct watch_source *source) {
    int descriptors[MDI_SOURCES+2];
    for (unsigned n=0;n<MDI_SOURCES;++n) {
        descriptors[n]=source->sources[n]; source->sources[n]=-1;
    }
    descriptors[MDI_SOURCES]=source->database; source->database=-1;
    descriptors[MDI_SOURCES+1]=source->control; source->control=-1;
    source->objects=-1;
    for (unsigned n=0;n<MDI_SOURCES+2;++n) retire_native(w,source,descriptors[n]);
    if (source->presence>=0) close(source->presence);
    source->presence=-1; source->publishing=0; source->birth_cookie=0;
}
static int logical_source_refresh(struct md_inode_watches *w,struct watch_source *source,
        const struct subscription *extra) {
    unsigned mask=extra && *extra->object ? extra->mask : 0;
    for (struct instance *i=w->instances;i;i=i->next)
        for (struct subscription *p=i->subscriptions;p;p=p->next)
            if (p->source==source && *p->object) mask|=p->mask;
    if (!mask) { logical_source_stop(w,source); return 0; }
    struct md_inode_store *s=source->store;
    int error=0;
    if (source->presence<0) {
        source->presence=openat(s->lifetime->root,"watch.lock",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
        if (source->presence<0 || flock(source->presence,LOCK_SH|LOCK_NB)) error=-errno;
        if (!error && (source->database=watch_path(source->native,s->lifetime->root,"/namespace.db-wal",IN_MODIFY))<0)
            error=source->database;
        if (!error && (source->control=watch_path(source->native,s->lifetime->root,"",IN_CREATE))<0)
            error=source->control;
    }
    /* Storage-wide data observation exists only for interested logical
     * subscribers. Native/provider watches must not collect unrelated IO. */
    unsigned data=mask&DATA_EVENTS;
    if (!error) {
        source->objects=watch_path(source->native,s->objects,"",data|IN_CREATE|IN_MOVED_FROM|IN_MOVED_TO);
        source->sources[0]=source->objects;
        if (source->objects<0) error=source->objects;
    }
    for (unsigned n=1;!error && n<MDI_SOURCES;++n) if (s->lifetime->sources[n]>=0) {
        if (data) {
            source->sources[n]=watch_path(source->native,s->lifetime->sources[n],"",data);
            if (source->sources[n]<0) error=source->sources[n];
        } else {
            int wd=source->sources[n]; source->sources[n]=-1; retire_native(w,source,wd);
        }
    }
    if (error && !source->logical_subscriptions) logical_source_stop(w,source);
    return error;
}
static int link_subscription(struct instance *i,struct subscription *p) {
    if (p->source) {
        ++p->source->subscriptions;
        if (*p->object) {
            /* The first logical subscriber starts at its registration snapshot,
             * not history accumulated while only native watches existed. */
            if (!p->source->logical_subscriptions) p->source->cursor=p->after;
            ++p->source->logical_subscriptions;
        }
    }
    if (p->provider) ++p->provider->subscriptions;
    p->wd=++i->last_wd;
    p->next=i->subscriptions; i->subscriptions=p;
    return p->wd;
}
static void unlink_subscription(struct md_inode_watches *w,struct subscription **link) {
    struct subscription *p=*link;
    struct watch_source *source=p->source;
    int native=p->native;
    *link=p->next;
    if (source) {
        --source->subscriptions;
        if (*p->object) --source->logical_subscriptions;
    }
    if (p->provider) {
        --p->provider->subscriptions;
        md_watch_listener_forget(p->provider->listener,p->interest);
    }
    md_user_charge_release(p->charge,MD_USER_INOTIFY_WATCHES);
    free(p);
    retire_native(w,source,native);
    if (source) logical_source_refresh(w,source,NULL);
}
static void free_instance(struct md_inode_watches *w, struct instance *i) {
    while (i->subscriptions) unlink_subscription(w,&i->subscriptions);
    epoll_ctl(w->epoll, EPOLL_CTL_DEL, md_watch_queue_lifetime(i->queue), NULL);
    md_watch_queue_destroy(i->queue);
    md_user_charge_release(i->charge,MD_USER_INOTIFY_INSTANCES);
    free(i);
}
static void source_close(struct watch_source *p) {
    if (p->native>=0) close(p->native);
    if (p->presence>=0) close(p->presence);
    if (p->owned) md_inode_store_close(p->store);
    free(p);
}
static void source_collect(struct md_inode_watches *w) {
    struct watch_source **link=&w->sources;
    while (*link) {
        struct watch_source *p=*link;
        if (!p->owned || p->subscriptions) link=&p->next;
        else { *link=p->next; source_close(p); }
    }
    struct provider_source **provider=&w->providers;
    while (*provider) {
        struct provider_source *p=*provider;
        if (p->subscriptions) provider=&p->next;
        else { *provider=p->next; md_watch_source_unsubscribe(p->listener); free(p); }
    }
}
void md_inode_watch_close(struct md_inode_store *s) {
    struct md_inode_watches *w = s->watches;
    if (!w) return;
    while (w->instances) {
        struct instance *next = w->instances->next;
        free_instance(w, w->instances); w->instances = next;
    }
    while (w->sources) {
        struct watch_source *p=w->sources; w->sources=p->next;
        source_close(p);
    }
    while (w->providers) {
        struct provider_source *p=w->providers; w->providers=p->next;
        md_watch_source_unsubscribe(p->listener); free(p);
    }
    if (w->epoll >= 0) close(w->epoll);
    free(w); s->watches = NULL;
}
static int source_get(struct md_inode_store *owner, struct md_inode_store *store, struct watch_source **out) {
    struct md_inode_watches *w=owner->watches;
    for (struct watch_source *p=w->sources;p;p=p->next)
        if (md_inode_lifetime_same(p->store->lifetime,store->lifetime)) { *out=p; return 0; }
    struct watch_source *p=calloc(1,sizeof(*p));
    if (!p) return -ENOMEM;
    p->native=p->presence=p->database=p->objects=p->control=-1;
    for (unsigned i=0;i<MDI_SOURCES;++i) p->sources[i]=-1;
    int r=0;
    if (store==owner) p->store=owner;
    else { r=md_inode_store_reconnect(store->lifetime,&p->store); p->owned=1; }
    struct md_inode_store *s=p->store;
    if (!r) r=mdi_begin(s,0);
    if (!r && (p->native=inotify_init1(IN_NONBLOCK|IN_CLOEXEC))<0) r=-errno;
    if (!r) r=epoll_add(w->epoll,p->native,EPOLLIN);
    if (!r) r=journal_end(s,&p->cursor);
    if (s && s->locked) r=mdi_finish(s,r);
    if (r) source_close(p);
    else { p->next=w->sources; w->sources=p; *out=p; }
    return r;
}
static int start(struct md_inode_store *s) {
    if (s->watches) return 0;
    struct md_inode_watches *w=calloc(1,sizeof(*w));
    if (!w) return -ENOMEM;
    w->epoll=epoll_create1(EPOLL_CLOEXEC);
    if (w->epoll<0) { int r=-errno; free(w); return r; }
    s->watches=w;
    struct watch_source *source;
    int r=source_get(s,s,&source);
    if (r) md_inode_watch_close(s);
    return r;
}
int md_inode_watch_create(struct md_inode_store *s, int flags) {
    if (flags & ~(IN_NONBLOCK | IN_CLOEXEC)) return -EINVAL;
    int r = md_inode_watch_pump(s);
    if (!r) r = start(s);
    if (r) return r;
    struct md_inode_watches *w = s->watches;
    struct instance *i = calloc(1, sizeof(*i));
    if (!i) { if (!w->instances) md_inode_watch_close(s); return -ENOMEM; }
    int fd = -1;
    r = md_user_charge_acquire(s->identity ? s->identity->user_namespace : NULL,
        s->identity ? s->identity->uid.real : 0,MD_USER_INOTIFY_INSTANCES,&i->charge);
    if (r==-ENOSPC) r=-EMFILE;
    if (!r) r = md_watch_queue_create(md_inode_watch_queue_limit(), flags, &i->queue, &fd);
    struct stat st;
    if (!r && fstat(fd, &st)) r = -errno;
    if (!r) r = epoll_add(w->epoll, md_watch_queue_lifetime(i->queue), 0);
    if (r) {
        if (fd >= 0) close(fd);
        md_watch_queue_destroy(i->queue);
        md_user_charge_release(i->charge,MD_USER_INOTIFY_INSTANCES); free(i);
        if (!w->instances) md_inode_watch_close(s);
        return r;
    }
    i->device = st.st_dev; i->inode = st.st_ino;
    i->next = w->instances; w->instances = i;
    return fd;
}
static int remove_subscription(struct md_inode_watches *w, struct instance *i, struct subscription **link) {
    struct subscription *p = *link;
    int r = md_watch_queue_emit(i->queue, p->wd, IN_IGNORED, 0, NULL);
    unlink_subscription(w,link);
    return r == -EPIPE ? 0 : r;
}
static int publish(struct md_inode_watches *w, struct instance *i, struct subscription **link,
        unsigned mask, unsigned cookie, const char *name) {
    struct subscription *p = *link;
    int r = 0, delivered = !!((p->mask & mask & IN_ALL_EVENTS) || (mask & IN_UNMOUNT));
    if (delivered) r = md_watch_queue_emit(i->queue, p->wd, mask, cookie, name);
    if (mask & (IN_DELETE_SELF | IN_IGNORED | IN_UNMOUNT) || (delivered && (p->mask & IN_ONESHOT))) {
        int removed = remove_subscription(w, i, link);
        return r ? r : removed;
    }
    return r == -EPIPE ? 0 : r;
}
static int overflow(struct md_inode_watches *w,struct watch_source *source,int logical) {
    for (struct instance *i = w->instances; i; i = i->next) {
        struct subscription *p=i->subscriptions;
        while (p && (p->source!=source || (logical && !*p->object))) p=p->next;
        if (!p) continue;
        int r = md_watch_queue_emit(i->queue, -1, IN_Q_OVERFLOW, 0, NULL);
        if (r && r != -EPIPE) return r;
    }
    return 0;
}
static int journal(struct md_inode_watches *w, struct watch_source *source,int64_t through) {
    struct md_inode_store *s=source->store;
    sqlite3_stmt *q = NULL;
    int r = mdi_query_acquire(s, MDI_EVENT_SCAN, &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, source->cursor));
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc != SQLITE_ROW) { r = mdi_sql_error(rc); break; }
        int64_t sequence = sqlite3_column_int64(q, 0);
        if (sequence>through) break;
        if (sequence != source->cursor + 1) r = overflow(w,source,1);
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
                if (p->source==source && sequence > p->after && !strcmp(p->object, *parent ? parent : object))
                    r = publish(w, i, link, mask, cookie, *parent ? name : NULL);
                if (*link == p) link = &p->next;
            }
        }
        source->cursor = sequence;
    }
    return mdi_query_release(q, r);
}
static int native_event(struct md_inode_watches *w, struct watch_source *backend, const struct inotify_event *event) {
    struct md_inode_store *s=backend->store;
    if (event->mask & IN_Q_OVERFLOW) { backend->publishing=0; backend->birth_cookie=0; return overflow(w,backend,0); }
    if (event->wd==backend->control) {
        if (event->len && !strcmp(event->name,"publication-v1.begin")) backend->publishing=1;
        if (event->len && !strcmp(event->name,"publication-v1.end")) backend->publishing=0;
        if (backend->logical_subscriptions && event->len && !strncmp(event->name,"events.",7)) {
            char *end; errno=0;
            long long sequence=strtoll(event->name+7,&end,10);
            if (!errno && !*end && sequence>0) return journal(w,backend,sequence);
        }
        return 0;
    }
    if (event->wd == backend->database) return 0;
    if (event->wd==backend->objects && (event->mask&IN_MOVED_FROM)) {
        backend->birth_cookie=event->cookie;
        return 0;
    }
    int birth=(event->mask&IN_MOVED_TO) && event->cookie && event->cookie==backend->birth_cookie;
    if (event->mask&(IN_MOVED_FROM|IN_MOVED_TO)) backend->birth_cookie=0;
    int r = 0;
    int source = -1;
    for (unsigned i = 0; i < MDI_SOURCES; ++i) if (event->wd == backend->sources[i]) { source = (int)i; break; }
    if (source >= 0) {
        unsigned mask=event->mask;
        if (mask&IN_MOVED_TO) {
            if (!birth || backend->publishing) return 0;
            mask=IN_OPEN;
        }
        if (!backend->logical_subscriptions) return 0;
        if (!event->len || strlen(event->name) != 32) return 0;
        sqlite3_stmt *object = NULL;
        r = mdi_query_acquire(s, MDI_BACKING_OBJECT, &object);
        if (!r) r = mdi_bind_id(object, 1, event->name);
        if (!r) r = mdi_sql_error(sqlite3_bind_int(object, 2, source));
        char id[33] = {0};
        int directory=0;
        if (!r) {
            int rc = mdi_step(s, object);
            if (rc == SQLITE_ROW && sqlite3_column_bytes(object, 0) == 32) {
                memcpy(id, sqlite3_column_text(object, 0), 32);
                directory=sqlite3_column_int(object,1)==S_IFDIR;
            }
            else if (rc != SQLITE_DONE) r = mdi_sql_failure(rc);
        }
        r = mdi_query_release(object, r);
        if (r || !*id) return r;
        /* Directory attributes are published by the logical mutation. Native
         * timestamps also change for child namespace operations, with no
         * corresponding parent MODIFY/ATTRIB notification in Linux. */
        if (directory) mask&=~(IN_MODIFY|IN_ATTRIB);
        if (!mask || (mask==IN_ISDIR)) return 0;
        if ((event->mask&IN_CREATE) || birth) {
            /* Allocation opens its backing before catalogue commit. Publish
             * the committed birth at this position, before that native OPEN,
             * without draining later mutations ahead of native names. */
            sqlite3_stmt *query=NULL;
            r=mdi_query_acquire(s,MDI_EVENT_BIRTH,&query);
            if (!r) r=mdi_bind_id(query,1,id);
            if (!r) r=mdi_sql_error(sqlite3_bind_int64(query,2,backend->cursor));
            int64_t sequence=0;
            if (!r) {
                int next=mdi_step(s,query);
                if (next==SQLITE_ROW) sequence=sqlite3_column_int64(query,0);
                else if (next!=SQLITE_DONE) r=mdi_sql_failure(next);
            }
            r=mdi_query_release(query,r);
            if (!r && sequence) r=journal(w,backend,sequence);
            if (r || !birth) return r;
        }
        /* Virtual subscriptions follow logical objects across copy-up. Watching
         * the retained backing directory observes the first write even before
         * another namespace owner processes the publication commit. */
        for (struct instance *i = w->instances; i && !r; i = i->next) {
            struct subscription **link = &i->subscriptions;
            while (*link && !r) {
                struct subscription *p = *link;
                if (p->source==backend && !strcmp(p->object, id)) r = publish(w, i, link, mask, 0, NULL);
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
                    if (p->source==backend && !strcmp(p->object, parent)) r = publish(w, i, link, mask, 0, name);
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
            if (p->source==backend && p->native == event->wd) {
                /* The gate serializes namespace writers, not retained-FD IO.
                 * Publication hides its physical move but never guest data IO. */
                unsigned mask=event->mask;
                if (*p->object) {
                    if (!event->len) mask=0;
                    else if (backend->publishing) mask&=~(IN_CREATE|IN_DELETE|IN_MOVED_FROM|IN_MOVED_TO);
                }
                if (mask && birth && *p->object) {
                    r=publish(w,i,link,IN_CREATE,0,event->name);
                    if (!r && *link==p) r=publish(w,i,link,IN_OPEN,0,event->name);
                } else if (mask) r=publish(w,i,link,mask,event->cookie,event->len ? event->name : NULL);
            }
            if (*link == p) link = &p->next;
        }
    }
    return r;
}
static int drain_native(struct md_inode_watches *w, struct watch_source *source, size_t budget) {
    union { struct inotify_event align; char bytes[65536]; } buffer;
    int r = 0;
    while (budget && !r) {
        ssize_t size = read(source->native, buffer.bytes, sizeof(buffer.bytes));
        if (size < 0) { if (errno != EAGAIN && errno != EINTR) r = -errno; break; }
        if (!size) { r = -EIO; break; }
        for (size_t pos = 0; pos < (size_t)size && !r;) {
            struct inotify_event *e = (struct inotify_event *)(buffer.bytes + pos);
            r = native_event(w, source, e);
            pos += sizeof(*e) + e->len;
        }
        budget = (size_t)size >= budget ? 0 : budget - (size_t)size;
    }
    return r;
}
int md_inode_watch_pump(struct md_inode_store *s) {
    struct md_inode_watches *w = s->watches;
    if (!w) return 0;
    /* Bounded dispatch batch, independent of the configurable user quota. */
    struct epoll_event events[128];
    int n = epoll_wait(w->epoll, events, 128, 0);
    if (n < 0) return errno == EINTR ? 0 : -errno;
    for (int j = 0; j < n; ++j) if (events[j].events & (EPOLLERR | EPOLLHUP)) {
        struct instance **link = &w->instances;
        while (*link && md_watch_queue_lifetime((*link)->queue) != events[j].data.fd) link = &(*link)->next;
        if (*link) { struct instance *i = *link; *link = i->next; free_instance(w, i); }
    }
    if (!w->instances) { md_inode_watch_close(s); return 0; }
    for (struct watch_source *p=w->sources;p;p=p->next) {
        /* WAL changes wake the same native source even if a writer dies before
         * publishing its marker. Idle sources need no catalogue snapshot. */
        if (!source_ready(events,n,p->native)) continue;
        if (!p->logical_subscriptions) {
            int r=drain_native(w,p,4*65536);
            if (r) return r;
            continue;
        }
        int r=mdi_begin(p->store,0);
        if (r) return r;
        /* Level-triggered readiness retains the remainder; transactions never
         * span another backing store or an unbounded native writer burst. */
        r=drain_native(w,p,4*65536);
        int backlog=0;
        if (!r && ioctl(p->native,FIONREAD,&backlog)) r=-errno;
        /* An owner may die after COMMIT but before its stream marker. Only
         * catch up once earlier queued native events have been consumed. */
        if (!r && !backlog) r=journal(w,p,INT64_MAX);
        r=mdi_finish(p->store,r);
        if (r) return r;
    }
    for (struct provider_source *p=w->providers;p;p=p->next) {
        if (!source_ready(events,n,md_watch_listener_descriptor(p->listener))) continue;
        struct md_watch_event event;
        for (unsigned budget=0;budget<PROVIDER_EVENT_BUDGET && md_watch_listener_next(p->listener,&event);++budget) {
            if (event.mask&IN_Q_OVERFLOW) {
                for (struct instance *i=w->instances;i;i=i->next) {
                    struct subscription *s=i->subscriptions;
                    while (s && s->provider!=p) s=s->next;
                    if (s) { int error=md_watch_queue_emit(i->queue,-1,IN_Q_OVERFLOW,0,NULL);
                        if (error) return error; }
                }
                continue;
            }
            for (struct instance *i=w->instances;i;i=i->next) {
                struct subscription **link=&i->subscriptions;
                while (*link) {
                    struct subscription *sub=*link;
                    if (sub->provider==p && sub->provider_after<event.sequence
                            && (sub->provider_object==event.object || (event.mask&IN_UNMOUNT))
                            && !(event.unlinked && (sub->mask&IN_EXCL_UNLINK))) {
                        int error=publish(w,i,link,event.mask,event.cookie,*event.name ? event.name : NULL);
                        if (error) return error;
                    }
                    if (*link==sub) link=&sub->next;
                }
            }
        }
    }
    source_collect(w);
    return 0;
}
int md_inode_watch_pollfd(struct md_inode_store *s) { return s->watches ? s->watches->epoll : -1; }
static int add(struct md_inode_store *owner, struct md_inode_store *selected,
        int fd, int object, unsigned mask) {
    if (!valid_mask(mask)) return -EINVAL;
    int r = md_inode_watch_pump(owner);
    if (r) return r;
    struct instance *i = find(owner, fd);
    if (!i) return -EINVAL;
    struct stat st;
    if (fstat(object, &st)) return -errno;
    if ((mask & IN_ONLYDIR) && !S_ISDIR(st.st_mode)) return -ENOTDIR;
    struct watch_source *source;
    r=source_get(owner,selected,&source);
    if (r) return r;
    struct md_inode_store *s=source->store;
    char identity[33] = {0};
    r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node watched;
    r = mdi_fd(s, object, &watched);
    const struct md_identity *previous=s->identity;
    s->identity=selected->identity;
    if (!r) r = mdi_permission(s, &watched, R_OK, 0);
    if (!r && watched.native && watched.attached) r=mdi_leaf_promote(s,&watched);
    s->identity=previous;
    if (!r && !watched.native) memcpy(identity, watched.id, sizeof(identity));
    r = mdi_finish(s, r);
    if (r && r != -EXDEV) return r;
    struct subscription *p = i->subscriptions;
    while (p && (p->source!=source || (*identity ? strcmp(p->object, identity) != 0
            : *p->object || p->device != st.st_dev || p->inode != st.st_ino))) p = p->next;
    if (p) {
        if (mask & IN_MASK_CREATE) return -EEXIST;
        unsigned previous=p->mask;
        p->mask = mask & IN_MASK_ADD ? p->mask | mask : mask;
        r=logical_source_refresh(owner->watches,source,NULL);
        if (r) { p->mask=previous; logical_source_refresh(owner->watches,source,NULL); return r; }
        return p->wd;
    }
    struct md_inode_watches *w = owner->watches;
    if (i->last_wd == INT_MAX) return -ENOSPC;
    p = calloc(1, sizeof(*p));
    if (!p) return -ENOMEM;
    r=md_user_charge_add(i->charge,MD_USER_INOTIFY_WATCHES);
    if (r) { free(p); return r; }
    p->charge=i->charge;
    r = mdi_begin(s, 0);
    if (r) { md_user_charge_release(p->charge,MD_USER_INOTIFY_WATCHES); free(p); return r; }
    struct mdi_node node;
    r = mdi_fd(s, object, &node);
    if (!r && !node.native) memcpy(p->object, node.id, sizeof(p->object));
    else if (r == -EXDEV) r = 0;
    p->mask=mask;
    /* Registration excludes already queued native data, just as the journal
     * cursor excludes earlier commits. Snapshot the finite backlog so writers
     * cannot extend registration indefinitely. */
    int backlog = 0;
    if (!r && ioctl(source->native, FIONREAD, &backlog)) r = -errno;
    if (!r) r = drain_native(w, source, (size_t)backlog);
    if (!r) r=logical_source_refresh(w,source,p);
    if (!r) r = journal_end(s, &p->after);
    if (!r) {
        if (*p->object && node.kind!=S_IFDIR) {
            p->native = -1;
        } else {
            p->native = watch_path(source->native, object, "", IN_ALL_EVENTS | IN_MASK_ADD);
            if (p->native < 0) r = p->native;
        }
    }
    r = mdi_finish(s, r);
    if (r) {
        logical_source_refresh(w,source,NULL);
        md_user_charge_release(p->charge,MD_USER_INOTIFY_WATCHES); free(p); return r;
    }
    p->mask = mask; p->device = st.st_dev; p->inode = st.st_ino; p->source=source;
    return link_subscription(i,p);
}
int md_inode_watch_add_from(struct md_inode_store *owner, struct md_inode_store *selected,
        int fd, int object, unsigned mask) {
    int r=add(owner,selected,fd,object,mask);
    if (r<0 && owner->watches) source_collect(owner->watches);
    return r;
}
int md_inode_watch_add(struct md_inode_store *s, int fd, int object, unsigned mask) {
    return md_inode_watch_add_from(s,s,fd,object,mask);
}
int md_inode_watch_add_provider(struct md_inode_store *owner,int fd,struct md_watch_source *source,
        uint64_t object,unsigned mode,unsigned mask) {
    if (!source || !valid_mask(mask)) return -EINVAL;
    if ((mask&IN_ONLYDIR) && !S_ISDIR(mode)) return -ENOTDIR;
    int error=md_inode_watch_pump(owner);
    if (error) return error;
    struct instance *i=find(owner,fd);
    if (!i) return -EINVAL;
    struct md_inode_watches *w=owner->watches;
    struct provider_source *p=w->providers;
    while (p && p->source!=source) p=p->next;
    for (struct subscription *s=i->subscriptions;p && s;s=s->next) {
        if (s->provider!=p || s->provider_object!=object) continue;
        if (mask&IN_MASK_CREATE) return -EEXIST;
        s->mask=mask&IN_MASK_ADD ? s->mask|mask : mask;
        md_watch_listener_update(p->listener,s->interest,s->mask);
        return s->wd;
    }
    if (i->last_wd==INT_MAX) return -ENOSPC;
    struct subscription *sub=calloc(1,sizeof(*sub));
    if (!sub) return -ENOMEM;
    error=md_user_charge_add(i->charge,MD_USER_INOTIFY_WATCHES);
    if (error) { free(sub); return error; }
    sub->charge=i->charge;
    if (!p) {
        p=calloc(1,sizeof(*p));
        if (!p) { error=-ENOMEM; goto fail; }
        error=md_watch_source_subscribe(source,&p->listener);
        if (!error) error=epoll_add(w->epoll,md_watch_listener_descriptor(p->listener),EPOLLIN);
        if (error) { md_watch_source_unsubscribe(p->listener); free(p); goto fail; }
        p->source=source; p->next=w->providers; w->providers=p;
    }
    error=md_watch_listener_admit(p->listener,object,mask,&sub->interest,&sub->provider_after);
    if (error) { source_collect(w); goto fail; }
    sub->provider=p; sub->provider_object=object;
    sub->native=-1; sub->mask=mask;
    return link_subscription(i,sub);
fail:
    md_user_charge_release(sub->charge,MD_USER_INOTIFY_WATCHES); free(sub); return error;
}
int md_inode_watch_remove(struct md_inode_store *s, int fd, int wd) {
    int r = md_inode_watch_pump(s);
    if (r) return r;
    struct instance *i = find(s, fd);
    if (!i) return -EINVAL;
    struct subscription **link = &i->subscriptions;
    while (*link && (*link)->wd != wd) link = &(*link)->next;
    r=*link ? remove_subscription(s->watches, i, link) : -EINVAL;
    source_collect(s->watches);
    return r;
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
