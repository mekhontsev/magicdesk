#ifndef MD_TASK_QUIESCE_H
#define MD_TASK_QUIESCE_H
#include <sys/resource.h>
#include <sys/types.h>

struct md_task_quiesce;
/* A cold explicitly selected task barrier. Real wait events retain their exact order and
 * rusage for the normal dispatcher; only our synthetic interrupt is consumed.
 * Already stopped/listening tasks stay stopped. A kernel-blocked vfork parent
 * also qualifies: mandatory TRACEVFORKDONE stops it before userspace resumes.
 * No policy callbacks run here. */
struct md_task_quiesce *md_task_quiesce_new(long (*trace)(int,pid_t,void *,void *));
void md_task_quiesce_destroy(struct md_task_quiesce *);
/* Zero resume_request means an external owner already awaits a stop (including
 * a requested interrupt or newborn). Do not interrupt or resume it ourselves. */
int md_task_quiesce_add(struct md_task_quiesce *, pid_t, int stopped, int resume_request);
int md_task_quiesce_stop(struct md_task_quiesce *);
int md_task_quiesce_ready(const struct md_task_quiesce *);
int md_task_quiesce_contains(struct md_task_quiesce *, pid_t);
/* A participant's stop is still outstanding, not held or lent to a helper. */
int md_task_quiesce_waiting(struct md_task_quiesce *, pid_t);
/* A real interrupt was requested and its stop has not yet been collected. */
int md_task_quiesce_interrupting(struct md_task_quiesce *, pid_t);
void md_task_quiesce_describe(const struct md_task_quiesce *, int fd);
int md_task_quiesce_event(struct md_task_quiesce *, pid_t, int, const struct rusage *);
/* Temporarily lend a stopped participant to a task-affine operation. HELD
 * belongs to an outer continuation; SYNTHETIC resumes only on barrier release;
 * EVENT keeps its exact status/rusage queued for the normal dispatcher.
 * The borrower routes its own events and restores this boundary before return.
 * No participant may be lent until the whole barrier is ready. While lent,
 * release, event replay and modifications of this participant are rejected.
 * Terminal failure requires outer cancellation, not replay of the old stop. */
enum md_task_quiesce_boundary { MD_TASK_QUIESCE_HELD=1, MD_TASK_QUIESCE_SYNTHETIC,
    MD_TASK_QUIESCE_EVENT };
struct md_task_quiesce_stop { enum md_task_quiesce_boundary kind; int status; };
int md_task_quiesce_borrow(struct md_task_quiesce *,pid_t,struct md_task_quiesce_stop *);
int md_task_quiesce_return(struct md_task_quiesce *,pid_t);
/* Releases only synthetic stops. Deferred events remain owned until take. */
int md_task_quiesce_release(struct md_task_quiesce *);
pid_t md_task_quiesce_take(struct md_task_quiesce *, int *, struct rusage *);
int md_task_quiesce_newborn(struct md_task_quiesce *, pid_t);
#endif
