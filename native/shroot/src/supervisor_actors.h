#ifndef MD_SUPERVISOR_ACTORS_H
#define MD_SUPERVISOR_ACTORS_H

#include "supervisor_thread.h"
#include "task_filter.h"
#include "namespace_broker.h"

struct task_action {
    struct md_pending_node pending;
    union { struct md_task_filter filter; struct md_task_call native; };
    struct md_interception_buffer buffer;
    struct user_pt_regs continuation;
    enum md_fd_selector_kind kind;
    enum md_task_call_boundary boundary;
    int selector_number;
    unsigned selector_instructions;
    int pid_filter;
    int number;
    struct thread *requester, *actor;
    struct md_fd_selector *group;
    struct md_fd_selector *selected_group;
    void (*complete)(struct thread *,long);
    struct md_fd_order files;
    pid_t rejected_group, rejected_task;
    int borrowed, remote, synthetic, checkpoint_suspended;
    /* The requester closes through its resident journal when available;
     * orphan retirement borrows an actor. Both retain the same table turn. */
    struct md_fd_loan *loan;
    int resident;
    struct capture_owner *capture;
    struct md_process_member *capture_capacity;
    int capture_capacity_needed;
    struct md_fd_origins *loan_table;
    struct md_fd_order *loan_turn;
    struct task_action *next;
    pid_t loan_waiter;
    struct md_broker_retirement retirement;
    int broker_borrowed;
    struct md_task_quiesce *outer_barrier;
    void (*retired)(void *,long);
    void *retired_context;
};

extern struct thread *broker_events;
extern struct task_action *task_action_execution;
extern struct task_action *loan_retirements;
extern struct md_task_quiesce *task_action_barrier;

struct md_task_call *task_action_call(struct task_action *action);
struct md_task_quiesce **quiescence_barrier(const struct mapping_owner *parent);
int quiescence_busy(const struct mapping_owner *parent);
int buffer_admission_quiescing(pid_t pid);
long quiesce_trace(int request,pid_t pid,void *address,void *data);
int quiesce_event(struct md_task_quiesce *barrier,pid_t pid,int status,const struct rusage *usage);
int quiesce_task_add(struct md_task_quiesce *barrier,struct thread *t,int stopped);
struct task_action *task_action_new(struct thread *t);
void task_action_cleanup(void);
void task_action_discard(struct thread *t);
void task_action_unpark(void);
void task_continuation_defer(struct thread *t);
void task_continuation_discard(struct thread *t);
void task_continuations_progress(void);
int task_checkpoint_borrow(struct thread *actor,int *owned);
int selector_has(struct thread *t,unsigned kind,int number);
int task_parked(const struct thread *t);
int task_continuation_available(const struct thread *t);
int task_completion_available(pid_t pid);
int task_action_borrowable(struct thread *p,struct thread *requester);
void task_action_start(struct thread *t);
void pid_filter_begin(struct thread *t,const struct user_pt_regs *continuation,
        void (*complete)(struct thread *,long));
void selector_deferred_complete(struct thread *t,long result);
void task_action_schedule(void);
void loan_retire(struct task_action *owner,struct thread *requester,
        struct md_fd_order *turn,void (*complete)(struct thread *,long));
int broker_retire(const struct md_broker_retirement *request);
void task_call_resume(struct thread *t,struct md_task_call *call,int signal);
struct md_task_call *task_call_continuation(struct thread *t);
void task_group_stop(struct thread *t);
int task_call_signal(struct thread *t,struct md_task_call *call,int status);
int task_action_event(pid_t pid,int status,const struct rusage *usage);
int fd_loans_ready(struct thread *t);
void selector_progress(struct thread *t);
int helper_newborn(pid_t pid);

#endif
