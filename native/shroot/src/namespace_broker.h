#ifndef MD_NAMESPACE_BROKER_H
#define MD_NAMESPACE_BROKER_H
#include "fs_worker.h"
#include "fs_buffer_service.h"
#include "fs_operation.h"
#include <sys/resource.h>
#include <linux/seccomp.h>
#include <sys/syscall.h>
#include "syscall_catalog.h"
#define MD_BROKER_CASE(n) case SYS_##n:
static inline int md_broker_filesystem_call(int nr) {
    switch (nr) { MD_FS_NOTIFICATION_CALLS(MD_BROKER_CASE) return 1; default: return 0; }
}
static inline int md_broker_metadata_call(int nr) {
    switch (nr) { MD_FS_METADATA_CALLS(MD_BROKER_CASE) return 1; default: return 0; }
}
#undef MD_BROKER_CASE
struct md_broker_completion {
    struct seccomp_data call;
    unsigned operation, flags;
    int notification_live;
    struct md_fs_result result;
    struct md_fs_buffer_open opened;
};
struct md_namespace_broker;
enum { MD_BROKER_READ=1, MD_BROKER_OPEN=2, MD_BROKER_COOPERATIVE=4, MD_BROKER_DISCOVERY=8 };
struct md_entry_channel;
/* Mapping lifetime belongs to the stopped task. Forget precedes its release. */
int md_broker_cooperative_bind(struct md_namespace_broker *,pid_t,struct md_entry_channel *,uintptr_t wake_gate);
void md_broker_cooperative_revoke(struct md_namespace_broker *,pid_t);
struct md_namespace_broker *md_broker_create(struct md_fs_worker *, int statistics,
        void (*delegate)(const struct seccomp_notif *));
void md_broker_listen(struct md_namespace_broker *, int listener);
/* Supervisor callback takes a completed operation, never permission to replay
 * it. The callback consumes opened ownership before returning. */
void md_broker_completion_handler(struct md_namespace_broker *,void (*)(pid_t,struct md_broker_completion *));
/* A stopped receiver has taken the retained result. Until this acknowledgement,
 * a kernel restart delegates without executing the operation a second time. */
void md_broker_completion_taken(struct md_namespace_broker *,pid_t);
void md_broker_control_factory(struct md_namespace_broker *,md_control_factory);
void md_broker_control_query(struct md_namespace_broker *,md_control_query);
struct md_entry_native;
/* Only the supervisor reads/changes stopped registers. The worker retains
 * resource ownership until the native journal has an authoritative result. */
void md_broker_native_observer(struct md_namespace_broker *,int (*)(pid_t,struct md_entry_native *));
struct md_fd_order;
struct md_fd_loan;
/* Failed publication transfers an exact provisional slot to the existing
 * supervisor retirement owner. The worker retains the table turn and payload
 * until complete; failures never authorize re-executing the native open. */
struct md_broker_retirement {
    pid_t task;
    int number;
    struct md_fd_order *turn;
    struct md_fd_loan *loan;
    void (*complete)(void *,int);
    void *context;
};
void md_broker_retirement_handler(struct md_namespace_broker *,int (*)(const struct md_broker_retirement *));
/* A cold cleanup action borrows a broker-held stop without delivering its
 * signal/lifecycle continuation. Its injected stops bypass normal admission;
 * take_event retains the original stop until native_return after restoration. */
int md_broker_native_borrow(struct md_namespace_broker *,pid_t,int *stopped);
void md_broker_native_return(struct md_namespace_broker *,pid_t);
/* Retain a real kernel event while its admitted worker operation retires.
 * No synchronous worker wait or filesystem IO occurs on the supervisor. */
int md_broker_hold_event(struct md_namespace_broker *,pid_t,int,const struct rusage *);
pid_t md_broker_take_event(struct md_namespace_broker *,int *,struct rusage *);
/* Publish before resuming a tracee; forget retires its eligibility. */
struct md_broker_code_ranges { uintptr_t starts[8]; size_t size; unsigned count; };
int md_broker_task(struct md_namespace_broker *, int pid, int eligible, uintptr_t raw_gate, uintptr_t watch_gate,
    const struct md_broker_code_ranges *);
/* A barrier restored an unchanged continuation after its synthetic stop.
 * Retain eligibility and resource ownership; wake a request published before
 * this acknowledgement. Never use for an injected helper or held real event. */
void md_broker_resumed(struct md_namespace_broker *,pid_t);
/* A suspended code site needs a fresh observed call after signal recovery.
 * This only invalidates cold discovery sampling; it does not install code. */
void md_broker_resample(struct md_namespace_broker *,pid_t,uintptr_t instruction_pointer);
void md_broker_forget(struct md_namespace_broker *, int pid);
/* Bounded cold diagnostic; never waits for a worker-held lock. */
void md_broker_describe(struct md_namespace_broker *,int fd);
void md_broker_describe_task(struct md_namespace_broker *,pid_t,int fd);
struct md_fd_origins;
struct md_fd_object;
/* Publish one already completed open before resuming its receiver. The caller
 * retains source until clear (also on cancellation); the broker pins the table
 * and origin, and admits only the exact task and seccomp call snapshot. The
 * stopped-call carrier uses fstat's existing notification route with unchanged
 * user registers; task-affine delivery uses gettid at the admission gate. Neither
 * carrier executes its native syscall when it matches the pending result.
 * Filtered task-affine delivery can instead re-admit the original filesystem
 * syscall with its complete original IP/argument context. */
int md_broker_result_begin(struct md_namespace_broker *, int pid, const struct seccomp_data *,
        struct md_fd_origins *, int source, struct md_fd_object *, unsigned flags);
/* Retire one pending result. Returns 1 with the kernel-confirmed response, 0
 * without a confirmed response, or a negative cleanup error. Does not infer
 * why an unconfirmed syscall returned; its stopped receiver owns that check. */
int md_broker_result_finish(struct md_namespace_broker *, int pid, long *value);
void md_broker_destroy(struct md_namespace_broker *);
#endif
