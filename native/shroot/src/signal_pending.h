#ifndef MD_SIGNAL_PENDING_H
#define MD_SIGNAL_PENDING_H
#include <signal.h>
#include <stdint.h>

/* One coordinator owns a selected standard signal. Private and process-wide
 * pending state must not be inferred from si_code: queued senders choose it.
 * Tokens identify consumption before a faultable user copy. No per-syscall
 * scanning or allocation is needed for tasks without a pending signal. */
enum md_signal_scope { MD_SIGNAL_PRIVATE=1, MD_SIGNAL_SHARED=2 };
struct md_signal_pending;
struct md_signal_pending_value { siginfo_t info; uint64_t token; enum md_signal_scope scope; };
struct md_signal_pending *md_signal_pending_new(void);
struct md_signal_pending *md_signal_pending_fork(struct md_signal_pending *,int same_group,int same_handlers);
/* signalfd wait queues follow sighand, not the pending signal's thread group.
 * Exec detaches a cross-process shared queue, retaining pending signals and
 * the native wait identity for a process-private sighand. */
int md_signal_pending_exec(struct md_signal_pending *);
void md_signal_pending_release(struct md_signal_pending *);
int md_signal_pending_put(struct md_signal_pending *,enum md_signal_scope,const siginfo_t *);
int md_signal_pending_peek(const struct md_signal_pending *,struct md_signal_pending_value *);
int md_signal_pending_take(struct md_signal_pending *,const struct md_signal_pending_value *);
int md_signal_pending_has(const struct md_signal_pending *,enum md_signal_scope);
void md_signal_pending_clear(struct md_signal_pending *,int shared);
uint64_t md_signal_pending_domain(const struct md_signal_pending *);
uint64_t md_signal_pending_revision(const struct md_signal_pending *);
#endif
