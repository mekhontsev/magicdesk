#define _GNU_SOURCE
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <unistd.h>

struct fifo_pin { char object[33]; int fd; struct fifo_pin *next; };
struct mdi_fifo_owner { char id[33]; int lease; struct fifo_pin *pins; };

static int owner(struct md_inode_store *s) {
    if (s->fifos) return 0;
    struct mdi_fifo_owner *o = calloc(1, sizeof(*o));
    if (!o) return -ENOMEM;
    o->lease = -1;
    int r = mdi_random_id(o->id);
    char name[48]; snprintf(name, sizeof(name), "fifo-%s", o->id);
    if (!r && (o->lease = openat(s->root, name, O_CREAT | O_EXCL | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600)) < 0)
        r = -errno;
    if (!r && flock(o->lease, LOCK_EX | LOCK_NB)) r = -errno;
    if (r) { if (o->lease >= 0) { close(o->lease); unlinkat(s->root, name, 0); } free(o); }
    else s->fifos = o;
    return r;
}
/* A live kernel lock, not a PID alone, authorizes a persisted pin route. */
static int live(struct md_inode_store *s, const char *id) {
    if (!id || strlen(id) != 32 || strspn(id, "0123456789abcdef") != 32) return -EIO;
    if (s->fifos && !strcmp(id, s->fifos->id)) return 1;
    char name[48]; snprintf(name, sizeof(name), "fifo-%s", id);
    int fd = openat(s->root, name, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno == ENOENT ? 0 : -errno;
    int r = flock(fd, LOCK_EX | LOCK_NB) ? (errno == EWOULDBLOCK ? 1 : -errno) : 0;
    close(fd); return r;
}
int mdi_fifo_descriptor(struct md_inode_store *s, const struct stat *st, struct mdi_node *node) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT object,owner FROM fifo_pins WHERE device=?1 AND inode=?2", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, (sqlite3_int64)st->st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st->st_ino));
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc != SQLITE_ROW) { r = rc == SQLITE_DONE ? -EXDEV : mdi_sql_failure(rc); break; }
        int active = live(s, (const char *)sqlite3_column_text(q, 1));
        if (active < 0) { r = active; break; }
        if (active) { r = mdi_node(s, (const char *)sqlite3_column_text(q, 0), node); break; }
    }
    sqlite3_finalize(q); return r;
}
static int acquire_pin(struct md_inode_store *s, const struct mdi_node *node) {
    int cached = -1;
    for (struct fifo_pin *p = s->fifos->pins; p; p = p->next)
        if (!strcmp(p->object, node->id)) { cached = p->fd; break; }
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT owner,pid,descriptor,device,inode FROM fifo_pins WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    int pin = cached;
    while (!r && pin < 0) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        int active = live(s, (const char *)sqlite3_column_text(q, 0));
        if (active < 0) { r = active; break; }
        if (!active) continue;
        char path[64]; snprintf(path, sizeof(path), "/proc/%d/fd/%d", sqlite3_column_int(q, 1), sqlite3_column_int(q, 2));
        pin = open(path, O_PATH | O_CLOEXEC);
        if (pin < 0) { if (errno == ENOENT) continue; r = -errno; break; }
        struct stat st;
        if (fstat(pin, &st)) r = -errno;
        if (!r && (!S_ISFIFO(st.st_mode) || st.st_dev != (dev_t)sqlite3_column_int64(q, 3)
                || st.st_ino != (ino_t)sqlite3_column_int64(q, 4))) r = -ESTALE;
        break;
    }
    sqlite3_finalize(q); q = NULL;
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
        "ON CONFLICT(object,owner) DO UPDATE SET pid=excluded.pid,descriptor=excluded.descriptor,"
        "device=excluded.device,inode=excluded.inode", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
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
    memcpy(p->object, node->id, sizeof(p->object)); p->fd = pin;
    p->next = s->fifos->pins; s->fifos->pins = p;
    return pin;
}
int mdi_fifo_prepare(struct md_inode_store *s, int original, struct md_open_completion *completion) {
    int r = mdi_begin(s, 1), control = -1, pin = -1;
    if (r) { close(original); return r; }
    struct mdi_node node;
    r = mdi_fd(s, original, &node);
    if (!r && node.kind != S_IFIFO) r = -EINVAL;
    if (!r) r = owner(s);
    if (!r) { pin = acquire_pin(s, &node); if (pin < 0) r = pin; }
    char state[48]; snprintf(state, sizeof(state), "fifo-state-%s", r ? "" : node.id);
    /* Rendezvous bookkeeping is outside watched backing objects. It must not
     * masquerade as guest data writes/open/close notifications. */
    if (!r && (control = openat(s->root, state, O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0) r = -errno;
    struct stat st;
    if (!r && fstat(control, &st)) r = -errno;
    if (!r && !st.st_size && ftruncate(control, 8)) r = -errno;
    if (!r && st.st_size && st.st_size != 8) r = -EIO;
    int out = -1;
    if (!r && (out = fcntl(pin, F_DUPFD_CLOEXEC, 0)) < 0) r = -errno;
    r = mdi_commit(s, r);
    close(original);
    if (r) { if (control >= 0) close(control); if (out >= 0) close(out); return r; }
    *completion = (struct md_open_completion){MD_OPEN_PIPE, control};
    return out;
}
void mdi_fifo_close(struct md_inode_store *s) {
    struct mdi_fifo_owner *o = s->fifos;
    if (!o) return;
    if (!mdi_begin(s, 1)) {
        sqlite3_stmt *q = NULL;
        int r = mdi_prepare(s, "DELETE FROM fifo_pins WHERE owner=?1", &q);
        if (!r) r = mdi_bind_id(q, 1, o->id);
        if (!r) r = mdi_sql_error(mdi_step(s, q));
        sqlite3_finalize(q); mdi_commit(s, r);
    }
    while (o->pins) { struct fifo_pin *p = o->pins; o->pins = p->next; close(p->fd); free(p); }
    char name[48]; snprintf(name, sizeof(name), "fifo-%s", o->id);
    unlinkat(s->root, name, 0); close(o->lease);
    free(o); s->fifos = NULL;
}
