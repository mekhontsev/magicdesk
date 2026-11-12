#ifndef MD_SUPERVISOR_NOTIFICATIONS_H
#define MD_SUPERVISOR_NOTIFICATIONS_H

#include "supervisor_thread.h"

enum metadata_restart_stage { METADATA_ENTRY, METADATA_WAIT };

struct metadata_restart {
    enum metadata_restart_stage stage;
    struct user_pt_regs saved;
    uint64_t mask;
    int requeued_signal;
    uint32_t instruction;
};

void completed_event_discard(struct thread *t);
void buffer_admission_yield(struct thread *t);
void notification_release(struct thread *t);
int notification_unexecuted(const struct thread *t);
int notification_entry_context(struct thread *t,const struct user_pt_regs *regs,
        const struct seccomp_data *call);
void notification_interrupted(struct thread *t);
void handle_notification(const struct seccomp_notif *request);
void delegated_notification(const struct seccomp_notif *request);
void completed_notification(pid_t pid,struct md_broker_completion *result);
int completed_event_resume(struct thread *t);
void metadata_restart_return(struct thread *t,long value);
int interrupted_metadata_event(struct thread *t,int status,const struct rusage *usage,
        struct md_task_signal_stop *stop);
int metadata_restart_event(struct thread *t,int status);
int completed_notification_event(struct thread *t,int status,const struct rusage *usage);

#endif
