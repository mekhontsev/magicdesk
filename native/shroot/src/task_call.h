#ifndef MD_TASK_CALL_H
#define MD_TASK_CALL_H
#include <asm/ptrace.h>
#include <stdint.h>
#include <sys/types.h>

enum md_task_call_boundary { MD_TASK_CALL_USER, MD_TASK_CALL_ENTRY,
    MD_TASK_CALL_EXIT, MD_TASK_CALL_SECCOMP, MD_TASK_CALL_EMULATED_ENTRY };
struct md_task_call_arguments { long number; unsigned long value[6]; };
/* One task-affine syscall, driven by the supervisor event loop. The owner
 * retains the actor, argument storage and original event until completion.
 * No guest FD, helper thread or application-memory write is allocated.
 * The actor must be seized with TRACESYSGOOD and TRACESECCOMP. Completion uses
 * syscall exit and a synthetic interrupt, never a forced application signal.
 * EMULATED_ENTRY retains a SYSEMU entry's kernel skip state; unlike an ordinary
 * ENTRY it cannot execute a replacement syscall before draining the skipped exit.
 * At a restart exit,
 * the kernel first recovers its private original arguments at a stopped entry.
 * Re-entry uses the stable call gate, restoring the original PC before seccomp;
 * it never fetches a potentially replaced source instruction.
 * Injected calls also leave ERESTART processing to the kernel; an internal
 * restart code is never published as their completed result. A kernel-forced
 * SIGSYS unblock must not be reversed by restoring the saved mask: the call
 * keeps ownership until native termination instead of resuming guest code. */
struct md_task_call {
    long (*trace)(int,pid_t,void *,void *);
    pid_t actor;
    unsigned stage;
    int restarting, restart_pending, replaced_entry;
    enum md_task_call_boundary boundary;
    struct user_pt_regs saved, entry;
    uintptr_t call,returned;
    struct md_task_call_arguments arguments;
    uint64_t mask;
    long result, active_number;
    unsigned long cookie;
};
int md_task_call_begin(struct md_task_call *,long (*trace)(int,pid_t,void *,void *),
    pid_t,enum md_task_call_boundary,const struct user_pt_regs *resume,
    uintptr_t call,uintptr_t returned,const struct md_task_call_arguments *);
/* 0 waits for the exact next task event; 1 leaves the actor stopped at its
 * restored boundary. Negative results retain ownership unless stage==0.
 * Unexpected signal/lifecycle events belong to the enclosing supervisor.
 * A restored restart exit has a pending synthetic interrupt, owned by the
 * enclosing dispatcher after completion or consumed by again(). */
int md_task_call_event(struct md_task_call *,int status);
/* Resume the same internal boundary after an unrelated stop. Pass zero after
 * retaining a signal, or its number for native signal delivery (e.g. SIGSTOP).
 * Does not change the saved application state. */
int md_task_call_resume(struct md_task_call *,int signal);
/* Whether a synthetic interrupt belongs to this call rather than an enclosing
 * quiescence barrier. Stage ownership stays inside the native-call executor. */
int md_task_call_expects_interrupt(const struct md_task_call *);
/* Run another syscall from a completed, restored boundary. */
int md_task_call_again(struct md_task_call *,const struct md_task_call_arguments *);
#endif
