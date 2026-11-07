#ifndef MD_TASK_CALL_H
#define MD_TASK_CALL_H
#include <asm/ptrace.h>
#include <stdint.h>
#include <sys/types.h>

enum md_task_call_boundary { MD_TASK_CALL_USER, MD_TASK_CALL_ENTRY,
    MD_TASK_CALL_EXIT, MD_TASK_CALL_SECCOMP, MD_TASK_CALL_EMULATED_ENTRY };
enum { MD_TASK_CALL_INTERRUPTIBLE=1 };
struct md_task_call_arguments { long number; unsigned long value[6]; unsigned flags; };
/* One task-affine syscall, driven by the supervisor event loop. The owner
 * retains the actor, argument storage and original event until completion.
 * No guest FD, helper thread or application-memory write is allocated.
 * The actor must be seized with TRACESYSGOOD and TRACESECCOMP. Completion uses
 * syscall exit and a synthetic interrupt, never a forced application signal.
 * EMULATED_ENTRY retains a SYSEMU entry's kernel skip state; unlike an ordinary
 * ENTRY it cannot execute a replacement syscall before draining the skipped exit.
 * Ordinary ENTRY/SECCOMP also preserve an owner's explicit syscall cancellation,
 * independently of x8 and the emulated result held in x0.
 * At a restart exit,
 * the kernel first recovers its private original arguments at a stopped entry.
 * Re-entry uses the stable call gate, restoring the original PC before seccomp;
 * it never fetches a potentially replaced source instruction.
 * Injected calls also leave ERESTART processing to the kernel; an internal
 * restart code is never published as their completed result. A kernel-forced
 * SIGSYS unblock must not be reversed by restoring the saved mask: the call
 * keeps ownership until native termination instead of resuming guest code.
 * INTERRUPTIBLE retains the application mask during the borrowed call. A native
 * restart result is returned to its owner at the restored original boundary;
 * that owner must choose application restart/EINTR semantics, never expose an
 * internal ERESTART value directly. Restoration itself remains shielded. */
struct md_task_call {
    long (*trace)(int,pid_t,void *,void *);
    pid_t actor;
    unsigned stage;
    int restarting, restart_pending, replaced_entry, cancelled_entry, retire_entry, admitted_entry;
    int interrupt_requested, interrupt_pending, interrupted_entry;
    enum md_task_call_boundary boundary;
    struct user_pt_regs saved, entry;
    uintptr_t call,returned;
    struct md_task_call_arguments arguments;
    uint64_t mask;
    uint64_t original_first_argument, seccomp_first_argument;
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
/* The owner has completed the original entry (for example a USER_NOTIF reply).
 * Restore at its skipped exit without evaluating a newer seccomp policy.
 * Completion publishes boundary==EXIT; the owner supplies the final result.
 * Call before handing the borrowed syscall's exit to event(). */
int md_task_call_retire_entry(struct md_task_call *);
/* A listener admitted CONTINUE for this saved entry (including a cancelled
 * kernel notification captured at USER/EXIT). Restore it at
 * the owned gate before returning the original registers to its dispatcher;
 * a later TSYNC must not re-evaluate the already-admitted operation. Requires
 * owned-gate delegation in every installed application filter. */
int md_task_call_preserve_admission(struct md_task_call *);
/* Resume the same internal boundary after an unrelated stop. Pass zero after
 * retaining a signal, or its number for native signal delivery (e.g. SIGSTOP).
 * Does not change the saved application state. */
int md_task_call_resume(struct md_task_call *,int signal);
/* Whether a synthetic interrupt belongs to this call rather than an enclosing
 * quiescence barrier. Stage ownership stays inside the native-call executor. */
int md_task_call_expects_interrupt(const struct md_task_call *);
/* Wake an interruptible borrowed wait. An entry not yet executed is cancelled;
 * a completed effect wins the race. The owner chooses whether to retry. */
int md_task_call_interrupt(struct md_task_call *);
/* Cancel a pre-seccomp entry through the admitted inert gettid gate. Using
 * syscall -1 here can itself trigger an inherited Android TRAP policy. */
int md_task_call_cancel_entry(long (*trace)(int,pid_t,void *,void *),pid_t,uintptr_t returned);
/* Exact kernel notification of the currently executing borrowed call. */
struct seccomp_notif;
int md_task_call_notification(const struct md_task_call *,const struct seccomp_notif *);
/* Run another syscall from a completed, restored boundary. */
int md_task_call_again(struct md_task_call *,const struct md_task_call_arguments *);
#endif
