#define _GNU_SOURCE
#include "sysv_ipc_internal.h"
#include "interception.h"
#include "event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <unistd.h>

static uint64_t spaces;
struct md_ipc_undo *md_ipc_undo_new(void) {
    struct md_ipc_undo *u=calloc(1,sizeof(*u));
    if (u) { u->refs=1; u->id=++spaces; }
    return u;
}
struct md_ipc_undo *md_ipc_undo_fork(struct md_ipc_undo *u, int shared) {
    if (shared) { ++u->refs; return u; }
    return md_ipc_undo_new();
}
int md_ipc_undo_release(struct md_ipc *s, struct md_ipc_undo *u) {
    if (!u || --u->refs) return 0;
    int r=s && u->used ? md_ipc_begin(s) : 0;
    if (s && u->used && !r) r=md_ipc_finish(s,md_ipc_undo_apply(s,s->owner,u->id));
    free(u); return r;
}
static int unregister(struct md_ipc *s, struct md_ipc_pending *p) {
    if (!p->registered) return 0;
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"DELETE FROM waiters WHERE owner=?1 AND tid=?2",&q);
    if (!r) {
        sqlite3_bind_int64(q,1,s->owner); sqlite3_bind_int(q,2,p->tid); r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); if (!r) p->registered=0; return r;
}
static void free_pending(struct md_ipc_pending **pending) {
    struct md_ipc_pending *p=*pending;
    if (!p) return;
    if (p->watcher>=0) close(p->watcher);
    md_identity_release(&p->identity); free(p->input); free(p); *pending=NULL;
}
int md_ipc_cancel(struct md_ipc *s, struct md_ipc_pending **pending) {
    struct md_ipc_pending *p=*pending;
    if (!p) return 0;
    int r=0;
    if (p->registered) { r=md_ipc_begin(s); if (!r) r=md_ipc_finish(s,unregister(s,p)); }
    free_pending(pending); return r;
}
static int watcher(struct md_ipc *s, struct md_ipc_pending *p) {
    p->watcher=inotify_init1(IN_CLOEXEC|IN_NONBLOCK);
    if (p->watcher<0) return -errno;
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",s->dir);
    /* Events include committed metadata and closed owner leases. A killed
     * supervisor therefore wakes peers to reap SEM_UNDO without polling. */
    return inotify_add_watch(p->watcher,path,IN_MODIFY|IN_CLOSE_WRITE|IN_DELETE)<0 ? -errno : 0;
}
static int prepare(struct md_ipc_pending *p) {
    long nr=p->call.nr; const unsigned long *a=p->call.args;
    p->deadline=INT64_MAX; p->number=-1;
    if (nr==SYS_semop || nr==SYS_semtimedop) {
        if (!a[2]) return -EINVAL;
        if (a[2]>SEMOPM) return -E2BIG;
        p->size=a[2]*sizeof(struct sembuf); p->input=malloc(p->size);
        if (!p->input) return -ENOMEM;
        int r=md_ipc_memory(p,a[1],p->input,p->size,0);
        if (!r && nr==SYS_semtimedop && a[3]) {
            struct timespec time;
            r=md_ipc_memory(p,a[3],&time,sizeof(time),0);
            if (!r && (time.tv_sec<0 || time.tv_nsec<0 || time.tv_nsec>=1000000000)) r=-EINVAL;
            if (!r) {
                int64_t now=md_event_now();
                if (now<0) r=now;
                else if (time.tv_sec<=(INT64_MAX-now-time.tv_nsec)/1000000000LL)
                    p->deadline=now+time.tv_sec*1000000000LL+time.tv_nsec;
            }
        }
        return r;
    }
    if (nr==SYS_msgsnd) {
        if (a[2]>MD_MSGMAX) return -EINVAL;
        p->size=a[2]; p->input=malloc(p->size+sizeof(long)); if (!p->input) return -ENOMEM;
        int r=md_ipc_memory(p,a[1],p->input,p->size+sizeof(long),0);
        long type=0; if (!r) memcpy(&type,p->input,sizeof(type));
        return r ? r : type<=0 ? -EINVAL : 0;
    }
    if (nr==SYS_msgrcv && a[2]>SSIZE_MAX) return -EINVAL;
    return 0;
}
static int register_wait(struct md_ipc *s, struct md_ipc_pending *p) {
    if (p->number<0) return 0;
    sqlite3_stmt *q=NULL;
    int r=md_ipc_query(s,"INSERT INTO waiters VALUES(?1,?2,?3,?4,?5) "
        "ON CONFLICT(owner,tid) DO UPDATE SET n=excluded.n,zero=excluded.zero "
        "WHERE n!=excluded.n OR zero!=excluded.zero",&q);
    if (!r) {
        sqlite3_bind_int64(q,1,s->owner); sqlite3_bind_int(q,2,p->tid);
        sqlite3_bind_int(q,3,p->call.args[0]); sqlite3_bind_int(q,4,p->number); sqlite3_bind_int(q,5,p->zero);
        r=md_ipc_sql(sqlite3_step(q));
    }
    sqlite3_finalize(q); if (!r) p->registered=1; return r;
}
long md_ipc_call(struct md_ipc *s, struct md_ipc_pending **pending, struct md_ipc_undo *undo,
        const struct md_identity *identity, pid_t tid, pid_t tgid, struct md_ipc_transport *transport) {
    struct md_ipc_pending *p=*pending;
    int r=0;
    if (!p) {
        p=calloc(1,sizeof(*p)); if (!p) return -ENOMEM;
        *pending=p; p->watcher=-1; p->call=transport->call; p->identity=md_identity_copy(identity);
        p->transport=transport;
        p->tid=tid; p->pid=tgid; p->undo=undo->id;
        r=prepare(p);
        if (!r && (p->call.nr==SYS_semop || p->call.nr==SYS_semtimedop)) {
            const struct sembuf *ops=p->input;
            for (size_t i=0; i<p->size/sizeof(*ops); i++)
                if (ops[i].sem_flg&SEM_UNDO) undo->used=1;
        }
    }
    p->transport=transport;
    long result=r;
    for (; !r;) {
        if (p->watcher>=0) {
            char events[4096];
            while (read(p->watcher,events,sizeof(events))>0) {}
            if (errno!=EAGAIN) { result=-errno; break; }
        }
        r=md_ipc_begin(s); if (r) { result=r; break; }
        r=md_ipc_exec(s,"SAVEPOINT operation"); int before=s->changed;
        long nr=p->call.nr;
        result=r ? r : nr==SYS_semget || nr==SYS_semctl || nr==SYS_semop || nr==SYS_semtimedop
            ? md_ipc_sem(s,p) : md_ipc_msg(s,p);
        if (result<0) {
            r=md_ipc_exec(s,"ROLLBACK TO operation"); s->changed=before;
        }
        if (!r) r=md_ipc_exec(s,"RELEASE operation");
        if (result==MD_IPC_WAIT && p->deadline!=INT64_MAX) {
            int64_t now=md_event_now();
            if (now<0) result=now; else if (now>=p->deadline) result=-EAGAIN;
        }
        if (!r) r=result==MD_IPC_WAIT ? register_wait(s,p) : unregister(s,p);
        r=md_ipc_finish(s,r); if (r) { result=r; break; }
        if (result!=MD_IPC_WAIT) break;
        p->blocked=1;
        if (p->watcher>=0) return result;
        r=watcher(s,p); if (r) { result=r; break; }
        /* Subscribe before rechecking: a mutation between the failed attempt
         * and subscription must not be lost. No state-polling interval exists. */
    }
    int cleanup=md_ipc_cancel(s,pending);
    return cleanup ? cleanup : result;
}
int md_ipc_wait_fd(const struct md_ipc_pending *p) { return p ? p->watcher : -1; }
int64_t md_ipc_deadline(const struct md_ipc_pending *p) { return p ? p->deadline : INT64_MAX; }
