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
int machine_observe(struct thread *t,const struct user_pt_regs *regs);
int machine_native_entry(struct thread *t,const struct user_pt_regs *regs,unsigned long *cookie);
int cooperative_copying(struct thread *t);
void cooperative_buffer_release(struct thread *t);
void cooperative_routes_publish(struct thread *t);
void cooperative_memory_publish(struct thread *t,int eligible);
long cooperative_bind(struct thread *t);
int registers(pid_t pid, struct user_pt_regs *out);
int set_registers(pid_t pid, struct user_pt_regs *regs);
int skip(pid_t pid);
int cooperative_native_observe(pid_t pid,struct md_entry_native *native);
int entry_reply(struct thread *t,long value,uintptr_t extra);
int entry_pending(const struct thread *t);
int entry_context(struct thread *t,enum md_execution_context reason);
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
int entry_require_stop(struct thread *t,enum md_execution_context reason,void (*continuation)(struct thread *));
void entry_effect(struct thread *t,long number,const uint64_t args[6],void (*complete)(struct thread *,long));
void wait_cancel_entry(struct thread *t,enum phase next);
void dispatch_complete(struct thread *t,long value);
int uts_access(const struct thread *t,long number);
void execution_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r,const char *mask_reason);
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
