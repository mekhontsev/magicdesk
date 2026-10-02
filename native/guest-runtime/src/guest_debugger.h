#ifndef MD_GUEST_DEBUGGER_H
#define MD_GUEST_DEBUGGER_H
#include <asm/ptrace.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/resource.h>

struct md_debugger;
enum md_debugger_wait_action { MD_DEBUG_WAIT_PROBE, MD_DEBUG_WAIT_REPROBE, MD_DEBUG_WAIT_NATIVE };
struct md_debugger_host {
    int (*parent)(pid_t);
    int (*group)(pid_t);
    uid_t (*uid)(pid_t);
    int (*allowed)(pid_t, pid_t);
    int (*attachable)(pid_t, pid_t);
    int (*interrupt)(pid_t);
    int (*options)(pid_t, unsigned long);
    int (*listen)(pid_t);
    void (*usage)(pid_t, struct rusage *);
    void (*wait_probe)(pid_t, const struct user_pt_regs *, enum md_debugger_wait_action);
    void (*complete)(pid_t, struct user_pt_regs *, long);
    void (*resume)(pid_t, int);
};
/* Sole physical ptrace ownership stays in the supervisor. This owner represents
 * guest debugger relationships and wait events, not a second system tracer. */
struct md_debugger *md_debugger_create(const struct md_debugger_host *);
void md_debugger_destroy(struct md_debugger *);
int md_debugger_call(struct md_debugger *, pid_t, struct user_pt_regs *);
int md_debugger_stop(struct md_debugger *, pid_t, int, const siginfo_t *, unsigned long);
int md_debugger_exec(struct md_debugger *, pid_t, pid_t);
int md_debugger_birth(struct md_debugger *, pid_t, pid_t, unsigned);
int md_debugger_event(struct md_debugger *, pid_t, unsigned, unsigned long);
void md_debugger_exit(struct md_debugger *, pid_t, int);
int md_debugger_step(struct md_debugger *, pid_t);
int md_debugger_traced(struct md_debugger *, pid_t);
int md_debugger_owns(struct md_debugger *, pid_t, pid_t);
int md_debugger_active(struct md_debugger *);
int md_debugger_boundary(struct md_debugger *, pid_t);
int md_debugger_newborn(struct md_debugger *, pid_t);
int md_debugger_observing(struct md_debugger *, pid_t);
int md_debugger_syscall_mode(struct md_debugger *, pid_t);
int md_debugger_syscall(struct md_debugger *, pid_t, const struct user_pt_regs *, int);
void md_debugger_wait_complete(struct md_debugger *, pid_t, long);
void md_debugger_rekey(struct md_debugger *, pid_t, pid_t);
#endif
