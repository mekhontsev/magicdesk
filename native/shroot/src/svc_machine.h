#ifndef MD_SVC_MACHINE_H
#define MD_SVC_MACHINE_H
#include "svc_registry.h"
#include <signal.h>
#include "signal_delivery.h"

struct md_svc_machine_thread {
    int syscall_exit, draining;
    uintptr_t retry_svc;
    uint64_t retry_number;
    uintptr_t signal_alias, signal_svc, signal_sp;
    struct user_pt_regs restored;
};
struct md_svc_registry *md_svc_machine_admit(pid_t,uintptr_t descriptor);
int md_svc_machine_return(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,struct user_pt_regs *);
/* 1 captures the original call's PC/SP/number/arguments, not full GPRs; the
 * supervisor owns admission and rearming. 0 leaves an unrelated stop to its
 * existing owner. Both retire the temporary retry observer. */
int md_svc_machine_retry(struct md_svc_machine_thread *,pid_t,struct user_pt_regs *observed);
void md_svc_machine_forget(struct md_svc_machine_thread *);
/* Terminal stop: release only the dying task's exact resident frame. No guest
 * continuation is resumed, and unrelated shared-mm slots remain untouched. */
int md_svc_machine_retire(struct md_svc_registry *,pid_t,const struct user_pt_regs *adapter);
/* The supervisor retains resume ownership. A handled checkpoint requests one
 * syscall-exit stop; the common signal queue owns subsequent delivery. */
int md_svc_machine_syscall(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,
    const struct user_pt_regs *observed);
/* A published site's alias can use the ordinary syscall owner without replay or register
 * writes: its resident branch returns to the original continuation. The owner
 * must exclude application filters/debuggers; signal recovery translates PC.
 * 1 is already admitted; 2 may benefit from discovery without syscall replay. */
int md_svc_machine_native_entry(const struct md_svc_registry *,const struct user_pt_regs *);
/* Cancel an unexecuted syscall at its entry stop, then restore the complete
 * continuation at exit, after the kernel has restored its temporary x7. */
int md_svc_machine_redirect(struct md_svc_machine_thread *,pid_t,const struct user_pt_regs *);
int md_svc_machine_exit(struct md_svc_machine_thread *,pid_t);
int md_svc_machine_signal(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,
    struct md_signal_delivery *,int *deliver);
/* Complete kernel signal delivery before another stop owner borrows registers. */
int md_svc_machine_signal_committed(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,int status);
/* Reconstruct the guest continuation before publishing queued signals.
 * Return 1 while a native operation must first reach its owned checkpoint. */
int md_svc_machine_flush(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t);
/* Cold filter fence: all entries have been revoked and this task is stopped.
 * Adapter/syscall continuations must finish before reconstructing registers. */
int md_svc_machine_drain(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,
    const struct user_pt_regs *adapter,int syscall_stop);
#endif
