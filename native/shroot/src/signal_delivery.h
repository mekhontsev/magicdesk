#ifndef MD_SIGNAL_DELIVERY_H
#define MD_SIGNAL_DELIVERY_H
#include "signal_origin.h"
#include <signal.h>
#include <sys/types.h>
#include <stdint.h>

struct md_signal_delivery_node;
struct md_signal_delivery {
    struct md_signal_delivery_node *first,*last;
    struct md_signal_delivery_node *realtime;
    unsigned carriers[NSIG];
    uintptr_t carrier_cookie;
    uint64_t mask, kernel_mask;
    int active, committing;
    md_signal_origin_capture capture;
    void *capture_context;
    struct md_signal_origin *child_origin;
};
/* Standard signals coalesce without losing the first siginfo. Realtime
 * instances retain FIFO order per signal, ahead of subsequent kernel arrivals. */
int md_signal_delivery_hold(struct md_signal_delivery *,const siginfo_t *);
/* Explicitly generated standard signal, after recipient/si_code admission.
 * A self-directed queued signal may legitimately carry a positive code. */
int md_signal_delivery_generated(struct md_signal_delivery *,const siginfo_t *);
/* A verified seccomp delivery stop displaced by a task-affine disposition
 * exchange. Caller retains the original user context and commits SIG_DFL
 * before publication. Ordinary memory faults must never use this route. */
int md_signal_delivery_seccomp(struct md_signal_delivery *,const siginfo_t *);
/* A shielded delivery stop: retain a signal in the queue, or return
 * the same blocked job-control signal for native requeue. Negative errno is a
 * failure. The caller must resume with the nonnegative return value. */
int md_signal_delivery_retain(struct md_signal_delivery *,pid_t,const siginfo_t *);
static inline int md_signal_delivery_pending(const struct md_signal_delivery *q) {
    return q->first || q->realtime || q->kernel_mask;
}
/* Start only at a restored guest boundary; resume with CONT. The kernel admits
 * an unexecuted syscall normally and owns its restart/EINTR decision. Chained
 * real signal stops retain context without service instructions or registers. */
int md_signal_delivery_publish(struct md_signal_delivery *,pid_t tid,pid_t group);
/* A native exception must build its original frame before a retained async
 * signal can borrow the mask. The shared interrupt event releases this gate. */
int md_signal_delivery_before_handler(struct md_signal_delivery *,pid_t,const siginfo_t *);
void md_signal_delivery_checkpoint(struct md_signal_delivery *,int status);
/* An admitted native mask change executes with q->mask, not the carrier mask,
 * under SYSCALL. At its exit, retain the new application mask and re-arm the
 * already pending carrier before allowing signal delivery or guest execution. */
int md_signal_delivery_update_mask(struct md_signal_delivery *,pid_t,uint64_t);
enum md_signal_delivery_event {
    MD_SIGNAL_NEW,
    MD_SIGNAL_CARRIER,
    MD_SIGNAL_RESTORED
};
/* CARRIER supplies the resume signal for an intermediate protocol stop.
 * RESTORED needs ordinary debugger/namespace admission, but must not enter
 * interrupted-syscall discovery again. Negative errno reports failure. */
int md_signal_delivery_event(struct md_signal_delivery *,pid_t tid,int signal,int *deliver);
void md_signal_delivery_clear(struct md_signal_delivery *);
#endif
