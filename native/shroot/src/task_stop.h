#ifndef MD_TASK_STOP_H
#define MD_TASK_STOP_H
#include <errno.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <asm/ptrace.h>
#include <elf.h>

/* One collected delivery stop, shared by recovery and policy readers. Discard
 * before resuming; mutations must invalidate or replace the affected snapshot.
 * Never cache across stops. */
struct md_task_signal_stop {
    siginfo_t info;
    struct user_pt_regs regs;
    long (*trace)(int,pid_t,void *,void *);
    int has_info, has_regs;
};
static inline void md_task_signal_stop_init(struct md_task_signal_stop *s,
        long (*trace)(int,pid_t,void *,void *)) {
    s->trace=trace; s->has_info=s->has_regs=0;
}
static inline int md_task_signal_info(pid_t pid,struct md_task_signal_stop *s) {
    if (!s->has_info) {
        if (s->trace(PTRACE_GETSIGINFO,pid,NULL,&s->info)<0) return -errno;
        s->has_info=1;
    }
    return 0;
}
static inline int md_task_signal_registers(pid_t pid,struct md_task_signal_stop *s) {
    if (!s->has_regs) {
        struct iovec io={&s->regs,sizeof(s->regs)};
        if (s->trace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)<0) return -errno;
        s->has_regs=1;
    }
    return 0;
}

/* Cold validation after an inconsistent observation: group death can replace
 * an already collected stop without making ptrace reads fail. No caller may
 * restore its saved continuation after a confirmed terminal replacement. */
static inline int md_task_stop_displaced(long (*trace)(int,pid_t,void *,void *),pid_t pid) {
    siginfo_t info={0};
    if (trace(PTRACE_GETSIGINFO,pid,NULL,&info)<0) return errno==ESRCH ? 1 : -errno;
    return info.si_signo==SIGTRAP && (info.si_code==(SIGTRAP|(PTRACE_EVENT_EXIT<<8))
        || info.si_code==(SIGTRAP|(PTRACE_EVENT_EXEC<<8)));
}
#endif
