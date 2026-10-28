#ifndef MD_TASK_CHECKPOINT_H
#define MD_TASK_CHECKPOINT_H
#include <stdint.h>
#include <sys/types.h>

/* Internal event, not a kernel signal or an application debugger stop.
 * Registers retain the kernel's post-SVC PC, already at the continuation. */
#define MD_TASK_CHECKPOINT_EVENT 256
#define MD_INTERCEPT_CHECKPOINT 55
#define MD_TASK_CHECKPOINT_BUCKETS 256
struct md_task_checkpoint;
struct md_task_checkpoints {
    struct md_task_checkpoint *buckets[MD_TASK_CHECKPOINT_BUCKETS];
    unsigned count;
    long (*trace)(int,pid_t,void *,void *);
    int (*restricted)(pid_t);
    int (*marker)(uintptr_t);
    /* C-only continuations that never restore application registers may
     * consume an entry stop, where ARM64 still owns its saved x7. */
    int (*entry_marker)(uintptr_t,unsigned long);
    uintptr_t begin,end;
};
/* All caller ptrace requests use this owner. Stop decoding hands off each
 * retained observation once; other requests/events invalidate the snapshot. */
long md_task_checkpoint_trace(struct md_task_checkpoints *,int,pid_t,void *,void *);
/* 1 consumed an intermediate stop; 0 publishes the original or typed
 * checkpoint event. Negative errno retains the actor for caller cleanup. */
int md_task_checkpoint_event(struct md_task_checkpoints *,pid_t,int *);
/* Slow-path validation after an inconsistent stop observation. Group death
 * can replace an already collected stop with EXIT without an ESRCH error.
 * Returns 1 only for a vanished actor or a confirmed terminal replacement. */
int md_task_checkpoint_displaced(struct md_task_checkpoints *,pid_t);
/* Lend an exact stopped boundary to a native-call owner. While suspended,
 * requests and events pass through without changing checkpoint state. Returns
 * 1 if an ENTRY observation is SYSEMU, 0 otherwise, or negative errno.
 * The borrower must restore the exact boundary before returning ownership. */
int md_task_checkpoint_suspend(struct md_task_checkpoints *,pid_t);
void md_task_checkpoint_restore(struct md_task_checkpoints *,pid_t);
void md_task_checkpoints_clear(struct md_task_checkpoints *);
#endif
