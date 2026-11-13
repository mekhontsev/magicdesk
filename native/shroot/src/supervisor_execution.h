#ifndef MD_SUPERVISOR_EXECUTION_H
#define MD_SUPERVISOR_EXECUTION_H

#include "supervisor_thread.h"
struct md_entry_native;

extern const unsigned long trace_options;
extern struct md_task_checkpoints checkpoints;

int checkpoint_restricted(pid_t pid);
int trace_continues(int request);
long native_trace(int request,pid_t pid,void *address,void *data);
int tracee_request(struct thread *t, int request, void *address, void *data, const char *operation);
int lost_stop_event(struct thread *t,unsigned event);
int machine_observe(struct thread *t,uintptr_t pc,long number);
int machine_discovery(struct thread *t);
int machine_native_entry(struct thread *t,const struct seccomp_data *call,unsigned long *cookie);
int cooperative_copying(struct thread *t);
void cooperative_buffer_release(struct thread *t);
void cooperative_routes_publish(struct thread *t);
void cooperative_memory_publish(struct thread *t,int eligible);
long cooperative_bind(struct thread *t);
int registers(pid_t pid, struct user_pt_regs *out);
int set_registers(pid_t pid, struct user_pt_regs *regs);
/* Stopped transport only. Ordinary shared completions already own a result. */
int stopped_result(struct thread *,long *);
int set_stopped_result(struct thread *,long);
int skip(pid_t pid);
int cooperative_native_observe(pid_t pid,struct md_entry_native *native);
int entry_reply(struct thread *t,long value,uintptr_t extra);
int entry_pending(const struct thread *t);
void entry_complete(struct thread *t,long result);
int entry_context(struct thread *t,enum md_execution_context reason);
/* Resume an already owned shared call at its real signal/restart boundary. */
void entry_restart(struct thread *,enum call_restart);
void native_continue(struct thread *t);
void control_return(struct thread *t,struct user_pt_regs *regs,long value,uintptr_t extra);
void return_value(struct thread *t, struct user_pt_regs *regs, long value);
void return_scalar(struct thread *t,long value);
void return_task_value(struct thread *t,struct user_pt_regs *regs,long value,
        enum md_task_call_boundary boundary);
void restart_call(struct thread *t,const struct user_pt_regs *original,uint64_t mask,
        enum call_restart policy);
void restart_boundary(struct thread *t,const struct user_pt_regs *original,uint64_t mask,
        enum call_restart policy,enum md_task_call_boundary boundary);
int entry_require_stop(struct thread *t,enum md_execution_context reason,long result,
    void (*continuation)(struct thread *));
void entry_effect(struct thread *t,long number,const uint64_t args[6],void (*complete)(struct thread *,long));
int entry_effect_observe(struct thread *t);
struct md_entry_native *entry_native_journal(const struct thread *t);
unsigned entry_effect_reconcile(struct thread *t);
int entry_wait_begin(struct thread *,int64_t,void (*complete)(struct thread *,long));
void entry_wait_end(struct thread *);
void entry_wait_complete(struct thread *,long);
struct thread *entry_wait_deadline_owner(void);
void entry_execution_retry(struct thread *);
void wait_cancel_entry(struct thread *t,enum phase next);
void dispatch_complete(struct thread *t,long value);
int uts_access(const struct thread *t,long number);
void execution_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r,const char *mask_reason);
/* Execute C for the retained, already admitted request, including after its
 * native effect. This cannot decline/replay or acquire initial-call context. */
void entry_execution_begin(struct thread *);
void entry_control(pid_t pid,struct md_entry_channel *channel);
void resume(pid_t pid, int sig);
int shield(struct thread *t);
int save_signal_mask(struct thread *t,uint64_t *mask,const char *reason);
int signal_mask(struct thread *t,uint64_t mask,const char *reason);
int restore_mask(struct thread *t);
uintptr_t dispatch_input(struct thread *t,unsigned policy);
unsigned dispatch_policy(struct thread *t);
void dispatch(struct thread *t);
void dispatch_with_stack(struct thread *t);
void signal_wait_restore(struct thread *t);
int interrupted_operation(struct thread *t);
void execution_seccomp_event(struct thread *t,pid_t pid,int status,const struct rusage *usage);
void execution_syscall_event(struct thread *t,pid_t pid,int status,const struct rusage *usage);
void execution_checkpoint_event(struct thread *t,pid_t pid);

#endif
