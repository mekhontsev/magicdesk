#ifndef MD_TASK_SIGNAL_CONTROL_H
#define MD_TASK_SIGNAL_CONTROL_H
#include "task_copy.h"
#include "signal_operation.h"
#include <linux/seccomp.h>
#include <sys/uio.h>
#include <time.h>

/* One stopped actor and one shared-action admission turn. The coordinator
 * owns scratch, action-table lifetime and delivery; this owner never waits
 * privately or changes another thread's mask. */
struct md_task_signal_control {
    struct md_signal_operation operation;
    long (*trace)(int,pid_t,void *,void *);
    pid_t pid;
    struct user_pt_regs original;
    enum md_task_call_boundary boundary;
    uintptr_t call,returned;
    struct md_task_copy copy;
    struct md_task_call native;
};
/* Signal action exchange. Returns 1 complete, 0 resumed,
 * negative infrastructure failure; syscall errors are in result. */
int md_task_signal_control_begin(struct md_task_signal_control *,
    long (*trace)(int,pid_t,void *,void *),pid_t,const struct user_pt_regs *,const struct seccomp_data *,
    uintptr_t call,uintptr_t returned,
    struct md_signal_actions *,int *blocked,void *scratch,uintptr_t peer,size_t capacity);
int md_task_signal_control_event(struct md_task_signal_control *,int status);
/* RELEASE requests private wait storage; STORAGE publishes a newly allocated
 * page. PARKED relinquishes scratch and the mapping turn until ACQUIRE. */
int md_task_signal_wait_park(struct md_task_signal_control *,uintptr_t page);
int md_task_signal_control_continue(struct md_task_signal_control *);
int md_task_signal_control_wake(struct md_task_signal_control *);
int md_task_signal_control_parked(const struct md_task_signal_control *);
/* An enclosing barrier already issued its interrupt. Recheck the wait after
 * release, retaining its deadline and any completed native signal delivery. */
void md_task_signal_control_quiesced(struct md_task_signal_control *);
int md_task_signal_descriptor_changed(struct md_task_signal_control *,const struct md_signal_fd *);
void md_task_signal_control_dispose(struct md_task_signal_control *);
int md_task_signal_descriptor_begin(struct md_task_signal_control *,
    long (*trace)(int,pid_t,void *,void *),pid_t,const struct user_pt_regs *,const struct seccomp_data *,
    uintptr_t call,uintptr_t returned,struct md_signal_pending *,int *blocked,
    void (*translate)(void *,siginfo_t *),void *,void *scratch,uintptr_t peer,size_t capacity);
/* The caller pins metadata under its FD turn. No table turn spans a wait. */
int md_task_signal_descriptor_admit(struct md_task_signal_control *,struct md_signal_fd *);
struct md_task_call *md_task_signal_control_call(struct md_task_signal_control *);
/* Task-affine sigtimedwait with native queue priorities and a selected logical
 * pending queue. Consumption precedes output copy; waits release admission. */
int md_task_signal_wait_begin(struct md_task_signal_control *,
    long (*trace)(int,pid_t,void *,void *),pid_t,const struct user_pt_regs *,const struct seccomp_data *,
    uintptr_t call,uintptr_t returned,struct md_signal_pending *,int *blocked,void (*translate)(void *,siginfo_t *),void *,
    void *scratch,uintptr_t peer,size_t capacity);
/* An interrupted atomic wait retains the kernel's native saved-mask restore.
 * Called at its frame-commit stop, before any handler instruction.
 * On completion next_mask is the handler mask, previous is its disposition. */
int md_task_signal_wait_entry_begin(struct md_task_signal_control *,
    long (*trace)(int,pid_t,void *,void *),pid_t,const struct user_pt_regs *,
    uintptr_t call,uintptr_t returned,struct md_signal_actions *,int *blocked,
    const struct md_kernel_action *,const struct md_signal_wait_view *,
    void *scratch,uintptr_t peer,size_t capacity);
/* A genuine guest TRAP with logical SIGSYS blocked must reset the shared
 * disposition, unlike a host-denied syscall. No application pointer is used. */
int md_task_signal_default_begin(struct md_task_signal_control *,
    long (*trace)(int,pid_t,void *,void *),pid_t,const struct user_pt_regs *,
    uintptr_t call,uintptr_t returned,struct md_signal_actions *,int *blocked,
    const struct md_kernel_action *,void *scratch,uintptr_t peer,size_t capacity);
#endif
