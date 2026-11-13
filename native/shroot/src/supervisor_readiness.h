#ifndef MD_SUPERVISOR_READINESS_H
#define MD_SUPERVISOR_READINESS_H

#include "supervisor_thread.h"
#include "poll_wait.h"
#include "fd_admission.h"
#include "epoll_wait.h"
int readiness_waiting(const struct thread *);

enum { READINESS_NATIVE_QUEUE, READINESS_TASK_QUEUE, READINESS_QUEUES };

struct readiness_loan {
    struct md_fd_install delivery;
    struct md_fd_origin_update publication;
    struct task_action *retirement;
    int number;
};

struct readiness_owner {
    struct md_fd_order files;
    struct md_resource_order publication_memory;
    struct md_fd_capture capture;
    struct md_received_fds *received;
    struct md_fd_object *queue,*target;
    struct md_fd_admission *admission;
    struct md_fd_admission_request request;
    struct md_epoll_wait wait;
    struct md_poll_wait *poll;
    unsigned poll_count,poll_loaded;
    int64_t poll_started;
    struct user_pt_regs continuation;
    struct epoll_event event;
    struct md_task_call_arguments native_call;
    int native_caller;
    struct readiness_loan loans[READINESS_QUEUES];
    unsigned lending;
    struct md_epoll_task_control *task_control;
    int task_target, task_started, park_pending, restore_completed;
    int delivery_claimed, delivery_waiting, copy_pending, resample;
    int evicting, parked, finishing, signal_ready, signal_pending;
    long finish_result;
    int operation,number,reserved,sealed,classified,done,io_waiting;
    struct md_epoll_frame *detached;
    enum { READINESS_ATTACHED, READINESS_RESTORE_WAIT, READINESS_RESTORE_LOAN,
        READINESS_RESTORE_REPLY } restoring;
    long restore_result;
};

extern struct readiness_owner *readiness_activation;

unsigned readiness_admission_purpose(const struct thread *t,const struct seccomp_data *call);
void readiness_unwatch(struct thread *t);
void readiness_evict_park(struct thread *t,int finished,long result);
void readiness_release(struct thread *t);

void readiness_reply(struct thread *t,long result);
void readiness_deliver(struct thread *t);
void readiness_loan_closed(pid_t pid,long result);
void readiness_start(struct thread *t);

void readiness_progress(struct thread *t);
void task_readiness_changed(struct thread *t);
void readiness_interrupted(struct thread *t);
int readiness_native_event(struct thread *t,int status);
int readiness_signal_event(struct thread *,int signal,struct md_task_signal_stop *,int restored);

void readiness_buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r);
int readiness_probe_exit(struct thread *t,pid_t pid);

#endif
