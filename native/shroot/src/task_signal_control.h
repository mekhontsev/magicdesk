#ifndef MD_TASK_SIGNAL_CONTROL_H
#define MD_TASK_SIGNAL_CONTROL_H
#include "task_copy.h"
#include "signal_actions.h"
#include "signal_pending.h"
#include "signal_view.h"
#include "signal_fd.h"
#include <linux/seccomp.h>
#include <sys/uio.h>
#include <time.h>
struct md_signal_wait_exchange { uint64_t set; struct timespec timeout; siginfo_t info; };

/* One stopped actor and one shared-action admission turn. The coordinator
 * owns scratch, action-table lifetime and delivery; this owner never waits
 * privately or changes another thread's mask. */
struct md_task_signal_control {
    long (*trace)(int,pid_t,void *,void *);
    pid_t pid;
    struct user_pt_regs original;
    struct seccomp_data request;
    uintptr_t signal_frame;
    enum md_task_call_boundary boundary;
    uintptr_t call, returned, peer;
    void *scratch;
    size_t capacity;
    struct md_signal_actions *actions;
    int *blocked;
    struct md_task_copy copy;
    struct md_task_call native;
    struct md_kernel_action requested, previous;
    uint64_t previous_mask, next_mask;
    unsigned stage;
    int entering, signal, force_default;
    int copying, invoking;
    long result;
    int waiting, passthrough;
    int wait_native, wait_woken, wait_timed, wait_interrupt;
    int64_t wait_deadline;
    uintptr_t wait_peer;
    struct md_signal_wait_exchange wait_exchange;
    uint64_t wait_set;
    struct timespec wait_timeout;
    siginfo_t wait_info;
    struct md_signal_pending *pending_owner;
    struct md_signal_pending_value selected;
    struct md_signal_wait_view wait_view;
    long wait_result;
    int descriptor, descriptor_stage, descriptor_nonblock, descriptor_flags;
    long descriptor_result;
    struct md_signal_fd *signal_file;
    struct iovec *vectors;
    struct iovec scalar;
    unsigned vector_count, vector_index;
    size_t vector_offset, descriptor_copied;
    size_t descriptor_available, record_copied, copy_length;
    struct signalfd_siginfo record;
    void (*translate)(void *,siginfo_t *);
    void *context;
};
/* Signal action exchange. Returns 1 complete, 0 resumed,
 * negative infrastructure failure; syscall errors are in result. */
int md_task_signal_control_begin(struct md_task_signal_control *,
    long (*trace)(int,pid_t,void *,void *),pid_t,const struct user_pt_regs *,const struct seccomp_data *,
    uintptr_t call,uintptr_t returned,
    struct md_signal_actions *,int *blocked,void *scratch,uintptr_t peer,size_t capacity);
int md_task_signal_control_event(struct md_task_signal_control *,int status);
enum { MD_SIGNAL_WAIT_RELEASE=2, MD_SIGNAL_WAIT_ACQUIRE=3,
    MD_SIGNAL_FD_ACQUIRE=4, MD_SIGNAL_FD_RELEASE=5,
    MD_SIGNAL_WAIT_STORAGE=6, MD_SIGNAL_WAIT_PARKED=7 };
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
