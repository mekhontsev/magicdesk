#ifndef MD_SUPERVISOR_SIGNALS_H
#define MD_SUPERVISOR_SIGNALS_H

#include "supervisor_thread.h"
#include "task_signal_control.h"
#include "signal_entry.h"

struct signal_control {
    struct md_resource_order memory;
    struct md_fd_order files;
    struct md_fd_origin_update publication;
    struct md_fd_object *descriptor;
    struct md_interception_buffer scratch;
    struct md_task_signal_control control;
    struct md_kernel_action action;
    uint64_t entry_mask;
    siginfo_t forced;
    int signal, started, checkpoint_suspended, reacquiring, descriptor_admission;
    unsigned selector;
    long descriptor_result;
    struct md_memory_page *wait_page;
    struct task_action *loan;
    int retiring,retire_error;
    struct thread *entry_thread;
    struct signal_control *entry_next, **entry_previous;
    struct md_signal_entry *entry_packet;
    struct md_signal_wait_view entry_wait_view;
    int entry_blocked;
    int returning;
    siginfo_t entry_info;
};

extern struct signal_control *signal_entries;

uid_t process_signal_uid(struct thread *observer,pid_t sender,uid_t fallback);
void signal_origin_capture(void *context,const siginfo_t *info,struct md_signal_origin *out);
void signal_origin_translate(struct thread *t,const struct md_signal_origin *origin,siginfo_t *info);
void signal_origin_consumed(struct thread *t,const siginfo_t *info);
void process_reap(struct thread *t,pid_t child);
int signal_observe(struct thread *t,struct md_task_signal_stop *stop,int with_registers);
int signal_replace_info(struct thread *t,struct md_task_signal_stop *stop,const siginfo_t *info);
int signal_control_parked(const struct thread *t);
int signal_ignored(struct thread *t,int signal,struct md_task_signal_stop *stop);
int signal_identity_allowed(const struct thread *from,const struct md_identity *identity,pid_t group,int signal);
int signal_allowed(const struct thread *from,const struct thread *to,int signal);
void deliver_signal(pid_t pid,int sig,int restored,struct md_task_signal_stop *stop);
void resume_signal(pid_t pid,int sig,const struct md_signal_origin *origin);
int init_signal_suppressed(const struct thread *from,const struct thread *to,int signal);

int signal_logical_enqueue(struct thread *to,enum md_signal_scope scope,const siginfo_t *info);
int signal_return_pending(const struct thread *t);
long signal_logical_send(struct thread *from,const struct seccomp_data *call,const siginfo_t *supplied);
long signal_process_next(struct thread *from,pid_t selector,int signal,pid_t after);
void signal_control_discard(struct thread *t);
int signal_entry_committed(struct thread *t);
void signal_entries_progress(void);
void signal_query_publish(struct thread *t);
int signal_entry_call(struct thread *t,const struct seccomp_data *call,unsigned long cookie);
int signal_descriptor_call(struct thread *t,long number,int descriptor);

void signal_control_progress(struct thread *t);
int signal_native_call(struct thread *t,uint64_t number,uint64_t input,uint64_t output,uint64_t size);
void signal_native_complete(struct thread *t);
int signal_control_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r,int signal,
        const struct md_kernel_action *action,uint64_t entry_mask,const siginfo_t *info);

int signal_control_event(struct thread *t,int status);
void child_signals_observe(struct thread *t,int status);

void signals_buffer_control(struct thread *t,const struct supervisor_control_request *control);
void signals_event(struct thread *t,pid_t pid,int status,int sig,struct md_task_signal_stop *signal_stop,int restored_signal);

#endif
