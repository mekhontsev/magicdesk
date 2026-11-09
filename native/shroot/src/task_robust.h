#ifndef MD_TASK_ROBUST_H
#define MD_TASK_ROBUST_H
#include "interception.h"
#include "robust_futex.h"
#include <sys/types.h>

/* Terminal work in an already stopped, private CLONE_VM executor. Its owner
 * retains the old mm across exit/exec and owns creation, reaping and signals.
 * No application thread is borrowed here; a copy fault cannot change an
 * application's SIGSEGV disposition. Ordinary futex operations stay native. */
struct md_task_robust {
    long (*trace)(int,pid_t,void *,void *);
    pid_t actor;
    struct md_interception_abi abi;
    struct md_robust_cleanup cleanup;
    uint64_t pstate;
    int running;
};
/* 0 awaits an actor event; 1 completed with the actor still stopped; 2 needs
 * another progress turn. Read-only walks have a bounded per-turn budget so
 * a malformed list cannot monopolize the supervisor event loop. Negative
 * errors retain ownership for cancellation; they never imply cleanup success. */
int md_task_robust_begin(struct md_task_robust *,long (*trace)(int,pid_t,void *,void *),
    pid_t,const struct md_interception_abi *,uintptr_t head,uint32_t owner);
int md_task_robust_progress(struct md_task_robust *);
/* Checkpoint events come from the shared task_checkpoint owner. Unrelated
 * lifecycle/signal events remain with the executor's lifetime owner (-EAGAIN).
 * Only a positive-code access fault inside our CAS is consumed as EFAULT. */
int md_task_robust_event(struct md_task_robust *,int status);
#endif
