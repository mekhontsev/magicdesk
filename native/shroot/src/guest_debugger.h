#ifndef MD_GUEST_DEBUGGER_H
#define MD_GUEST_DEBUGGER_H
#include "signal_origin.h"
#include <asm/ptrace.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/resource.h>
#include <stdint.h>

struct md_debugger;
enum md_debugger_wait_action {
    MD_DEBUG_WAIT_PROBE, MD_DEBUG_WAIT_REPROBE, MD_DEBUG_WAIT_NATIVE,
    MD_DEBUG_WAIT_PARK, MD_DEBUG_WAIT_RESTART
};
struct md_debugger_host {
    /* The host admits original guest policy and distinguishes its TRACE stops
     * from internal selector cookies. Absent until that owner is connected. */
    int seccomp_events;
    /* Optional logical mask owner. NULL retains direct kernel mask access. */
    int (*signal_mask)(pid_t, int write, uint64_t *);
    pid_t (*visible_pid)(pid_t observer, pid_t native);
    pid_t (*native_pid)(pid_t observer, pid_t visible);
    uid_t (*visible_uid)(pid_t observer, pid_t sender, uid_t fallback);
    int (*parent)(pid_t);
    int (*group)(pid_t);
    uid_t (*uid)(pid_t);
    int (*allowed)(pid_t, pid_t);
    /* Voluntary tracing checks capabilities, not attach/dumpability admission. */
    int (*traceme)(pid_t parent, pid_t child);
    int (*attachable)(pid_t, pid_t);
    int (*observe)(pid_t);
    int (*interrupt)(pid_t);
    int (*options)(pid_t, unsigned long);
    int (*listen)(pid_t);
    void (*usage)(pid_t, struct rusage *);
    void (*wait)(pid_t, const struct user_pt_regs *, enum md_debugger_wait_action);
    void (*wait_wake)(pid_t);
    void (*complete)(pid_t, struct user_pt_regs *, long);
    void (*resume)(pid_t,int,const struct md_signal_origin *);
    void (*reaped)(pid_t observer,pid_t child,int native_wait);
    void (*signal_origin)(pid_t recipient,const siginfo_t *,struct md_signal_origin *);
    void (*signal_translate)(pid_t observer,const struct md_signal_origin *,siginfo_t *);
};
/* Sole physical ptrace ownership stays in the supervisor. This owner represents
 * guest debugger relationships and wait events, not a second system tracer. */
struct md_debugger *md_debugger_create(const struct md_debugger_host *);
void md_debugger_destroy(struct md_debugger *);
int md_debugger_call(struct md_debugger *, pid_t, struct user_pt_regs *);
int md_debugger_stop(struct md_debugger *, pid_t, int, const siginfo_t *, unsigned long);
int md_debugger_defer_stop(struct md_debugger *, pid_t, const siginfo_t *);
int md_debugger_exec(struct md_debugger *, pid_t, pid_t);
int md_debugger_birth(struct md_debugger *, pid_t, pid_t, unsigned);
int md_debugger_event(struct md_debugger *, pid_t, unsigned, unsigned long);
int md_debugger_seccomp(struct md_debugger *, pid_t, const struct user_pt_regs *, unsigned);
void md_debugger_exit(struct md_debugger *, pid_t, int);
int md_debugger_step(struct md_debugger *, pid_t);
int md_debugger_traced(struct md_debugger *, pid_t);
pid_t md_debugger_owner(struct md_debugger *, pid_t);
/* Only the exact stopped debugger request may borrow its inferior's registers.
 * The caller must keep that request parked until the kernel context is restored. */
int md_debugger_stopped_by(struct md_debugger *, pid_t target, pid_t owner);
int md_debugger_running(struct md_debugger *, pid_t target);
int md_debugger_active(struct md_debugger *);
int md_debugger_boundary(struct md_debugger *, pid_t);
int md_debugger_newborn(struct md_debugger *, pid_t);
int md_debugger_syscall_mode(struct md_debugger *, pid_t);
int md_debugger_syscall(struct md_debugger *, pid_t, const struct user_pt_regs *, int);
void md_debugger_wait_complete(struct md_debugger *, pid_t, long);
/* Zero means event wake; -512 requests kernel-owned restart/signal delivery. */
void md_debugger_wait_resumed(struct md_debugger *, pid_t, long);
void md_debugger_rekey(struct md_debugger *, pid_t, pid_t);
#endif
