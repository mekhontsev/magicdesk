#ifndef MD_TASK_STOP_H
#define MD_TASK_STOP_H
#include <errno.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/types.h>

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
