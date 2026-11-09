#ifndef MD_TASK_BIRTH_H
#define MD_TASK_BIRTH_H
#include <sys/types.h>

/* Ownership transfer preserves a newborn's already collected terminal stop. */
enum md_task_birth_stop { MD_TASK_BIRTH_PENDING, MD_TASK_BIRTH_READY, MD_TASK_BIRTH_EXIT };

/* Read an observed FORK/VFORK/CLONE stop, before changing child ownership.
 * A concurrent group exit can replace its message with an exit code. In that
 * case the completed clone's native return still identifies the child, and
 * terminal is set: the parent must await its own EXIT event, not be resumed.
 * Returns a positive child PID or negative errno; never an inferred PID. */
long md_task_birth_read(long (*trace)(int,pid_t,void *,void *),pid_t,
    unsigned event,int *terminal);
/* Recover an unreported successful clone at EXIT. The submitted first argument
 * is required: a clone killed at seccomp can retain that argument as x0. */
long md_task_birth_terminal(long (*trace)(int,pid_t,void *,void *),pid_t,
    long syscall_number,unsigned long argument);
#endif
