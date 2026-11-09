#define _GNU_SOURCE
#include "pty_events.h"
#include "fs_anchor.h"
#include "inode_watch.h"
#include "watch_source.h"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

enum { BUCKETS=64, NATIVE=1, TRACKED=2 };
struct terminal {
    struct terminal *next;
    dev_t device;
    ino_t inode;
    uint64_t id;
    unsigned references;
    int pin,retired,uncertain;
};
struct md_pty_events {
    atomic_uint references;
    pthread_mutex_t lock;
    struct terminal *buckets[BUCKETS];
    struct md_watch_source *source;
    uint64_t next_id;
    int mode;
};
struct description {
    struct md_fd_object object;
    struct md_fs_anchor *origin;
    struct md_pty_events *owner;
    struct terminal *terminal;
    int master,published,flags;
};
static unsigned bucket(dev_t device,ino_t inode) { return (device^inode)%BUCKETS; }
static struct terminal *find(struct md_pty_events *p,const struct stat *st) {
    for (struct terminal *t=p->buckets[bucket(st->st_dev,st->st_ino)];t;t=t->next)
        if (t->device==st->st_dev && t->inode==st->st_ino && !t->retired) return t;
    return NULL;
}
void md_pty_events_release(struct md_pty_events *p) {
    if (!p || atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)!=1) return;
    md_watch_source_emit(p->source,0,IN_UNMOUNT,0,NULL,0);
    md_watch_source_release(p->source);
    pthread_mutex_destroy(&p->lock); free(p);
}
static void emit(struct description *d,unsigned event) {
    if (!d->terminal->uncertain)
        md_watch_source_emit(d->owner->source,d->terminal->id,event,0,NULL,0);
}
static void destroy(struct md_fd_object *object) {
    struct description *d=(void *)object;
    struct md_pty_events *p=d->owner;
    struct terminal *t=d->terminal;
    pthread_mutex_lock(&p->lock);
    if (d->master) {
        t->retired=1;
        emit(d,IN_DELETE_SELF);
    } else if (d->published) emit(d,(d->flags&O_ACCMODE)==O_RDONLY ? IN_CLOSE_NOWRITE : IN_CLOSE_WRITE);
    if (!--t->references) {
        struct terminal **link=&p->buckets[bucket(t->device,t->inode)];
        while (*link!=t) link=&(*link)->next;
        *link=t->next; close(t->pin); free(t);
    }
    pthread_mutex_unlock(&p->lock);
    md_fs_anchor_release(d->origin); free(d); md_pty_events_release(p);
}
static void published(struct md_fd_object *object) {
    struct description *d=(void *)object;
    pthread_mutex_lock(&d->owner->lock);
    if (!d->published) {
        d->published=1;
        if (!d->master) emit(d,IN_OPEN);
    }
    pthread_mutex_unlock(&d->owner->lock);
}
static struct md_fs_anchor *origin(struct md_fd_object *object) {
    return ((struct description *)object)->origin;
}
static void lost(struct md_fd_object *object) {
    struct description *d=(void *)object;
    pthread_mutex_lock(&d->owner->lock);
    if (!d->terminal->uncertain) {
        /* An untracked alias cannot establish final-close semantics. Retire
         * the watch, not the terminal; never invent a close or a deletion. */
        emit(d,IN_IGNORED); d->terminal->uncertain=1;
    }
    pthread_mutex_unlock(&d->owner->lock);
}
static int export_begin(struct md_fd_object *object) { (void)object; return 0; }
static void export_end(struct md_fd_object *object,int escaped) { if (escaped) lost(object); }
static const struct md_fd_object_type type={.destroy=destroy,.origin=origin,.published=published,.portable_origin=1,
    .alias_lost=lost,.export_begin=export_begin,.export_end=export_end};

static int admit(struct md_pty_events *p,int pin) {
    if (p->mode) return 0;
    int watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK);
    if (watch<0) return -errno;
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",pin);
    int result=inotify_add_watch(watch,path,IN_ALL_EVENTS);
    int error=result<0 ? errno : 0; close(watch);
    if (!error) { p->mode=NATIVE; return 0; }
    if (error!=EACCES && error!=EPERM) return -error;
    int r=md_watch_source_new(&p->source);
    if (!r) p->mode=TRACKED;
    return r;
}
int md_pty_events_open(struct md_pty_events **owner,int fd,struct md_fs_anchor *anchor,
        int flags,int caller_open,struct md_fd_object **out) {
    if (!anchor || md_fs_anchor_kind(anchor)!=S_IFCHR || (flags&O_PATH) || *out) return 0;
    if (*owner && (*owner)->mode==NATIVE) return 0;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    int master=major(st.st_rdev)==5 && minor(st.st_rdev)==2;
    if (!master && (major(st.st_rdev)<136 || major(st.st_rdev)>143)) return 0;
    if (master && caller_open) return 0;
    int pin=-1;
    if (master) {
        unsigned number;
        if (ioctl(fd,TIOCGPTN,&number)) return -errno;
        char path[64]; snprintf(path,sizeof(path),"/dev/pts/%u",number);
        pin=open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC);
        if (pin<0) return -errno;
        if (fstat(pin,&st)) { int error=-errno; close(pin); return error; }
    }
    if (!*owner && master) {
        struct md_pty_events *p=calloc(1,sizeof(*p));
        if (!p) { close(pin); return -ENOMEM; }
        atomic_init(&p->references,1);
        int error=pthread_mutex_init(&p->lock,NULL);
        if (error) { close(pin); free(p); return -error; }
        *owner=p;
    }
    struct md_pty_events *p=*owner;
    if (!p) return 0;
    int error=master ? admit(p,pin) : 0;
    if (error || p->mode!=TRACKED) { if (pin>=0) close(pin); return error; }
    struct description *d=calloc(1,sizeof(*d));
    if (!d) { if (pin>=0) close(pin); return -ENOMEM; }
    pthread_mutex_lock(&p->lock);
    struct terminal *t=find(p,&st);
    if (master && !t) {
        t=calloc(1,sizeof(*t));
        if (!t) error=-ENOMEM;
        else {
            unsigned b=bucket(st.st_dev,st.st_ino);
            *t=(struct terminal){.next=p->buckets[b],.device=st.st_dev,.inode=st.st_ino,
                .id=++p->next_id,.pin=pin};
            p->buckets[b]=t; pin=-1;
        }
    }
    if (t && !error) {
        ++t->references;
        md_fd_object_init(&d->object,&type);
        d->origin=md_fs_anchor_retain(anchor); d->owner=p; d->terminal=t;
        d->master=master; d->flags=flags;
        atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed);
        *out=&d->object;
    } else free(d);
    pthread_mutex_unlock(&p->lock);
    if (pin>=0) close(pin);
    return error;
}
int md_pty_events_watch(struct md_pty_events *p,struct md_inode_store *store,
        int instance,int object,unsigned mask) {
    if (!p || p->mode!=TRACKED) return -EOPNOTSUPP;
    if (mask&IN_ALL_EVENTS&~(IN_OPEN|IN_CLOSE|IN_DELETE_SELF)) return -EOPNOTSUPP;
    struct stat st;
    if (fstat(object,&st)) return -errno;
    pthread_mutex_lock(&p->lock);
    struct terminal *t=find(p,&st);
    int r=t && !t->uncertain
        ? md_inode_watch_add_provider(store,instance,p->source,t->id,st.st_mode,mask) : -EOPNOTSUPP;
    pthread_mutex_unlock(&p->lock);
    return r;
}
