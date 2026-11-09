#ifndef MD_TASK_RETAINED_H
#define MD_TASK_RETAINED_H
#include "interception.h"
#include "task_call.h"

/* A stack/TLS-free private process that retains the caller's mm. Unlike a
 * CLONE_THREAD helper it survives group exit, SIGKILL and successful exec.
 * Creation borrows one stopped caller, then closes the copied descriptor
 * table and separates job control before publishing readiness. Idle execution
 * is a ptrace stop, never a polling thread. No memory-manager writer reference
 * is held: an idle retainer must not disable sole-writer accelerated paths.
 * The caller owns native task tracking, early newborn stops and final reaping,
 * and lends its checkpoint owner throughout the borrowed parent call. Newborn
 * events use the ordinary shared checkpoint decoder. */
struct md_task_retained {
    struct md_task_call parent;
    struct md_interception_abi abi;
    pid_t actor;
    uint64_t pstate;
    int stage,parent_ready,parent_terminal;
};
int md_task_retained_begin(struct md_task_retained *,long (*trace)(int,pid_t,void *,void *),
    pid_t,enum md_task_call_boundary,const struct user_pt_regs *,const struct md_interception_abi *);
/* 0 awaits another event, 1 publishes a stopped initialized actor AND the
 * exactly restored caller. The actor PID becomes available at the caller's
 * CLONE event; transfer any earlier newborn stop then. -EAGAIN leaves an
 * unrelated caller signal/lifecycle event with its enclosing owner. If group
 * death displaces a CLONE stop, parent_terminal records cancellation and actor
 * retains the recovered child; do not resume that caller or publish readiness. Other
 * negative results retain any born actor for cancellation and exact reaping. */
int md_task_retained_event(struct md_task_retained *,pid_t,int status);
#endif
