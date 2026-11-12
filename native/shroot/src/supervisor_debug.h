#ifndef MD_SUPERVISOR_DEBUG_H
#define MD_SUPERVISOR_DEBUG_H

#include "supervisor_thread.h"
#include "guest_debugger.h"

struct debug_wait_output {
    struct md_interception_buffer buffer;
    struct seccomp_data call;
    enum md_debugger_wait_action action;
};

void debugger_wait_release(struct thread *t);
int debugger_parent(pid_t pid);
void debugger_reaped(pid_t observer,pid_t pid,int native_wait);
int debugger_group(pid_t pid);
uid_t debugger_uid(pid_t pid);
int debugger_signal_mask(pid_t pid,int writing,uint64_t *mask);
uid_t debugger_visible_uid(pid_t observer,pid_t sender,uid_t fallback);
void debugger_signal_origin(pid_t recipient,const siginfo_t *info,struct md_signal_origin *origin);
void debugger_signal_translate(pid_t observer,const struct md_signal_origin *origin,siginfo_t *info);
pid_t debugger_visible_pid(pid_t observer,pid_t native);
pid_t debugger_native_pid(pid_t observer,pid_t visible);
int inspect_allowed(pid_t caller, pid_t pid, int filesystem);
int debugger_allowed(pid_t caller, pid_t pid);
int debugger_traceme(pid_t parent,pid_t child);
int proc_allowed(pid_t caller, pid_t pid);
int debugger_call(struct thread *,const struct seccomp_data *,const struct user_pt_regs *);
void debugger_complete(pid_t pid,long value);
int debugger_attachable(pid_t caller, pid_t pid);
int debugger_interrupt(pid_t pid);
int debugger_observe(pid_t owner);
int debugger_options(pid_t pid, unsigned long options);
void debugger_usage(pid_t pid, struct rusage *usage);
int debugger_listen(pid_t pid);
void debugger_wait_wake(pid_t pid);
int debugger_wait_admit(pid_t pid);
int debugger_wait_pending(const struct thread *);
void debugger_wait(pid_t pid,const struct seccomp_data *call,enum md_debugger_wait_action action);
void debugger_wait_retry(struct thread *t);
int debug_syscall_exit(struct thread *t, const struct user_pt_regs *regs);
int debug_host_policy_pending(struct thread *t,const struct user_pt_regs *regs);
int debugger_at_boundary(const struct thread *t);
int debugger_boundary(struct thread *t);
void debugger_park_complete(struct thread *t,int cancelled);
int debug_wait_exit(struct thread *t,pid_t pid);

#endif
