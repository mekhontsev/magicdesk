#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_fifo.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <unistd.h>

struct fifo_pin { char channel[33]; int fd; struct fifo_pin *next; };
struct fifo_source { int source; struct md_inode_store *store; struct mdi_fifo_owner *custody; struct fifo_source *next; };
struct mdi_fifo_owner { atomic_uint refs; char id[33]; int lease, detached; struct md_inode_lifetime *lifetime;
    struct fifo_pin *pins; struct fifo_source *sources; };

static int owner(struct md_inode_store *s) {
    if (s->fifos) return 0;
    struct mdi_fifo_owner *o = calloc(1, sizeof(*o));
    if (!o) return -ENOMEM;
    atomic_init(&o->refs,1);
    o->lease = -1;
    int r = mdi_random_id(o->id);
    char name[48]; snprintf(name, sizeof(name), "fifo-%s", o->id);
    if (!r && (o->lease = openat(s->lifetime->root, name, O_CREAT | O_EXCL | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600)) < 0)
        r = -errno;
    if (!r && flock(o->lease, LOCK_EX | LOCK_NB)) r = -errno;
    if (r) { if (o->lease >= 0) { close(o->lease); unlinkat(s->lifetime->root, name, 0); } free(o); }
    /* Attached custody borrows its owning store. Retaining it here would make
     * an idle storage cache keep its own mounted view alive indefinitely. */
    else { o->lifetime=s->lifetime; s->fifos = o; }
    return r;
}
/* A live kernel lock, not a PID alone, authorizes a persisted pin route. */
static int live(struct md_inode_store *s, const char *id) {
    if (!id || strlen(id) != 32 || strspn(id, "0123456789abcdef") != 32) return -EIO;
    if (s->fifos && !strcmp(id, s->fifos->id)) return 1;
    char name[48]; snprintf(name, sizeof(name), "fifo-%s", id);
    int fd = openat(s->lifetime->root, name, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -errno;
    int r = flock(fd, LOCK_EX | LOCK_NB) ? (errno == EWOULDBLOCK ? 1 : -errno) : 0;
    close(fd); return r;
}
int mdi_fifo_descriptor(struct md_inode_store *s, const struct stat *st, struct mdi_node *node) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT c.object,p.owner FROM fifo_pins p JOIN inode_channels c ON c.channel=p.channel "
        "WHERE p.device=?1 AND p.inode=?2 AND c.scope=?3", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, (sqlite3_int64)st->st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st->st_ino));
    if (!r) r=mdi_bind_id(q,3,s->lifetime->scope);
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc != SQLITE_ROW) { r = rc == SQLITE_DONE ? -EXDEV : mdi_sql_failure(rc); break; }
        int active = live(s, (const char *)sqlite3_column_text(q, 1));
        if (active < 0) { r = active; break; }
        if (active) { r = mdi_node(s, (const char *)sqlite3_column_text(q, 0), node); break; }
    }
    sqlite3_finalize(q); return r;
}
int md_inode_fifo_channel(struct md_inode_store *s,int fd,char channel[33]) {
    struct stat st;
    memset(channel,0,33);
    if (fstat(fd,&st)) return -errno;
    if (!S_ISFIFO(st.st_mode)) return 0;
    int r=mdi_begin(s,0);
    if (r) return r;
    struct mdi_node node;
    r=mdi_fifo_descriptor(s,&st,&node);
    if (!r) r=mdi_channel(s,&node,channel,NULL);
    if (r==-EXDEV) r=0;
    return mdi_finish(s,r);
}
struct stale_pin { char owner[33]; struct stale_pin *next; };
static int acquire_pin(struct md_inode_store *s, const char *channel, int seed) {
    int cached = -1;
    for (struct fifo_pin *p = s->fifos->pins; p; p = p->next)
        if (!strcmp(p->channel, channel)) { cached = p->fd; break; }
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT owner,pid,descriptor,device,inode FROM fifo_pins WHERE channel=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, channel);
    int pin = cached;
    if (!r && pin<0 && seed>=0 && (pin=fcntl(seed,F_DUPFD_CLOEXEC,0))<0) r=-errno;
    struct stat expected;
    if (!r && seed>=0 && fstat(seed,&expected)) r=-errno;
    if (!r && seed>=0 && pin>=0) {
        struct stat actual;
        if (fstat(pin,&actual)) r=-errno;
        else if (!S_ISFIFO(actual.st_mode) || actual.st_dev!=expected.st_dev || actual.st_ino!=expected.st_ino) r=-ESTALE;
    }
    struct stale_pin *stale=NULL;
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        const char *id=(const char *)sqlite3_column_text(q,0);
        int active = live(s, id);
        if (active < 0) { r = active; break; }
        if (!active) {
            struct stale_pin *p=calloc(1,sizeof(*p));
            if (!p) { r=-ENOMEM; break; }
            memcpy(p->owner,id,33); p->next=stale; stale=p;
            continue;
        }
        if (seed>=0 && (expected.st_dev!=(dev_t)sqlite3_column_int64(q,3)
                || expected.st_ino!=(ino_t)sqlite3_column_int64(q,4))) { r=-ESTALE; break; }
        if (pin>=0) continue;
        char path[64]; snprintf(path, sizeof(path), "/proc/%d/fd/%d", sqlite3_column_int(q, 1), sqlite3_column_int(q, 2));
        pin = open(path, O_PATH | O_CLOEXEC);
        if (pin < 0) { if (errno == ENOENT) continue; r = -errno; break; }
        struct stat st;
        if (fstat(pin, &st)) r = -errno;
        if (!r && (!S_ISFIFO(st.st_mode) || st.st_dev != (dev_t)sqlite3_column_int64(q, 3)
                || st.st_ino != (ino_t)sqlite3_column_int64(q, 4))) r = -ESTALE;
    }
    sqlite3_finalize(q); q = NULL;
    while (stale) {
        struct stale_pin *p=stale; stale=p->next;
        if (!r) r=mdi_prepare(s,"DELETE FROM fifo_pins WHERE channel=?1 AND owner=?2",&q);
        if (!r) r=mdi_bind_id(q,1,channel);
        if (!r) r=mdi_bind_id(q,2,p->owner);
        if (!r) r=mdi_sql_error(mdi_step(s,q));
        sqlite3_finalize(q); q=NULL; free(p);
    }
    if (!r && pin < 0) {
        int ends[2];
        if (pipe2(ends, O_CLOEXEC)) r = -errno;
        else {
            char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", ends[0]);
            pin = open(path, O_PATH | O_CLOEXEC);
            if (pin < 0) r = -errno;
            close(ends[0]); close(ends[1]);
        }
    }
    struct fifo_pin *p = r || cached >= 0 ? NULL : calloc(1, sizeof(*p));
    if (!r && cached < 0 && !p) r = -ENOMEM;
    struct stat st;
    if (!r && fstat(pin, &st)) r = -errno;
    if (!r) r = mdi_prepare(s, "INSERT INTO fifo_pins VALUES(?1,?2,?3,?4,?5,?6) "
        "ON CONFLICT(channel,owner) DO UPDATE SET pid=excluded.pid,descriptor=excluded.descriptor,"
        "device=excluded.device,inode=excluded.inode", &q);
    if (!r) r = mdi_bind_id(q, 1, channel);
    if (!r) r = mdi_bind_id(q, 2, s->fifos->id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 3, getpid()));
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 4, pin));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 5, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 6, (sqlite3_int64)st.st_ino));
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    sqlite3_finalize(q);
    if (r) { if (pin >= 0 && cached < 0) close(pin); free(p); return r; }
    if (cached >= 0) return pin;
    /* Retain even across an uncertain commit. No read/write endpoint is held,
     * so the kernel alone determines EOF, SIGPIPE and data lifetime. */
    memcpy(p->channel, channel, sizeof(p->channel)); p->fd = pin;
    p->next = s->fifos->pins; s->fifos->pins = p;
    return pin;
}
static int source_store(struct md_inode_store *s,int source,struct md_inode_store **out) {
    if (!source) { *out=s; return 0; }
    for (struct fifo_source *p=s->fifos->sources;p;p=p->next)
        if (p->source==source) { *out=p->store; return 0; }
    int root=openat(s->lifetime->source_leases[source],".",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (root<0) return -errno;
    struct stat st;
    int r=fstat(root,&st) ? -errno : 0;
    if (!r && flock(root,LOCK_SH|LOCK_NB)) r=errno==EWOULDBLOCK ? -EBUSY : -errno;
    struct md_inode_lifetime *life=r ? NULL : mdi_lifetime_new(root,&st);
    if (!r && !life) r=-ENOMEM;
    struct fifo_source *p=r ? NULL : calloc(1,sizeof(*p));
    if (!r && !p) r=-ENOMEM;
    if (!r) r=md_inode_store_reconnect(life,&p->store);
    if (life) md_inode_lifetime_release(life); else close(root);
    if (r) { free(p); return r; }
    p->source=source; p->next=s->fifos->sources; s->fifos->sources=p;
    *out=p->store; return 0;
}
static int retain_channel(struct md_inode_store *s,const char *channel,int source,int seed) {
    int r=owner(s),pin=seed;
    struct md_inode_store *canonical=NULL;
    if (!r) r=source_store(s,source,&canonical);
    /* Never nest store transactions: projected names and canonical channel
     * custody have independent metadata gates. */
    if (!r && canonical!=s) {
        r=mdi_runtime_begin(canonical);
        if (!r) {
            r=owner(canonical);
            if (!r) { pin=acquire_pin(canonical,channel,seed); if (pin<0) r=pin; }
            r=mdi_commit(canonical,r);
        }
    }
    if (!r) {
        r=mdi_runtime_begin(s);
        if (!r) { pin=acquire_pin(s,channel,pin); r=mdi_commit(s,pin<0 ? pin : 0); }
    }
    return r ? r : pin;
}
int mdi_fifo_prepare(struct md_inode_store *s, int original, struct md_open_completion *completion) {
    int r = mdi_begin(s, 0), control = -1, pin = -1;
    if (r) { close(original); return r; }
    struct mdi_node node;
    r = mdi_fd(s, original, &node);
    if (!r && node.kind != S_IFIFO) r = -EINVAL;
    char channel[33]; int source=0;
    if (!r) r=mdi_channel(s,&node,channel,&source);
    r=mdi_finish(s,r);
    if (!r) { pin=retain_channel(s,channel,source,-1); if (pin<0) r=pin; }
    struct md_inode_store *canonical=NULL;
    if (!r) r=source_store(s,source,&canonical);
    char state[48]; snprintf(state, sizeof(state), "fifo-state-%s", r ? "" : channel);
    /* Rendezvous bookkeeping is outside watched backing objects. It must not
     * masquerade as guest data writes/open/close notifications. */
    if (!r && (control = openat(canonical->lifetime->root, state, O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0) r = -errno;
    struct stat st;
    if (!r && fstat(control, &st)) r = -errno;
    if (!r && !st.st_size && ftruncate(control, 8)) r = -errno;
    if (!r && st.st_size && st.st_size != 8) r = -EIO;
    int out = -1;
    if (!r && (out = fcntl(pin, F_DUPFD_CLOEXEC, 0)) < 0) r = -errno;
    close(original);
    if (r) { if (control >= 0) close(control); if (out >= 0) close(out); return r; }
    *completion = (struct md_open_completion){.kind=MD_OPEN_PIPE, .control=control};
    return out;
}
static struct mdi_fifo_owner *detach(struct md_inode_store *s) {
    struct mdi_fifo_owner *o=s->fifos;
    s->fifos=NULL;
    if (o) { md_inode_lifetime_retain(o->lifetime); o->detached=1; }
    if (o) for (struct fifo_source *p=o->sources;p;p=p->next) {
        p->custody=detach(p->store); md_inode_store_close(p->store); p->store=NULL;
    }
    return o;
}
struct mdi_fifo_owner *md_inode_fifo_retain(struct md_inode_store *s) {
    struct mdi_fifo_owner *o=s ? s->fifos : NULL;
    if (o) atomic_fetch_add_explicit(&o->refs,1,memory_order_relaxed);
    return o;
}
int md_inode_fifo_adopt(struct md_inode_lifetime *life,const char channel[33],int fd,
        struct mdi_fifo_owner **out) {
    if (!life || !out || *out || channel[32] || strspn(channel,"0123456789abcdef")!=32) return -EINVAL;
    struct md_inode_store *s=NULL;
    int r=md_inode_store_reconnect(life,&s),source=0;
    if (!r) {
        r=mdi_begin(s,0);
        if (!r) {
            sqlite3_stmt *q=NULL; struct mdi_node node;
            r=mdi_prepare(s,"SELECT object FROM inode_channels WHERE scope=?1 AND channel=?2",&q);
            if (!r) r=mdi_bind_id(q,1,life->scope);
            if (!r) r=mdi_bind_id(q,2,channel);
            if (!r) {
                int n=mdi_step(s,q);
                r=n==SQLITE_ROW ? mdi_node(s,(const char *)sqlite3_column_text(q,0),&node)
                    : n==SQLITE_DONE ? -ESTALE : mdi_sql_failure(n);
            }
            sqlite3_finalize(q);
            if (!r && node.kind!=S_IFIFO) r=-EPROTO;
            char actual[33];
            if (!r) r=mdi_channel(s,&node,actual,&source);
            r=mdi_finish(s,r);
        }
    }
    if (!r) { int pin=retain_channel(s,channel,source,fd); if (pin<0) r=pin; }
    if (!r) *out=detach(s);
    md_inode_store_close(s); return r;
}
void md_inode_fifo_release(struct mdi_fifo_owner *o) {
    if (!o || atomic_fetch_sub_explicit(&o->refs,1,memory_order_acq_rel)!=1) return;
    while (o->pins) { struct fifo_pin *p=o->pins; o->pins=p->next; close(p->fd); free(p); }
    while (o->sources) {
        struct fifo_source *p=o->sources; o->sources=p->next;
        md_inode_store_close(p->store); md_inode_fifo_release(p->custody); free(p);
    }
    char name[48]; snprintf(name,sizeof(name),"fifo-%s",o->id);
    unlinkat(o->lifetime->root,name,0); close(o->lease);
    if (o->detached) md_inode_lifetime_release(o->lifetime);
    free(o);
}
void mdi_fifo_close(struct md_inode_store *s) {
    struct mdi_fifo_owner *o = s->fifos;
    if (!o) return;
    /* Live descriptor origins retain channel custody, not this evictable
     * SQLite connection. Keep their leased routes until the final release. */
    if (atomic_load_explicit(&o->refs,memory_order_acquire)>1) {
        md_inode_fifo_release(detach(s)); return;
    }
    if (!mdi_runtime_begin(s)) {
        sqlite3_stmt *q = NULL;
        int r = mdi_prepare(s, "DELETE FROM fifo_pins WHERE owner=?1", &q);
        if (!r) r = mdi_bind_id(q, 1, o->id);
        if (!r) r = mdi_sql_error(mdi_step(s, q));
        sqlite3_finalize(q); mdi_commit(s, r);
    }
    md_inode_fifo_release(o); s->fifos = NULL;
}
