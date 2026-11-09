#define _GNU_SOURCE
#include "task_birth.h"
#include "clone_call.h"
#include <asm/ptrace.h>
#include <elf.h>
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/uio.h>

long md_task_birth_terminal(long (*trace)(int,pid_t,void *,void *),pid_t pid,
        long number,unsigned long argument) {
    struct user_pt_regs r;
    struct iovec io={&r,sizeof(r)};
    if (trace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)<0) return -errno;
    if (r.regs[8]!=(unsigned long)number || !r.regs[0] || r.regs[0]>INT_MAX) return 0;
    if (r.regs[0]==argument) {
        /* An unchanged entry argument is not a successful syscall result.
         * Admit it only if an actual traced newborn has the cloned registers. */
        struct user_pt_regs child;
        io=(struct iovec){&child,sizeof(child)};
        if (trace(PTRACE_GETREGSET,(pid_t)r.regs[0],(void *)NT_PRSTATUS,&io)<0)
            return errno==ESRCH ? 0 : -errno;
        if (child.regs[0] || child.pc!=r.pc) return 0;
        for (unsigned i=1;i<31;++i)
            if (i!=7 && child.regs[i]!=r.regs[i]) return 0;
    }
    return (long)r.regs[0];
}

long md_task_birth_read(long (*trace)(int,pid_t,void *,void *),pid_t pid,
        unsigned event,int *terminal) {
    *terminal=0;
    if (event!=PTRACE_EVENT_CLONE && event!=PTRACE_EVENT_FORK && event!=PTRACE_EVENT_VFORK)
        return -EINVAL;
    unsigned long child=0;
    if (trace(PTRACE_GETEVENTMSG,pid,NULL,&child)<0) return -errno;
    siginfo_t info={0};
    if (trace(PTRACE_GETSIGINFO,pid,NULL,&info)<0) return -errno;
    if (info.si_signo!=SIGTRAP) return -EPROTO;
    if (info.si_code==(int)(SIGTRAP|(event<<8)))
        return child>0 && child<=INT_MAX ? (long)child : -EPROTO;
    if (info.si_code!=(SIGTRAP|(PTRACE_EVENT_EXIT<<8))) return -EPROTO;
    /* The observed birth proves clone completed. Group death may wake that
     * stop, but cannot execute another userspace call before its EXIT stop. */
    struct user_pt_regs r;
    struct iovec io={&r,sizeof(r)};
    if (trace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)<0) return -errno;
    if (!md_clone_call(r.regs[8]) || !r.regs[0] || r.regs[0]>INT_MAX) return -EPROTO;
    *terminal=1;
    return (long)r.regs[0];
}
