#define _GNU_SOURCE
#include "task_wait.h"
#include <errno.h>
#include <sys/wait.h>

void md_task_wait_track(struct md_task_wait *w,struct md_task_wait_member *m,pid_t pid) {
    ++w->members;
    m->pid=pid;
    if (!w->cursor) { m->next=m->previous=m; w->cursor=m; }
    else {
        m->next=w->cursor; m->previous=w->cursor->previous;
        m->previous->next=m; m->next->previous=m;
    }
}
void md_task_wait_untrack(struct md_task_wait *w,struct md_task_wait_member *m) {
    if (!m->next) return;
    --w->members;
    if (m->next==m) w->cursor=NULL;
    else {
        m->previous->next=m->next; m->next->previous=m->previous;
        if (w->cursor==m) w->cursor=m->next;
    }
    *m=(struct md_task_wait_member){0};
}
static void forget(struct md_task_wait *w, pid_t pid) {
    unsigned out=0;
    for (unsigned i=0;i<sizeof(w->recent)/sizeof(*w->recent);++i)
        if (w->recent[i] && w->recent[i]!=pid) w->recent[out++]=w->recent[i];
    while (out<sizeof(w->recent)/sizeof(*w->recent)) w->recent[out++]=0;
}
static pid_t remember(struct md_task_wait *w, pid_t pid, int status) {
    if (pid<=0) return pid;
    forget(w,pid);
    if (WIFSTOPPED(status)) {
        for (unsigned i=sizeof(w->recent)/sizeof(*w->recent)-1;i;--i)
            w->recent[i]=w->recent[i-1];
        w->recent[0]=pid;
    }
    return pid;
}
/* One discovery pass may name a task through the fair cursor, SIGCHLD hints
 * and the recent working set. A miss is useful to all three, not a reason to
 * query the same task again before the general queue check. */
struct queried {
    pid_t tasks[1+sizeof(((struct md_task_wait *)0)->hints)/sizeof(pid_t)
        +sizeof(((struct md_task_wait *)0)->recent)/sizeof(pid_t)];
    unsigned count;
};
static int first_query(struct queried *q,pid_t pid) {
    for (unsigned i=0;i<q->count;++i) if (q->tasks[i]==pid) return 0;
    if (q->count<sizeof(q->tasks)/sizeof(*q->tasks)) q->tasks[q->count++]=pid;
    return 1;
}
void md_task_wait_hint(struct md_task_wait *w, pid_t pid) {
    if (pid <= 0) return;
    w->drained = 0;
    unsigned capacity=sizeof(w->hints)/sizeof(*w->hints);
    for (unsigned i=0;i<w->count;i++)
        if (w->hints[(w->first+i)%capacity]==pid) return;
    if (w->count<capacity) w->hints[(w->first+w->count++)%capacity]=pid;
}
pid_t md_task_wait_take(struct md_task_wait *w, int *status, struct rusage *usage) {
    if (w->pending_count) {
        unsigned i=w->pending_first++;
        --w->pending_count;
        *status=w->pending[i].status;
        if (usage) *usage=w->pending[i].usage;
        return remember(w,w->pending[i].pid,*status);
    }
    return 0;
}
static pid_t collect(struct md_task_wait *w,int *status,struct rusage *usage,unsigned limit) {
    w->pending_first=0;
    w->count=w->directed=0;
    w->fair_phase=0;
    while (w->pending_count<limit) {
        unsigned i=w->pending_count;
        w->pending[i].usage=(struct rusage){0};
        pid_t pid=wait4(-1,&w->pending[i].status,__WALL|WNOHANG,
            usage ? &w->pending[i].usage : NULL);
        if (pid<=0) {
            if (!pid) w->drained=1;
            if (!w->pending_count) return pid;
            break;
        }
        w->pending[i].pid=pid; ++w->pending_count;
    }
    return md_task_wait_take(w,status,usage);
}
pid_t md_task_wait_ready(struct md_task_wait *w, int *status, struct rusage *usage) {
    pid_t pending=md_task_wait_take(w,status,usage);
    if (pending) return pending;
    if (w->drained) return 0;
    /* Small trees cost less to scan once than to query several sleeping TIDs.
     * Collect before resuming anyone: a hot child cannot take the list head
     * again while an older stop is waiting. Untracked births are retained too. */
    if (w->members>1 && w->members<=4) return collect(w,status,usage,4);
    struct queried queried={0};
    /* A hot child cannot indefinitely postpone stops whose SIGCHLD coalesced.
     * This is an event budget, not a timer or a readiness assumption. */
    unsigned directed_budget=w->members>4 ? 64 : 8;
    /* Amortize the linear kernel scan across the known population. Round-robin
     * checks below remain independent; discovery is bounded even for huge trees. */
    if (w->members>64) directed_budget=w->members<1024 ? w->members : 1024;
    /* wait4(-1) scans from the same child-list head: even frequent general
     * waits can starve an unnotified stop behind perpetually hot children.
     * Advance round-robin on two of every three discovery passes. This bounds
     * unnotified stops independently of lossy hints while amortizing idle probes.
     * A single tracee keeps the original fast path; no full task scan occurs. */
    if (w->cursor && w->cursor->next!=w->cursor && w->directed<directed_budget
            && w->fair_phase<2) {
        ++w->fair_phase;
        pid_t selected=w->cursor->pid;
        w->cursor=w->cursor->next;
        first_query(&queried,selected);
        ++w->directed;
        pid_t pid=wait4(selected,status,__WALL|WNOHANG,usage);
        if (pid>0) return remember(w,pid,*status);
        if (pid<0 && errno!=ECHILD && errno!=ESRCH) return pid;
    } else w->fair_phase=0;
    while (w->count && w->directed < directed_budget) {
        unsigned capacity=sizeof(w->hints)/sizeof(*w->hints);
        pid_t selected=w->hints[w->first];
        w->first=(w->first+1)%capacity; w->count--;
        forget(w,selected);
        if (!first_query(&queried,selected)) continue;
        w->directed++;
        pid_t pid = wait4(selected, status, __WALL | WNOHANG, usage);
        if (pid > 0) return remember(w,pid,*status);
        if (pid < 0 && errno != ECHILD && errno != ESRCH) return pid;
    }
    /* Checkpoints often alternate between a caller and its helper, or between
     * active clients. Remember only a small working set, never the whole tree.
     * These are bounded kernel queries, never evidence of another stop.
     * Fresh SIGCHLD hints take precedence; general collection remains fair. */
    for (unsigned i=0;i<sizeof(w->recent)/sizeof(*w->recent)
            && w->recent[i] && w->directed<directed_budget;++i) {
        pid_t selected=w->recent[i];
        if (!first_query(&queried,selected)) continue;
        ++w->directed;
        pid_t pid=wait4(selected,status,__WALL|WNOHANG,usage);
        if (pid>0) return remember(w,pid,*status);
        if (pid<0 && errno!=ECHILD && errno!=ESRCH) return pid;
    }
    w->directed = 0;
    if (w->members>4) {
        /* General wait scans every child, including sleeping threads. Reuse
         * directed hints first; when discovery is needed, collect a bounded
         * batch before any hot child can reoccupy the kernel's list head. */
        return collect(w,status,usage,sizeof(w->pending)/sizeof(*w->pending));
    }
    pid_t pid = wait4(-1, status, __WALL | WNOHANG, usage);
    if (!pid) {
        /* Only a general wait proves the kernel queue empty. A stale directed
         * hint cannot prove that other tracees have no outstanding events. */
        w->drained = 1;
        w->count = 0;
        for (unsigned i=0;i<sizeof(w->recent)/sizeof(*w->recent);++i) w->recent[i]=0;
    }
    return pid>0 ? remember(w,pid,*status) : pid;
}
