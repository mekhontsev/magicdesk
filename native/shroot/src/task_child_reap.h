#ifndef MD_TASK_CHILD_REAP_H
#define MD_TASK_CHILD_REAP_H
#include "task_call.h"
#include <signal.h>
#include <time.h>

struct md_task_child_reap_scratch { uint64_t mask; struct timespec timeout; siginfo_t info; };
/* Exact-child retirement in its stopped real parent. The lifetime owner holds
 * the parent's entire thread group, stages real child notices that coalesced
 * behind the private notice, and defers concurrent guest signal generation.
 * This does not change normal wait/signalfd handling or signal dispositions.
 * The parent is borrowed through task_call; its checkpoint owner must be lent
 * for the duration. Scratch belongs to the parent mm, not the retired child. */
struct md_task_child_reap {
    struct md_task_call call;
    struct md_task_child_reap_scratch *scratch;
    uintptr_t peer;
    pid_t group,child;
    int stage;
    siginfo_t directed;
    const siginfo_t *displaced;
};
/* displaced remains owned until completion and may be updated by the lifetime
 * owner when another real child exits. Zero si_signo means no displaced notice.
 * Its full native siginfo is retained, including CPU accounting and si_code. */
int md_task_child_reap_begin(struct md_task_child_reap *,long (*trace)(int,pid_t,void *,void *),
    pid_t actor,pid_t group,pid_t child,enum md_task_call_boundary,const struct user_pt_regs *,
    uintptr_t call,uintptr_t returned,struct md_task_child_reap_scratch *,uintptr_t peer,
    const siginfo_t *displaced);
/* task_call event contract: 0 awaits an event, 1 restores the original boundary,
 * negative retains ownership for cancellation. No independent wait loop. */
int md_task_child_reap_event(struct md_task_child_reap *,int status);
#endif
