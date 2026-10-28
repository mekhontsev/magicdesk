#define _GNU_SOURCE
#include "task_quiesce.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/wait.h>

struct participant {
    struct participant *next, *indexed;
    pid_t pid;
    int stopped, resume, interrupted, synthetic, borrowed;
};
struct deferred {
    struct deferred *next;
    pid_t pid;
    int status;
    struct rusage usage;
};
enum { PARTICIPANT_BUCKETS=257 };
struct md_task_quiesce {
    struct participant *participants;
    struct participant *index[PARTICIPANT_BUCKETS];
    unsigned waiting, borrowed;
    struct deferred *first, *last;
    long (*trace)(int,pid_t,void *,void *);
};
struct md_task_quiesce *md_task_quiesce_new(long (*trace)(int,pid_t,void *,void *)) {
    struct md_task_quiesce *q=calloc(1,sizeof(*q));
    if (q) q->trace=trace;
    return q;
}
void md_task_quiesce_destroy(struct md_task_quiesce *q) {
    if (!q) return;
    while (q->participants) { struct participant *p=q->participants; q->participants=p->next; free(p); }
    while (q->first) { struct deferred *d=q->first; q->first=d->next; free(d); }
    free(q);
}
static struct participant *find(struct md_task_quiesce *q,pid_t pid) {
    if (!q) return NULL;
    for (struct participant *p=q->index[(unsigned)pid%PARTICIPANT_BUCKETS];p;p=p->indexed)
        if (p->pid==pid) return p;
    return NULL;
}
int md_task_quiesce_contains(struct md_task_quiesce *q,pid_t pid) {
    return find(q,pid)!=NULL;
}
int md_task_quiesce_waiting(struct md_task_quiesce *q,pid_t pid) {
    struct participant *p=find(q,pid);
    return p && !p->stopped;
}
int md_task_quiesce_interrupting(struct md_task_quiesce *q,pid_t pid) {
    struct participant *p=find(q,pid);
    return p && p->interrupted && !p->stopped;
}
static void stopped(struct md_task_quiesce *q,struct participant *p,int value) {
    value=!!value;
    if (p->stopped==value) return;
    if (value) --q->waiting; else ++q->waiting;
    p->stopped=value;
}
int md_task_quiesce_add(struct md_task_quiesce *q,pid_t pid,int is_stopped,int request) {
    struct participant *p=find(q,pid);
    if (p && p->borrowed) return -EBUSY;
    if (!p) {
        p=calloc(1,sizeof(*p)); if (!p) return -ENOMEM;
        unsigned bucket=(unsigned)pid%PARTICIPANT_BUCKETS;
        *p=(struct participant){.next=q->participants,.indexed=q->index[bucket],.pid=pid,.stopped=1};
        q->participants=p; q->index[bucket]=p;
    }
    /* Helper ownership overrides the outer thread's saved continuation. */
    p->resume=request;
    for (struct deferred *d=q->first;d;d=d->next) if (d->pid==pid) is_stopped=1;
    stopped(q,p,is_stopped);
    return 0;
}
int md_task_quiesce_stop(struct md_task_quiesce *q) {
    for (struct deferred *d=q->first;d;d=d->next)
        if (!find(q,d->pid)) { int e=md_task_quiesce_add(q,d->pid,1,0); if (e) return e; }
    for (struct participant *p=q->participants;p;p=p->next) if (!p->stopped && p->resume) {
        if (q->trace(PTRACE_INTERRUPT,p->pid,NULL,NULL)<0 && errno!=ESRCH) return -errno;
        p->interrupted=1;
    }
    return 0;
}
int md_task_quiesce_ready(const struct md_task_quiesce *q) {
    return !q->waiting && !q->borrowed;
}
void md_task_quiesce_describe(const struct md_task_quiesce *q,int fd) {
    for (const struct participant *p=q->participants;p;p=p->next) if (!p->stopped)
        dprintf(fd,"guest-runtime: memory writer pid=%d resume=%d interrupted=%d\n",
            p->pid,p->resume,p->interrupted);
    unsigned count=0;
    for (const struct deferred *d=q->first;d && count<16;d=d->next,++count)
        dprintf(fd,"Shroot: deferred writer event pid=%d status=%#x\n",d->pid,d->status);
}
int md_task_quiesce_event(struct md_task_quiesce *q,pid_t pid,int status,const struct rusage *usage) {
    struct participant *p=find(q,pid);
    if (p && p->borrowed) return -EBUSY;
    if (!p) { int e=md_task_quiesce_add(q,pid,0,0); if (e) return e; p=find(q,pid); }
    unsigned event=(unsigned)status>>16;
    if (!p->stopped && p->interrupted && event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
        stopped(q,p,1); p->synthetic=1; return 0;
    }
    stopped(q,p,1); p->synthetic=0;
    struct deferred *d=calloc(1,sizeof(*d)); if (!d) return -ENOMEM;
    *d=(struct deferred){.pid=pid,.status=status}; if (usage) d->usage=*usage;
    if (q->last) q->last->next=d; else q->first=d; q->last=d;
    if (event==PTRACE_EVENT_CLONE || event==PTRACE_EVENT_FORK || event==PTRACE_EVENT_VFORK) {
        unsigned long child;
        if (q->trace(PTRACE_GETEVENTMSG,pid,NULL,&child)<0) return -errno;
        /* Birth already stops the child; wait for its own stop, in either order. */
        if (!find(q,(pid_t)child)) return md_task_quiesce_add(q,(pid_t)child,0,0);
    }
    return 0;
}
int md_task_quiesce_borrow(struct md_task_quiesce *q,pid_t pid,struct md_task_quiesce_stop *out) {
    struct participant *p=find(q,pid);
    if (!p || !out) return -EINVAL;
    if (!md_task_quiesce_ready(q)) return -EBUSY;
    struct md_task_quiesce_stop boundary={.kind=p->synthetic
        ? MD_TASK_QUIESCE_SYNTHETIC : MD_TASK_QUIESCE_HELD};
    for (struct deferred *d=q->first;d;d=d->next) if (d->pid==pid) {
        boundary.kind=MD_TASK_QUIESCE_EVENT; boundary.status=d->status;
        if (!WIFSTOPPED(d->status)) return -ESRCH;
        break;
    }
    p->borrowed=1; ++q->borrowed; *out=boundary;
    return 0;
}
int md_task_quiesce_return(struct md_task_quiesce *q,pid_t pid) {
    struct participant *p=find(q,pid);
    if (!p || !p->borrowed) return -EINVAL;
    p->borrowed=0; --q->borrowed;
    return 0;
}
int md_task_quiesce_release(struct md_task_quiesce *q) {
    if (q->borrowed) return -EBUSY;
    int error=0;
    memset(q->index,0,sizeof(q->index)); q->waiting=0;
    while (q->participants) {
        struct participant *p=q->participants; q->participants=p->next;
        if (p->synthetic && p->resume && q->trace(p->resume,p->pid,NULL,NULL)<0 && errno!=ESRCH) error=-errno;
        free(p);
    }
    return error;
}
pid_t md_task_quiesce_take(struct md_task_quiesce *q,int *status,struct rusage *usage) {
    if (!q || !q->first || q->borrowed) return 0;
    struct deferred *d=q->first; q->first=d->next; if (!q->first) q->last=NULL;
    pid_t pid=d->pid; *status=d->status; if (usage) *usage=d->usage; free(d); return pid;
}
int md_task_quiesce_newborn(struct md_task_quiesce *q,pid_t pid) {
    if (!q) return 0;
    struct participant *p=find(q,pid);
    if (p && p->borrowed) return -EBUSY;
    struct deferred **at=&q->first,*previous=NULL;
    while (*at && (*at)->pid!=pid) { previous=*at; at=&(*at)->next; }
    if (!*at) return 0;
    struct deferred *d=*at;
    if ((unsigned)d->status>>16!=PTRACE_EVENT_STOP || WSTOPSIG(d->status)!=SIGTRAP) return -EPROTO;
    *at=d->next; if (q->last==d) q->last=previous; free(d); return 1;
}
