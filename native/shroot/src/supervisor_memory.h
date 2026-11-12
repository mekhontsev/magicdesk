#ifndef MD_SUPERVISOR_MEMORY_H
#define MD_SUPERVISOR_MEMORY_H

#include "supervisor_thread.h"
#include "private_memory.h"
#include "memory_replace.h"
#include "fs_worker.h"
#include "task_helper.h"

struct buffer_mapping {
    pid_t caller;
    struct user_pt_regs continuation;
    struct md_interception_buffer scratch;
    struct md_interception_buffers *buffers;
    struct md_seccomp_policy *policy;
    uint64_t ancestor;
    uintptr_t parent_view;
    struct md_interception_memory *memory;
    struct md_resource_order turn;
    int channels;
    int clients;
    unsigned client_bucket;
};

struct mapping_owner {
    pid_t caller, group;
    struct md_pid_identity *process;
    struct user_pt_regs continuation;
    struct md_shm_space *space;
    struct md_shm_mapping *pending;
    struct md_interception_memory *memory;
    struct md_resource_order order;
    struct md_task_mapping_request request;
    struct md_mapping_change change;
    struct md_task_mapping direct;
    struct md_fd_order files;
    struct md_fd_origin_update delivery;
    struct task_action *loan;
    int started, admitted, file, noexec, readonly, guarded, borrowed, retiring;
    int64_t deadline;
};

struct svc_pool_mapping {
    struct md_task_call call;
    struct md_resource_order order;
    uintptr_t address;
    int status;
    struct rusage usage;
};
int svc_pool_begin(struct thread *,const struct user_pt_regs *,int status,const struct rusage *);
int svc_pool_event(pid_t,int status);

struct memory_capture {
    pid_t caller;
    uint64_t mask;
    struct user_pt_regs returned;
    struct md_interception_buffer buffer;
};

enum memory_stage { MEMORY_WRITERS, MEMORY_WORKER, MEMORY_VALIDATE, MEMORY_ACTOR, MEMORY_MAP, MEMORY_COPY,
    MEMORY_ADVICE, MEMORY_REPLACE };

struct memory_execution {
    struct thread *actor;
    struct retained_memory *executor;
    enum memory_stage stage;
    int64_t deadline, timing[7];
    struct user_pt_regs returned;
    int status;
    struct rusage usage;
    struct md_private_memory mapping;
    struct md_private_memory_plan plan;
    struct md_private_memory_scan *scan;
    size_t copied;
    struct md_interception_buffer buffer;
    struct md_fs_worker_pause pause;
    struct md_memory_replace replacement;
    struct md_memory_replace_call call;
    struct md_task_helper_result result;
    unsigned advice;
    int complete, calling, terminal, global;
    long error;
    unsigned other_events;
    unsigned progress_calls;
    int64_t last_progress;
    char scratch[];
};

extern struct md_interception_memory *entry_fence_memory;
extern struct thread *entry_fence_owner;
extern int entry_fence_stage, entry_fence_error;
extern int64_t entry_fence_retry_at;
extern int64_t entry_fence_deadline;
extern struct md_task_quiesce *mapping_barrier;
extern struct mapping_owner *mapping_execution;
extern struct md_task_quiesce *memory_barrier;
extern int memory_completions_pending;
extern struct memory_execution *memory_execution;

void entry_fence_cancel(struct thread *t);
void memory_capture_discard(struct thread *t);
void memory_buffers_discard(struct thread *t);
void memory_operation_discard(struct thread *t);
int buffer_mapping_start(struct thread *t,const struct user_pt_regs *regs,
        struct buffer_mapping *owner);
void memory_buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r);
int svc_revoke_entry(struct thread *t);
int svc_entry_observe(struct thread *t);
void memory_access_capture(struct thread *t);

int mapping_notification(struct thread *t,const struct seccomp_notif *q);
int mapping_event(pid_t pid,int status);
void mapping_start(struct thread *t);
void mapping_progress(void);
int mapping_guard_event(pid_t pid,int status,const struct rusage *usage);
void memory_start(struct thread *t);
void memory_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs,enum memory_action action);
int memory_observed(struct thread *t,const struct ptrace_syscall_info *info);
int memory_capture_begin(struct thread *t,const struct user_pt_regs *returned);
void memory_kernel_complete(struct thread *t,long value);
int entry_fence_drained(const struct thread *t);
int entry_fence_adapt(const struct thread *t);
int entry_fence_admission(struct thread *t,const struct user_pt_regs *r,int status,const struct rusage *usage);
void entry_fence_progress(void);
pid_t entry_fence_take(int *status,struct rusage *usage);
int64_t memory_deadline(void);
void memory_discard(void);
int memory_available(pid_t pid);
int memory_runnable(void);
void memory_progress(void);
int quiescence_repair_event(pid_t pid,int status,const struct rusage *usage);
int memory_event(pid_t pid,int status,const struct rusage *usage);
int memory_repair(struct thread *t,const struct user_pt_regs *returned,int status,const struct rusage *usage);

#endif
