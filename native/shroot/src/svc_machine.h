#ifndef MD_SVC_MACHINE_H
#define MD_SVC_MACHINE_H
#include "svc_registry.h"
#include <signal.h>
#include <linux/seccomp.h>
#include "signal_delivery.h"
#include "task_stop.h"

/* The continuation owner observes mutations as well as reads. Bypassing it
 * leaves checkpoint scope and retained observations stale after recovery. */
typedef long (*md_svc_trace_fn)(int,pid_t,void *,void *);

struct md_svc_machine_thread {
    unsigned pool_failed;
    int syscall_exit, draining;
    uintptr_t retry_svc;
    uint64_t retry_number;
    uintptr_t signal_alias, signal_svc, signal_sp;
    struct user_pt_regs restored;
};
struct md_svc_registry *md_svc_machine_admit(pid_t,uintptr_t descriptor);
int md_svc_machine_return(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,struct user_pt_regs *);
/* 1 captures the original logical syscall, not a machine register image; the
 * supervisor owns admission and rearming. 0 leaves an unrelated stop to its
 * existing owner. Both retire the temporary retry observer. */
int md_svc_machine_retry(md_svc_trace_fn,struct md_svc_machine_thread *,pid_t,struct seccomp_data *observed);
void md_svc_machine_forget(struct md_svc_machine_thread *);
/* Terminal stop: release only the dying task's exact resident frame. No guest
 * continuation is resumed, and unrelated shared-mm slots remain untouched. */
int md_svc_machine_retire(md_svc_trace_fn,struct md_svc_registry *,pid_t,const struct user_pt_regs *adapter);
/* Stopped, completed resident C execution, before restarting an interrupted
 * syscall. No pending provider or native effect may retain the private stack. */
int md_svc_machine_unwind(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,struct user_pt_regs *);
/* The supervisor retains resume ownership. A handled checkpoint requests one
 * syscall-exit stop; the common signal queue owns subsequent delivery. */
int md_svc_machine_syscall(md_svc_trace_fn,struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,
    const struct user_pt_regs *observed);
/* A published site's alias can use the ordinary syscall owner without replay or register
 * writes: its resident branch returns to the original continuation. The owner
 * must exclude application filters/debuggers; signal recovery translates PC.
 * 1 is already admitted; 2 may benefit from discovery without syscall replay. */
int md_svc_machine_native_entry(const struct md_svc_registry *,uintptr_t pc,long number);
/* Cancel an unexecuted syscall at its entry stop, then restore the complete
 * continuation at exit, after the kernel has restored its temporary x7. */
int md_svc_machine_redirect(md_svc_trace_fn,struct md_svc_machine_thread *,pid_t,const struct user_pt_regs *);
int md_svc_machine_exit(md_svc_trace_fn,struct md_svc_machine_thread *,pid_t);
int md_svc_machine_signal(struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,
    struct md_signal_delivery *,struct md_task_signal_stop *,int *deliver);
/* Kernel denial at a native alias: retain its action, but publish the original
 * application PC to both the signal owner and a guest debugger. */
int md_svc_machine_seccomp(struct md_svc_registry *,pid_t,struct md_task_signal_stop *);
/* Complete kernel signal delivery before another stop owner borrows registers. */
int md_svc_machine_signal_committed(md_svc_trace_fn,struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,int status);
/* Reconstruct the guest continuation before publishing queued signals.
 * Return 1 while a native operation must first reach its owned checkpoint. */
int md_svc_machine_flush(md_svc_trace_fn,struct md_svc_registry *,struct md_svc_machine_thread *,pid_t);
/* Cold filter fence: all entries have been revoked and this task is stopped.
 * Adapter/syscall continuations must finish before reconstructing registers. */
int md_svc_machine_drain(md_svc_trace_fn,struct md_svc_registry *,struct md_svc_machine_thread *,pid_t,
    const struct user_pt_regs *adapter,int syscall_stop);
#endif
