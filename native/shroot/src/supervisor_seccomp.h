#ifndef MD_SUPERVISOR_SECCOMP_H
#define MD_SUPERVISOR_SECCOMP_H

#include "supervisor_thread.h"
#include "seccomp_capture.h"
#include "seccomp_notify.h"
#include "task_copy.h"

struct filter_install {
    struct md_resource_order memory;
    struct md_fd_order files;
    struct md_fd_origin_update listener;
    struct md_interception_buffer scratch;
    struct md_task_copy copy;
    struct md_seccomp_capture capture;
    struct md_task_call admission;
    struct task_action *loan;
    struct sock_fprog program;
    struct sock_filter code[BPF_MAXINSNS];
    unsigned stage;
    int checkpoint_suspended, virtual_listener, source, admitted, native_completed, retiring;
    long error;
};

struct guest_notification {
    struct md_task_call call;
    struct md_seccomp_notify *queue;
    struct md_seccomp_notification *message;
    struct seccomp_data original;
    siginfo_t host_signal;
    int checkpoint_suspended,continuing_native,promoting;
};

extern struct md_task_quiesce *policy_barrier;
extern struct thread *policy_installer;

struct md_seccomp_policy *admission_policy(const struct thread *t);
enum { FILTER_SERVICE_GATES=4 };
void filter_service_gates(struct md_seccomp_gate gates[FILTER_SERVICE_GATES]);

int guest_trace_rejected(struct thread *t,const struct seccomp_data *call);
void guest_notification_discard(struct thread *t);
int guest_notification_begin(struct thread *t,const struct seccomp_data *data,
        const struct user_pt_regs *regs,const struct seccomp_notif *kernel,const siginfo_t *host_signal);
int guest_notification_kernel(struct thread *t,const struct seccomp_notif *q);
int guest_notification_event(struct thread *t,int status);
unsigned long guest_notification_route(struct thread *t,const struct seccomp_data *call);
uint32_t host_policy_interception(const struct seccomp_data *call);
int host_policy_signal(struct thread *t,struct md_task_signal_stop *stop);
int filter_revoke_entry(struct thread *t,const struct seccomp_data *call);
int filter_entry_eligible(const struct thread *t);
void filter_entry_rearm(struct thread *t);
void filter_entry_complete(struct thread *t,const struct user_pt_regs *regs);
int filter_entry_map(struct thread *t,const struct user_pt_regs *regs);
void filter_install_discard(struct thread *t);
void filter_install_fail(struct thread *t,int error);

int filter_listener_notification(struct thread *t,const struct seccomp_notif *q);

void policy_sync_progress(void);
void filter_install_progress(struct thread *t);
int filter_install_event(struct thread *t,int status);
void filter_install_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r);
int filter_observed(struct thread *t,const struct user_pt_regs *regs);

int describe_filter(pid_t pid,const struct seccomp_data *call);

uint32_t interception_decision(struct thread *t,const struct seccomp_data *call);

#endif
