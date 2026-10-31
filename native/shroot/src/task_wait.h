#ifndef MD_TASK_WAIT_H
#define MD_TASK_WAIT_H
#include <sys/resource.h>
#include <sys/types.h>

/* SIGCHLD is a lossy hint, never ownership or proof of a stopped task.
 * Collected statuses remain owned here until returned exactly once. */
struct md_task_wait_member {
    struct md_task_wait_member *next, *previous;
    pid_t pid;
};
struct md_task_wait {
    struct md_task_wait_member *cursor;
    pid_t hints[8];
    pid_t recent[4];
    unsigned first, count;
    unsigned directed, fair_phase;
    unsigned members, pending_first, pending_count;
    struct {
        pid_t pid;
        int status;
        struct rusage usage;
    } pending[32];
    int drained;
};
void md_task_wait_track(struct md_task_wait *, struct md_task_wait_member *, pid_t);
void md_task_wait_untrack(struct md_task_wait *, struct md_task_wait_member *);
void md_task_wait_hint(struct md_task_wait *, pid_t);
/* Transfer an already collected status, including during fatal cleanup.
 * Never queries the kernel or infers that its event queue is empty. */
pid_t md_task_wait_take(struct md_task_wait *, int *, struct rusage *);
/* Nonblocking __WALL wait with bounded preference for notified TIDs.
 * A general empty result remains valid until SIGCHLD invalidates it. Callers
 * must service the blocked signal source and include it in readiness waits.
 * Returns the unmodified wait4 result, status, rusage and errno. */
pid_t md_task_wait_ready(struct md_task_wait *, int *, struct rusage *);
#endif
